package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Scores and validates {@link ClaimExitService} landing spots (ground-level, not wall-tops).
 */
final class ClaimExitLandingSupport {

    private static final int OPEN_AREA_RADIUS = 2;
    private static final int WALL_COLUMN_PROBE = 8;

    private ClaimExitLandingSupport() {
    }

    /**
     * Finds a standing location near {@code preferredFeetBlockY} without scanning from world max height.
     */
    static @Nullable Location findStandingNearHeight(@NotNull World world, int x, int z,
                                                      int preferredFeetBlockY, int maxVerticalDifference,
                                                      @NotNull Set<Material> unsafe) {
        int bestDelta = Integer.MAX_VALUE;
        Location best = null;
        int minGround = preferredFeetBlockY - maxVerticalDifference - 1;
        int maxGround = preferredFeetBlockY + maxVerticalDifference - 1;
        for (int groundY = maxGround; groundY >= minGround; groundY--) {
            Block ground = world.getBlockAt(x, groundY, z);
            Block feet = world.getBlockAt(x, groundY + 1, z);
            Block head = world.getBlockAt(x, groundY + 2, z);
            if (!ClaimLandingSupport.isSafeGroundBlock(ground, unsafe)) {
                continue;
            }
            if (!ClaimLandingSupport.isPassableBlock(feet, unsafe)) {
                continue;
            }
            if (!ClaimLandingSupport.isPassableBlock(head, unsafe)) {
                continue;
            }
            int feetY = groundY + 1;
            int delta = Math.abs(feetY - preferredFeetBlockY);
            if (delta > maxVerticalDifference) {
                continue;
            }
            if (delta < bestDelta) {
                bestDelta = delta;
                best = new Location(world, x + 0.5, feetY, z + 0.5, 0.0F, 0.0F);
            }
        }
        return best;
    }

    static double scoreExitCandidate(@NotNull Player player, @NotNull Location candidate,
                                     @NotNull Claim claim, int referenceFeetY,
                                     @NotNull ClaimAntiEncasementConfig.ClaimExit cfg,
                                     @NotNull CPSMPPlugin plugin, @NotNull ClaimManager manager) {
        World world = candidate.getWorld();
        if (world == null) {
            return Double.MAX_VALUE;
        }
        int feetY = candidate.getBlockY();
        double score = 0.0D;

        double vertWeight = cfg.preferSameHeight() ? 14.0D : 8.0D;
        score += Math.abs(feetY - referenceFeetY) * vertWeight;

        double horiz = horizontalDistanceToClaimEdge(candidate.getBlockX(), candidate.getBlockZ(), claim);
        score += Math.max(0.0D, horiz - 1.0D) * 2.0D;
        score += candidate.distance(player.getLocation()) * 0.15D;

        score -= openAreaScore(world, candidate) * 8.0D;

        if (cfg.avoidObstructionTops() && isLikelyWallTop(world, candidate, referenceFeetY)) {
            score += 500.0D;
        }
        if (isNarrowLedge(world, candidate)) {
            score += 120.0D;
        }

        Claim other = manager.claimAt(candidate);
        if (other != null && claim.id() != other.id()
                && !manager.canBuild(player, other)) {
            score += 800.0D;
        }
        return score;
    }

    static boolean passesStrictVertical(@NotNull Location candidate, int referenceFeetY,
                                        @NotNull ClaimAntiEncasementConfig.ClaimExit cfg) {
        return Math.abs(candidate.getBlockY() - referenceFeetY) <= cfg.maxVerticalDifferenceBlocks();
    }

    static boolean passesObstructionRules(@NotNull Location candidate, int referenceFeetY,
                                          @NotNull World world,
                                          @NotNull ClaimAntiEncasementConfig.ClaimExit cfg) {
        if (cfg.avoidObstructionTops() && isLikelyWallTop(world, candidate, referenceFeetY)) {
            return false;
        }
        return !isNarrowLedge(world, candidate);
    }

    private static double horizontalDistanceToClaimEdge(int bx, int bz, @NotNull Claim claim) {
        return ClaimSpatialUtil.flatDistanceToRect(bx, bz, claim);
    }

    /**
     * True when the player would stand on a tall narrow column (typical wall top).
     */
    static boolean isLikelyWallTop(@NotNull World world, @NotNull Location candidate, int referenceFeetY) {
        int x = candidate.getBlockX();
        int z = candidate.getBlockZ();
        int groundY = candidate.getBlockY() - 1;
        if (groundY < world.getMinHeight()) {
            return false;
        }
        if (groundY - referenceFeetY > 3) {
            int columnHeight = countSolidColumnBelow(world, x, groundY, z);
            if (columnHeight >= 3 && countSolidNeighborsAt(world, x, groundY, z) <= 1) {
                return true;
            }
        }
        int below2 = groundY - 1;
        if (below2 >= world.getMinHeight()) {
            Block below = world.getBlockAt(x, below2, z);
            if (below.getType().isAir() && countSolidColumnBelow(world, x, groundY, z) >= 2) {
                return countSolidNeighborsAt(world, x, groundY, z) <= 1;
            }
        }
        return false;
    }

    static boolean isNarrowLedge(@NotNull World world, @NotNull Location candidate) {
        int x = candidate.getBlockX();
        int z = candidate.getBlockZ();
        int y = candidate.getBlockY();
        int solidNeighbors = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Block n = world.getBlockAt(x + dx, y, z + dz);
                if (n.getType().isSolid()) {
                    solidNeighbors++;
                }
            }
        }
        return solidNeighbors <= 1;
    }

    private static int countSolidColumnBelow(@NotNull World world, int x, int startY, int z) {
        int count = 0;
        for (int y = startY; y >= Math.max(world.getMinHeight(), startY - WALL_COLUMN_PROBE); y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type.isAir()) {
                break;
            }
            if (type.isSolid()) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private static int countSolidNeighborsAt(@NotNull World world, int x, int y, int z) {
        int count = 0;
        if (world.getBlockAt(x + 1, y, z).getType().isSolid()) {
            count++;
        }
        if (world.getBlockAt(x - 1, y, z).getType().isSolid()) {
            count++;
        }
        if (world.getBlockAt(x, y, z + 1).getType().isSolid()) {
            count++;
        }
        if (world.getBlockAt(x, y, z - 1).getType().isSolid()) {
            count++;
        }
        return count;
    }

    private static double openAreaScore(@NotNull World world, @NotNull Location candidate) {
        int x = candidate.getBlockX();
        int y = candidate.getBlockY();
        int z = candidate.getBlockZ();
        int open = 0;
        int total = 0;
        for (int dx = -OPEN_AREA_RADIUS; dx <= OPEN_AREA_RADIUS; dx++) {
            for (int dz = -OPEN_AREA_RADIUS; dz <= OPEN_AREA_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                total++;
                Block feet = world.getBlockAt(x + dx, y, z + dz);
                Block head = world.getBlockAt(x + dx, y + 1, z + dz);
                if (!feet.getType().isSolid() && !head.getType().isSolid()) {
                    open++;
                }
            }
        }
        return total == 0 ? 0.0D : (double) open / (double) total;
    }
}
