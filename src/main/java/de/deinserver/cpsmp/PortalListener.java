package de.deinserver.cpsmp;

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
 */
public final class PortalListener implements Listener {

    private static final String PORTAL_COOLDOWN_KEY = "portal";

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
            if (portal.contains(loc)) {
                matched = portal;
                break;
            }
        }
        if (matched == null) {
            inside.remove(player.getUniqueId());
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
                player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
            case RTP -> plugin.getRtpService().runRandomTeleport(player);
            case ZONE_DANGER -> plugin.getZoneManager().teleportToZone(player, ZoneManager.ZoneKind.DANGER);
            case ZONE_ATTACK -> plugin.getZoneManager().teleportToZone(player, ZoneManager.ZoneKind.ATTACK);
        }
    }

    private void playPortalSound(Player player, Portal portal) {
        String soundName = portal.getSound();
        if (soundName == null || soundName.isBlank()) return;
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 0.7F, 1.0F);
        } catch (IllegalArgumentException ignored) {
            // Unknown sound name - intentionally silent.
        }
    }

    private void playPortalParticles(Player player, Portal portal) {
        String particleName = portal.getParticles();
        if (particleName == null || particleName.isBlank()) return;
        try {
            Particle particle = Particle.valueOf(particleName);
            player.getWorld().spawnParticle(particle, player.getLocation().add(0, 1, 0), 30,
                    0.4, 0.6, 0.4, 0.01);
        } catch (IllegalArgumentException ignored) {
            // Unknown particle name - intentionally silent.
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
    }
}
