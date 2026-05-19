package de.deinserver.cpsmp.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Loaded from {@code claims.anti-encasement} in {@code claims.yml}.
 */
public final class ClaimAntiEncasementConfig {

    private final boolean enabled;
    private final boolean ignoreOwner;
    private final boolean ignoreTrusted;
    private final boolean ignoreAdminBypass;
    private final int maxClaimsCheckedPerEvent;
    private final DirectEdge directEdge;
    private final AccessIntegrity accessIntegrity;
    private final ClaimExit claimExit;

    public ClaimAntiEncasementConfig(@Nullable ConfigurationSection root) {
        if (root == null) {
            this.enabled = true;
            this.ignoreOwner = true;
            this.ignoreTrusted = true;
            this.ignoreAdminBypass = true;
            this.maxClaimsCheckedPerEvent = 5;
            this.directEdge = DirectEdge.defaults();
            this.accessIntegrity = AccessIntegrity.defaults();
            this.claimExit = ClaimExit.defaults();
            return;
        }
        this.enabled = root.getBoolean("enabled", true);
        this.ignoreOwner = root.getBoolean("ignore-owner", true);
        this.ignoreTrusted = root.getBoolean("ignore-trusted", true);
        this.ignoreAdminBypass = root.getBoolean("ignore-admin-bypass", true);
        this.maxClaimsCheckedPerEvent = Math.max(1, root.getInt("max-claims-checked-per-event", 5));
        this.directEdge = DirectEdge.from(root.getConfigurationSection("direct-edge"));
        this.accessIntegrity = AccessIntegrity.from(root.getConfigurationSection("access-integrity"));
        this.claimExit = ClaimExit.from(root.getConfigurationSection("claim-exit"));
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean ignoreOwner() {
        return ignoreOwner;
    }

    public boolean ignoreTrusted() {
        return ignoreTrusted;
    }

    public boolean ignoreAdminBypass() {
        return ignoreAdminBypass;
    }

    public int maxClaimsCheckedPerEvent() {
        return maxClaimsCheckedPerEvent;
    }

    public DirectEdge directEdge() {
        return directEdge;
    }

    public AccessIntegrity accessIntegrity() {
        return accessIntegrity;
    }

    public ClaimExit claimExit() {
        return claimExit;
    }

    public record DirectEdge(
            boolean enabled,
            int radiusBlocks,
            boolean blockPlace,
            boolean liquidPlace,
            boolean buckets,
            boolean pistons,
            boolean explosions) {

        static DirectEdge defaults() {
            return new DirectEdge(true, 2, true, true, true, true, true);
        }

        static DirectEdge from(@Nullable ConfigurationSection s) {
            if (s == null) {
                return defaults();
            }
            return new DirectEdge(
                    s.getBoolean("enabled", true),
                    Math.max(0, s.getInt("radius-blocks", 2)),
                    s.getBoolean("block-place", true),
                    s.getBoolean("liquid-place", true),
                    s.getBoolean("buckets", true),
                    s.getBoolean("pistons", true),
                    s.getBoolean("explosions", true));
        }
    }

    public record AccessIntegrity(
            boolean enabled,
            int scanRadiusBlocks,
            int requiredOpenExits,
            int minPathWidthBlocks,
            int minPathHeightBlocks,
            boolean checkBlockPlace,
            boolean checkLiquidPlace,
            boolean checkLiquidFlow,
            boolean checkPistons,
            boolean checkExplosions,
            boolean requireCheckNearClaimOnly,
            int maxNodesPerCheck,
            boolean debug) {

        static AccessIntegrity defaults() {
            return new AccessIntegrity(true, 64, 2, 2, 2,
                    true, true, true, true, true, true, 12000, false);
        }

        static AccessIntegrity from(@Nullable ConfigurationSection s) {
            if (s == null) {
                return defaults();
            }
            return new AccessIntegrity(
                    s.getBoolean("enabled", true),
                    Math.max(8, s.getInt("scan-radius-blocks", 64)),
                    Math.max(1, s.getInt("required-open-exits", 2)),
                    Math.max(1, s.getInt("min-path-width-blocks", 2)),
                    Math.max(1, s.getInt("min-path-height-blocks", 2)),
                    s.getBoolean("check-block-place", true),
                    s.getBoolean("check-liquid-place", true),
                    s.getBoolean("check-liquid-flow", true),
                    s.getBoolean("check-pistons", true),
                    s.getBoolean("check-explosions", true),
                    s.getBoolean("require-check-near-claim-only", true),
                    Math.max(256, s.getInt("max-nodes-per-check", 12000)),
                    s.getBoolean("debug", false));
        }
    }

    public record ClaimExit(
            boolean enabled,
            boolean allowTrusted,
            int teleportDelaySeconds,
            int searchRadiusBlocks) {

        static ClaimExit defaults() {
            return new ClaimExit(true, true, 3, 12);
        }

        static ClaimExit from(@Nullable ConfigurationSection s) {
            if (s == null) {
                return defaults();
            }
            return new ClaimExit(
                    s.getBoolean("enabled", true),
                    s.getBoolean("allow-trusted", true),
                    Math.max(0, s.getInt("teleport-delay-seconds", 3)),
                    Math.max(2, s.getInt("search-radius-blocks", 12)));
        }
    }
}
