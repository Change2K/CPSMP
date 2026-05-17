package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.auction.AuctionBrowseSort;
import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionConfig;
import de.deinserver.cpsmp.auction.AuctionHouseManager;
import de.deinserver.cpsmp.auction.AuctionListing;
import de.deinserver.cpsmp.auction.AuctionPermission;
import de.deinserver.cpsmp.auction.AuctionPriceParser;
import de.deinserver.cpsmp.auction.gui.AuctionGuiItemKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Orchestrates every Auction House GUI screen. All Bukkit inventory
 * creation goes through this class so the click/drag/close listener
 * has a single place to look up sessions and the GUI logic stays out
 * of {@link AuctionHouseManager}.
 *
 * <p>The GUI <em>only</em> calls existing backend methods (
 * {@code createListing} / {@code cancelListing} / {@code collectAll} /
 * {@code collectOne} / {@code buyListing} / {@code browseListings} /
 * {@code getActiveListings} / {@code getCollectItems}). No buy /
 * cancel / collect / listing <em>transaction</em> logic is duplicated
 * here. V2.4 routes GUI selling through {@code createListing} after
 * optional read-only validation for faster feedback.
 *
 * <p>Threading: every public entry point is called from the main
 * server thread (either directly from a command, or from a
 * {@code CompletableFuture#thenAccept} callback that the
 * AuctionHouseManager guarantees runs on the main thread). Inventory
 * mutations are therefore always main-thread.
 */
public final class AuctionGuiManager {

    // -------- Main GUI slot layout (3 rows = 27 slots) ---------------------

    private static final int MAIN_SLOT_BROWSE = 10;
    private static final int MAIN_SLOT_SELL = 12;
    private static final int MAIN_SLOT_LISTINGS = 14;
    private static final int MAIN_SLOT_COLLECT = 16;
    private static final int MAIN_SLOT_INFO = 22;
    private static final int MAIN_SLOT_CLOSE = 26;

    // -------- Sell GUI (3 rows) -------------------------------------------

    private static final int SELL_INPUT_SLOT = 13;
    private static final int SELL_SLOT_SET_PRICE = 11;
    private static final int SELL_SLOT_CANCEL = 15;
    private static final int SELL_SLOT_BACK = 22;

    // -------- Sell confirm GUI (3 rows) -------------------------------------

    private static final int SELL_CONFIRM_SLOT_CREATE = 11;
    private static final int SELL_CONFIRM_SLOT_ITEM = 13;
    private static final int SELL_CONFIRM_SLOT_CANCEL = 15;

    // -------- Browse / Listings / Collect navigation row layout ------------

    private static final int NAV_ROW_OFFSET_BACK = 0;        // slot 45 on a 6-row GUI
    private static final int NAV_ROW_OFFSET_REFRESH = 1;     // V2.5
    private static final int NAV_ROW_OFFSET_SORT = 2;      // V2.5
    private static final int NAV_ROW_OFFSET_PREV = 3;        // slot 48
    private static final int NAV_ROW_OFFSET_PAGE = 4;        // slot 49
    private static final int NAV_ROW_OFFSET_NEXT = 5;        // slot 50
    private static final int NAV_ROW_OFFSET_COLLECT_ALL = 4; // slot 49 (collect GUI)
    private static final int NAV_ROW_OFFSET_CLOSE = 8;       // slot 53

    // -------- Confirm GUI slot layout (3 rows = 27 slots) ------------------

    private static final int CONFIRM_SLOT_BUTTON_CONFIRM = 11;
    private static final int CONFIRM_SLOT_ITEM = 13;
    private static final int CONFIRM_SLOT_BUTTON_CANCEL = 15;

    private final CPSMPPlugin plugin;
    private final AuctionHouseManager auction;
    private final MessageManager messages;
    private final AuctionGuiItemFactory items;

    /**
     * Sessions keyed by player UUID. Concurrent because the close
     * listener may run on the main thread while a buy callback is
     * scheduled - in practice both are main-thread, but ConcurrentHashMap
     * future-proofs us against Folia-style schedulers.
     */
    private final Map<UUID, AuctionGuiSession> sessions = new ConcurrentHashMap<>();

    public AuctionGuiManager(CPSMPPlugin plugin, AuctionHouseManager auction) {
        this.plugin = plugin;
        this.auction = auction;
        this.messages = plugin.getMessageManager();
        this.items = new AuctionGuiItemFactory(messages, auction);
    }

    // --------------------------------------------------------------- lifecycle

    /**
     * Returns whether the GUI is enabled at all. {@link AuctionCommand}
     * checks this before trying to open; on {@code false} or on a Paper
     * API failure the command falls back to its German text help.
     */
    public boolean isEnabled() {
        AuctionConfig cfg = auction.getConfig();
        return cfg != null && cfg.isGuiEnabled();
    }

    /**
     * Closes every open Auction House GUI. Used on plugin disable so
     * players don't keep stale inventories around with click handlers
     * that no longer exist.
     */
    public void closeAll() {
        for (UUID id : new java.util.ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            AuctionGuiSession s = sessions.get(id);
            if (s != null) {
                ItemStack escrow = s.takePendingSellEscrow();
                if (p != null && p.isOnline() && escrow != null) {
                    if (!AuctionGuiItemKeys.isGuiItem(escrow)) {
                        auction.safeReturnItemOrCollect(p, escrow);
                    } else {
                        plugin.getLogger().warning("[AH-GUI] Dropping GUI-tagged escrow on plugin disable for "
                                + p.getName());
                    }
                }
                s.clearSellFlowState();
            }
            if (p != null && p.isOnline()) {
                p.closeInventory();
            }
        }
        sessions.clear();
    }

    // -------------------------------------------------------------- public API

    public void openMain(Player player) {
        if (!checkGuiOrFallback(player)) return;
        AuctionGuiSession session = ensureSession(player);
        Inventory inv = buildMain(session);
        showInventory(player, session, AuctionGuiSession.Screen.MAIN, inv);
    }

    public void openBrowse(Player player, int page) {
        if (!checkGuiOrFallback(player)) return;
        if (!player.hasPermission(AuctionPermission.BROWSE)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return;
        }
        AuctionGuiSession session = ensureSession(player);
        session.setCurrentPage(page);
        loadBrowseAndOpen(player, session, page);
    }

    /**
     * Opens browse with a substring filter (same semantics as /ah search).
     */
    public void openBrowseWithSearchFilter(Player player, String query) {
        if (!checkGuiOrFallback(player)) return;
        if (!player.hasPermission(AuctionPermission.BROWSE)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return;
        }
        AuctionGuiSession session = ensureSession(player);
        session.setBrowseSearchFilter(query);
        session.setCurrentPage(1);
        loadBrowseAndOpen(player, session, 1);
    }

    public void openListings(Player player, int page) {
        if (!checkGuiOrFallback(player)) return;
        if (!player.hasPermission(AuctionPermission.LISTINGS)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return;
        }
        AuctionGuiSession session = ensureSession(player);
        session.setCurrentPage(page);
        loadListingsAndOpen(player, session, page);
    }

    public void openCollect(Player player) {
        if (!checkGuiOrFallback(player)) return;
        if (!player.hasPermission(AuctionPermission.COLLECT)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return;
        }
        AuctionGuiSession session = ensureSession(player);
        loadCollectAndOpen(player, session);
    }

    /** @return null if the player has no open Auction GUI session */
    @Nullable
    public AuctionGuiSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    /**
     * Runs when a {@link AuctionGuiSession} inventory closes. Handles
     * sell-slot returns and optional session teardown.
     */
    public void handleSessionHolderClose(Player player, AuctionGuiSession session, Inventory closed) {
        if (session.consumeSellToAnvilTransition()) {
            return;
        }
        if (session.getCurrentScreen() == AuctionGuiSession.Screen.SELL_CONFIRM) {
            ItemStack escrow = session.takePendingSellEscrow();
            session.setPendingSellConfirmPrice(null);
            if (escrow != null) {
                if (AuctionGuiItemKeys.isGuiItem(escrow)) {
                    plugin.getLogger().warning("[AH-GUI] Dropping GUI-tagged escrow on confirm close for "
                            + player.getName());
                } else {
                    auction.safeReturnItemOrCollect(player, escrow);
                    messages.sendPrefixed(player, "auction.gui.sell-item-returned");
                }
            }
        } else if (session.getCurrentScreen() == AuctionGuiSession.Screen.SELL) {
            ItemStack inSlot = closed.getItem(SELL_INPUT_SLOT);
            if (inSlot != null && !inSlot.getType().isAir()) {
                closed.setItem(SELL_INPUT_SLOT, null);
                if (AuctionGuiItemKeys.isGuiItem(inSlot)) {
                    plugin.getLogger().warning("[AH-GUI] Dropping GUI chrome from sell slot on close for "
                            + player.getName());
                } else {
                    auction.safeReturnItemOrCollect(player, inSlot);
                    messages.sendPrefixed(player, "auction.gui.sell-item-returned");
                }
            }
        }
        if (sessionInventory(session) == closed) {
            sessions.remove(player.getUniqueId());
        }
    }

    /**
     * Player quit while a sell / anvil / confirm flow might still hold
     * escrow outside any inventory.
     */
    public void handlePlayerQuit(Player player) {
        UUID id = player.getUniqueId();
        AuctionGuiSession s = sessions.remove(id);
        if (s == null) {
            return;
        }
        ItemStack escrow = s.takePendingSellEscrow();
        if (escrow != null && !AuctionGuiItemKeys.isGuiItem(escrow)) {
            auction.safeReturnItemOrCollect(player, escrow);
        } else if (escrow != null) {
            plugin.getLogger().warning("[AH-GUI] Dropping GUI-tagged escrow on quit for " + player.getName());
        }
        s.clearSellFlowState();
    }

    /** Called by the listener for every valid click in a session GUI. */
    public void handleClick(Player player, AuctionGuiSession session, int slot) {
        switch (session.getCurrentScreen()) {
            case MAIN -> handleClickMain(player, session, slot);
            case BROWSE -> handleClickBrowse(player, session, slot);
            case LISTINGS -> handleClickListings(player, session, slot);
            case COLLECT -> handleClickCollect(player, session, slot);
            case CONFIRM -> handleClickBuyConfirm(player, session, slot);
            case SELL -> handleClickSellButtons(player, session, slot);
            case SELL_CONFIRM -> handleClickSellConfirm(player, session, slot);
        }
    }

    // -------------------------------------------------------- session helpers

    private AuctionGuiSession ensureSession(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), AuctionGuiSession::new);
    }

    @Nullable
    private Inventory sessionInventory(AuctionGuiSession session) {
        try {
            return session.getInventory();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    private void showInventory(Player player, AuctionGuiSession session,
                               AuctionGuiSession.Screen screen, Inventory inv) {
        session.setCurrentScreen(screen);
        // Set the session's inventory BEFORE openInventory, so the
        // implicit close of the previous inventory fired by Bukkit
        // does not match `session.getInventory()` and therefore does
        // not tear the session down.
        session.setCurrentInventory(inv);
        player.openInventory(inv);
    }

    private boolean checkGuiOrFallback(Player player) {
        if (!isEnabled()) {
            messages.sendPrefixed(player, "auction.disabled");
            return false;
        }
        if (!auction.isActive()) {
            messages.sendPrefixed(player, "auction.disabled");
            return false;
        }
        if (!player.hasPermission(AuctionPermission.BASE)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return false;
        }
        return true;
    }

    // --------------------------------------------------------- main GUI build

    private Inventory buildMain(AuctionGuiSession session) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsMain();
        int size = rows * 9;
        Inventory inv = createInventory(session, size, "auction.gui.main-title");
        fill(inv, cfg);
        inv.setItem(clamp(MAIN_SLOT_BROWSE, size), items.mainButtonBrowse());
        if (cfg.isGuiSellEnabled()) {
            inv.setItem(clamp(MAIN_SLOT_SELL, size), items.mainButtonSell());
        }
        inv.setItem(clamp(MAIN_SLOT_LISTINGS, size), items.mainButtonListings());
        inv.setItem(clamp(MAIN_SLOT_COLLECT, size), items.mainButtonCollect());
        inv.setItem(clamp(MAIN_SLOT_INFO, size), items.mainButtonInfo());
        inv.setItem(clamp(MAIN_SLOT_CLOSE, size), items.closeButton());
        return inv;
    }

    private void handleClickMain(Player player, AuctionGuiSession session, int slot) {
        AuctionConfig cfg = auction.getConfig();
        switch (slot) {
            case MAIN_SLOT_BROWSE -> {
                ensureSession(player).setBrowseSearchFilter(null);
                openBrowse(player, 1);
            }
            case MAIN_SLOT_SELL -> {
                if (!cfg.isGuiSellEnabled()) {
                    return;
                }
                openSell(player);
            }
            case MAIN_SLOT_LISTINGS -> {
                if (!player.hasPermission(AuctionPermission.LISTINGS)) {
                    messages.sendPrefixed(player, "auction.no-permission");
                    return;
                }
                openListings(player, 1);
            }
            case MAIN_SLOT_COLLECT -> openCollect(player);
            case MAIN_SLOT_INFO -> {
                // Info button: just print the text help inline so the
                // GUI stays on the main screen.
                MessageManager m = messages;
                m.sendPrefixed(player, "auction.help");
                m.sendPrefixed(player, "auction.help-browse");
                m.sendPrefixed(player, "auction.help-buy");
                m.sendPrefixed(player, "auction.help-sell");
                m.sendPrefixed(player, "auction.help-listings");
                m.sendPrefixed(player, "auction.help-cancel");
                m.sendPrefixed(player, "auction.help-collect");
            }
            case MAIN_SLOT_CLOSE -> player.closeInventory();
            default -> { /* filler / no-op */ }
        }
    }

    // ------------------------------------------------------- browse GUI build

    private void loadBrowseAndOpen(Player player, AuctionGuiSession session, int page) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsBrowse();
        int gridSlots = (rows - 1) * 9;
        AuctionBrowseSort sort = session.effectiveBrowseSort(cfg.getGuiBrowseDefaultSort());
        String q = session.getBrowseSearchFilter();
        final int fetchGen = session.beginBrowseFetch();
        auction.browseListings(page, gridSlots, sort, q).thenAccept(browsePage -> {
            if (!player.isOnline()) return;
            if (fetchGen != session.getBrowseFetchGeneration()) return;
            session.setVisibleListings(browsePage.listings());
            session.setCurrentPage(browsePage.page());
            Inventory inv = buildBrowse(session, browsePage);
            showInventory(player, session, AuctionGuiSession.Screen.BROWSE, inv);
        });
    }

    private Inventory buildBrowse(AuctionGuiSession session, AuctionHouseManager.BrowsePage browsePage) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsBrowse();
        int size = rows * 9;
        Inventory inv = createInventory(session, size, "auction.gui.browse-title");
        fill(inv, cfg);
        if (browsePage.totalListings() == 0) {
            inv.setItem(size / 2, items.browseEmptyPlaceholder());
        } else {
            long now = System.currentTimeMillis();
            boolean allowOwn = cfg.isAllowOwnPurchase();
            UUID viewer = session.getPlayerId();
            int max = Math.min(browsePage.listings().size(), (rows - 1) * 9);
            for (int i = 0; i < max; i++) {
                inv.setItem(i, items.browseTile(browsePage.listings().get(i), viewer, allowOwn, now));
            }
        }
        applyBrowseNavRow(inv, rows, browsePage, session, cfg);
        return inv;
    }

    private void applyBrowseNavRow(Inventory inv,
                                   int rows,
                                   AuctionHouseManager.BrowsePage browsePage,
                                   AuctionGuiSession session,
                                   AuctionConfig cfg) {
        int navBase = (rows - 1) * 9;
        int page = browsePage.page();
        int totalPages = browsePage.totalPages();
        inv.setItem(navBase + NAV_ROW_OFFSET_BACK, items.backButton());
        if (cfg.isGuiBrowseShowRefreshButton()) {
            inv.setItem(navBase + NAV_ROW_OFFSET_REFRESH, items.browseRefreshButton());
        }
        if (cfg.isGuiBrowseShowSortButton()) {
            AuctionBrowseSort eff = session.effectiveBrowseSort(cfg.getGuiBrowseDefaultSort());
            inv.setItem(navBase + NAV_ROW_OFFSET_SORT, items.browseSortButton(eff));
        }
        if (page > 1) {
            inv.setItem(navBase + NAV_ROW_OFFSET_PREV, items.previousPageButton(page - 1));
        }
        inv.setItem(navBase + NAV_ROW_OFFSET_PAGE, items.pageIndicator(page, totalPages));
        if (page < totalPages) {
            inv.setItem(navBase + NAV_ROW_OFFSET_NEXT, items.nextPageButton(page + 1));
        }
        inv.setItem(navBase + NAV_ROW_OFFSET_CLOSE, items.closeButton());
    }

    private void handleClickBrowse(Player player, AuctionGuiSession session, int slot) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsBrowse();
        int gridSlots = (rows - 1) * 9;
        int navBase = (rows - 1) * 9;
        if (slot == navBase + NAV_ROW_OFFSET_BACK) {
            openMain(player);
            return;
        }
        if (cfg.isGuiBrowseShowRefreshButton() && slot == navBase + NAV_ROW_OFFSET_REFRESH) {
            loadBrowseAndOpen(player, session, session.getCurrentPage());
            return;
        }
        if (cfg.isGuiBrowseShowSortButton() && slot == navBase + NAV_ROW_OFFSET_SORT) {
            session.cycleBrowseSort(cfg.getGuiBrowseDefaultSort());
            loadBrowseAndOpen(player, session, 1);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_PREV && session.getCurrentPage() > 1) {
            loadBrowseAndOpen(player, session, session.getCurrentPage() - 1);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_NEXT) {
            loadBrowseAndOpen(player, session, session.getCurrentPage() + 1);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= gridSlots) return;
        List<AuctionListing> visible = session.getVisibleListings();
        if (slot >= visible.size()) return;
        AuctionListing target = visible.get(slot);
        // Own-listing guard. Backend will also enforce, but we surface
        // a quicker GUI-level message and skip the round-trip.
        if (target.sellerUuid().equals(player.getUniqueId())
                && !cfg.isAllowOwnPurchase()) {
            messages.sendPrefixed(player, "auction.buy-own-listing");
            return;
        }
        // Either jump straight to buy, or open the confirm GUI when
        // the price crosses the configured threshold.
        if (cfg.isGuiConfirmationEnabled()
                && target.price() >= cfg.getExpensivePurchaseThreshold()) {
            openConfirm(player, session, target);
        } else {
            performBuy(player, session, target);
        }
    }

    // ------------------------------------------------------ listings GUI build

    private void loadListingsAndOpen(Player player, AuctionGuiSession session, int requestedPage) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsListings();
        int gridSlots = (rows - 1) * 9;
        auction.getActiveListings(player.getUniqueId()).thenAccept(all -> {
            if (!player.isOnline()) return;
            int total = all.size();
            int totalPages = Math.max(1, (total + gridSlots - 1) / gridSlots);
            int page = Math.max(1, Math.min(requestedPage, totalPages));
            int from = (page - 1) * gridSlots;
            int to = Math.min(total, from + gridSlots);
            List<AuctionListing> visible = all.subList(from, to);
            session.setVisibleListings(visible);
            session.setCurrentPage(page);
            Inventory inv = buildListings(session, visible, page, totalPages, total);
            showInventory(player, session, AuctionGuiSession.Screen.LISTINGS, inv);
        });
    }

    private Inventory buildListings(AuctionGuiSession session, List<AuctionListing> visible,
                                    int page, int totalPages, int total) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsListings();
        int size = rows * 9;
        Inventory inv = createInventory(session, size, "auction.gui.listings-title");
        fill(inv, cfg);
        if (total == 0) {
            inv.setItem(size / 2, items.emptyStateTile("auction.gui.no-listings"));
        } else {
            long now = System.currentTimeMillis();
            for (int i = 0; i < visible.size(); i++) {
                inv.setItem(i, items.listingsTile(visible.get(i), now));
            }
        }
        applyNavRow(inv, rows, page, totalPages);
        return inv;
    }

    private void handleClickListings(Player player, AuctionGuiSession session, int slot) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsListings();
        int gridSlots = (rows - 1) * 9;
        int navBase = (rows - 1) * 9;
        if (slot == navBase + NAV_ROW_OFFSET_BACK) {
            openMain(player);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_PREV && session.getCurrentPage() > 1) {
            loadListingsAndOpen(player, session, session.getCurrentPage() - 1);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_NEXT) {
            loadListingsAndOpen(player, session, session.getCurrentPage() + 1);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= gridSlots) return;
        List<AuctionListing> visible = session.getVisibleListings();
        if (slot >= visible.size()) return;
        AuctionListing target = visible.get(slot);
        auction.cancelListing(player.getUniqueId(), target.listingId(), false)
                .thenAccept(result -> {
                    switch (result) {
                        case AuctionHouseManager.CancelResult.Success s ->
                                messages.sendPrefixed(player, "auction.listing-cancelled",
                                        Map.of("id", Long.toString(s.listing().listingId())));
                        case AuctionHouseManager.CancelResult.Failure f ->
                                messages.sendPrefixed(player, f.messageKey());
                    }
                    if (cfg.isGuiRefreshAfterAction() && player.isOnline()) {
                        loadListingsAndOpen(player, session, session.getCurrentPage());
                    }
                });
    }

    // ------------------------------------------------------- collect GUI build

    private void loadCollectAndOpen(Player player, AuctionGuiSession session) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsCollect();
        int gridSlots = (rows - 1) * 9;
        auction.getCollectItems(player.getUniqueId()).thenAccept(collectItems -> {
            if (!player.isOnline()) return;
            // No pagination on collect for V2.3 - admins can grow the
            // GUI to 6 rows for 45 visible items, which is more than
            // any reasonable collect storage holds in normal play.
            List<AuctionCollectItem> visible = collectItems.size() > gridSlots
                    ? collectItems.subList(0, gridSlots)
                    : collectItems;
            session.setVisibleCollect(visible);
            Inventory inv = buildCollect(session, visible, collectItems.size());
            showInventory(player, session, AuctionGuiSession.Screen.COLLECT, inv);
        });
    }

    private Inventory buildCollect(AuctionGuiSession session,
                                   List<AuctionCollectItem> visible,
                                   int total) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsCollect();
        int size = rows * 9;
        Inventory inv = createInventory(session, size, "auction.gui.collect-title");
        fill(inv, cfg);
        if (total == 0) {
            inv.setItem(size / 2, items.emptyStateTile("auction.gui.no-collect-items"));
        } else {
            long now = System.currentTimeMillis();
            for (int i = 0; i < visible.size(); i++) {
                inv.setItem(i, items.collectTile(visible.get(i), now));
            }
        }
        int navBase = (rows - 1) * 9;
        inv.setItem(navBase + NAV_ROW_OFFSET_BACK, items.backButton());
        if (total > 0) {
            inv.setItem(navBase + NAV_ROW_OFFSET_COLLECT_ALL, items.collectAllButton());
        }
        inv.setItem(navBase + NAV_ROW_OFFSET_CLOSE, items.closeButton());
        return inv;
    }

    private void handleClickCollect(Player player, AuctionGuiSession session, int slot) {
        AuctionConfig cfg = auction.getConfig();
        int rows = cfg.getGuiRowsCollect();
        int gridSlots = (rows - 1) * 9;
        int navBase = (rows - 1) * 9;
        if (slot == navBase + NAV_ROW_OFFSET_BACK) {
            openMain(player);
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_COLLECT_ALL) {
            auction.collectAll(player).thenAccept(result -> {
                handleCollectResult(player, result);
                if (cfg.isGuiRefreshAfterAction() && player.isOnline()) {
                    loadCollectAndOpen(player, session);
                }
            });
            return;
        }
        if (slot == navBase + NAV_ROW_OFFSET_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= gridSlots) return;
        List<AuctionCollectItem> visible = session.getVisibleCollect();
        if (slot >= visible.size()) return;
        AuctionCollectItem target = visible.get(slot);
        auction.collectOne(player, target.collectId()).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.CollectOneResult.Delivered ignored ->
                        messages.sendPrefixed(player, "auction.collect-success",
                                Map.of("count", "1"));
                case AuctionHouseManager.CollectOneResult.Partial p ->
                        messages.sendPrefixed(player, "auction.collect-partial",
                                Map.of("count", Integer.toString(p.deliveredAmount()),
                                        "remaining", Integer.toString(p.remainingAmount())));
                case AuctionHouseManager.CollectOneResult.InventoryFull ignored ->
                        messages.sendPrefixed(player, "auction.buy-inventory-full");
                case AuctionHouseManager.CollectOneResult.NotFound ignored ->
                        messages.sendPrefixed(player, "auction.gui.stale-listing");
                case AuctionHouseManager.CollectOneResult.Failure f ->
                        messages.sendPrefixed(player, f.messageKey());
            }
            if (cfg.isGuiRefreshAfterAction() && player.isOnline()) {
                loadCollectAndOpen(player, session);
            }
        });
    }

    private void handleCollectResult(Player player, AuctionHouseManager.CollectResult result) {
        switch (result) {
            case AuctionHouseManager.CollectResult.Empty ignored ->
                    messages.sendPrefixed(player, "auction.collect-empty");
            case AuctionHouseManager.CollectResult.Success s ->
                    messages.sendPrefixed(player, "auction.collect-success",
                            Map.of("count", Integer.toString(s.delivered())));
            case AuctionHouseManager.CollectResult.Partial p ->
                    messages.sendPrefixed(player, "auction.collect-partial",
                            Map.of("count", Integer.toString(p.delivered()),
                                    "remaining", Integer.toString(p.remaining())));
            case AuctionHouseManager.CollectResult.Failure f ->
                    messages.sendPrefixed(player, f.messageKey());
        }
    }

    // ------------------------------------------------------- confirm GUI build

    private void openConfirm(Player player, AuctionGuiSession session, AuctionListing target) {
        session.setPendingConfirmListing(target);
        session.setConfirmReturnScreen(AuctionGuiSession.Screen.BROWSE);
        session.setConfirmReturnPage(session.getCurrentPage());
        Inventory inv = buildConfirm(session, target);
        showInventory(player, session, AuctionGuiSession.Screen.CONFIRM, inv);
    }

    private Inventory buildConfirm(AuctionGuiSession session, AuctionListing target) {
        AuctionConfig cfg = auction.getConfig();
        int size = 27;
        Inventory inv = createInventory(session, size, "auction.gui.confirm-title");
        fill(inv, cfg);
        inv.setItem(CONFIRM_SLOT_BUTTON_CONFIRM, items.confirmButton(target));
        inv.setItem(CONFIRM_SLOT_ITEM, items.confirmPreview(target, System.currentTimeMillis()));
        inv.setItem(CONFIRM_SLOT_BUTTON_CANCEL, items.cancelButton());
        return inv;
    }

    private void handleClickBuyConfirm(Player player, AuctionGuiSession session, int slot) {
        AuctionListing pending = session.getPendingConfirmListing();
        if (pending == null) {
            openBrowse(player, 1);
            return;
        }
        switch (slot) {
            case CONFIRM_SLOT_BUTTON_CONFIRM -> performBuy(player, session, pending);
            case CONFIRM_SLOT_BUTTON_CANCEL -> {
                AuctionGuiSession.Screen back = session.getConfirmReturnScreen();
                if (back == AuctionGuiSession.Screen.BROWSE) {
                    openBrowse(player, session.getConfirmReturnPage());
                } else {
                    openMain(player);
                }
            }
            default -> { /* filler / item slot - no-op */ }
        }
    }

    // ----------------------------------------------------------- buy delegate

    /**
     * Bridges a GUI click to {@link AuctionHouseManager#buyListing}.
     * The manager performs the atomic claim and re-validates everything
     * (status, expiry, balance, own-listing rule) so this method just
     * surfaces the German result message and optionally refreshes the
     * browse view.
     */
    private void performBuy(Player player, AuctionGuiSession session, AuctionListing target) {
        AuctionConfig cfg = auction.getConfig();
        auction.buyListing(player, target.listingId()).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.BuyResult.Success s -> {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("id", Long.toString(s.listing().listingId()));
                    ph.put("item", s.listing().itemStack().getType().name());
                    ph.put("amount", Integer.toString(s.listing().itemStack().getAmount()));
                    ph.put("price", auction.formatPrice(s.price()));
                    ph.put("tax", auction.formatPrice(s.tax()));
                    ph.put("payout", auction.formatPrice(s.sellerPayout()));
                    ph.put("seller", s.listing().sellerName() == null ? "-" : s.listing().sellerName());
                    messages.sendPrefixed(player, "auction.buy-success", ph);
                    if (s.tax() > 0.0D) {
                        messages.sendPrefixed(player, "auction.sale-tax-info", ph);
                    }
                    if (s.inventoryFull()) {
                        messages.sendPrefixed(player, "auction.buy-inventory-full");
                    }
                }
                case AuctionHouseManager.BuyResult.Failure f -> {
                    // Re-route "buy-already-sold" / "buy-expired" through
                    // the GUI's "stale listing" copy so the message
                    // reads naturally after closing a confirmation
                    // dialog from a list that may have aged.
                    String key = f.messageKey();
                    if ("auction.buy-already-sold".equals(key)
                            || "auction.buy-expired".equals(key)
                            || "auction.buy-not-found".equals(key)
                            || "auction.buy-not-active".equals(key)) {
                        messages.sendPrefixed(player, "auction.gui.stale-listing");
                    } else {
                        messages.sendPrefixed(player, key, f.placeholders());
                    }
                }
            }
            if (cfg.isGuiRefreshAfterAction() && player.isOnline()) {
                // After a confirm-flow buy, return to browse; after a
                // direct browse-flow buy, refresh in place.
                if (session.getCurrentScreen() == AuctionGuiSession.Screen.CONFIRM) {
                    openBrowse(player, session.getConfirmReturnPage());
                } else if (session.getCurrentScreen() == AuctionGuiSession.Screen.BROWSE) {
                    loadBrowseAndOpen(player, session, session.getCurrentPage());
                }
            }
        });
    }

    // ----------------------------------------------------------- V2.4 sell GUI

    public void openSell(Player player) {
        if (!checkGuiOrFallback(player)) return;
        AuctionConfig cfg = auction.getConfig();
        if (!cfg.isGuiSellEnabled()) {
            messages.sendPrefixed(player, "auction.disabled");
            return;
        }
        if (!player.hasPermission(AuctionPermission.SELL)) {
            messages.sendPrefixed(player, "auction.no-permission");
            return;
        }
        AuctionGuiSession session = ensureSession(player);
        session.clearSellFlowState();
        Inventory inv = buildSell(session);
        showInventory(player, session, AuctionGuiSession.Screen.SELL, inv);
    }

    private Inventory buildSell(AuctionGuiSession session) {
        AuctionConfig cfg = auction.getConfig();
        int rows = 3;
        int size = rows * 9;
        Inventory inv = createInventory(session, size, "auction.gui.sell-title");
        fill(inv, cfg);
        inv.setItem(SELL_INPUT_SLOT, null);
        inv.setItem(SELL_SLOT_SET_PRICE, items.sellSetPriceButton());
        inv.setItem(SELL_SLOT_CANCEL, items.sellAbortButton());
        inv.setItem(SELL_SLOT_BACK, items.sellBackButton());
        return inv;
    }

    /**
     * Button-only clicks on the sell GUI top inventory (input + player
     * inventory interactions bypass this).
     */
    void handleClickSellButtons(Player player, AuctionGuiSession session, int slot) {
        AuctionConfig cfg = auction.getConfig();
        switch (slot) {
            case SELL_SLOT_SET_PRICE -> beginAnvilPriceEntry(player, session, cfg);
            case SELL_SLOT_BACK -> {
                returnSellInputSlotToPlayer(player, player.getOpenInventory().getTopInventory());
                messages.sendPrefixed(player, "auction.gui.sell-cancelled");
                openMain(player);
            }
            case SELL_SLOT_CANCEL -> {
                returnSellInputSlotToPlayer(player, player.getOpenInventory().getTopInventory());
                messages.sendPrefixed(player, "auction.gui.sell-cancelled");
                player.closeInventory();
            }
            default -> { /* filler */ }
        }
    }

    private void returnSellInputSlotToPlayer(Player player, Inventory top) {
        ItemStack s = top.getItem(SELL_INPUT_SLOT);
        if (s != null && !s.getType().isAir()) {
            top.setItem(SELL_INPUT_SLOT, null);
            if (AuctionGuiItemKeys.isGuiItem(s)) {
                plugin.getLogger().warning("[AH-GUI] Dropping GUI chrome from sell slot for " + player.getName());
                return;
            }
            auction.safeReturnItemOrCollect(player, s);
            messages.sendPrefixed(player, "auction.gui.sell-item-returned");
        }
    }

    private void beginAnvilPriceEntry(Player player, AuctionGuiSession session, AuctionConfig cfg) {
        Inventory top = player.getOpenInventory().getTopInventory();
        if (top.getHolder() != session || session.getCurrentScreen() != AuctionGuiSession.Screen.SELL) {
            return;
        }
        ItemStack listed = top.getItem(SELL_INPUT_SLOT);
        if (listed == null || listed.getType().isAir() || listed.getAmount() <= 0) {
            messages.sendPrefixed(player, "auction.gui.sell-no-item");
            return;
        }
        if (!listed.getType().isItem()) {
            messages.sendPrefixed(player, "auction.invalid-item");
            return;
        }
        if (AuctionGuiItemKeys.isGuiItem(listed)) {
            messages.sendPrefixed(player, "auction.gui.sell-no-item");
            return;
        }
        if (cfg.getBlockedMaterials().contains(listed.getType())) {
            messages.sendPrefixed(player, "auction.blocked-item",
                    Map.of("material", listed.getType().name()));
            return;
        }
        if (!cfg.isGuiSellUseAnvilPriceInput()) {
            messages.sendPrefixed(player, "auction.help-sell");
            return;
        }

        ItemStack escrow = listed.clone();
        top.setItem(SELL_INPUT_SLOT, null);
        session.setPendingSellEscrow(escrow);
        session.setSellToAnvilTransition(true);
        session.setAwaitingAnvilPrice(true);

        Location loc = player.getLocation();
        openAnvilVirtual(player, loc);
        Bukkit.getScheduler().runTask(plugin, () -> primeAnvilPaperTemplate(player, session));
    }

    /**
     * Paper still routes virtual anvils through {@link Player#openAnvil(
     * Location, boolean)}; newer API entries may supersede this in a
     * future Paper release without changing our AnvilView-based rename
     * plumbing.
     */
    @SuppressWarnings("deprecation")
    private static void openAnvilVirtual(Player player, Location loc) {
        player.openAnvil(loc, true);
    }

    private void primeAnvilPaperTemplate(Player player, AuctionGuiSession session) {
        if (!player.isOnline() || !session.isAwaitingAnvilPrice()) {
            return;
        }
        org.bukkit.inventory.InventoryView view = player.getOpenInventory();
        if (view.getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.ANVIL) {
            abortAnvilFlow(player, session, "auction.gui.sell-session-expired");
            return;
        }
        org.bukkit.inventory.ItemStack t = items.anvilPriceTemplatePaper();
        view.getTopInventory().setItem(0, t);
        applyZeroAnvilRepairCost(view);
    }

    private static void applyZeroAnvilRepairCost(org.bukkit.inventory.InventoryView view) {
        if (!(view instanceof org.bukkit.inventory.view.AnvilView av)) {
            return;
        }
        av.setRepairCost(0);
        try {
            av.setMaximumRepairCost(0);
        } catch (Throwable ignored) {
        }
    }

    private static void clearAnvilOutputAndCursor(Player player, org.bukkit.inventory.InventoryView view) {
        player.setItemOnCursor(null);
        view.getTopInventory().setItem(2, null);
        applyZeroAnvilRepairCost(view);
    }

    /**
     * Called from the click listener when the player clicks the anvil
     * output slot. Uses {@link org.bukkit.inventory.view.AnvilView
     * #getRenameText()} (Paper) — never {@code AnvilInventory#getRenameText}.
     */
    public void handleAnvilOutputClick(Player player, AuctionGuiSession session,
                                       org.bukkit.inventory.InventoryView view, int rawSlot) {
        player.setItemOnCursor(null);
        if (!session.isAwaitingAnvilPrice() || rawSlot != 2) {
            return;
        }
        if (!(view instanceof org.bukkit.inventory.view.AnvilView anvilView)) {
            messages.sendPrefixed(player, "auction.gui.sell-session-expired");
            abortAnvilFlow(player, session, null);
            return;
        }
        String rename;
        try {
            rename = anvilView.getRenameText();
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "AnvilView#getRenameText() unavailable", t);
            messages.sendPrefixed(player, "auction.gui.sell-session-expired");
            abortAnvilFlow(player, session, null);
            return;
        }
        AuctionPriceParser.Result parsed = AuctionPriceParser.parseStrictPositive(rename);
        if (!parsed.ok()) {
            messages.sendPrefixed(player, "auction.gui.sell-invalid-price");
            clearAnvilOutputAndCursor(player, view);
            Bukkit.getScheduler().runTask(plugin, () -> primeAnvilPaperTemplate(player, session));
            return;
        }
        AuctionConfig cfg = auction.getConfig();
        if (!validateParsedPrice(player, cfg, parsed.value())) {
            clearAnvilOutputAndCursor(player, view);
            Bukkit.getScheduler().runTask(plugin, () -> primeAnvilPaperTemplate(player, session));
            return;
        }
        session.setAwaitingAnvilPrice(false);
        session.setPendingSellConfirmPrice(parsed.value());
        player.setItemOnCursor(null);
        view.getTopInventory().clear();
        applyZeroAnvilRepairCost(view);
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            openSellConfirm(player, session);
        });
    }

    private boolean validateParsedPrice(Player player, AuctionConfig cfg, double price) {
        if (price < cfg.getMinPrice()) {
            messages.sendPrefixed(player, "auction.price-too-low",
                    Map.of("min", auction.formatPrice(cfg.getMinPrice())));
            return false;
        }
        if (price > cfg.getMaxPrice()) {
            messages.sendPrefixed(player, "auction.price-too-high",
                    Map.of("max", auction.formatPrice(cfg.getMaxPrice())));
            return false;
        }
        return true;
    }

    /** Anvil closed without submitting a valid price — reopen sell GUI. */
    public void handleAnvilClose(Player player, AuctionGuiSession session) {
        if (!session.isAwaitingAnvilPrice()) {
            return;
        }
        player.setItemOnCursor(null);
        session.setAwaitingAnvilPrice(false);
        ItemStack escrow = session.takePendingSellEscrow();
        session.setSellToAnvilTransition(false);
        if (!player.isOnline()) {
            if (escrow != null) {
                auction.safeReturnItemOrCollect(player, escrow);
            }
            return;
        }
        Inventory inv = buildSell(session);
        showInventory(player, session, AuctionGuiSession.Screen.SELL, inv);
        if (escrow != null) {
            player.getOpenInventory().getTopInventory().setItem(SELL_INPUT_SLOT, escrow.clone());
        }
    }

    private void abortAnvilFlow(Player player, AuctionGuiSession session, @Nullable String messageKey) {
        session.setAwaitingAnvilPrice(false);
        if (player.isOnline()) {
            player.setItemOnCursor(null);
        }
        ItemStack escrow = session.takePendingSellEscrow();
        session.setSellToAnvilTransition(false);
        if (escrow != null) {
            auction.safeReturnItemOrCollect(player, escrow);
        }
        if (messageKey != null && player.isOnline()) {
            messages.sendPrefixed(player, messageKey);
        }
    }

    private void openSellConfirm(Player player, AuctionGuiSession session) {
        ItemStack escrow = session.getPendingSellEscrow();
        Double price = session.getPendingSellConfirmPrice();
        if (escrow == null || price == null) {
            messages.sendPrefixed(player, "auction.gui.sell-session-expired");
            openMain(player);
            return;
        }
        Inventory inv = buildSellConfirm(session, escrow, price);
        showInventory(player, session, AuctionGuiSession.Screen.SELL_CONFIRM, inv);
    }

    private Inventory buildSellConfirm(AuctionGuiSession session, ItemStack escrow, double price) {
        AuctionConfig cfg = auction.getConfig();
        int size = 27;
        Inventory inv = createInventory(session, size, "auction.gui.sell-confirm-title");
        fill(inv, cfg);
        inv.setItem(SELL_CONFIRM_SLOT_ITEM, items.sellConfirmPreview(escrow, price, cfg));
        inv.setItem(SELL_CONFIRM_SLOT_CREATE, items.sellConfirmCreateButton());
        inv.setItem(SELL_CONFIRM_SLOT_CANCEL, items.sellConfirmCancelButton());
        return inv;
    }

    void handleClickSellConfirm(Player player, AuctionGuiSession session, int slot) {
        switch (slot) {
            case SELL_CONFIRM_SLOT_CREATE -> performCreateListingFromGui(player, session);
            case SELL_CONFIRM_SLOT_CANCEL -> {
                ItemStack escrow = session.takePendingSellEscrow();
                session.setPendingSellConfirmPrice(null);
                if (escrow != null) {
                    if (AuctionGuiItemKeys.isGuiItem(escrow)) {
                        plugin.getLogger().warning("[AH-GUI] Dropping GUI-tagged escrow on sell-confirm cancel for "
                                + player.getName());
                    } else {
                        auction.safeReturnItemOrCollect(player, escrow);
                        messages.sendPrefixed(player, "auction.gui.sell-item-returned");
                    }
                }
                messages.sendPrefixed(player, "auction.gui.sell-cancelled");
                openMain(player);
            }
            default -> { }
        }
    }

    private void performCreateListingFromGui(Player player, AuctionGuiSession session) {
        ItemStack escrow = session.takePendingSellEscrow();
        Double priceObj = session.getPendingSellConfirmPrice();
        session.setPendingSellConfirmPrice(null);
        if (escrow == null || priceObj == null) {
            messages.sendPrefixed(player, "auction.gui.sell-session-expired");
            openMain(player);
            return;
        }
        double price = priceObj;
        auction.createListing(player, escrow, price).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.ListingCreateResult.Success s -> {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("id", Long.toString(s.listingId()));
                    ph.put("item", s.snapshot().getType().name());
                    ph.put("amount", Integer.toString(s.snapshot().getAmount()));
                    ph.put("price", auction.formatPrice(s.price()));
                    messages.sendPrefixed(player, "auction.gui.sell-created", ph);
                    session.clearSellFlowState();
                    if (player.isOnline()) {
                        navigateAfterSellCreate(player);
                    }
                }
                case AuctionHouseManager.ListingCreateResult.Failure f -> {
                    messages.sendPrefixed(player, f.messageKey(), f.placeholders());
                    if ("auction.storage-error".equals(f.messageKey())) {
                        session.clearSellFlowState();
                        if (player.isOnline()) {
                            openMain(player);
                        }
                    } else {
                        session.setPendingSellEscrow(escrow);
                        session.setPendingSellConfirmPrice(price);
                        if (player.isOnline()) {
                            openSellConfirm(player, session);
                        }
                    }
                }
            }
        });
    }

    void sendSellInputOccupiedMessage(Player player) {
        messages.sendPrefixed(player, "auction.gui.sell-input-occupied");
    }

    private void navigateAfterSellCreate(Player player) {
        AuctionConfig cfg = auction.getConfig();
        if (cfg.isGuiSellOpenListingsAfterCreate()) {
            if (player.hasPermission(AuctionPermission.LISTINGS)) {
                openListings(player, 1);
            } else {
                openMain(player);
            }
        } else if (cfg.isGuiSellReturnToMainAfterCreate()) {
            openMain(player);
        } else {
            openMain(player);
        }
    }

    // --------------------------------------------------------------- helpers

    @SuppressWarnings("deprecation") // Spigot-only fallback to legacy String title overload
    private Inventory createInventory(AuctionGuiSession session, int size, String titleKey) {
        Component title = messages.component(titleKey);
        try {
            return Bukkit.createInventory(session, size, title);
        } catch (Throwable t) {
            // Spigot fallback: createInventory(Holder, int, Component)
            // doesn't exist there. Surface a SEVERE log so admins know
            // the GUI cannot render on this platform.
            plugin.getLogger().log(Level.SEVERE,
                    "Bukkit.createInventory(Holder,int,Component) is not available; "
                            + "GUI requires Paper.", t);
            // Fall back to the deprecated String overload using a
            // best-effort plain-text version of the title.
            return Bukkit.createInventory(session, size,
                    net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(title));
        }
    }

    private void fill(Inventory inv, AuctionConfig cfg) {
        if (!cfg.isGuiFillerEnabled()) return;
        ItemStack fillerStack = items.filler(cfg);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, fillerStack);
        }
    }

    private void applyNavRow(Inventory inv, int rows, int page, int totalPages) {
        int navBase = (rows - 1) * 9;
        inv.setItem(navBase + NAV_ROW_OFFSET_BACK, items.backButton());
        if (page > 1) {
            inv.setItem(navBase + NAV_ROW_OFFSET_PREV, items.previousPageButton(page - 1));
        }
        inv.setItem(navBase + NAV_ROW_OFFSET_PAGE, items.pageIndicator(page, totalPages));
        if (page < totalPages) {
            inv.setItem(navBase + NAV_ROW_OFFSET_NEXT, items.nextPageButton(page + 1));
        }
        inv.setItem(navBase + NAV_ROW_OFFSET_CLOSE, items.closeButton());
    }

    private static int clamp(int slot, int size) {
        return Math.max(0, Math.min(slot, size - 1));
    }
}
