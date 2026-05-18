package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.compat.RegistryLookup;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Player-local claim boundary preview (configurable particle, duration, interval).
 */
public final class ClaimVisualService {

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;
    private final Map<UUID, BukkitTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger visualEpoch = new AtomicInteger();

    public ClaimVisualService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void cancelAll() {
        visualEpoch.incrementAndGet();
        for (BukkitTask task : tasks.values()) {
            task.cancel();
        }
        tasks.clear();
    }

    public void cancel(UUID playerId) {
        BukkitTask t = tasks.remove(playerId);
        if (t != null) {
            t.cancel();
        }
    }

    /**
     * Draws the XZ footprint border for {@code claim}, visible only to {@code viewer}.
     */
    public void showBorder(Player viewer, Claim claim) {
        ClaimConfig cfg = manager.getConfig();
        if (!cfg.isEnabled() || !cfg.isVisualsEnabled()) {
            return;
        }
        Particle resolved = RegistryLookup.particle(cfg.getBorderParticleRaw());
        final Particle particle = resolved != null ? resolved : Particle.END_ROD;
        cancel(viewer.getUniqueId());
        World world = Bukkit.getWorld(claim.worldName());
        if (world == null) {
            return;
        }
        int viewY = viewer.getLocation().getBlockY() + 1;
        long durationTicks = Math.max(20L, (long) cfg.getVisualDurationSeconds() * 20L);
        long interval = Math.max(1L, cfg.getVisualIntervalTicks());
        final int epoch = visualEpoch.get();
        long deadline = System.currentTimeMillis() + durationTicks * 50L;
        AtomicReference<BukkitTask> holder = new AtomicReference<>();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (epoch != visualEpoch.get() || !viewer.isOnline()) {
                BukkitTask self = holder.get();
                if (self != null) {
                    self.cancel();
                }
                tasks.remove(viewer.getUniqueId());
                return;
            }
            if (viewer.getWorld() != world) {
                BukkitTask self = holder.get();
                if (self != null) {
                    self.cancel();
                }
                tasks.remove(viewer.getUniqueId());
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                BukkitTask self = holder.get();
                if (self != null) {
                    self.cancel();
                }
                tasks.remove(viewer.getUniqueId());
                return;
            }
            strokeRectangle(world, claim.minX(), claim.maxX(), claim.minZ(), claim.maxZ(), viewY, particle, viewer);
        }, 0L, interval);
        holder.set(task);
        tasks.put(viewer.getUniqueId(), task);
    }

    private static void strokeRectangle(World world, int minX, int maxX, int minZ, int maxZ, int y,
                                       Particle particle, Player viewer) {
        double py = y + 0.1;
        int step = Math.max(1, Math.max(maxX - minX, maxZ - minZ) / 24);
        for (int x = minX; x <= maxX; x += step) {
            spawnParticle(viewer, particle, world, x + 0.5, py, minZ + 0.5);
            spawnParticle(viewer, particle, world, x + 0.5, py, maxZ + 0.5);
        }
        for (int z = minZ; z <= maxZ; z += step) {
            spawnParticle(viewer, particle, world, minX + 0.5, py, z + 0.5);
            spawnParticle(viewer, particle, world, maxX + 0.5, py, z + 0.5);
        }
        spawnParticle(viewer, particle, world, minX + 0.5, py, minZ + 0.5);
        spawnParticle(viewer, particle, world, maxX + 0.5, py, minZ + 0.5);
        spawnParticle(viewer, particle, world, minX + 0.5, py, maxZ + 0.5);
        spawnParticle(viewer, particle, world, maxX + 0.5, py, maxZ + 0.5);
    }

    private static void spawnParticle(@NotNull Player viewer, Particle particle,
                                      World world, double x, double y, double z) {
        Location loc = new Location(world, x, y, z);
        viewer.spawnParticle(particle, loc, 1);
    }
}
