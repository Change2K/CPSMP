package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Anti-encasement: direct edge band + bounded access-integrity checks for untrusted players only.
 */
public final class ClaimAntiEncasementService {

    public enum DenyKind {
        EDGE_BLOCK,
        EDGE_LIQUID,
        EDGE_PISTON,
        ACCESS_BLOCK,
        ACCESS_LIQUID,
        ACCESS_PISTON
    }

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;
    private final Map<UUID, Long> actionbarCooldown = new ConcurrentHashMap<>();

    public ClaimAntiEncasementService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public boolean isEnabled() {
        ClaimConfig cfg = manager.getConfig();
        return manager.isOperational() && cfg.isEnabled() && cfg.getAntiEncasement().enabled();
    }

    public boolean shouldBypass(@NotNull Player player, @NotNull Claim claim) {
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        if (ae.ignoreAdminBypass() && ClaimPermission.hasBypass(player)) {
            return true;
        }
        if (ae.ignoreOwner() && claim.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        if (ae.ignoreTrusted() && manager.getCache().isTrusted(claim.id(), player.getUniqueId())) {
            return true;
        }
        return false;
    }

    public boolean denyBlockPlace(@NotNull Player player, @NotNull Block block) {
        if (!isEnabled()) {
            return false;
        }
        ClaimConfig cfg = manager.getConfig();
        ClaimAntiEncasementConfig ae = cfg.getAntiEncasement();
        if (!ae.directEdge().blockPlace() && !ae.accessIntegrity().checkBlockPlace()) {
            return false;
        }
        Material type = block.getType();
        if (!type.isSolid() && !type.isOccluding()) {
            return false;
        }
        return denyAtColumn(player, block.getX(), block.getY(), block.getZ(), block.getWorld(),
                ae.directEdge().blockPlace(), ae.accessIntegrity().checkBlockPlace(),
                DenyKind.EDGE_BLOCK, DenyKind.ACCESS_BLOCK);
    }

    public boolean denyLiquidPlace(@NotNull Player player, @NotNull Block block) {
        if (!isEnabled()) {
            return false;
        }
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        if (!ae.directEdge().liquidPlace() && !ae.accessIntegrity().checkLiquidPlace()) {
            return false;
        }
        Material type = block.getType();
        if (!ClaimMaterialUtil.isLiquid(type)) {
            return false;
        }
        return denyAtColumn(player, block.getX(), block.getY(), block.getZ(), block.getWorld(),
                ae.directEdge().liquidPlace(), ae.accessIntegrity().checkLiquidPlace(),
                DenyKind.EDGE_LIQUID, DenyKind.ACCESS_LIQUID);
    }

    public boolean denyLiquidFlowTo(@NotNull Block to) {
        if (!isEnabled()) {
            return false;
        }
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        if (!ae.accessIntegrity().checkLiquidFlow()) {
            return false;
        }
        Material type = to.getType();
        if (!ClaimMaterialUtil.isLiquid(type) && !type.isAir()) {
            return false;
        }
        return denyFlowAt(to.getWorld(), to.getX(), to.getY(), to.getZ());
    }

    public boolean denyPistonBlocks(@Nullable Player player, @NotNull List<Block> movedBlocks) {
        if (!isEnabled() || movedBlocks.isEmpty()) {
            return false;
        }
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        if (!ae.directEdge().pistons() && !ae.accessIntegrity().checkPistons()) {
            return false;
        }
        for (Block b : movedBlocks) {
            if (player != null) {
                if (denyAtColumn(player, b.getX(), b.getY(), b.getZ(), b.getWorld(),
                        ae.directEdge().pistons(), ae.accessIntegrity().checkPistons(),
                        DenyKind.EDGE_PISTON, DenyKind.ACCESS_PISTON)) {
                    return true;
                }
            } else if (denyFlowAt(b.getWorld(), b.getX(), b.getY(), b.getZ())) {
                return true;
            }
        }
        return false;
    }

    public void filterExplosionBlocks(@NotNull List<Block> blocks) {
        if (!isEnabled()) {
            return;
        }
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        if (!ae.directEdge().explosions() && !ae.accessIntegrity().checkExplosions()) {
            return;
        }
        blocks.removeIf(b -> wouldObstructForeignClaim(b.getWorld(), b.getX(), b.getY(), b.getZ()));
    }

    public void notifyDeny(@NotNull Player player, @NotNull DenyKind kind) {
        String path = switch (kind) {
            case EDGE_BLOCK -> "claim.anti-edge-blocked";
            case EDGE_LIQUID -> "claim.anti-edge-liquid-blocked";
            case EDGE_PISTON -> "claim.anti-edge-piston-blocked";
            case ACCESS_BLOCK -> "claim.access-blocked";
            case ACCESS_LIQUID -> "claim.access-liquid-blocked";
            case ACCESS_PISTON -> "claim.access-piston-blocked";
        };
        long now = System.currentTimeMillis();
        UUID id = player.getUniqueId();
        Long last = actionbarCooldown.get(id);
        if (last == null || now - last > 2000L) {
            actionbarCooldown.put(id, now);
            plugin.getMessageManager().sendPrefixed(player, path);
        }
        plugin.getMessageManager().sendActionBar(player, path);
    }

    private boolean denyAtColumn(@NotNull Player player, int x, int y, int z, @NotNull World world,
                                 boolean checkEdge, boolean checkAccess,
                                 @NotNull DenyKind edgeKind, @NotNull DenyKind accessKind) {
        Claim at = manager.claimAt(new Location(world, x, y, z));
        if (at != null && manager.canBuild(player, at)) {
            return false;
        }
        int expand = expandBlocks(checkEdge, checkAccess);
        List<Claim> nearby = manager.getCache().listNear(world.getName(), x, z, expand,
                manager.getConfig().getAntiEncasement().maxClaimsCheckedPerEvent(),
                c -> !shouldBypass(player, c));
        if (nearby.isEmpty()) {
            return false;
        }
        int walkY = player.getLocation().getBlockY();
        Logger log = plugin.getLogger();
        for (Claim claim : nearby) {
            if (checkEdge && manager.getConfig().getAntiEncasement().directEdge().enabled()
                    && ClaimSpatialUtil.isOutsideWithinDistance(x, z, claim,
                    manager.getConfig().getAntiEncasement().directEdge().radiusBlocks())) {
                notifyDeny(player, edgeKind);
                return true;
            }
            if (checkAccess && manager.getConfig().getAntiEncasement().accessIntegrity().enabled()) {
                ClaimAntiEncasementConfig.AccessIntegrity acc = manager.getConfig().getAntiEncasement().accessIntegrity();
                if (ClaimAccessIntegrityChecker.wouldCloseRequiredExits(claim, world, walkY, x, z, acc, log)) {
                    if (acc.debug()) {
                        int open = ClaimAccessIntegrityChecker.countOpenExits(claim, world, walkY, x, z, acc, log);
                        plugin.getMessageManager().sendPrefixed(player, "claim.access-debug-open-exits",
                                Map.of("exits", Integer.toString(open)));
                    }
                    notifyDeny(player, accessKind);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean denyFlowAt(@NotNull World world, int x, int y, int z) {
        return wouldObstructForeignClaim(world, x, y, z);
    }

    private boolean wouldObstructForeignClaim(@NotNull World world, int x, int y, int z) {
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        int expand = expandBlocks(ae.directEdge().enabled() && ae.directEdge().explosions(),
                ae.accessIntegrity().enabled() && ae.accessIntegrity().checkExplosions());
        List<Claim> nearby = manager.getCache().listNear(world.getName(), x, z, expand,
                ae.maxClaimsCheckedPerEvent(), c -> true);
        for (Claim claim : nearby) {
            if (ae.directEdge().enabled() && ae.directEdge().explosions()
                    && ClaimSpatialUtil.isOutsideWithinDistance(x, z, claim, ae.directEdge().radiusBlocks())) {
                return true;
            }
            if (ae.accessIntegrity().enabled() && ae.accessIntegrity().checkExplosions()) {
                ClaimAntiEncasementConfig.AccessIntegrity acc = ae.accessIntegrity();
                if (ClaimAccessIntegrityChecker.wouldCloseRequiredExits(claim, world, y, x, z, acc,
                        plugin.getLogger())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int expandBlocks(boolean checkEdge, boolean checkAccess) {
        ClaimAntiEncasementConfig ae = manager.getConfig().getAntiEncasement();
        int expand = 0;
        if (checkEdge && ae.directEdge().enabled()) {
            expand = Math.max(expand, ae.directEdge().radiusBlocks());
        }
        if (checkAccess && ae.accessIntegrity().enabled()) {
            expand = Math.max(expand, ae.accessIntegrity().scanRadiusBlocks());
        }
        return Math.max(1, expand);
    }
}
