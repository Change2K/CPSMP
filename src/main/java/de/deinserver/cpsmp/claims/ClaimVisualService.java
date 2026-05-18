package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.compat.RegistryLookup;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-player claim outline: WorldBorder-first when configured, minimal particles as fallback.
 * {@code /plot show} uses {@link #toggleFeetFollowDisplay(Player)}; GUI may pin a claim via
 * {@link #toggleGuiPinnedDisplay(Player, long)}.
 */
public final class ClaimVisualService {

    public enum ToggleFeetResult {
        SHOWN,
        HIDDEN,
        NOT_IN_CLAIM
    }

    public enum ToggleGuiResult {
        SHOWN,
        HIDDEN,
        NOT_FOUND,
        WRONG_WORLD
    }

    private enum Source {
        NONE,
        FEET,
        GUI
    }

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;
    private final Map<UUID, Source> sourceByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> guiPinnedClaimId = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> refreshTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pulseTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pulseBorderClearTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pulseGeneration = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAppliedClaimId = new ConcurrentHashMap<>();
    private final AtomicInteger visualEpoch = new AtomicInteger();

    public ClaimVisualService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public void cancelAll() {
        visualEpoch.incrementAndGet();
        for (BukkitTask t : refreshTasks.values()) {
            t.cancel();
        }
        refreshTasks.clear();
        for (BukkitTask t : pulseTasks.values()) {
            t.cancel();
        }
        pulseTasks.clear();
        for (BukkitTask t : pulseBorderClearTasks.values()) {
            t.cancel();
        }
        pulseBorderClearTasks.clear();
        pulseGeneration.clear();
        sourceByPlayer.clear();
        guiPinnedClaimId.clear();
        lastAppliedClaimId.clear();
        clearAllPlayerWorldBordersOnline();
    }

    public void clearPlayer(@NotNull Player player) {
        deactivateFully(player.getUniqueId(), player);
    }

    /**
     * When visuals are turned off in config: stop everything for this player.
     */
    public void clearBecauseVisualsDisabled(@NotNull Player player) {
        deactivateFully(player.getUniqueId(), player);
    }

    public boolean isFeetDisplayActive(@NotNull UUID playerId) {
        return sourceByPlayer.get(playerId) == Source.FEET;
    }

    /**
     * /plot show — toggles follow-at-feet claim outline.
     */
    public @NotNull ToggleFeetResult toggleFeetFollowDisplay(@NotNull Player player) {
        UUID id = player.getUniqueId();
        ClaimConfig cfg = manager.getConfig();
        if (!cfg.isVisualsEnabled() || !cfg.isEnabled()) {
            deactivateFully(id, player);
            return ToggleFeetResult.HIDDEN;
        }
        if (sourceByPlayer.get(id) == Source.FEET) {
            deactivateFully(id, player);
            return ToggleFeetResult.HIDDEN;
        }
        if (sourceByPlayer.containsKey(id)) {
            deactivateFully(id, player);
        }
        Claim at = manager.claimAt(player.getLocation());
        if (at == null) {
            return ToggleFeetResult.NOT_IN_CLAIM;
        }
        sourceByPlayer.put(id, Source.FEET);
        startRefreshTask(player, cfg);
        applyVisualForPlayer(player, at, cfg, true);
        return ToggleFeetResult.SHOWN;
    }

    /**
     * GUI list/detail: toggle pinned border for a specific claim (same world only).
     */
    public @NotNull ToggleGuiResult toggleGuiPinnedDisplay(@NotNull Player player, long claimId) {
        UUID id = player.getUniqueId();
        ClaimConfig cfg = manager.getConfig();
        if (!cfg.isVisualsEnabled() || !cfg.isEnabled()) {
            deactivateFully(id, player);
            return ToggleGuiResult.HIDDEN;
        }
        if (sourceByPlayer.get(id) == Source.GUI && guiPinnedClaimId.getOrDefault(id, -1L) == claimId) {
            deactivateFully(id, player);
            return ToggleGuiResult.HIDDEN;
        }
        Claim c = manager.getCache().byId(claimId);
        if (c == null) {
            return ToggleGuiResult.NOT_FOUND;
        }
        if (player.getWorld() == null
                || !player.getWorld().getName().equalsIgnoreCase(c.worldName())) {
            return ToggleGuiResult.WRONG_WORLD;
        }
        deactivateFully(id, player);
        sourceByPlayer.put(id, Source.GUI);
        guiPinnedClaimId.put(id, claimId);
        startRefreshTask(player, cfg);
        applyVisualForPlayer(player, c, cfg, true);
        return ToggleGuiResult.SHOWN;
    }

    public void showTimedPreview(@NotNull Player viewer, @NotNull Claim claim) {
        ClaimConfig cfg = manager.getConfig();
        if (!cfg.isEnabled() || !cfg.isVisualsEnabled()) {
            return;
        }
        UUID pid = viewer.getUniqueId();
        if (sourceByPlayer.containsKey(pid)) {
            return;
        }
        cancelPulseOnly(pid);
        boolean wantWb = cfg.getVisualMode() == ClaimVisualMode.WORLDBORDER_IF_SAFE;
        boolean wbOk = wantWb && tryWorldBorder(viewer, claim, cfg);
        if (wantWb && !wbOk && canAnnounceWorldBorderFallback(claim, cfg)) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.show-worldborder-unavailable");
        }
        if (wbOk) {
            scheduleTimedWorldBorderClear(viewer, cfg.getVisualDurationSeconds());
            return;
        }
        boolean useParticles = cfg.getVisualMode() == ClaimVisualMode.PARTICLES
                || cfg.isParticlesFallbackEnabled();
        if (useParticles) {
            startParticlePulse(viewer, claim, cfg, true);
        }
    }

    private void scheduleTimedWorldBorderClear(@NotNull Player viewer, int durationSeconds) {
        UUID vid = viewer.getUniqueId();
        int gen = pulseGeneration.merge(vid, 1, Integer::sum);
        BukkitTask prev = pulseBorderClearTasks.remove(vid);
        if (prev != null) {
            prev.cancel();
        }
        long delay = Math.max(20L, durationSeconds * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pulseBorderClearTasks.remove(vid);
            if (!Integer.valueOf(gen).equals(pulseGeneration.get(vid))) {
                return;
            }
            if (sourceByPlayer.containsKey(vid)) {
                return;
            }
            Player p = Bukkit.getPlayer(vid);
            if (p != null && p.isOnline()) {
                clearPlayerWorldBorder(p);
            }
        }, delay);
        pulseBorderClearTasks.put(vid, task);
    }

    public void onClaimDeleted(long claimId) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (sourceByPlayer.get(id) == Source.GUI && guiPinnedClaimId.getOrDefault(id, -1L) == claimId) {
                deactivateFully(id, p);
            }
        }
    }

    private void deactivateFully(@NotNull UUID playerId, @Nullable Player onlineIfKnown) {
        BukkitTask r = refreshTasks.remove(playerId);
        if (r != null) {
            r.cancel();
        }
        cancelPulseOnly(playerId);
        sourceByPlayer.remove(playerId);
        guiPinnedClaimId.remove(playerId);
        lastAppliedClaimId.remove(playerId);
        if (onlineIfKnown != null && onlineIfKnown.isOnline()) {
            clearPlayerWorldBorder(onlineIfKnown);
        } else {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null && p.isOnline()) {
                clearPlayerWorldBorder(p);
            }
        }
    }

    private void cancelPulseOnly(@NotNull UUID playerId) {
        BukkitTask t = pulseTasks.remove(playerId);
        if (t != null) {
            t.cancel();
        }
        BukkitTask c = pulseBorderClearTasks.remove(playerId);
        if (c != null) {
            c.cancel();
        }
        pulseGeneration.remove(playerId);
    }

    private void startRefreshTask(@NotNull Player player, @NotNull ClaimConfig cfg) {
        UUID pid = player.getUniqueId();
        BukkitTask old = refreshTasks.remove(pid);
        if (old != null) {
            old.cancel();
        }
        long interval = Math.max(1L, cfg.getVisualRefreshIntervalTicks());
        final int epoch = visualEpoch.get();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (epoch != visualEpoch.get() || !player.isOnline()) {
                refreshTasks.remove(pid);
                return;
            }
            if (!cfg.isVisualsEnabled() || !cfg.isEnabled()) {
                deactivateFully(pid, player);
                return;
            }
            Claim claim = resolveDisplayedClaim(player, pid);
            if (claim == null) {
                deactivateFully(pid, player);
                plugin.getMessageManager().sendActionBar(player, "claim.show-left-claim-actionbar");
                return;
            }
            applyVisualForPlayer(player, claim, cfg, false);
        }, interval, interval);
        refreshTasks.put(pid, task);
    }

    private @Nullable Claim resolveDisplayedClaim(@NotNull Player player, @NotNull UUID pid) {
        Source src = sourceByPlayer.get(pid);
        if (src == null || src == Source.NONE) {
            return null;
        }
        if (src == Source.FEET) {
            return manager.claimAt(player.getLocation());
        }
        if (src == Source.GUI) {
            Long cid = guiPinnedClaimId.get(pid);
            if (cid == null) {
                return null;
            }
            Claim c = manager.getCache().byId(cid);
            if (c == null) {
                return null;
            }
            if (player.getWorld() == null
                    || !player.getWorld().getName().equalsIgnoreCase(c.worldName())) {
                return null;
            }
            return c;
        }
        return null;
    }

    /**
     * @param forceReapply set on claim switches; refresh ticks omit to avoid respawning tasks every interval.
     */
    private void applyVisualForPlayer(@NotNull Player player, @NotNull Claim claim,
                                     @NotNull ClaimConfig cfg, boolean forceReapply) {
        UUID pid = player.getUniqueId();
        if (!forceReapply) {
            Long last = lastAppliedClaimId.get(pid);
            if (last != null && last == claim.id()) {
                return;
            }
        }
        lastAppliedClaimId.put(pid, claim.id());

        boolean wantWb = cfg.getVisualMode() == ClaimVisualMode.WORLDBORDER_IF_SAFE;
        boolean wbOk = wantWb && tryWorldBorder(player, claim, cfg);
        if (wantWb && !wbOk && canAnnounceWorldBorderFallback(claim, cfg)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.show-worldborder-unavailable");
        }
        boolean useParticles = cfg.getVisualMode() == ClaimVisualMode.PARTICLES
                || (!wbOk && cfg.isParticlesFallbackEnabled());
        cancelPulseOnly(pid);
        if (useParticles) {
            startParticlePulse(player, claim, cfg, false);
        }
    }

    /**
     * Square claims: announce only if WB path failed unexpectedly; rectangular uses approximate WB — no spam.
     */
    private static boolean canAnnounceWorldBorderFallback(@NotNull Claim claim, @NotNull ClaimConfig cfg) {
        if (!cfg.isParticlesFallbackEnabled()) {
            return false;
        }
        return claim.widthBlocks() == claim.depthBlocks();
    }

    private boolean tryWorldBorder(@NotNull Player viewer, @NotNull Claim claim, @NotNull ClaimConfig cfg) {
        World world = Bukkit.getWorld(claim.worldName());
        if (world == null || viewer.getWorld() != world) {
            return false;
        }
        WorldBorder wb;
        try {
            wb = Bukkit.createWorldBorder();
        } catch (Throwable ex) {
            return false;
        }
        try {
            double cx = (claim.minX() + claim.maxX()) / 2.0 + 0.5;
            double cz = (claim.minZ() + claim.maxZ()) / 2.0 + 0.5;
            int side = Math.max(claim.widthBlocks(), claim.depthBlocks());
            wb.setCenter(cx, cz);
            wb.setSize(Math.max(1.0D, side));
            wb.setDamageBuffer(0);
            wb.setDamageAmount(0);
            wb.setWarningTime(0);
            wb.setWarningDistance(0);
            viewer.setWorldBorder(wb);
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private void startParticlePulse(@NotNull Player viewer, @NotNull Claim claim, @NotNull ClaimConfig cfg,
                                   boolean timedFinite) {
        Particle resolved = RegistryLookup.particle(cfg.getBorderParticleRaw());
        final Particle particle = resolved != null ? resolved : Particle.END_ROD;
        World world = Bukkit.getWorld(claim.worldName());
        if (world == null || viewer.getWorld() != world) {
            return;
        }
        int viewY = viewer.getLocation().getBlockY() + 1;
        long interval = Math.max(1L, cfg.getVisualIntervalTicks());
        int maxPer = cfg.getMaxVisibleParticlesPerTick();
        final int epoch = visualEpoch.get();
        long deadline = timedFinite
                ? System.currentTimeMillis() + Math.max(20L, cfg.getVisualDurationSeconds()) * 50L
                : Long.MAX_VALUE;

        if (timedFinite) {
            cancelPulseOnly(viewer.getUniqueId());
        }
        AtomicReference<BukkitTask> holder = new AtomicReference<>();
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (epoch != visualEpoch.get() || !viewer.isOnline()) {
                cancelPulseHolder(holder, viewer.getUniqueId());
                return;
            }
            if (viewer.getWorld() != world) {
                cancelPulseHolder(holder, viewer.getUniqueId());
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                cancelPulseHolder(holder, viewer.getUniqueId());
                if (timedFinite) {
                    clearPlayerWorldBorder(viewer);
                }
                return;
            }
            strokeRectangleMinimal(world, claim.minX(), claim.maxX(), claim.minZ(), claim.maxZ(),
                    viewY, particle, viewer, maxPer);
        }, 0L, interval);
        holder.set(t);
        pulseTasks.put(viewer.getUniqueId(), t);

        if (timedFinite) {
            int gen = pulseGeneration.merge(viewer.getUniqueId(), 1, Integer::sum);
            UUID vid = viewer.getUniqueId();
            long ticks = Math.max(20L, (long) cfg.getVisualDurationSeconds() * 20L);
            BukkitTask prev = pulseBorderClearTasks.remove(vid);
            if (prev != null) {
                prev.cancel();
            }
            BukkitTask clearLater = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pulseBorderClearTasks.remove(vid);
                if (!Integer.valueOf(gen).equals(pulseGeneration.get(vid))) {
                    return;
                }
                if (sourceByPlayer.containsKey(vid)) {
                    return;
                }
                Player p = Bukkit.getPlayer(vid);
                if (p != null && p.isOnline()) {
                    clearPlayerWorldBorder(p);
                }
            }, ticks);
            pulseBorderClearTasks.put(vid, clearLater);
        }
    }

    private void cancelPulseHolder(AtomicReference<BukkitTask> holder, UUID playerId) {
        BukkitTask self = holder.get();
        if (self != null) {
            self.cancel();
        }
        pulseTasks.remove(playerId);
    }

    private static void strokeRectangleMinimal(World world, int minX, int maxX, int minZ, int maxZ, int y,
                                              Particle particle, Player viewer, int maxParticles) {
        double py = y + 0.1;
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        int step = Math.max(1, chooseStepForBudget(w, d, maxParticles));
        int spawned = 0;
        for (int x = minX; x <= maxX && spawned < maxParticles; x += step) {
            spawn(viewer, particle, world, x + 0.5, py, minZ + 0.5);
            spawned++;
            if (spawned >= maxParticles) {
                break;
            }
            spawn(viewer, particle, world, x + 0.5, py, maxZ + 0.5);
            spawned++;
        }
        for (int z = minZ; z <= maxZ && spawned < maxParticles; z += step) {
            spawn(viewer, particle, world, minX + 0.5, py, z + 0.5);
            spawned++;
            if (spawned >= maxParticles) {
                break;
            }
            spawn(viewer, particle, world, maxX + 0.5, py, z + 0.5);
            spawned++;
        }
    }

    private static int chooseStepForBudget(int w, int d, int budget) {
        int spanX = Math.max(1, w - 1);
        int spanZ = Math.max(1, d - 1);
        for (int step = 1; step <= Math.max(spanX, spanZ); step++) {
            int nx = (spanX + step - 1) / step + 1;
            int nz = (spanZ + step - 1) / step + 1;
            if (2 * nx + 2 * nz + 8 <= budget) {
                return step;
            }
        }
        return Math.max(1, Math.max(spanX, spanZ));
    }

    private static void spawn(Player viewer, Particle particle, World world, double x, double y, double z) {
        viewer.spawnParticle(particle, new Location(world, x, y, z), 1);
    }

    private void clearAllPlayerWorldBordersOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearPlayerWorldBorder(p);
        }
    }

    private void clearPlayerWorldBorder(@NotNull Player player) {
        try {
            player.setWorldBorder(null);
        } catch (Throwable ignored) {
        }
    }

    public void handleWorldChange(@NotNull Player player) {
        if (!manager.getConfig().isClearBorderOnWorldChange()) {
            return;
        }
        UUID id = player.getUniqueId();
        if (sourceByPlayer.containsKey(id)) {
            deactivateFully(id, player);
        }
    }
}
