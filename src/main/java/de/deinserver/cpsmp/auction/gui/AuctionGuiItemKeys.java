package de.deinserver.cpsmp.auction.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

/**
 * Internal marker for every CPSMP-created Auction House GUI stack
 * (fillers, buttons, anvil templates, listing/collect <em>display</em>
 * clones). Player-owned auction items and collect-storage payloads must
 * never carry this tag.
 *
 * <p>Used to block {@link de.deinserver.cpsmp.auction.AuctionHouseManager#safeReturnItemOrCollect}
 * and collect delivery from ever treating UI chrome as real items.
 */
public final class AuctionGuiItemKeys {

    private static final byte MARKER_VALUE = 1;

    @Nullable
    private static NamespacedKey guiItemKey;

    private AuctionGuiItemKeys() {
    }

    public static void init(Plugin plugin) {
        guiItemKey = new NamespacedKey(plugin, "auction_gui_item");
    }

    public static boolean hasInitialized() {
        return guiItemKey != null;
    }

    /**
     * Tags {@code stack} as CPSMP GUI chrome. No-op when {@link #init}
     * was not called or {@code stack} has no meta.
     */
    public static void markGuiItem(ItemStack stack) {
        if (guiItemKey == null || stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(guiItemKey, PersistentDataType.BYTE, MARKER_VALUE);
        stack.setItemMeta(meta);
    }

    public static boolean isGuiItem(@Nullable ItemStack stack) {
        if (guiItemKey == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte v = meta.getPersistentDataContainer().get(guiItemKey, PersistentDataType.BYTE);
        return v != null && v == MARKER_VALUE;
    }
}
