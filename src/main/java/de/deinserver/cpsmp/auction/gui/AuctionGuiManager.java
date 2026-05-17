package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionConfig;
import de.deinserver.cpsmp.auction.AuctionHouseManager;
import de.deinserver.cpsmp.auction.AuctionListing;
import de.deinserver.cpsmp.auction.AuctionPermission;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
 * cancel / collect logic is duplicated here.
 *
 * <p>Threading: every public entry point is called from the main
 * server thread (either directly from a command, or from a
 * {@code CompletableFuture#thenAccept} callback that the
 * AuctionHouseManager guarantees runs on the main thread). Inventory
 * mutations are therefore always main-thread.
 */
public final class AuctionGuiManager {

    // -------- Main GUI slot layout (3 rows = 27 slots) ---------------------

    private static final int MAIN_SLOT_BROWSE = 11;
    private static final int MAIN_SLOT_LISTINGS = 13;
    private static final int MAIN_SLOT_COLLECT = 15;
    private static final int MAIN_SLOT_INFO = 22;
    private static final int MAIN_SLOT_CLOSE = 26;

    // -------- Browse / Listings / Collect navigation row layout ------------

    private static final int NAV_ROW_OFFSET_BACK = 0;        // slot 45 on a 6-row GUI
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

    public void openListings(Player player, int page) {
        if (!checkGuiOrFallback(player)) return;
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

    /** Called by the listener when a player closes the active inventory. */
    public void handleClose(UUID playerId, Inventory closed) {
        AuctionGuiSession session = sessions.get(playerId);
        if (session == null) return;
        // Only tear the session down when the inventory that closed is
        // the one we currently track. Bukkit fires CloseEvent for the
        // previous inventory when we open a new screen, and we must
        // ignore that case.
        if (sessionInventory(session) == closed) {
            sessions.remove(playerId);
        }
    }

    /** Called by the listener for every valid click in a session GUI. */
    public void handleClick(Player player, AuctionGuiSession session, int slot) {
        switch (session.getCurrentScreen()) {
            case MAIN -> handleClickMain(player, session, slot);
            case BROWSE -> handleClickBrowse(player, session, slot);
            case LISTINGS -> handleClickListings(player, session, slot);
            case COLLECT -> handleClickCollect(player, session, slot);
            case CONFIRM -> handleClickConfirm(player, session, slot);
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
        inv.setItem(clamp(MAIN_SLOT_LISTINGS, size), items.mainButtonListings());
        inv.setItem(clamp(MAIN_SLOT_COLLECT, size), items.mainButtonCollect());
        inv.setItem(clamp(MAIN_SLOT_INFO, size), items.mainButtonInfo());
        inv.setItem(clamp(MAIN_SLOT_CLOSE, size), items.closeButton());
        return inv;
    }

    private void handleClickMain(Player player, AuctionGuiSession session, int slot) {
        switch (slot) {
            case MAIN_SLOT_BROWSE -> openBrowse(player, 1);
            case MAIN_SLOT_LISTINGS -> openListings(player, 1);
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
        auction.browseListings(page, gridSlots).thenAccept(browsePage -> {
            if (!player.isOnline()) return;
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
            inv.setItem(size / 2, items.emptyStateTile("auction.gui.no-auctions"));
        } else {
            long now = System.currentTimeMillis();
            boolean allowOwn = cfg.isAllowOwnPurchase();
            UUID viewer = session.getPlayerId();
            int max = Math.min(browsePage.listings().size(), (rows - 1) * 9);
            for (int i = 0; i < max; i++) {
                inv.setItem(i, items.browseTile(browsePage.listings().get(i), viewer, allowOwn, now));
            }
        }
        applyNavRow(inv, rows, browsePage.page(), browsePage.totalPages());
        return inv;
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

    private void handleClickConfirm(Player player, AuctionGuiSession session, int slot) {
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
