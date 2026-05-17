package de.deinserver.cpsmp.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One persisted named home row.
 */
public record Home(
        long homeId,
        UUID ownerUuid,
        @Nullable String ownerName,
        String homeName,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        long createdAt,
        long updatedAt
) {

    @Nullable
    public Location toLocation() {
        if (Bukkit.getWorld(worldName) == null) {
            return null;
        }
        return new Location(Bukkit.getWorld(worldName), x, y, z, yaw, pitch);
    }
}
