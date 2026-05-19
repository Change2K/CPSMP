package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.compat.RegistryLookup;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-player claim outline: default {@link ClaimVisualMode#DISPLAY} uses {@link BlockDisplay} (visual-only,
 * visible even with client particle settings reduced). {@link ClaimVisualMode#PARTICLES} and optional
 * WorldBorder (opt-in only) remain as fallbacks.
 */
public final class ClaimVisualService {

    public enum ToggleFeetResult {
        SHOWN,
        HIDDEN,
        NOT_IN_CLAIM,
        BORDER_DENIED
    }

    public enum ToggleGuiResult {
        SHOWN,
        HIDDEN,
        NOT_FOUND,
        WRONG_WORLD,
        BORDER_DENIED
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
    /** Claim locked when /plot show was toggled on while standing in a claim (feet mode). */
    private final Map<UUID, Long> feetTrackedClaimId = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> refreshTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pulseTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> pulseBorderClearTasks = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> previewCleanupTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pulseGeneration = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAppliedClaimId = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastOutlineSignature = new ConcurrentHashMap<>();
    private final Map<UUID, List<BlockDisplay>> persistentDisplays = new ConcurrentHashMap<>();
    private final Map<UUID, List<BlockDisplay>> previewDisplays = new ConcurrentHashMap<>();
    private final AtomicInteger visualEpoch = new AtomicInteger();

    public ClaimVisualService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * Hides every outline {@link BlockDisplay} from {@code joiner} (so only the owning viewer sees their border).
     */
    public void hideOutlineEntitiesFromJoiningPlayer(@NotNull Player joiner) {
        for (List<BlockDisplay> list : persistentDisplays.values()) {
            hideListFrom(joiner, list);
        }
        for (List<BlockDisplay> list : previewDisplays.values()) {
            hideListFrom(joiner, list);
        }
    }

    private void hideListFrom(@NotNull Player joiner, @Nullable List<BlockDisplay> list) {
        if (list == null) {
            return;
        }
        for (BlockDisplay d : list) {
            if (d != null && d.isValid()) {
                joiner.hideEntity(plugin, d);
            }
        }
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
        for (BukkitTask t : previewCleanupTasks.values()) {
            t.cancel();
        }
        previewCleanupTasks.clear();
        pulseGeneration.clear();
        sourceByPlayer.clear();
        guiPinnedClaimId.clear();
        feetTrackedClaimId.clear();
        lastAppliedClaimId.clear();
        lastOutlineSignature.clear();
        removeAllDisplays(persistentDisplays);
        removeAllDisplays(previewDisplays);
        clearAllPlayerWorldBordersOnline();
    }

    private void removeAllDisplays(@NotNull Map<UUID, List<BlockDisplay>> map) {
        for (List<BlockDisplay> list : map.values()) {
            removeDisplayList(list);
        }
        map.clear();
    }

    public void clearPlayer(@NotNull Player player) {
        deactivateFully(player.getUniqueId(), player);
    }

    public void clearBecauseVisualsDisabled(@NotNull Player player) {
        deactivateFully(player.getUniqueId(), player);
    }

    public boolean isFeetDisplayActive(@NotNull UUID playerId) {
        return sourceByPlayer.get(playerId) == Source.FEET;
    }

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
        if (sourceByPlayer.get(id) == Source.GUI) {
            deactivateFully(id, player);
            return ToggleFeetResult.HIDDEN;
        }
        Claim at = manager.claimAt(player.getLocation());
        if (at == null) {
            return ToggleFeetResult.NOT_IN_CLAIM;
        }
        sourceByPlayer.put(id, Source.FEET);
        feetTrackedClaimId.put(id, at.id());
        startRefreshTask(player, cfg);
        applyVisualForPlayer(player, at, cfg, true);
        return ToggleFeetResult.SHOWN;
    }

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
        if (!manager.canShowBorderFor(player, c)) {
            return ToggleGuiResult.BORDER_DENIED;
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
        clearPreviewDisplays(pid);

        boolean wantWb = cfg.isAllowWorldborderMode() && cfg.getVisualMode() == ClaimVisualMode.WORLDBORDER_IF_SAFE;
        boolean wbOk = wantWb && tryWorldBorder(viewer, claim, cfg);
        if (wantWb && !wbOk && canAnnounceWorldBorderFallback(claim, cfg)) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.show-worldborder-unavailable");
        }
        if (wbOk) {
            scheduleTimedWorldBorderClear(viewer, cfg.getVisualDurationSeconds());
            return;
        }

        if (tryDisplayOutline(viewer, claim, cfg, previewDisplays, cfg.getMaxDisplayEntitiesPerPlayer())) {
            schedulePreviewRemoval(pid, cfg.getVisualDurationSeconds());
            return;
        }
        if (cfg.getVisualMode() == ClaimVisualMode.DISPLAY && cfg.isParticlesFallbackEnabled()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.show-display-unavailable");
        }
        if (shouldUseParticles(cfg, wantWb, wbOk)) {
            startParticlePulse(viewer, claim, cfg, true);
        }
    }

    private void schedulePreviewRemoval(@NotNull UUID viewerId, int durationSeconds) {
        BukkitTask prev = previewCleanupTasks.remove(viewerId);
        if (prev != null) {
            prev.cancel();
        }
        long ticks = Math.max(20L, (long) durationSeconds * 20L);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            previewCleanupTasks.remove(viewerId);
            clearPreviewDisplays(viewerId);
        }, ticks);
        previewCleanupTasks.put(viewerId, task);
    }

    private void clearPreviewDisplays(@NotNull UUID viewerId) {
        List<BlockDisplay> list = previewDisplays.remove(viewerId);
        removeDisplayList(list);
    }

    private static boolean shouldUseParticles(ClaimConfig cfg, boolean wantWb, boolean wbOk) {
        if (wbOk) {
            return false;
        }
        if (cfg.getVisualMode() == ClaimVisualMode.PARTICLES || !cfg.isAllowWorldborderMode()) {
            return cfg.isParticlesPrimaryEnabled();
        }
        if (cfg.getVisualMode() == ClaimVisualMode.DISPLAY) {
            return cfg.isParticlesFallbackEnabled();
        }
        return cfg.isParticlesFallbackEnabled();
    }

    private static boolean shouldTryDisplayPrimary(@NotNull ClaimConfig cfg) {
        return cfg.getVisualMode() == ClaimVisualMode.DISPLAY && cfg.isDisplayPrimaryEnabled();
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
            if (sourceByPlayer.get(id) == Source.FEET && feetTrackedClaimId.getOrDefault(id, -1L) == claimId) {
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
        clearPreviewDisplays(playerId);
        removePersistentDisplays(playerId);
        sourceByPlayer.remove(playerId);
        guiPinnedClaimId.remove(playerId);
        feetTrackedClaimId.remove(playerId);
        lastAppliedClaimId.remove(playerId);
        lastOutlineSignature.remove(playerId);
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
                plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
                plugin.getMessageManager().sendActionBar(player, "claim.show-left-claim-actionbar");
                return;
            }
            World pw = player.getWorld();
            if (pw == null || !claim.worldName().equalsIgnoreCase(pw.getName())) {
                deactivateFully(pid, player);
                return;
            }
            double dist = flatDistanceToRectBlocks(
                    player.getLocation().getBlockX(),
                    player.getLocation().getBlockZ(),
                    claim.minX(), claim.maxX(), claim.minZ(), claim.maxZ());
            if (dist > cfg.getShowRadiusBlocks()) {
                deactivateFully(pid, player);
                plugin.getMessageManager().sendPrefixed(player, "claim.show-too-far");
                plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
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
            Long cid = feetTrackedClaimId.get(pid);
            if (cid == null) {
                return null;
            }
            return manager.getCache().byId(cid);
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
     * @param forceReapply force rebuild (toggle on, claim switch, bounds update).
     */
    private void applyVisualForPlayer(@NotNull Player player, @NotNull Claim claim,
                                     @NotNull ClaimConfig cfg, boolean forceReapply) {
        UUID pid = player.getUniqueId();
        String sig = visualSignature(claim, player);
        if (!forceReapply && sig.equals(lastOutlineSignature.get(pid))) {
            return;
        }
        lastOutlineSignature.put(pid, sig);
        lastAppliedClaimId.put(pid, claim.id());

        cancelPulseOnly(pid);
        removePersistentDisplays(pid);

        boolean wantWb = cfg.isAllowWorldborderMode() && cfg.getVisualMode() == ClaimVisualMode.WORLDBORDER_IF_SAFE;
        boolean wbOk = wantWb && tryWorldBorder(player, claim, cfg);
        if (wantWb && !wbOk && canAnnounceWorldBorderFallback(claim, cfg)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.show-worldborder-unavailable");
        }
        if (wbOk) {
            return;
        }

        if (shouldTryDisplayPrimary(cfg)) {
            if (tryDisplayOutline(player, claim, cfg, persistentDisplays, cfg.getMaxDisplayEntitiesPerPlayer())) {
                return;
            }
            if (cfg.isParticlesFallbackEnabled()) {
                plugin.getMessageManager().sendPrefixed(player, "claim.show-display-unavailable");
            }
        }

        if (shouldUseParticles(cfg, wantWb, wbOk)) {
            startParticlePulse(player, claim, cfg, false);
        }
    }

    private static @NotNull String visualSignature(@NotNull Claim claim, @NotNull Player player) {
        return outlineSignature(claim) + "|fy:" + player.getLocation().getBlockY();
    }

    private static @NotNull String outlineSignature(@NotNull Claim claim) {
        return claim.id() + "|" + claim.worldName() + "|"
                + claim.minX() + "|" + claim.maxX() + "|" + claim.minZ() + "|" + claim.maxZ()
                + "|" + claim.updatedAt();
    }

    /**
     * Shortest 2D distance from a block column to the claim rectangle: 0 inside or on the edge,
     * otherwise Euclidean distance to the closest point on the rectangle (in X/Z).
     */
    private static double flatDistanceToRectBlocks(int px, int pz,
                                                   int minX, int maxX, int minZ, int maxZ) {
        int nx = Math.min(Math.max(px, minX), maxX);
        int nz = Math.min(Math.max(pz, minZ), maxZ);
        if (px >= minX && px <= maxX && pz >= minZ && pz <= maxZ) {
            return 0.0D;
        }
        double dx = px - nx;
        double dz = pz - nz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static boolean canAnnounceWorldBorderFallback(@NotNull Claim claim, @NotNull ClaimConfig cfg) {
        if (!cfg.isAllowWorldborderMode() || !cfg.isParticlesFallbackEnabled()) {
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
        int footBlockY = viewer.getLocation().getBlockY();
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
            strokeClaimOutline(world, claim.minX(), claim.maxX(), claim.minZ(), claim.maxZ(),
                    footBlockY, particle, viewer, cfg, maxPer);
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

    private boolean tryDisplayOutline(@NotNull Player viewer, @NotNull Claim claim, @NotNull ClaimConfig cfg,
                                     @NotNull Map<UUID, List<BlockDisplay>> targetMap, int maxEntities) {
        World world = Bukkit.getWorld(claim.worldName());
        if (world == null || viewer.getWorld() != world) {
            return false;
        }
        Material lineMat = parseMaterial(cfg.getDisplayLineMaterialName(), Material.LIGHT_BLUE_STAINED_GLASS);
        Material cornerMat = parseMaterial(cfg.getDisplayCornerMaterialName(), Material.GOLD_BLOCK);
        int footY = viewer.getLocation().getBlockY();
        float scale = cfg.getDisplayScale();
        List<BlockDisplay> spawned = new ArrayList<>();
        try {
            int minX = claim.minX();
            int maxX = claim.maxX();
            int minZ = claim.minZ();
            int maxZ = claim.maxZ();
            int w = maxX - minX + 1;
            int d = maxZ - minZ + 1;
            int step = Math.max(1, cfg.getDisplayLineStepBlocks());
            int layers = Math.max(1, cfg.getDisplayYOffsetRel().size());
            while (estimateMarkersPerLayer(minX, maxX, minZ, maxZ, step) * layers > maxEntities && step < Math.max(w, d) + 64) {
                step++;
            }
            int budget = maxEntities;
            for (double yRel : cfg.getDisplayYOffsetRel()) {
                if (budget <= 0) {
                    break;
                }
                double y = footY + yRel;
                Set<Long> used = new HashSet<>();
                budget = appendCornerAndEdgeDisplays(world, minX, maxX, minZ, maxZ, y, step,
                        cornerMat, lineMat, scale, viewer, spawned, used, budget);
            }
            if (spawned.isEmpty()) {
                return false;
            }
            UUID vid = viewer.getUniqueId();
            List<BlockDisplay> previous = targetMap.put(vid, spawned);
            removeDisplayList(previous);
            return true;
        } catch (Throwable ex) {
            removeDisplayList(spawned);
            return false;
        }
    }

    /** Upper bound on distinct markers per horizontal layer (matches corner/edge loop, without dedupe). */
    private static int estimateMarkersPerLayer(int minX, int maxX, int minZ, int maxZ, int step) {
        int nx = 0;
        if (maxX > minX) {
            for (int x = minX + step; x <= maxX - step; x += step) {
                nx += 2;
            }
        }
        int nz = 0;
        if (maxZ > minZ) {
            for (int z = minZ + step; z <= maxZ - step; z += step) {
                nz += 2;
            }
        }
        return 4 + nx + nz;
    }

    private static long packXz(int bx, int bz) {
        return (((long) bx) << 32) | (bz & 0xffffffffL);
    }

    /**
     * Spawns distinct {@link BlockDisplay} markers at corners (gold) and along edges (glass), deduped by block column.
     * @return remaining budget
     */
    private int appendCornerAndEdgeDisplays(@NotNull World world,
                                            int minX, int maxX, int minZ, int maxZ,
                                            double y, int step,
                                            @NotNull Material cornerMat, @NotNull Material lineMat,
                                            float scale, @NotNull Player viewer,
                                            @NotNull List<BlockDisplay> out,
                                            @NotNull Set<Long> used,
                                            int budget) {
        int b = budget;
        b = tryAddDisplay(world, minX + 0.5D, y, minZ + 0.5D, cornerMat, scale, viewer, out, used, b);
        b = tryAddDisplay(world, maxX + 0.5D, y, minZ + 0.5D, cornerMat, scale, viewer, out, used, b);
        b = tryAddDisplay(world, minX + 0.5D, y, maxZ + 0.5D, cornerMat, scale, viewer, out, used, b);
        b = tryAddDisplay(world, maxX + 0.5D, y, maxZ + 0.5D, cornerMat, scale, viewer, out, used, b);
        if (b <= 0) {
            return b;
        }
        if (maxX > minX) {
            for (int x = minX + step; x <= maxX - step && b > 0; x += step) {
                b = tryAddDisplay(world, x + 0.5D, y, minZ + 0.5D, lineMat, scale, viewer, out, used, b);
                if (b <= 0) {
                    return b;
                }
                b = tryAddDisplay(world, x + 0.5D, y, maxZ + 0.5D, lineMat, scale, viewer, out, used, b);
            }
        }
        if (maxZ > minZ) {
            for (int z = minZ + step; z <= maxZ - step && b > 0; z += step) {
                b = tryAddDisplay(world, minX + 0.5D, y, z + 0.5D, lineMat, scale, viewer, out, used, b);
                if (b <= 0) {
                    return b;
                }
                b = tryAddDisplay(world, maxX + 0.5D, y, z + 0.5D, lineMat, scale, viewer, out, used, b);
            }
        }
        return b;
    }

    private int tryAddDisplay(@NotNull World world, double x, double y, double z,
                              @NotNull Material mat, float scale, @NotNull Player viewer,
                              @NotNull List<BlockDisplay> out, @NotNull Set<Long> used, int budget) {
        if (budget <= 0) {
            return budget;
        }
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        long key = packXz(bx, bz);
        if (!used.add(key)) {
            return budget;
        }
        BlockDisplay d = spawnOneBlockDisplay(world, x, y, z, mat, scale, viewer);
        if (d == null) {
            used.remove(key);
            return budget;
        }
        out.add(d);
        return budget - 1;
    }

    private @Nullable BlockDisplay spawnOneBlockDisplay(@NotNull World world, double x, double y, double z,
                                                        @NotNull Material material, float scale,
                                                        @NotNull Player viewer) {
        Location loc = new Location(world, x, y, z);
        try {
            BlockDisplay display = world.spawn(loc, BlockDisplay.class, bd -> {
                BlockData data = material.createBlockData();
                bd.setBlock(data);
                bd.setPersistent(false);
                bd.setInvulnerable(true);
                bd.setGravity(false);
                bd.setSilent(true);
                bd.setGlowing(false);
                try {
                    bd.setTransformation(new Transformation(
                            new Vector3f(0, 0, 0),
                            new AxisAngle4f(0, 0, 1, 0),
                            new Vector3f(scale, scale, scale),
                            new AxisAngle4f(0, 0, 1, 0)));
                } catch (Throwable ignored) {
                }
            });
            hideDisplayFromNonViewers(viewer, display);
            return display;
        } catch (Throwable ex) {
            return null;
        }
    }

    private void hideDisplayFromNonViewers(@NotNull Player viewer, @NotNull BlockDisplay display) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(viewer.getUniqueId())) {
                continue;
            }
            try {
                other.hideEntity(plugin, display);
            } catch (Throwable ignored) {
            }
        }
    }

    private void removePersistentDisplays(@NotNull UUID viewerId) {
        List<BlockDisplay> list = persistentDisplays.remove(viewerId);
        removeDisplayList(list);
    }

    private static void removeDisplayList(@Nullable List<BlockDisplay> list) {
        if (list == null) {
            return;
        }
        for (BlockDisplay d : list) {
            if (d != null && d.isValid()) {
                d.remove();
            }
        }
        list.clear();
    }

    private static @NotNull Material parseMaterial(@Nullable String name, @NotNull Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim());
        return m != null && m.isBlock() ? m : fallback;
    }

    private static void strokeClaimOutline(World world, int minX, int maxX, int minZ, int maxZ, int footBlockY,
                                           Particle particle, Player viewer, ClaimConfig cfg, int maxParticles) {
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        int baseStep = Math.max(1, cfg.getParticleLineStepBlocks());
        List<Double> yOffsets = cfg.getParticleYOffsetRel();
        int layers = Math.max(1, yOffsets.size());
        int step = baseStep;
        while (estimateMarkersPerLayer(minX, maxX, minZ, maxZ, step) * layers > maxParticles && step < Math.max(w, d) + 16) {
            step++;
        }
        int spawned = 0;
        int particleIndex = 0;
        for (double yRel : yOffsets) {
            double py = footBlockY + yRel;
            for (int x = minX; x <= maxX && spawned < maxParticles; x += step) {
                spawned += spawnOutlinePoint(viewer, world, x + 0.5, py, minZ + 0.5, particle, cfg, particleIndex++);
                if (spawned >= maxParticles) {
                    break;
                }
                spawned += spawnOutlinePoint(viewer, world, x + 0.5, py, maxZ + 0.5, particle, cfg, particleIndex++);
                if (spawned >= maxParticles) {
                    break;
                }
            }
            for (int z = minZ; z <= maxZ && spawned < maxParticles; z += step) {
                spawned += spawnOutlinePoint(viewer, world, minX + 0.5, py, z + 0.5, particle, cfg, particleIndex++);
                if (spawned >= maxParticles) {
                    break;
                }
                spawned += spawnOutlinePoint(viewer, world, maxX + 0.5, py, z + 0.5, particle, cfg, particleIndex++);
                if (spawned >= maxParticles) {
                    break;
                }
            }
        }
    }

    private static int spawnOutlinePoint(Player viewer, World world, double x, double y, double z,
                                         Particle particle, ClaimConfig cfg, int index) {
        Location loc = new Location(world, x, y, z);
        if (cfg.isParticleStatic() && particle == Particle.DUST) {
            Color c1 = cfg.getDustPrimaryColor();
            Color c2 = cfg.getDustSecondaryColor();
            Particle.DustOptions primary = new Particle.DustOptions(c1, 0.9f);
            Particle.DustOptions secondary = new Particle.DustOptions(c2, 0.9f);
            Particle.DustOptions use = (index & 1) == 0 ? primary : secondary;
            viewer.spawnParticle(Particle.DUST, loc, 1, 0.0D, 0.0D, 0.0D, 0.0D, use);
            return 1;
        }
        if (cfg.isParticleStatic()) {
            viewer.spawnParticle(particle, loc, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            return 1;
        }
        viewer.spawnParticle(particle, loc, 1);
        return 1;
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
