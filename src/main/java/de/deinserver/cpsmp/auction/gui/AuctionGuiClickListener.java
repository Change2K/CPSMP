package de.deinserver.cpsmp.auction.gui;

import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.view.AnvilView;

/**
 * Single source of truth for Auction House GUI interaction, including
 * the V2.4 sell-flow anvil (still Bukkit/Paper public API only).
 */
public final class AuctionGuiClickListener implements Listener {

    private static final int SELL_INPUT_SLOT = 13;

    private final AuctionGuiManager manager;

    public AuctionGuiClickListener(AuctionGuiManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onQuit(PlayerQuitEvent event) {
        manager.handlePlayerQuit(event.getPlayer());
    }

    /**
     * Runs before vanilla anvil bookkeeping: cancelling here blocks result
     * pickup, XP charges, and item movement for the virtual price editor.
     */
    @SuppressWarnings({"deprecation", "removal"})
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAnvilAwaitingClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (view.getTopInventory().getType() != InventoryType.ANVIL) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        AuctionGuiSession session = manager.getSession(player.getUniqueId());
        if (session == null || !session.isAwaitingAnvilPrice()) {
            return;
        }

        ClickType type = event.getClick();
        InventoryAction action = event.getAction();
        if (isAnvilPriceInputBlocked(type, action)) {
            event.setCancelled(true);
            return;
        }

        int raw = event.getRawSlot();
        Inventory top = view.getTopInventory();
        int topSize = top.getSize();
        Inventory clicked = event.getClickedInventory();

        if (clicked == top || raw < topSize) {
            event.setCancelled(true);
            if (raw == 2
                    && (type == ClickType.LEFT
                    || type == ClickType.RIGHT
                    || type == ClickType.MIDDLE)) {
                manager.handleAnvilOutputClick(player, session, view, raw);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        InventoryView view = event.getView();
        Player player = resolveAnvilOperator(view);
        if (player == null) {
            return;
        }
        AuctionGuiSession session = manager.getSession(player.getUniqueId());
        if (session == null || !session.isAwaitingAnvilPrice()) {
            return;
        }
        if (view instanceof AnvilView av) {
            av.setRepairCost(0);
            try {
                av.setMaximumRepairCost(0);
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isAnvilPriceInputBlocked(ClickType type, InventoryAction action) {
        if (type == ClickType.NUMBER_KEY
                || type == ClickType.SWAP_OFFHAND
                || type == ClickType.SHIFT_LEFT
                || type == ClickType.SHIFT_RIGHT
                || type == ClickType.DOUBLE_CLICK
                || type == ClickType.DROP
                || type == ClickType.CONTROL_DROP
                || type == ClickType.CREATIVE
                || type == ClickType.UNKNOWN) {
            return true;
        }
        return action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK
                || action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
    }

    @SuppressWarnings({"deprecation", "removal"})
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        AuctionGuiSession session = manager.getSession(player.getUniqueId());

        if (top.getType() == InventoryType.ANVIL
                && session != null
                && session.isAwaitingAnvilPrice()) {
            // Handled exclusively in {@link #onAnvilAwaitingClick} (LOWEST).
            return;
        }

        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof AuctionGuiSession ahSession)) {
            return;
        }

        // ----- V2.4: sell GUI allows placing items in the input slot
        if (ahSession.getCurrentScreen() == AuctionGuiSession.Screen.SELL) {
            handleSellInventoryClick(event, player, ahSession, view, top);
            return;
        }

        // ----- Standard CPSMP GUIs: cancel everything, then dispatch top clicks
        event.setCancelled(true);

        ClickType type = event.getClick();
        if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.MIDDLE) {
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

        manager.handleClick(player, ahSession, event.getSlot());
    }

    @SuppressWarnings({"deprecation", "removal"})
    private void handleSellInventoryClick(InventoryClickEvent event,
                                          Player player,
                                          AuctionGuiSession session,
                                          InventoryView view,
                                          Inventory top) {
        Inventory clicked = event.getClickedInventory();
        ClickType type = event.getClick();

        if ((type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT)
                && clicked == view.getBottomInventory()) {
            event.setCancelled(true);
            int slot = event.getSlot();
            ItemStack stack = clicked.getItem(slot);
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                return;
            }
            if (AuctionGuiItemKeys.isGuiItem(stack)) {
                return;
            }
            ItemStack cur = top.getItem(SELL_INPUT_SLOT);
            if (cur != null && !cur.getType().isAir()) {
                manager.sendSellInputOccupiedMessage(player);
                return;
            }
            ItemStack move = stack.clone();
            clicked.setItem(slot, null);
            top.setItem(SELL_INPUT_SLOT, move);
            return;
        }

        if (type == ClickType.NUMBER_KEY
                || type == ClickType.SWAP_OFFHAND
                || type == ClickType.DROP
                || type == ClickType.CONTROL_DROP
                || type == ClickType.CREATIVE
                || type == ClickType.UNKNOWN
                || type == ClickType.DOUBLE_CLICK
                || type == ClickType.SHIFT_LEFT
                || type == ClickType.SHIFT_RIGHT) {
            event.setCancelled(true);
            return;
        }

        if (type != ClickType.LEFT && type != ClickType.RIGHT && type != ClickType.MIDDLE) {
            event.setCancelled(true);
            return;
        }

        InventoryAction action = event.getAction();
        if (action == InventoryAction.HOTBAR_SWAP
                || action == InventoryAction.HOTBAR_MOVE_AND_READD
                || action == InventoryAction.COLLECT_TO_CURSOR
                || action == InventoryAction.CLONE_STACK) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = top.getSize();

        // Allow moves between player inventory and the single input slot only.
        if (clicked == top && event.getSlot() == SELL_INPUT_SLOT) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(false);
            return;
        }
        if (clicked == view.getBottomInventory()) {
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                // Shift-move into the sell GUI would target arbitrary slots.
                event.setCancelled(true);
                return;
            }
            event.setCancelled(false);
            return;
        }
        if (clicked == top && rawSlot < topSize && event.getSlot() != SELL_INPUT_SLOT) {
            event.setCancelled(true);
            manager.handleClickSellButtons(player, session, event.getSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryView view = event.getView();
        Inventory top = view.getTopInventory();
        AuctionGuiSession session = manager.getSession(player.getUniqueId());

        if (top.getType() == InventoryType.ANVIL
                && session != null
                && session.isAwaitingAnvilPrice()) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
            return;
        }

        if (!(top.getHolder() instanceof AuctionGuiSession ahSession)) {
            return;
        }
        int topSize = top.getSize();

        if (ahSession.getCurrentScreen() == AuctionGuiSession.Screen.SELL) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize && rawSlot != SELL_INPUT_SLOT) {
                    event.setCancelled(true);
                    return;
                }
            }
            event.setCancelled(false);
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize) {
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

        if (closed.getHolder() instanceof AuctionGuiSession session) {
            manager.handleSessionHolderClose(player, session, closed);
            return;
        }

        if (closed.getType() == InventoryType.ANVIL) {
            AuctionGuiSession s = manager.getSession(player.getUniqueId());
            if (s != null) {
                manager.handleAnvilClose(player, s);
            }
        }
    }

    private static Player resolveAnvilOperator(InventoryView view) {
        for (HumanEntity he : view.getTopInventory().getViewers()) {
            if (he instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
