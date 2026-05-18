package de.deinserver.cpsmp.claims;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable rectangular XZ claim from floor {@link World#getMinHeight()} to ceiling.
 *
 * @param id                 global primary key (SQLite {@code claim_id})
 * @param ownerClaimNumber   visible per-owner index (1..n), unique per owner
 */
public record Claim(long id, int ownerClaimNumber, UUID ownerUuid, String ownerName, String worldName,
                    int minX, int maxX, int minZ, int maxZ,
                    long createdAt, long updatedAt) {

    public boolean containsBlock(int blockX, int blockZ) {
        return blockX >= minX && blockX <= maxX && blockZ >= minZ && blockZ <= maxZ;
    }

    public boolean overlapsXZ(int oMinX, int oMaxX, int oMinZ, int oMaxZ) {
        return minX <= oMaxX && maxX >= oMinX && minZ <= oMaxZ && maxZ >= oMinZ;
    }

    public int widthBlocks() {
        return maxX - minX + 1;
    }

    public int depthBlocks() {
        return maxZ - minZ + 1;
    }

    public int centerX() {
        return (minX + maxX) / 2;
    }

    public int centerZ() {
        return (minZ + maxZ) / 2;
    }

    public @Nullable Location centerLocation() {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return null;
        }
        int y = w.getHighestBlockYAt(centerX(), centerZ()) + 1;
        return new Location(w, centerX() + 0.5, y, centerZ() + 0.5);
    }
}
