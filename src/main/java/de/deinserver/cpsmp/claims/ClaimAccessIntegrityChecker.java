package de.deinserver.cpsmp.claims;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.logging.Logger;

/**
 * Bounded 2D flood-fill from claim perimeter to scan boundary; counts open compass sectors.
 */
public final class ClaimAccessIntegrityChecker {

    private ClaimAccessIntegrityChecker() {
    }

    /**
     * @param extraSolidX optional extra solid column from a pending placement (-1 to ignore)
     */
    public static int countOpenExits(@NotNull Claim claim, @NotNull World world, int walkBaseY,
                                     int extraSolidX, int extraSolidZ,
                                     @NotNull ClaimAntiEncasementConfig.AccessIntegrity cfg,
                                     @Nullable Logger debugLog) {
        int scanR = cfg.scanRadiusBlocks();
        int minX = claim.minX() - scanR;
        int maxX = claim.maxX() + scanR;
        int minZ = claim.minZ() - scanR;
        int maxZ = claim.maxZ() + scanR;
        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        if (width <= 0 || depth <= 0) {
            return 0;
        }
        int pathH = Math.max(1, cfg.minPathHeightBlocks());
        int pathW = Math.max(1, cfg.minPathWidthBlocks());
        int maxNodes = Math.max(256, cfg.maxNodesPerCheck());
        int centerX = claim.centerX();
        int centerZ = claim.centerZ();

        int size = width * depth;
        byte[] sectorMask = new byte[size];
        ArrayDeque<Long> queue = new ArrayDeque<>();
        int nodes = 0;

        enqueuePerimeterStarts(claim, queue, sectorMask, minX, maxX, minZ, maxZ, width, centerX, centerZ);

        BitSet openSectors = new BitSet(8);
        int[] dx = {1, -1, 0, 0};
        int[] dz = {0, 0, 1, -1};

        while (!queue.isEmpty() && nodes < maxNodes) {
            long packed = queue.poll();
            nodes++;
            int x = unpackX(packed);
            int z = unpackZ(packed);
            int idx = index(x, z, minX, minZ, width);
            int mask = sectorMask[idx] & 0xFF;
            if (mask == 0) {
                continue;
            }
            if (x == minX || x == maxX || z == minZ || z == maxZ) {
                for (int s = 0; s < 8; s++) {
                    if ((mask & (1 << s)) != 0) {
                        openSectors.set(s);
                    }
                }
                if (openSectors.cardinality() >= cfg.requiredOpenExits()) {
                    return openSectors.cardinality();
                }
            }
            for (int i = 0; i < 4; i++) {
                int nx = x + dx[i];
                int nz = z + dz[i];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                if (ClaimSpatialUtil.containsBlock(nx, nz, claim)) {
                    continue;
                }
                int nIdx = index(nx, nz, minX, minZ, width);
                if (isColumnBlocked(world, nx, nz, walkBaseY, pathH, pathW, extraSolidX, extraSolidZ, cfg)) {
                    continue;
                }
                int merged = (sectorMask[nIdx] & 0xFF) | mask;
                if (merged == (sectorMask[nIdx] & 0xFF)) {
                    continue;
                }
                sectorMask[nIdx] = (byte) merged;
                queue.add(pack(nx, nz));
            }
        }

        if (nodes >= maxNodes && debugLog != null && cfg.debug()) {
            debugLog.warning("[CPSMP] Claim access check aborted (node cap) for claim #" + claim.ownerClaimNumber());
        }
        return openSectors.cardinality();
    }

    public static boolean wouldCloseRequiredExits(@NotNull Claim claim, @NotNull World world, int walkBaseY,
                                                  int extraSolidX, int extraSolidZ,
                                                  @NotNull ClaimAntiEncasementConfig.AccessIntegrity cfg,
                                                  @Nullable Logger debugLog) {
        int open = countOpenExits(claim, world, walkBaseY, extraSolidX, extraSolidZ, cfg, debugLog);
        return open < cfg.requiredOpenExits();
    }

    private static void enqueuePerimeterStarts(@NotNull Claim claim, ArrayDeque<Long> queue, byte[] sectorMask,
                                               int scanMinX, int scanMaxX, int scanMinZ, int scanMaxZ,
                                               int width, int centerX, int centerZ) {
        int cminX = claim.minX();
        int cmaxX = claim.maxX();
        int cminZ = claim.minZ();
        int cmaxZ = claim.maxZ();
        for (int x = cminX; x <= cmaxX; x++) {
            tryStart(queue, sectorMask, x, cminZ - 1, scanMinX, scanMaxX, scanMinZ, scanMaxZ, width, centerX, centerZ);
            tryStart(queue, sectorMask, x, cmaxZ + 1, scanMinX, scanMaxX, scanMinZ, scanMaxZ, width, centerX, centerZ);
        }
        for (int z = cminZ; z <= cmaxZ; z++) {
            tryStart(queue, sectorMask, cminX - 1, z, scanMinX, scanMaxX, scanMinZ, scanMaxZ, width, centerX, centerZ);
            tryStart(queue, sectorMask, cmaxX + 1, z, scanMinX, scanMaxX, scanMinZ, scanMaxZ, width, centerX, centerZ);
        }
    }

    private static void tryStart(ArrayDeque<Long> queue, byte[] sectorMask,
                                 int x, int z, int scanMinX, int scanMaxX, int scanMinZ, int scanMaxZ,
                                 int width, int centerX, int centerZ) {
        if (x < scanMinX || x > scanMaxX || z < scanMinZ || z > scanMaxZ) {
            return;
        }
        int idx = index(x, z, scanMinX, scanMinZ, width);
        if (idx < 0 || idx >= sectorMask.length) {
            return;
        }
        int sector = ClaimSpatialUtil.compassSector(centerX, centerZ, x, z);
        int bit = 1 << sector;
        if ((sectorMask[idx] & bit) != 0) {
            return;
        }
        sectorMask[idx] = (byte) ((sectorMask[idx] & 0xFF) | bit);
        queue.add(pack(x, z));
    }

    private static boolean isColumnBlocked(@NotNull World world, int x, int z, int walkBaseY, int pathH, int pathW,
                                           int extraSolidX, int extraSolidZ,
                                           @NotNull ClaimAntiEncasementConfig.AccessIntegrity cfg) {
        if (pathW > 1) {
            for (int ox = 0; ox < pathW; ox++) {
                for (int oz = 0; oz < pathW; oz++) {
                    if (isSingleColumnBlocked(world, x + ox, z + oz, walkBaseY, pathH, extraSolidX, extraSolidZ, cfg)) {
                        return true;
                    }
                }
            }
            return false;
        }
        return isSingleColumnBlocked(world, x, z, walkBaseY, pathH, extraSolidX, extraSolidZ, cfg);
    }

    private static boolean isSingleColumnBlocked(@NotNull World world, int x, int z, int walkBaseY, int pathH,
                                                 int extraSolidX, int extraSolidZ,
                                                 @NotNull ClaimAntiEncasementConfig.AccessIntegrity cfg) {
        for (int y = walkBaseY; y < walkBaseY + pathH; y++) {
            if (extraSolidX >= 0 && x == extraSolidX && z == extraSolidZ) {
                return true;
            }
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();
            if (type.isAir()) {
                continue;
            }
            if (cfg.checkLiquidPlace() && ClaimMaterialUtil.isLiquid(type)) {
                return true;
            }
            if (type.isSolid() || type.isOccluding()) {
                return true;
            }
        }
        return false;
    }

    private static int index(int x, int z, int minX, int minZ, int width) {
        return (z - minZ) * width + (x - minX);
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
