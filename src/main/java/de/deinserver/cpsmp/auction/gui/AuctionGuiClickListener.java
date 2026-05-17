package de.deinserver.cpsmp.auction.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Single source of truth for Auction House GUI interaction. Every
 * mitigation lives here so anyone auditing dupe-protection can read
 * one file:
 *
 * <ul>
 *     <li>Scoped by holder. We only act when the <em>top</em> inventory
 *         of the view is one we created (its holder is an
 *         {@link AuctionGuiSession}). Other plugins' inventories and
 *         vanilla containers are ignored entirely.</li>
 *     <li>Every {@link InventoryClickEvent} inside an AH GUI is
 *         cancelled before any logic runs. Cancellation blocks the
 *         vanilla side-effect for shift-click, number-key swap,
 *         double-click stack pick-up, drop, control-drop, swap-offhand
 *         and creative click - regardless of what we choose to dispatch
 *         afterwards.</li>
 *     <li>Only "neutral" click types (LEFT, RIGHT, MIDDLE) reach the
 *         manager dispatcher. SHIFT_LEFT/SHIFT_RIGHT/NUMBER_KEY/
 *         DOUBLE_CLICK/DROP/CONTROL_DROP/SWAP_OFFHAND/CREATIVE/UNKNOWN
 *         all stay cancelled but do <em>not</em> trigger any action,
 *         which keeps a misclick from accidentally buying an item.</li>
 *     <li>{@link InventoryDragEvent} is cancelled the moment any of its
 *         raw slots overlap with the top inventory.</li>
 *     <li>{@link InventoryCloseEvent} drives session teardown. Identity
 *         check on the closed inventory ensures the implicit close
 *         fired by Bukkit when we open a follow-up screen doesn't tear
 *         the session down.</li>
 * </ul>
 */
public final class AuctionGuiClickListener implements Listener {

    private final AuctionGuiManager manager;

    public AuctionGuiClickListener(AuctionGuiManager manager) {
        this.manager = manager;
    }

    @SuppressWarnings({"deprecation", "removal"}) // HOTBAR_MOVE_AND_READD listed defensively for legacy Paper builds
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof AuctionGuiSession session)) {
            return;
        }
        // First and most important: kill the side-effect of EVERY
        // click while an AH GUI is open. This single line blocks
        // shift-click moves, number-key swaps, drop keys, offhand
        // swap, double-click hoover, and creative clicks - whether
        // the click landed in the GUI or the player's inventory.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        // Reject anything that isn't a clean left/right/middle click
        // in the top inventory. We don't want a shift-click on the
        // player inventory side to accidentally trigger a "buy" tile
        // dispatch, and we don't want a number-key press to do
        // anything either.
        ClickType type = event.getClick();
        if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.MIDDLE) {
            return;
        }
        // Defence in depth: forbid swap/move actions explicitly even
        // for LEFT/RIGHT/MIDDLE click types (some launchers map the
        // offhand swap to those internally).
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
            // Click landed in the player's inventory (or outside).
            // Already cancelled - just don't dispatch.
            return;
        }

        manager.handleClick(player, session, event.getSlot());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        if (!(top.getHolder() instanceof AuctionGuiSession)) {
            return;
        }
        int topSize = top.getSize();
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
                // The drag touches at least one slot in our GUI.
                // Cancel the whole drag - we never allow players to
                // place items into the AH inventory.
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClose(InventoryCloseEvent event) {
        Inventory closed = event.getInventory();
        InventoryHolder holder = closed.getHolder();
        if (!(holder instanceof AuctionGuiSession session)) {
            return;
        }
        // Identity-scoped teardown. See class javadoc - this is the
        // anti "open-replaces-old-inventory close event tears the
        // session down" guard.
        manager.handleClose(session.getPlayerId(), closed);
    }
}
