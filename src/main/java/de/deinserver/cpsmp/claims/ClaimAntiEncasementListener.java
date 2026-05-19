package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

/**
 * Direct edge + access-integrity anti-encasement (untrusted players only).
 */
public final class ClaimAntiEncasementListener implements Listener {

    private final CPSMPPlugin plugin;
    private final ClaimManager claims;
    private final ClaimAntiEncasementService anti;

    public ClaimAntiEncasementListener(CPSMPPlugin plugin, ClaimManager claims, ClaimAntiEncasementService anti) {
        this.plugin = plugin;
        this.claims = claims;
        this.anti = anti;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player == null || !anti.isEnabled()) {
            return;
        }
        Block block = event.getBlockPlaced();
        if (anti.denyBlockPlace(player, block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (!anti.isEnabled()) {
            return;
        }
        ClaimAntiEncasementConfig.DirectEdge edge = claims.getConfig().getAntiEncasement().directEdge();
        if (!edge.buckets() && !claims.getConfig().getAntiEncasement().accessIntegrity().checkLiquidPlace()) {
            return;
        }
        Block block = event.getBlockClicked().getRelative(event.getBlockFace());
        if (anti.denyLiquidPlace(player, block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLiquidFlow(BlockFromToEvent event) {
        if (!anti.isEnabled()) {
            return;
        }
        if (anti.denyLiquidFlowTo(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!anti.isEnabled()) {
            return;
        }
        if (anti.denyPistonBlocks(null, event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!anti.isEnabled()) {
            return;
        }
        if (anti.denyPistonBlocks(null, event.getBlocks())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!anti.isEnabled()) {
            return;
        }
        anti.filterExplosionBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!anti.isEnabled()) {
            return;
        }
        anti.filterExplosionBlocks(event.blockList());
    }
}
