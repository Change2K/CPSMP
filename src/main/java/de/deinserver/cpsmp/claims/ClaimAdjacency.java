package de.deinserver.cpsmp.claims;

/**
 * Axis-aligned claim rectangles: overlap or edge adjacency (optional corner-only).
 */
public final class ClaimAdjacency {

    private ClaimAdjacency() {
    }

    /**
     * Two claims in the same world are mergeable if they overlap, share a face (positive-length edge),
     * or (when {@code allowDiagonalTouch}) touch at a single corner only (Chebyshev gap 1 in both axes).
     */
    public static boolean mergeable(Claim a, Claim b, boolean allowDiagonalTouch) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.worldName().equalsIgnoreCase(b.worldName())) {
            return false;
        }
        if (a.overlapsXZ(b.minX(), b.maxX(), b.minZ(), b.maxZ())) {
            return true;
        }
        int gx = gap1d(a.minX(), a.maxX(), b.minX(), b.maxX());
        int gz = gap1d(a.minZ(), a.maxZ(), b.minZ(), b.maxZ());
        if (gx == 1 && gz == 0) {
            return true;
        }
        if (gx == 0 && gz == 1) {
            return true;
        }
        return allowDiagonalTouch && gx == 1 && gz == 1;
    }

    private static int gap1d(int loA, int hiA, int loB, int hiB) {
        if (hiA < loB) {
            return loB - hiA;
        }
        if (hiB < loA) {
            return loA - hiB;
        }
        return 0;
    }
}
