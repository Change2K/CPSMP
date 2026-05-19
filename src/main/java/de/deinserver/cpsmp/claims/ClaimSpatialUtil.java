package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.NotNull;

/**
 * 2D claim geometry helpers (XZ only).
 */
public final class ClaimSpatialUtil {

    private ClaimSpatialUtil() {
    }

    public static boolean containsBlock(int blockX, int blockZ, @NotNull Claim claim) {
        return blockX >= claim.minX() && blockX <= claim.maxX()
                && blockZ >= claim.minZ() && blockZ <= claim.maxZ();
    }

    /**
     * Shortest horizontal distance from a block column to the claim rectangle (0 if inside or on edge).
     */
    public static double flatDistanceToRect(int blockX, int blockZ, @NotNull Claim claim) {
        int nx = Math.min(Math.max(blockX, claim.minX()), claim.maxX());
        int nz = Math.min(Math.max(blockZ, claim.minZ()), claim.maxZ());
        if (containsBlock(blockX, blockZ, claim)) {
            return 0.0D;
        }
        double dx = blockX - nx;
        double dz = blockZ - nz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * True when the column is outside the claim but within {@code radiusBlocks} of its border (2D).
     */
    public static boolean isOutsideWithinDistance(int blockX, int blockZ, @NotNull Claim claim, int radiusBlocks) {
        if (radiusBlocks < 0) {
            return false;
        }
        double dist = flatDistanceToRect(blockX, blockZ, claim);
        return dist > 0.0D && dist <= radiusBlocks + 0.001D;
    }

    public static int compassSector(int fromCenterX, int fromCenterZ, int x, int z) {
        double dx = x - fromCenterX;
        double dz = z - fromCenterZ;
        if (dx == 0.0D && dz == 0.0D) {
            return 0;
        }
        double angle = Math.atan2(dz, dx);
        int sector = (int) Math.floor((angle + Math.PI) / (Math.PI / 4.0D));
        if (sector < 0) {
            sector = 0;
        }
        if (sector > 7) {
            sector = 7;
        }
        return sector;
    }
}
