package de.deinserver.cpsmp.claims;

import org.bukkit.Material;

/**
 * Material helpers for claim protection (Bukkit 1.21.4-safe).
 */
public final class ClaimMaterialUtil {

    private ClaimMaterialUtil() {
    }

    public static boolean isLiquid(Material type) {
        if (type.isAir()) {
            return false;
        }
        return type == Material.WATER
                || type == Material.LAVA
                || type == Material.BUBBLE_COLUMN
                || type == Material.WATER_CAULDRON
                || type == Material.LAVA_CAULDRON;
    }
}
