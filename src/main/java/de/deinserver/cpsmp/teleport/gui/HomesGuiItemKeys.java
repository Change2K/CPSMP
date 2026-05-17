package de.deinserver.cpsmp.teleport.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public final class HomesGuiItemKeys {

    private static final byte MARKER = 1;
    private static @Nullable NamespacedKey key;

    private HomesGuiItemKeys() {
    }

    public static void init(Plugin plugin) {
        key = new NamespacedKey(plugin, "homes_gui_item");
    }

    public static boolean hasInitialized() {
        return key != null;
    }

    public static void mark(ItemStack stack) {
        if (key == null || stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, MARKER);
        stack.setItemMeta(meta);
    }

    public static boolean isMarked(@Nullable ItemStack stack) {
        if (key == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte v = meta.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return v != null && v == MARKER;
    }
}
