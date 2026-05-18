package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Block, interact, explosion, fire, fluid and entity protection for claims.
 */
public final class ClaimProtectionListener implements Listener {

    private final CPSMPPlugin plugin;
    private final ClaimManager claims;

    public ClaimProtectionListener(CPSMPPlugin plugin, ClaimManager claims) {
        this.plugin = plugin;
        this.claims = claims;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!shouldCheck(event.getPlayer())) {
            return;
        }
        if (claims.allowsBlockChange(event.getPlayer(), event.getBlock().getLocation(), true)) {
            return;
        }
        event.setCancelled(true);
        plugin.getMessageManager().sendPrefixed(event.getPlayer(), "claim.protection-deny-break");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!shouldCheck(event.getPlayer())) {
            return;
        }
        if (claims.allowsBlockChange(event.getPlayer(), event.getBlock().getLocation(), false)) {
            return;
        }
        event.setCancelled(true);
        plugin.getMessageManager().sendPrefixed(event.getPlayer(), "claim.protection-deny-build");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (!shouldCheck(player)) {
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null) {
            return;
        }
        Material type = b.getType();
        ClaimManager.InteractionKind kind;
        if (isProtectedContainer(type)) {
            kind = ClaimManager.InteractionKind.CONTAINER;
        } else if (isDoorLike(type)) {
            kind = ClaimManager.InteractionKind.DOOR;
        } else if (isRedstoneInteract(type)) {
            kind = ClaimManager.InteractionKind.REDSTONE;
        } else {
            kind = ClaimManager.InteractionKind.GENERIC;
        }
        if (claims.denyInteraction(player, b.getLocation(), kind)) {
            event.setCancelled(true);
            if (kind == ClaimManager.InteractionKind.CONTAINER) {
                plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-container");
            } else {
                plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-interact");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!shouldCheck(player)) {
            return;
        }
        Entity e = event.getRightClicked();
        if (claims.getConfig().isProtectItemFrames() && e instanceof ItemFrame) {
            Claim claim = claims.claimAt(e.getLocation());
            if (claim != null && !claims.canBuild(player, claim)) {
                event.setCancelled(true);
                plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-interact");
            }
            return;
        }
        if (claims.getConfig().isProtectArmorStands() && e instanceof ArmorStand) {
            Claim claim = claims.claimAt(e.getLocation());
            if (claim != null && !claims.canBuild(player, claim)) {
                event.setCancelled(true);
                plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-entity");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (!claims.isOperational() || !claims.getConfig().isProtectArmorStands()) {
            return;
        }
        Player player = event.getPlayer();
        if (!shouldCheck(player)) {
            return;
        }
        ArmorStand stand = event.getRightClicked();
        Claim claim = claims.claimAt(stand.getLocation());
        if (claim == null) {
            return;
        }
        if (claims.canBuild(player, claim)) {
            return;
        }
        event.setCancelled(true);
        plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-armorstand");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!claims.isOperational() || !claims.getConfig().isProtectEntityDamage()) {
            return;
        }
        Entity victim = event.getEntity();
        Player attacker = resolveDamagerPlayer(event.getDamager());
        if (attacker == null || !shouldCheck(attacker)) {
            return;
        }
        Claim claim = claims.claimAt(victim.getLocation());
        if (claim == null) {
            return;
        }
        if (claims.canBuild(attacker, claim)) {
            return;
        }
        if (victim instanceof Player) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(attacker, "claim.protection-deny-entity");
            return;
        }
        if (victim instanceof LivingEntity) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(attacker, "claim.protection-deny-entity");
            return;
        }
        if (claims.getConfig().isProtectItemFrames() && victim instanceof ItemFrame) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(attacker, "claim.protection-deny-entity");
            return;
        }
        if (claims.getConfig().isProtectVehicles() && victim instanceof Vehicle) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(attacker, "claim.protection-deny-entity");
            return;
        }
        if (victim instanceof Hanging) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(attacker, "claim.protection-deny-entity");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent event) {
        if (!claims.isOperational()) {
            return;
        }
        if (!claims.getConfig().isProtectItemFrames()) {
            return;
        }
        Entity remover = event.getRemover();
        Player player = remover instanceof Player p ? p : null;
        if (player == null || !shouldCheck(player)) {
            return;
        }
        Claim claim = claims.claimAt(event.getEntity().getLocation());
        if (claim == null) {
            return;
        }
        if (claims.canBuild(player, claim)) {
            return;
        }
        event.setCancelled(true);
        plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-entity");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        List<Block> blocks = event.blockList();
        claims.filterExplosionBlocks(blocks);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        List<Block> blocks = event.blockList();
        claims.filterExplosionBlocks(blocks);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpread(BlockSpreadEvent event) {
        if (claims.shouldCancelFireSpread(event.getBlock(), event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        Block source = event.getIgnitingBlock();
        if (claims.shouldCancelFireSpread(event.getBlock(), source)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (claims.shouldCancelLiquidFlow(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        if (!shouldCheck(player)) {
            return;
        }
        Block b = event.getBlockClicked().getRelative(event.getBlockFace());
        if (claims.denyBucket(player, b.getLocation())) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-interact");
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Player player = event.getPlayer();
        if (!shouldCheck(player)) {
            return;
        }
        if (claims.denyBucket(player, event.getBlockClicked().getLocation())) {
            event.setCancelled(true);
            plugin.getMessageManager().sendPrefixed(player, "claim.protection-deny-interact");
        }
    }

    private static boolean shouldCheck(Player player) {
        return player != null && player.isOnline();
    }

    private static boolean isProtectedContainer(Material type) {
        if (type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL) {
            return true;
        }
        if (type == Material.SHULKER_BOX || type.name().endsWith("_SHULKER_BOX")) {
            return true;
        }
        return type == Material.FURNACE
                || type == Material.BLAST_FURNACE
                || type == Material.SMOKER
                || type == Material.HOPPER
                || type == Material.DISPENSER
                || type == Material.DROPPER
                || type == Material.BREWING_STAND;
    }

    private static boolean isDoorLike(Material type) {
        return Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
    }

    private static boolean isRedstoneInteract(Material type) {
        return Tag.BUTTONS.isTagged(type)
                || Tag.PRESSURE_PLATES.isTagged(type)
                || type == Material.LEVER;
    }

    private static @Nullable Player resolveDamagerPlayer(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj) {
            Object shooter = proj.getShooter();
            if (shooter instanceof Player p) {
                return p;
            }
        }
        return null;
    }
}
