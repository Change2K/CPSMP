package de.deinserver.cpsmp.teleport.gui;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.teleport.CpsmpTeleportSubsystem;
import de.deinserver.cpsmp.teleport.Home;
import de.deinserver.cpsmp.ui.CpsmpGuiTexts;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HomesGuiManager {

    private static final int SIZE = 54;
    private static final int HOMES_PER_PAGE = 45;

    private final CPSMPPlugin plugin;
    private final CpsmpTeleportSubsystem subsystem;
    private final MessageManager messages;
    private final Map<UUID, HomesGuiSession> sessions = new ConcurrentHashMap<>();
    private final DateTimeFormatter createdFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    public HomesGuiManager(CPSMPPlugin plugin, CpsmpTeleportSubsystem subsystem) {
        this.plugin = plugin;
        this.subsystem = subsystem;
        this.messages = plugin.getMessageManager();
    }

    private NamespacedKey homeNameKey() {
        return new NamespacedKey(plugin, "homes_entry");
    }

    public NamespacedKey getHomeNameKey() {
        return homeNameKey();
    }

    public void openHomes(Player player, List<Home> homes, int page) {
        HomesGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), HomesGuiSession::new);
        session.setHomes(homes);
        session.setPage(Math.max(1, page));
        session.setScreen(HomesGuiSession.Screen.LIST);
        session.setPendingDelete(null);
        Inventory inv = buildListInventory(session);
        session.setInventory(inv);
        player.openInventory(inv);
    }

    public void openDeleteConfirm(Player player, Home home) {
        HomesGuiSession session = sessions.computeIfAbsent(player.getUniqueId(), HomesGuiSession::new);
        session.setPendingDelete(home);
        session.setScreen(HomesGuiSession.Screen.DELETE_CONFIRM);
        Inventory inv = buildDeleteInventory(session, home);
        session.setInventory(inv);
        player.openInventory(inv);
    }

    public @Nullable HomesGuiSession getSession(UUID id) {
        return sessions.get(id);
    }

    public void removeSession(UUID id) {
        sessions.remove(id);
    }

    public void closeAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                p.closeInventory();
            }
        }
        sessions.clear();
    }

    @SuppressWarnings("deprecation")
    private Inventory buildListInventory(HomesGuiSession session) {
        List<Home> all = session.getHomes();
        int page = session.getPage();
        int totalPages = Math.max(1, (all.size() + HOMES_PER_PAGE - 1) / HOMES_PER_PAGE);
        page = Math.min(page, totalPages);
        session.setPage(page);
        int from = (page - 1) * HOMES_PER_PAGE;
        int to = Math.min(from + HOMES_PER_PAGE, all.size());

        HomesGuiSession holder = session;
        Inventory inv;
        Component title = messages.component("home.gui-title");
        try {
            inv = Bukkit.createInventory(holder, SIZE, title);
        } catch (Throwable t) {
            inv = Bukkit.createInventory(holder, SIZE,
                    PlainTextComponentSerializer.plainText().serialize(title));
        }

        for (int i = 0; i < to - from; i++) {
            Home h = all.get(from + i);
            ItemStack bed = new ItemStack(Material.RED_BED);
            ItemMeta meta = bed.getItemMeta();
            if (meta != null) {
                String dateStr = createdFmt.format(Instant.ofEpochMilli(h.createdAt()));
                meta.displayName(CpsmpGuiTexts.homeItemName(messages, h.homeName()));
                meta.lore(CpsmpGuiTexts.homeItemLore(messages, h, dateStr));
                meta.getPersistentDataContainer().set(homeNameKey(), PersistentDataType.STRING, h.homeName());
                bed.setItemMeta(meta);
            }
            HomesGuiItemKeys.mark(bed);
            inv.setItem(i, bed);
        }

        fillBottomNav(inv, page, totalPages);
        return inv;
    }

    private void fillBottomNav(Inventory inv, int page, int totalPages) {
        ItemStack filler = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        if (fm != null) {
            fm.displayName(Component.space());
            filler.setItemMeta(fm);
        }
        HomesGuiItemKeys.mark(filler);
        for (int s = 45; s < SIZE; s++) {
            inv.setItem(s, filler.clone());
        }
        if (page > 1) {
            inv.setItem(45, navButton(Material.ARROW, "home.gui-prev-page",
                    Map.of("page", Integer.toString(page - 1))));
        }
        if (page < totalPages) {
            inv.setItem(53, navButton(Material.ARROW, "home.gui-next-page",
                    Map.of("page", Integer.toString(page + 1))));
        }
        inv.setItem(49, navButton(Material.BARRIER, "home.gui-close", Map.of()));
    }

    private ItemStack navButton(Material mat, String nameKey, Map<String, String> ph) {
        ItemStack st = new ItemStack(mat);
        ItemMeta mst = st.getItemMeta();
        if (mst != null) {
            mst.displayName(messages.component(nameKey, ph));
            HomesGuiItemKeys.mark(st);
            st.setItemMeta(mst);
        }
        return st;
    }

    @SuppressWarnings("deprecation")
    private Inventory buildDeleteInventory(HomesGuiSession session, Home home) {
        HomesGuiSession holder = session;
        Inventory inv;
        Component title = messages.component("home.gui-delete-confirm-title");
        try {
            inv = Bukkit.createInventory(holder, 27, title);
        } catch (Throwable t) {
            inv = Bukkit.createInventory(holder, 27,
                    PlainTextComponentSerializer.plainText().serialize(title));
        }
        ItemStack yes = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta ym = yes.getItemMeta();
        if (ym != null) {
            ym.displayName(messages.component("home.gui-delete-confirm"));
            ym.getPersistentDataContainer().set(homeNameKey(), PersistentDataType.STRING, home.homeName());
            yes.setItemMeta(ym);
        }
        HomesGuiItemKeys.mark(yes);
        inv.setItem(11, yes);

        ItemStack no = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta nm = no.getItemMeta();
        if (nm != null) {
            nm.displayName(messages.component("home.gui-delete-cancel"));
            no.setItemMeta(nm);
        }
        HomesGuiItemKeys.mark(no);
        inv.setItem(15, no);
        return inv;
    }

    public void handleNavClick(Player player, int slot) {
        HomesGuiSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getScreen() != HomesGuiSession.Screen.LIST) {
            return;
        }
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        int page = session.getPage();
        int totalPages = Math.max(1, (session.getHomes().size() + HOMES_PER_PAGE - 1) / HOMES_PER_PAGE);
        if (slot == 45 && page > 1) {
            openHomes(player, session.getHomes(), page - 1);
            return;
        }
        if (slot == 53 && page < totalPages) {
            openHomes(player, session.getHomes(), page + 1);
        }
    }

    public void handleDeleteConfirm(Player player, boolean confirm, @Nullable String homeName) {
        HomesGuiSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getPendingDelete() == null) {
            return;
        }
        HomesGuiSession snapshot = session;
        Home pending = snapshot.getPendingDelete();
        if (!confirm) {
            openHomes(player, snapshot.getHomes(), snapshot.getPage());
            return;
        }
        String name = homeName != null ? homeName : pending.homeName();
        subsystem.deleteHome(player.getUniqueId(), name).whenComplete((ok, ex) ->
                subsystem.runSync(() -> {
                    if (ex != null || !Boolean.TRUE.equals(ok)) {
                        messages.sendPrefixed(player, "home.not-found");
                    } else {
                        messages.sendPrefixed(player, "home.deleted", Map.of("name", name));
                    }
                    subsystem.listHomes(player.getUniqueId()).thenAccept(list ->
                            subsystem.runSync(() -> openHomes(player, list, 1)));
                }));
    }

    public CpsmpTeleportSubsystem getSubsystem() {
        return subsystem;
    }

    public static final class HomesGuiSession implements InventoryHolder {

        public enum Screen {
            LIST,
            DELETE_CONFIRM
        }

        private List<Home> homes = List.of();
        private int page = 1;
        private Screen screen = Screen.LIST;
        private @Nullable Home pendingDelete;
        private @Nullable Inventory inventory;

        HomesGuiSession(@SuppressWarnings("unused") UUID id) {
        }

        @Override
        public @NotNull Inventory getInventory() {
            if (inventory == null) {
                throw new IllegalStateException("no inv");
            }
            return inventory;
        }

        void setInventory(Inventory inventory) {
            this.inventory = inventory;
        }

        List<Home> getHomes() {
            return homes;
        }

        void setHomes(List<Home> homes) {
            this.homes = homes;
        }

        int getPage() {
            return page;
        }

        void setPage(int page) {
            this.page = page;
        }

        Screen getScreen() {
            return screen;
        }

        void setScreen(Screen screen) {
            this.screen = screen;
        }

        @Nullable Home getPendingDelete() {
            return pendingDelete;
        }

        void setPendingDelete(@Nullable Home pendingDelete) {
            this.pendingDelete = pendingDelete;
        }
    }
}
