package de.deinserver.cpsmp.claims;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Finds a safe standing spot near claim center X/Z using the same surface rules as {@code RTPService},
 * without depending on RTP internals.
 */
public final class ClaimLandingSupport {

    private ClaimLandingSupport() {
    }

    public static Set<Material> unsafeMaterials(JavaPlugin plugin) {
        Set<Material> set = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("rtp.unsafe-blocks")) {
            try {
                set.add(Material.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        set.add(Material.LAVA);
        set.add(Material.WATER);
        set.add(Material.VOID_AIR);
        set.add(Material.CACTUS);
        set.add(Material.FIRE);
        set.add(Material.SOUL_FIRE);
        set.add(Material.MAGMA_BLOCK);
        set.add(Material.POWDER_SNOW);
        set.add(Material.SWEET_BERRY_BUSH);
        set.add(Material.WITHER_ROSE);
        return set;
    }

    public static @Nullable Location findSafeStanding(World world, int x, int z, Set<Material> unsafe) {
        if (world == null) {
            return null;
        }
        int start = world.getHighestBlockYAt(x, z);
        for (int y = start; y >= world.getMinHeight() + 1; y--) {
            Block ground = world.getBlockAt(x, y, z);
            Block feet = world.getBlockAt(x, y + 1, z);
            Block head = world.getBlockAt(x, y + 2, z);
            if (!isSafeGround(ground, unsafe)) {
                continue;
            }
            if (!isPassable(feet, unsafe)) {
                continue;
            }
            if (!isPassable(head, unsafe)) {
                continue;
            }
            return new Location(world, x + 0.5, y + 1.0, z + 0.5, 0.0F, 0.0F);
        }
        return null;
    }

    static boolean isSafeGroundBlock(Block block, Set<Material> unsafe) {
        Material type = block.getType();
        if (type.isAir()) {
            return false;
        }
        if (unsafe.contains(type)) {
            return false;
        }
        return type.isSolid();
    }

    static boolean isPassableBlock(Block block, Set<Material> unsafe) {
        Material type = block.getType();
        if (unsafe.contains(type)) {
            return false;
        }
        return !type.isSolid() || !type.isOccluding();
    }

    private static boolean isSafeGround(Block block, Set<Material> unsafe) {
        return isSafeGroundBlock(block, unsafe);
    }

    private static boolean isPassable(Block block, Set<Material> unsafe) {
        return isPassableBlock(block, unsafe);
    }
}
