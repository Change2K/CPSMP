package de.deinserver.cpsmp.claims;

/**
 * Computes inclusive claim bounds from a center block and exact sizes.
 * <p>
 * Odd sizes (e.g. 17) are symmetric around the center block ({@code min = cx - half}, {@code max = cx + half}).
 * Even sizes (e.g. 24) place the center block one step toward positive X/Z
 * ({@code min = cx - half + 1}, {@code max = cx + half}) so width/depth equals exactly {@code size}, not {@code size + 1}.
 */
public record ClaimCreationBounds(int minX, int maxX, int minZ, int maxZ) {

    public static ClaimCreationBounds fromCenter(int centerBlockX, int centerBlockZ, int sizeX, int sizeZ) {
        int sx = Math.max(1, sizeX);
        int sz = Math.max(1, sizeZ);
        return new ClaimCreationBounds(
                axisMin(centerBlockX, sx),
                axisMax(centerBlockX, sx),
                axisMin(centerBlockZ, sz),
                axisMax(centerBlockZ, sz));
    }

    private static int axisMin(int center, int size) {
        int half = size / 2;
        if ((size & 1) == 0) {
            return center - half + 1;
        }
        return center - half;
    }

    private static int axisMax(int center, int size) {
        int half = size / 2;
        if ((size & 1) == 0) {
            return center + half;
        }
        return center + half;
    }

    public int widthBlocks() {
        return maxX - minX + 1;
    }

    public int depthBlocks() {
        return maxZ - minZ + 1;
    }
}
