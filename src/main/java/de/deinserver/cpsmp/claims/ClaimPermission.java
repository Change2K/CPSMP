package de.deinserver.cpsmp.claims;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Permission helpers for claim counts (V4.0 — count-based limits only).
 */
public final class ClaimPermission {

    public static final String CLAIM_ROOT = "cpsmp.claim";
    public static final String CLAIM_INFO = "cpsmp.claim.info";
    /**
     * Preview / toggle of claim boundaries (/plot show, /claim show). Default true in plugin.yml.
     */
    public static final String CLAIM_SHOW = "cpsmp.claim.show";
    public static final String CLAIM_LIST = "cpsmp.claim.list";
    public static final String CLAIM_TRUST = "cpsmp.claim.trust";
    public static final String CLAIM_UNTRUST = "cpsmp.claim.untrust";
    public static final String CLAIM_ABANDON = "cpsmp.claim.abandon";
    public static final String BYPASS = "cpsmp.claim.bypass";
    public static final String ADMIN = "cpsmp.claim.admin";
    /** Optional: teleport to another player's claim without full claim admin. */
    public static final String CLAIM_TELEPORT_ADMIN = "cpsmp.claim.teleport.admin";
    public static final String CLAIM_MERGE = "cpsmp.claim.merge";
    /** Safe exit from own/trusted claim ({@code /claimexit}). */
    public static final String CLAIM_EXIT = "cpsmp.claim.exit";
    public static final String FLAGS = "cpsmp.claim.flags";
    public static final String FLAGS_ADMIN = "cpsmp.claim.flags.admin";

    private ClaimPermission() {
    }

    public static boolean hasAdminClaimTeleport(@NotNull Player player) {
        return player.hasPermission(ADMIN) || player.hasPermission(CLAIM_TELEPORT_ADMIN);
    }

    /**
     * Effective max number of claims. OP = unlimited ({@link Integer#MAX_VALUE}).
     */
    public static int resolveClaimLimit(@NotNull Player player, @NotNull ClaimConfig cfg) {
        if (player.isOp()) {
            return Integer.MAX_VALUE;
        }
        if (player.hasPermission("cpsmp.claims.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int limit = Math.max(0, cfg.getDefaultClaimLimit());
        for (int n : new int[]{1, 3, 4, 5, 10}) {
            if (player.hasPermission("cpsmp.claims." + n)) {
                limit = Math.max(limit, n);
            }
        }
        return limit;
    }

    public static boolean hasBypass(@NotNull Player player) {
        return player.isOp() || player.hasPermission(BYPASS);
    }

    /**
     * Plot/Claim-Grenze anzeigen ({@link ClaimPermission#CLAIM_INFO} oder {@link ClaimPermission#CLAIM_SHOW}).
     */
    public static boolean canShowClaimBorder(@NotNull Player player) {
        return player.hasPermission(CLAIM_INFO) || player.hasPermission(CLAIM_SHOW);
    }
}
