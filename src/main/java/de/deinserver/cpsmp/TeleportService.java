package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Delayed-teleport pipeline used by both /spawn and /rtp. The service
 * - shows a German actionbar countdown,
 * - cancels the teleport on movement (configurable threshold),
 * - cancels the teleport on damage (configurable),
 * - plays a sound and dispatches optional success / cancel callbacks once done.
 *
 * <p>All teleports use Paper's {@code teleportAsync} so chunks load safely
 * without stalling the main thread.
 */
public final class TeleportService implements Listener {

    private final CPSMPPlugin plugin;
    private final Map<UUID, PendingTeleport> pending = new HashMap<>();

    public TeleportService(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void shutdown() {
        for (PendingTeleport pendingTeleport : pending.values()) {
            if (pendingTeleport.task != null) {
                pendingTeleport.task.cancel();
            }
        }
        pending.clear();
        HandlerList.unregisterAll(this);
    }

    /**
     * Begins a delayed teleport.
     *
     * @param player      the player to teleport
     * @param destination the destination location (must have a loaded world)
     * @param onSuccess   optional callback invoked after a successful teleport
     * @param onCancel    optional callback invoked when the teleport is aborted
     */
    public void requestTeleport(Player player,
                                Location destination,
                                @Nullable Consumer<Player> onSuccess,
                                @Nullable Consumer<Player> onCancel) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(destination);

        if (destination.getWorld() == null) {
            plugin.getMessageManager().sendPrefixed(player, "general.world-missing",
                    Map.of("world", "?"));
            if (onCancel != null) onCancel.accept(player);
            return;
        }

        // Replace any previously-pending teleport.
        cancel(player, false);

        int delaySeconds = Math.max(0, plugin.getConfig().getInt("teleport.delay-seconds", 3));

        if (delaySeconds == 0) {
            performTeleport(player, destination, onSuccess);
            return;
        }

        plugin.getMessageManager().sendPrefixed(player, "teleport.start");

        PendingTeleport entry = new PendingTeleport(
                player.getUniqueId(),
                player.getLocation().clone(),
                destination,
                delaySeconds,
                onSuccess,
                onCancel
        );
        pending.put(player.getUniqueId(), entry);

        // 1 tick = 50ms, so 20 ticks = 1s.
        entry.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(entry), 20L, 20L);

        // Show the initial countdown immediately so the player has feedback right away.
        plugin.getMessageManager().sendActionBar(player, "teleport.countdown-actionbar",
                Map.of("time", Integer.toString(entry.remaining)));
    }

    private void tick(PendingTeleport entry) {
        Player player = Bukkit.getPlayer(entry.player);
        if (player == null || !player.isOnline()) {
            cancelInternal(entry, false, true);
            return;
        }
        entry.remaining--;
        if (entry.remaining <= 0) {
            pending.remove(entry.player);
            if (entry.task != null) {
                entry.task.cancel();
            }
            performTeleport(player, entry.destination, entry.onSuccess);
            return;
        }
        plugin.getMessageManager().sendActionBar(player, "teleport.countdown-actionbar",
                Map.of("time", Integer.toString(entry.remaining)));
    }

    private void performTeleport(Player player, Location destination, @Nullable Consumer<Player> onSuccess) {
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                playSuccessSound(player);
                if (onSuccess != null) {
                    onSuccess.accept(player);
                }
            });
        });
    }

    private void playSuccessSound(Player player) {
        String soundName = plugin.getConfig().getString("teleport.success-sound", "ENTITY_ENDERMAN_TELEPORT");
        float volume = (float) plugin.getConfig().getDouble("teleport.success-sound-volume", 0.7D);
        float pitch = (float) plugin.getConfig().getDouble("teleport.success-sound-pitch", 1.2D);
        try {
            Sound sound = Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // Unknown sound name - intentionally silent to avoid console spam.
        }
    }

    /** Public API for callers. {@code notify} controls whether the player sees a message. */
    public void cancel(Player player, boolean notify) {
        PendingTeleport entry = pending.remove(player.getUniqueId());
        if (entry == null) {
            return;
        }
        cancelInternal(entry, true, !notify);
    }

    public boolean isPending(UUID playerId) {
        return pending.containsKey(playerId);
    }

    /**
     * @param notifyMove if true the player sees the "cancelled by movement" message,
     *                   otherwise the "cancelled by damage" message
     * @param silent     suppress all feedback (used for quits / silent cancels)
     */
    private void cancelInternal(PendingTeleport entry, boolean notifyMove, boolean silent) {
        if (entry.task != null) {
            entry.task.cancel();
        }
        pending.remove(entry.player);
        Player player = Bukkit.getPlayer(entry.player);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!silent) {
            plugin.getMessageManager().sendPrefixed(player,
                    notifyMove ? "teleport.cancelled-move" : "teleport.cancelled-damage");
        }
        if (entry.onCancel != null) {
            entry.onCancel.accept(player);
        }
    }

    // --- Listener hooks --------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PendingTeleport entry = pending.get(event.getPlayer().getUniqueId());
        if (entry == null) {
            return;
        }
        Location from = entry.origin;
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        double threshold = plugin.getConfig().getDouble("teleport.move-threshold", 0.35D);
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double distSquared = dx * dx + dy * dy + dz * dz;
        if (distSquared >= threshold * threshold) {
            cancelInternal(entry, true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!plugin.getConfig().getBoolean("teleport.cancel-on-damage", true)) {
            return;
        }
        PendingTeleport entry = pending.get(player.getUniqueId());
        if (entry != null) {
            cancelInternal(entry, false, false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PendingTeleport entry = pending.remove(event.getPlayer().getUniqueId());
        if (entry != null && entry.task != null) {
            entry.task.cancel();
        }
    }

    // --- Helper types ----------------------------------------------------

    private static final class PendingTeleport {
        final UUID player;
        final Location origin;
        final Location destination;
        int remaining;
        @Nullable
        final Consumer<Player> onSuccess;
        @Nullable
        final Consumer<Player> onCancel;
        @Nullable
        BukkitTask task;

        PendingTeleport(UUID player,
                        Location origin,
                        Location destination,
                        int remaining,
                        @Nullable Consumer<Player> onSuccess,
                        @Nullable Consumer<Player> onCancel) {
            this.player = player;
            this.origin = origin;
            this.destination = destination;
            this.remaining = remaining;
            this.onSuccess = onSuccess;
            this.onCancel = onCancel;
        }
    }
}
