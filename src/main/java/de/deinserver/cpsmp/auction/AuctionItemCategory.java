package de.deinserver.cpsmp.auction;

import org.bukkit.Material;

/**
 * Lightweight item grouping for future category filters / analytics.
 * Not used by the V2.5 GUI yet.
 */
public enum AuctionItemCategory {

    BLOCKS,
    TOOLS,
    WEAPONS,
    ARMOR,
    FOOD,
    MISC;

    public static AuctionItemCategory of(Material material) {
        if (material == null || material.isAir()) {
            return MISC;
        }
        if (material.isEdible()) {
            return FOOD;
        }
        String name = material.name();
        if (name.endsWith("_HELMET")
                || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS")
                || material == Material.ELYTRA) {
            return ARMOR;
        }
        if (material == Material.BOW
                || material == Material.CROSSBOW
                || material == Material.TRIDENT
                || material == Material.MACE
                || name.endsWith("_SWORD")) {
            return WEAPONS;
        }
        if (name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_HOE")
                || name.endsWith("_AXE")
                || material == Material.FISHING_ROD
                || material == Material.SHEARS
                || material == Material.BRUSH
                || material == Material.FLINT_AND_STEEL
                || material == Material.SPYGLASS) {
            return TOOLS;
        }
        if (material.isBlock()) {
            return BLOCKS;
        }
        return MISC;
    }
}
