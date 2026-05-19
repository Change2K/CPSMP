package de.deinserver.cpsmp.claims.gui;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.claims.Claim;
import de.deinserver.cpsmp.claims.ClaimConfig;
import de.deinserver.cpsmp.claims.ClaimFlag;
import de.deinserver.cpsmp.claims.ClaimManager;
import de.deinserver.cpsmp.claims.ClaimPermission;
import de.deinserver.cpsmp.claims.ClaimVisualService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public final class ClaimGuiManager {

    private static final int LIST_SIZE = 54;
    private static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final int BTN_PREV = 45;
    private static final int BTN_NEXT = 53;
    private static final int BTN_MERGE = 49;

    private static final int DETAIL_SIZE = 27;
    private static final int DEL_SIZE = 27;
    private static final int FLAGS_SIZE = 54;
    private static final int FLAGS_BACK_SLOT = 49;
    private static final int[] FLAG_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23
    };

    private final CPSMPPlugin plugin;
    private final ClaimManager claims;
    private final ClaimGuiItemFactory items;
    private final Map<UUID, ClaimGuiSession> sessions = new ConcurrentHashMap<>();

    public ClaimGuiManager(CPSMPPlugin plugin, ClaimManager claims) {
        this.plugin = plugin;
        this.claims = claims;
        this.items = new ClaimGuiItemFactory(plugin.getMessageManager());
    }

    public @Nullable ClaimGuiSession getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public void handlePlayerQuit(@NotNull Player player) {
        sessions.remove(player.getUniqueId());
    }

    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top.getHolder() instanceof ClaimGuiSession) {
                p.closeInventory();
            }
        }
        sessions.clear();
    }

    public void handleSessionHolderClose(@NotNull Player player, @NotNull ClaimGuiSession session,
                                         @NotNull Inventory closed) {
        if (session.attachedInventory() == closed) {
            session.clearInventoryRef();
        }
    }

    public void openMain(@NotNull Player player) {
        ClaimConfig cfg = claims.getConfig();
        if (!cfg.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (!claims.isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        ClaimGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ClaimGuiSession::new);
        session.setScreen(ClaimGuiSession.Screen.LIST);
        List<Claim> owned = claims.getCache().listForOwner(player.getUniqueId());
        paintList(player, session, owned);
    }

    private void paintList(@NotNull Player player, @NotNull ClaimGuiSession session, @NotNull List<Claim> owned) {
        Inventory inv = createInv(session, LIST_SIZE, "claim.gui-title");
        session.attachInventory(inv);
        ItemStack fill = items.filler();
        for (int i = 0; i < LIST_SIZE; i++) {
            inv.setItem(i, fill.clone());
        }
        int page = session.listPage();
        int perPage = LIST_SLOTS.length;
        int totalPages = Math.max(1, (owned.size() + perPage - 1) / perPage);
        if (page >= totalPages) {
            page = totalPages - 1;
            session.setListPage(page);
        }
        int start = page * perPage;
        if (owned.isEmpty()) {
            inv.setItem(22, items.emptyState());
        } else {
            for (int i = 0; i < LIST_SLOTS.length; i++) {
                int idx = start + i;
                if (idx >= owned.size()) {
                    inv.setItem(LIST_SLOTS[i], fill.clone());
                    continue;
                }
                Claim c = owned.get(idx);
                int tr = claims.getCache().trustedSnapshot(c.id()).size();
                inv.setItem(LIST_SLOTS[i], items.claimRow(c, tr));
            }
        }
        if (page > 0) {
            inv.setItem(BTN_PREV, items.prevPage());
        }
        if (page < totalPages - 1) {
            inv.setItem(BTN_NEXT, items.nextPage());
        }
        if (player.hasPermission(ClaimPermission.CLAIM_MERGE) && claims.getConfig().isMergeEnabled()) {
            inv.setItem(BTN_MERGE, items.btnMergeClaims());
        }
        player.openInventory(inv);
    }

    public void openDetails(@NotNull Player player, long claimId) {
        Claim c = claims.getCache().byId(claimId);
        if (c == null || !c.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.gui-delete-not-owner");
            return;
        }
        ClaimGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ClaimGuiSession::new);
        session.setScreen(ClaimGuiSession.Screen.DETAILS);
        session.setDetailsClaimId(claimId);
        session.setPendingDeleteClaimId(-1L);
        Inventory inv = createInv(session, DETAIL_SIZE, "claim.gui-details-title");
        for (int i = 0; i < DETAIL_SIZE; i++) {
            inv.setItem(i, items.filler());
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(c.ownerUuid());
        String ownerName = op.getName() != null ? op.getName() : c.ownerUuid().toString();
        var trust = claims.getCache().trustedSnapshot(c.id());
        String tlist = trust.stream().map(u -> {
            String n = Bukkit.getOfflinePlayer(u).getName();
            return n != null ? n : u.toString();
        }).sorted().collect(Collectors.joining(", "));
        if (tlist.isEmpty()) {
            tlist = "-";
        }
        inv.setItem(13, items.detailsInfo(c, ownerName, trust.size(), tlist));
        inv.setItem(11, items.btnShowBorder(claimId));
        inv.setItem(15, items.btnDelete(claimId));
        if (player.hasPermission(ClaimPermission.CLAIM_EXIT)
                && claims.getConfig().getAntiEncasement().claimExit().enabled()) {
            inv.setItem(20, items.btnClaimExit(claimId));
        }
        if (claims.getConfig().getFlags().enabled()
                && (player.hasPermission(ClaimPermission.FLAGS) || player.hasPermission(ClaimPermission.FLAGS_ADMIN))) {
            inv.setItem(24, items.btnFlags(claimId));
        }
        inv.setItem(22, items.btnBack());
        session.attachInventory(inv);
        player.openInventory(inv);
    }

    public void openFlags(@NotNull Player player, long claimId) {
        Claim c = claims.getCache().byId(claimId);
        if (c == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-found");
            return;
        }
        if (!claims.canEditFlags(player, c)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-owner");
            return;
        }
        if (!claims.getConfig().getFlags().enabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        ClaimGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ClaimGuiSession::new);
        session.setScreen(ClaimGuiSession.Screen.FLAGS);
        session.setFlagsClaimId(claimId);
        paintFlags(player, session, c);
    }

    private void paintFlags(@NotNull Player player, @NotNull ClaimGuiSession session, @NotNull Claim claim) {
        Inventory inv = createInv(session, FLAGS_SIZE, "claim.flags-title");
        session.attachInventory(inv);
        ItemStack fill = items.filler();
        for (int i = 0; i < FLAGS_SIZE; i++) {
            inv.setItem(i, fill.clone());
        }
        ClaimFlag[] allFlags = ClaimFlag.values();
        for (int i = 0; i < allFlags.length && i < FLAG_SLOTS.length; i++) {
            ClaimFlag flag = allFlags[i];
            boolean value = claims.flagEnabled(claim, flag);
            boolean editable = claims.getFlagService().playerMayEditFlag(player, claim, flag);
            inv.setItem(FLAG_SLOTS[i], items.flagToggleItem(claim.id(), flag, value, editable));
        }
        inv.setItem(FLAGS_BACK_SLOT, items.flagsBackButton());
        player.openInventory(inv);
    }

    public void openDeleteConfirm(@NotNull Player player, long claimId) {
        Claim c = claims.getCache().byId(claimId);
        if (c == null || !c.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.gui-delete-not-owner");
            return;
        }
        ClaimGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), ClaimGuiSession::new);
        session.setScreen(ClaimGuiSession.Screen.DELETE_CONFIRM);
        session.setPendingDeleteClaimId(claimId);
        Inventory inv = createInv(session, DEL_SIZE, "claim.gui-delete-title");
        for (int i = 0; i < DEL_SIZE; i++) {
            inv.setItem(i, items.filler());
        }
        inv.setItem(11, items.btnDeleteConfirm(claimId));
        inv.setItem(15, items.btnDeleteCancel());
        session.attachInventory(inv);
        player.openInventory(inv);
    }

    @SuppressWarnings("deprecation")
    private Inventory createInv(ClaimGuiSession session, int size, String titleKey) {
        Component title = plugin.getMessageManager().component(titleKey);
        try {
            return Bukkit.createInventory(session, size, title);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "Claims GUI requires Paper Component inventory titles.", t);
            return Bukkit.createInventory(session, size, PlainTextComponentSerializer.plainText().serialize(title));
        }
    }

    public void handleClick(@NotNull Player player, @NotNull ClaimGuiSession session,
                            @NotNull ClickType click, int slot) {
        if (!player.isOnline()) {
            return;
        }
        Inventory inv = session.attachedInventory();
        if (inv == null || slot < 0 || slot >= inv.getSize()) {
            return;
        }
        ItemStack stack = inv.getItem(slot);
        if (!ClaimGuiKeys.isGuiItem(stack)) {
            return;
        }
        ClaimGuiKeys.Kind kind = ClaimGuiKeys.readKind(stack);
        if (kind == null) {
            return;
        }
        switch (session.screen()) {
            case LIST -> handleListClick(player, session, kind, stack, slot, click);
            case DETAILS -> handleDetailClick(player, session, kind, stack, click);
            case DELETE_CONFIRM -> handleDelClick(player, session, kind, stack, click);
            case FLAGS -> handleFlagsClick(player, session, kind, stack, click);
        }
    }

    private void handleListClick(Player player, ClaimGuiSession session, ClaimGuiKeys.Kind kind, ItemStack stack,
                                 int slot, ClickType click) {
        List<Claim> owned = claims.getCache().listForOwner(player.getUniqueId());
        if (kind == ClaimGuiKeys.Kind.PREV_PAGE && slot == BTN_PREV) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) {
                return;
            }
            session.setListPage(session.listPage() - 1);
            paintList(player, session, owned);
            return;
        }
        if (kind == ClaimGuiKeys.Kind.NEXT_PAGE && slot == BTN_NEXT) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) {
                return;
            }
            session.setListPage(session.listPage() + 1);
            paintList(player, session, owned);
            return;
        }
        if (kind == ClaimGuiKeys.Kind.BTN_MERGE_CLAIMS && slot == BTN_MERGE) {
            if (click != ClickType.LEFT && click != ClickType.RIGHT) {
                return;
            }
            claims.tryMergeAdjacentOwnedClaims(player, () -> {
                List<Claim> refreshed = claims.getCache().listForOwner(player.getUniqueId());
                paintList(player, session, refreshed);
            });
            return;
        }
        if (kind == ClaimGuiKeys.Kind.LIST_CLAIM) {
            long id = ClaimGuiKeys.readClaimId(stack);
            if (id < 0) {
                return;
            }
            if (click == ClickType.LEFT) {
                applyGuiBorderToggle(player, id);
                return;
            }
            if (click == ClickType.RIGHT && !player.isSneaking()) {
                openDetails(player, id);
                return;
            }
            if (click == ClickType.SHIFT_RIGHT) {
                openDeleteConfirm(player, id);
            }
        }
    }

    private void handleDetailClick(Player player, ClaimGuiSession session, ClaimGuiKeys.Kind kind,
                                   ItemStack stack, ClickType click) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        long claimId = ClaimGuiKeys.readClaimId(stack);
        switch (kind) {
            case BTN_SHOW_BORDER -> {
                if (claimId >= 0) {
                    applyGuiBorderToggle(player, claimId);
                }
            }
            case BTN_OPEN_DELETE -> {
                if (claimId >= 0) {
                    openDeleteConfirm(player, claimId);
                }
            }
            case BTN_CLAIM_EXIT -> {
                if (claimId >= 0) {
                    claims.getClaimExit().requestExit(player);
                }
            }
            case BTN_FLAGS -> {
                if (claimId >= 0) {
                    openFlags(player, claimId);
                }
            }
            case BTN_BACK -> openMain(player);
            default -> {
            }
        }
    }

    private void handleFlagsClick(@NotNull Player player, @NotNull ClaimGuiSession session,
                                  @Nullable ClaimGuiKeys.Kind kind, @Nullable ItemStack stack,
                                  @NotNull ClickType click) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        long claimId = session.flagsClaimId();
        if (kind == ClaimGuiKeys.Kind.FLAGS_BACK) {
            if (claimId >= 0) {
                openDetails(player, claimId);
            } else {
                openMain(player);
            }
            return;
        }
        if (kind != ClaimGuiKeys.Kind.FLAG_TOGGLE || stack == null) {
            return;
        }
        ClaimFlag flag = ClaimGuiKeys.readFlag(stack);
        if (flag == null) {
            return;
        }
        long stackClaimId = ClaimGuiKeys.readClaimId(stack);
        if (stackClaimId != claimId || claimId < 0) {
            return;
        }
        Claim claim = claims.getCache().byId(claimId);
        if (claim == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-found");
            openMain(player);
            return;
        }
        claims.getFlagService().toggleFlag(player, claimId, flag, () -> {
            plugin.getMessageManager().sendActionBar(player, "claim.flags-updated");
            Claim refreshed = claims.getCache().byId(claimId);
            if (refreshed != null) {
                paintFlags(player, session, refreshed);
            }
        });
    }

    private void handleDelClick(Player player, ClaimGuiSession session, ClaimGuiKeys.Kind kind,
                               ItemStack stack, ClickType click) {
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        long pending = session.pendingDeleteClaimId();
        if (kind == ClaimGuiKeys.Kind.DEL_CANCEL) {
            if (pending >= 0) {
                openDetails(player, pending);
            } else {
                openMain(player);
            }
            return;
        }
        if (kind != ClaimGuiKeys.Kind.DEL_CONFIRM) {
            return;
        }
        long stackId = ClaimGuiKeys.readClaimId(stack);
        if (pending < 0 || stackId != pending) {
            return;
        }
        Claim still = claims.getCache().byId(pending);
        if (still == null || !still.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.gui-delete-not-owner");
            openMain(player);
            return;
        }
        claims.deleteOwnedClaimAsPlayer(player, pending, () -> openMain(player));
    }

    private void applyGuiBorderToggle(@NotNull Player player, long claimId) {
        ClaimConfig cfg = claims.getConfig();
        if (!cfg.isVisualsEnabled()) {
            claims.getVisuals().clearBecauseVisualsDisabled(player);
            plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
            return;
        }
        ClaimVisualService.ToggleGuiResult r = claims.getVisuals().toggleGuiPinnedDisplay(player, claimId);
        switch (r) {
            case SHOWN -> plugin.getMessageManager().sendPrefixed(player, "claim.show-enabled");
            case HIDDEN -> plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
            case NOT_FOUND -> plugin.getMessageManager().sendPrefixed(player, "claim.gui-claim-not-found");
            case WRONG_WORLD -> plugin.getMessageManager().sendPrefixed(player, "claim.gui-border-wrong-world");
            case BORDER_DENIED -> plugin.getMessageManager().sendPrefixed(player, "claim.flags-border-denied");
        }
    }
}
