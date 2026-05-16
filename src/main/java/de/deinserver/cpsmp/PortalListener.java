package de.deinserver.cpsmp;

import de.deinserver.cpsmp.compat.RegistryLookup;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Polls online players and triggers a configured portal once a player walks
 * into the portal's region. Movement is sampled on a fixed interval (default
 * 5 ticks = 4x per second) instead of {@link org.bukkit.event.player.PlayerMoveEvent}
 * to avoid burning CPU on every micro-move.
 *
 * <p>Per-player cooldowns make sure portals never form a teleport loop, even
 * if the destination overlaps another portal.
 *
 * <p>Detection uses the player's current block-integer position checked
 * against the portal's exact inclusive cuboid - never a radius and never a
 * distance test. See {@link Portal#contains(Location)}.
 *
 * <p>Admin bypass: while sneaking, OP players and holders of
 * {@code cpsmp.portal.bypass} pass through portal regions without
 * triggering. A short German actionbar is shown so admins know the bypass
 * is active.
 */
public final class PortalListener implements Listener {

    private static final String PORTAL_COOLDOWN_KEY = "portal";
    /** Permission node that, combined with sneaking, suppresses portal triggers. */
    public static final String BYPASS_PERMISSION = "cpsmp.portal.bypass";
    /** Cooldown bucket for the bypass actionbar so we don't spam it every tick. */
    private static final String BYPASS_HINT_COOLDOWN_KEY = "portal_bypass_hint";
    private static final long BYPASS_HINT_COOLDOWN_MS = 3000L;

    private final CPSMPPlugin plugin;
    /** Players currently inside any portal region (used to fire once per entry). */
    private final Set<UUID> inside = new HashSet<>();
    private BukkitTask scanTask;

    public PortalListener(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long interval = Math.max(1L, plugin.getConfig().getLong("portals.check-interval-ticks", 5L));
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scan, interval, interval);
    }

    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        inside.clear();
        HandlerList.unregisterAll(this);
    }

    private void scan() {
        if (plugin.getPortalManager() == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            handlePlayer(player);
        }
    }

    private void handlePlayer(Player player) {
        if (!player.hasPermission("cpsmp.portal.use")) {
            inside.remove(player.getUniqueId());
            return;
        }
        if (plugin.getTeleportService().isPending(player.getUniqueId())) {
            return; // Already in an outgoing teleport.
        }
        Location loc = player.getLocation();
        Portal matched = null;
        for (Portal portal : plugin.getPortalManager().enabledValid()) {
            // Portal.contains() does an exact inclusive block-cuboid check.
            if (portal.contains(loc)) {
                matched = portal;
                break;
            }
        }
        if (matched == null) {
            inside.remove(player.getUniqueId());
            return;
        }

        // Admin bypass: sneaking + (OP or cpsmp.portal.bypass) suppresses
        // the trigger so admins can configure portals from inside the
        // region without being teleported away.
        if (isBypassing(player)) {
            inside.add(player.getUniqueId());
            sendBypassHint(player);
            return;
        }

        if (inside.contains(player.getUniqueId())) {
            return; // Still inside the same portal; do nothing.
        }
        inside.add(player.getUniqueId());

        if (plugin.getCooldowns().isOnCooldown(PORTAL_COOLDOWN_KEY, player.getUniqueId())) {
            plugin.getMessageManager().sendActionBar(player, "portal.cooldown");
            return;
        }

        triggerPortal(player, matched);
    }

    private boolean isBypassing(Player player) {
        if (!player.isSneaking()) {
            return false;
        }
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    private void sendBypassHint(Player player) {
        if (plugin.getCooldowns().isOnCooldown(BYPASS_HINT_COOLDOWN_KEY, player.getUniqueId())) {
            return;
        }
        plugin.getCooldowns().set(BYPASS_HINT_COOLDOWN_KEY, player.getUniqueId(),
                BYPASS_HINT_COOLDOWN_MS);
        plugin.getMessageManager().sendActionBar(player, "portal.bypass-active");
    }

    private void triggerPortal(Player player, Portal portal) {
        long cooldown = Math.max(
                plugin.getConfig().getLong("portals.global-cooldown-ms", 4000L),
                portal.getCooldownMs()
        );
        plugin.getCooldowns().set(PORTAL_COOLDOWN_KEY, player.getUniqueId(), cooldown);

        // Feedback first.
        plugin.getMessageManager().sendTitleRaw(player, portal.getTitleRaw(), portal.getSubtitleRaw());
        plugin.getMessageManager().sendActionBarRaw(player, portal.getActionbarRaw());
        playPortalSound(player, portal);
        playPortalParticles(player, portal);

        switch (portal.getType()) {
            case TELEPORT -> {
                Location target = portal.getTarget();
                if (target == null) {
                    plugin.getMessageManager().sendPrefixed(player, "portal.target-missing");
                    return;
                }
                // Delegated to the active TeleportAdapter so portal teleports
                // work identically on Paper (async) and Spigot (sync fallback).
                plugin.getTeleportAdapter()
                        .teleport(player, target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            case RTP -> plugin.getRtpService().runRandomTeleport(player);
            case ZONE_DANGER -> plugin.getZoneManager().teleportToZone(player, ZoneManager.ZoneKind.DANGER);
            case ZONE_ATTACK -> plugin.getZoneManager().teleportToZone(player, ZoneManager.ZoneKind.ATTACK);
        }
    }

    private void playPortalSound(Player player, Portal portal) {
        // RegistryLookup accepts both legacy enum names ("BLOCK_BEACON_ACTIVATE")
        // and namespaced keys ("minecraft:block.beacon.activate") so portal
        // configs survive Sound enum reshuffles in newer Paper versions.
        Sound sound = RegistryLookup.sound(portal.getSound());
        if (sound != null) {
            player.playSound(player.getLocation(), sound, 0.7F, 1.0F);
        }
    }

    private void playPortalParticles(Player player, Portal portal) {
        // Same forward-compat treatment for particle identifiers; the Particle
        // enum has historically been renamed across Minecraft versions.
        Particle particle = RegistryLookup.particle(portal.getParticles());
        if (particle != null) {
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), 30,
                    0.4, 0.6, 0.4, 0.01);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
    }
}
