package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Teleports claim owners (and optionally trusted players) to a safe spot just outside the claim border.
 */
public final class ClaimExitService {

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;

    public ClaimExitService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void requestExit(@NotNull Player player) {
        ClaimAntiEncasementConfig.ClaimExit cfg = manager.getConfig().getAntiEncasement().claimExit();
        if (!manager.getConfig().isEnabled() || !cfg.enabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.exit-disabled");
            return;
        }
        Claim at = manager.claimAt(player.getLocation());
        if (at == null || !canUseExit(player, at, cfg)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.exit-not-in-own-claim");
            return;
        }
        Location dest = findSafeExit(player, at, cfg.searchRadiusBlocks());
        if (dest == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.exit-no-safe-location");
            return;
        }
        plugin.getMessageManager().sendPrefixed(player, "claim.exit-start");
        int delay = cfg.teleportDelaySeconds();
        plugin.getTeleportService().requestTeleport(player, dest, delay,
                p -> plugin.getMessageManager().sendPrefixed(p, "claim.exit-success"),
                p -> plugin.getMessageManager().sendPrefixed(p, "teleport.cancelled-move"));
    }

    public boolean canUseExit(@NotNull Player player, @NotNull Claim claim,
                              @NotNull ClaimAntiEncasementConfig.ClaimExit cfg) {
        if (claim.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        return cfg.allowTrusted() && manager.getCache().isTrusted(claim.id(), player.getUniqueId());
    }

    private boolean canUseExit(@NotNull Player player, @NotNull Claim claim) {
        return canUseExit(player, claim, manager.getConfig().getAntiEncasement().claimExit());
    }

    public @Nullable Location findSafeExit(@NotNull Player player, @NotNull Claim claim, int searchRadius) {
        World world = player.getWorld();
        if (world == null || !world.getName().equalsIgnoreCase(claim.worldName())) {
            return null;
        }
        Set<org.bukkit.Material> unsafe = ClaimLandingSupport.unsafeMaterials(plugin);
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        List<int[]> candidates = buildExitCandidates(claim, px, pz, searchRadius);
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        for (int[] c : candidates) {
            Location loc = ClaimLandingSupport.findSafeStanding(world, c[0], c[1], unsafe);
            if (loc == null) {
                continue;
            }
            if (ClaimSpatialUtil.containsBlock(loc.getBlockX(), loc.getBlockZ(), claim)) {
                continue;
            }
            double d = loc.distanceSquared(player.getLocation());
            if (d < bestDist) {
                bestDist = d;
                best = loc;
            }
        }
        return best;
    }

    private static List<int[]> buildExitCandidates(@NotNull Claim claim, int nearX, int nearZ, int searchRadius) {
        List<int[]> out = new ArrayList<>();
        int minX = claim.minX();
        int maxX = claim.maxX();
        int minZ = claim.minZ();
        int maxZ = claim.maxZ();
        for (int d = 1; d <= searchRadius; d++) {
            for (int x = minX - d; x <= maxX + d; x++) {
                out.add(new int[]{x, minZ - d});
                out.add(new int[]{x, maxZ + d});
            }
            for (int z = minZ - d + 1; z <= maxZ + d - 1; z++) {
                out.add(new int[]{minX - d, z});
                out.add(new int[]{maxX + d, z});
            }
        }
        out.sort((a, b) -> {
            double da = distSq(a[0], a[1], nearX, nearZ);
            double db = distSq(b[0], b[1], nearX, nearZ);
            return Double.compare(da, db);
        });
        return out;
    }

    private static double distSq(int x, int z, int nx, int nz) {
        double dx = x - nx;
        double dz = z - nz;
        return dx * dx + dz * dz;
    }
}
