package de.deinserver.cpsmp.teleport.gui;

import de.deinserver.cpsmp.teleport.Home;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Optional;

public final class HomesGuiClickListener implements Listener {

    private final HomesGuiManager manager;

    public HomesGuiClickListener(HomesGuiManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof HomesGuiManager.HomesGuiSession session)) {
            return;
        }
        event.setCancelled(true);
        ClickType type = event.getClick();
        if (type != ClickType.LEFT && type != ClickType.RIGHT) {
            return;
        }
        InventoryAction action = event.getAction();
        if (action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK) {
            return;
        }
        int slot = event.getRawSlot();
        int topSize = top.getSize();
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
            return;
        }
        if (session.getScreen() == HomesGuiManager.HomesGuiSession.Screen.DELETE_CONFIRM) {
            ItemStack cur = top.getItem(slot);
            if (slot == 11 && HomesGuiItemKeys.isMarked(cur)) {
                String name = readHomeName(cur);
                manager.handleDeleteConfirm(player, true, name);
                player.closeInventory();
            } else if (slot == 15 && HomesGuiItemKeys.isMarked(cur)) {
                manager.handleDeleteConfirm(player, false, null);
            }
            return;
        }
        if (slot >= topSize - 9) {
            manager.handleNavClick(player, slot);
            return;
        }
        ItemStack icon = top.getItem(slot);
        if (!HomesGuiItemKeys.isMarked(icon)) {
            return;
        }
        String homeName = readHomeName(icon);
        if (homeName == null) {
            return;
        }
        Optional<Home> h = session.getHomes().stream()
                .filter(x -> x.homeName().equals(homeName))
                .findFirst();
        if (h.isEmpty()) {
            return;
        }
        if (type == ClickType.LEFT) {
            manager.getSubsystem().teleportPlayerToHome(player, h.get());
            player.closeInventory();
        } else {
            manager.openDeleteConfirm(player, h.get());
        }
    }

    private String readHomeName(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        String v = meta.getPersistentDataContainer().get(manager.getHomeNameKey(), PersistentDataType.STRING);
        return v;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof HomesGuiManager.HomesGuiSession)) {
            return;
        }
        int topSize = top.getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean hasHomesGuiOpen(Player player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return top.getHolder() instanceof HomesGuiManager.HomesGuiSession;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (hasHomesGuiOpen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (hasHomesGuiOpen(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof HomesGuiManager.HomesGuiSession) {
            manager.removeSession(player.getUniqueId());
        }
    }
}
