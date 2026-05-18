package de.deinserver.cpsmp.claims.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

/**
 * Claims / plots GUI: cancel all inventory manipulation; dispatch whitelisted clicks only.
 */
public final class ClaimGuiClickListener implements Listener {

    private final ClaimGuiManager manager;

    public ClaimGuiClickListener(ClaimGuiManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.handlePlayerQuit(event.getPlayer());
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        if (!(top.getHolder() instanceof ClaimGuiSession session)) {
            return;
        }
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        ClickType type = event.getClick();
        if (type == ClickType.NUMBER_KEY
                || type == ClickType.SWAP_OFFHAND
                || type == ClickType.DROP
                || type == ClickType.CONTROL_DROP
                || type == ClickType.CREATIVE
                || type == ClickType.UNKNOWN
                || type == ClickType.DOUBLE_CLICK
                || type == ClickType.MIDDLE
                || type == ClickType.WINDOW_BORDER_LEFT
                || type == ClickType.WINDOW_BORDER_RIGHT) {
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

        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked != top) {
            return;
        }

        boolean listShiftDelete = session.screen() == ClaimGuiSession.Screen.LIST
                && type == ClickType.SHIFT_RIGHT;
        if (type != ClickType.LEFT && type != ClickType.RIGHT && !listShiftDelete) {
            return;
        }
        if (type == ClickType.SHIFT_LEFT) {
            return;
        }

        manager.handleClick(player, session, type, event.getSlot());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ClaimGuiSession)) {
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory closed = event.getInventory();
        if (closed.getHolder() instanceof ClaimGuiSession session) {
            manager.handleSessionHolderClose(player, session, closed);
        }
    }
}
