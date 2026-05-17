package de.deinserver.cpsmp.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

/**
 * Last /back position for a player.
 */
public record BackSnapshot(
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long savedAt
) {
    @Nullable
    public Location toLocation() {
        if (Bukkit.getWorld(worldName) == null) {
            return null;
        }
        return new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
    }
}
