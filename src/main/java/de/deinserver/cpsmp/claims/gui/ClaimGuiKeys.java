package de.deinserver.cpsmp.claims.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PDC for Claims / Plots GUI.
 */
public final class ClaimGuiKeys {

    private static final byte MARKER = 1;

    @Nullable
    private static NamespacedKey guiKey;
    @Nullable
    private static NamespacedKey claimIdKey;
    @Nullable
    private static NamespacedKey kindKey;
    @Nullable
    private static NamespacedKey flagKeyKey;

    public enum Kind {
        FILLER,
        LIST_CLAIM,
        PREV_PAGE,
        NEXT_PAGE,
        BTN_SHOW_BORDER,/* details */
        BTN_OPEN_DELETE,
        BTN_BACK,
        DEL_CONFIRM,
        DEL_CANCEL,
        EMPTY_STATE,
        BTN_MERGE_CLAIMS,
        BTN_CLAIM_EXIT,
        BTN_FLAGS,
        FLAG_TOGGLE,
        FLAGS_BACK
    }

    private ClaimGuiKeys() {
    }

    public static void init(Plugin plugin) {
        guiKey = new NamespacedKey(plugin, "claim_gui_item");
        claimIdKey = new NamespacedKey(plugin, "claim_gui_claim_id");
        kindKey = new NamespacedKey(plugin, "claim_gui_kind");
        flagKeyKey = new NamespacedKey(plugin, "claim_gui_flag_key");
    }

    public static boolean hasInitialized() {
        return guiKey != null && claimIdKey != null && kindKey != null && flagKeyKey != null;
    }

    public static void markGuiItem(ItemStack stack) {
        if (guiKey == null || stack == null || stack.getType().isAir()) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(guiKey, PersistentDataType.BYTE, MARKER);
        stack.setItemMeta(meta);
    }

    public static void tagKind(ItemStack stack, Kind kind) {
        markGuiItem(stack);
        if (kindKey == null || stack.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(kindKey, PersistentDataType.BYTE, (byte) kind.ordinal());
        stack.setItemMeta(meta);
    }

    public static void tagClaimRow(ItemStack stack, long claimId) {
        tagKind(stack, Kind.LIST_CLAIM);
        if (claimIdKey == null || stack.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(claimIdKey, PersistentDataType.LONG, claimId);
        stack.setItemMeta(meta);
    }

    public static void tagClaimButton(ItemStack stack, Kind kind, long claimId) {
        tagKind(stack, kind);
        if (claimIdKey == null || stack.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(claimIdKey, PersistentDataType.LONG, claimId);
        stack.setItemMeta(meta);
    }

    public static boolean isGuiItem(@Nullable ItemStack stack) {
        if (guiKey == null || stack == null || stack.getType().isAir()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        Byte v = meta.getPersistentDataContainer().get(guiKey, PersistentDataType.BYTE);
        return v != null && v == MARKER;
    }

    public static @Nullable Kind readKind(@Nullable ItemStack stack) {
        if (kindKey == null || stack == null || stack.getItemMeta() == null) {
            return null;
        }
        Byte b = stack.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.BYTE);
        if (b == null) {
            return null;
        }
        int o = b & 0xFF;
        Kind[] vals = Kind.values();
        if (o < 0 || o >= vals.length) {
            return null;
        }
        return vals[o];
    }

    public static long readClaimId(@Nullable ItemStack stack) {
        if (claimIdKey == null || stack == null || stack.getItemMeta() == null) {
            return -1L;
        }
        Long v = stack.getItemMeta().getPersistentDataContainer().get(claimIdKey, PersistentDataType.LONG);
        return v != null ? v : -1L;
    }

    public static void tagFlagToggle(@NotNull ItemStack stack, long claimId, @NotNull de.deinserver.cpsmp.claims.ClaimFlag flag) {
        tagKind(stack, Kind.FLAG_TOGGLE);
        if (claimIdKey == null || flagKeyKey == null || stack.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        meta.getPersistentDataContainer().set(claimIdKey, PersistentDataType.LONG, claimId);
        meta.getPersistentDataContainer().set(flagKeyKey, PersistentDataType.STRING, flag.configKey());
        stack.setItemMeta(meta);
    }

    public static @Nullable de.deinserver.cpsmp.claims.ClaimFlag readFlag(@Nullable ItemStack stack) {
        if (flagKeyKey == null || stack == null || stack.getItemMeta() == null) {
            return null;
        }
        String key = stack.getItemMeta().getPersistentDataContainer().get(flagKeyKey, PersistentDataType.STRING);
        if (key == null || key.isBlank()) {
            return null;
        }
        try {
            return de.deinserver.cpsmp.claims.ClaimFlag.byConfigKey(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
