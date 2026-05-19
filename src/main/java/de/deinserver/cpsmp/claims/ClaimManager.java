package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * V4.0 claims: SQLite persistence, in-memory {@link ClaimCache}, protection policy, visuals.
 */
public final class ClaimManager {

    private final CPSMPPlugin plugin;
    private ClaimConfig config;
    private final ClaimCache cache = new ClaimCache();
    private final ClaimVisualService visuals;
    private final ClaimAntiEncasementService antiEncasement;
    private final ClaimExitService claimExit;
    private @Nullable SQLiteClaimStorage storage;
    private @Nullable ExecutorService dbExecutor;
    private boolean storageReady;
    private @Nullable ClaimProtectionListener protectionListener;
    private @Nullable ClaimAntiEncasementListener antiEncasementListener;
    private final java.util.concurrent.ConcurrentHashMap<UUID, PendingAbandon> abandonPending
            = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Long> bypassActionbarAt
            = new java.util.concurrent.ConcurrentHashMap<>();

    private record PendingAbandon(long claimId, long expiresAtMillis) {
    }

    public ClaimManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
        this.visuals = new ClaimVisualService(plugin, this);
        this.antiEncasement = new ClaimAntiEncasementService(plugin, this);
        this.claimExit = new ClaimExitService(plugin, this);
        this.config = new ClaimConfig(plugin.getConfigManager().getClaims());
        Bukkit.getPluginManager().registerEvents(new ClaimVisualQuitListener(visuals), plugin);
    }

    public ClaimConfig getConfig() {
        return config;
    }

    public ClaimVisualService getVisuals() {
        return visuals;
    }

    public ClaimAntiEncasementService getAntiEncasement() {
        return antiEncasement;
    }

    public ClaimExitService getClaimExit() {
        return claimExit;
    }

    public ClaimCache getCache() {
        return cache;
    }

    public boolean isStorageReady() {
        return storageReady && storage != null;
    }

    /**
     * Claims module is on and storage is usable.
     */
    public boolean isOperational() {
        return config.isEnabled() && isStorageReady();
    }

    public void enable() {
        this.config = new ClaimConfig(plugin.getConfigManager().getClaims());
        if (!config.isEnabled()) {
            plugin.getLogger().info("[CPSMP] Claims are disabled in claims.yml.");
            return;
        }
        if (!config.isUseExplicitDefaultSize()) {
            plugin.getLogger().info("[CPSMP] claims.yml: Nutze default-size-x/default-size-z (z. B. 24) statt default-radius-x/z fuer exakte Claim-Groessen.");
        }
        if (!initSqlite()) {
            return;
        }
        registerProtectionListener();
        registerAntiEncasementListener();
        reloadCacheFromDbAsync();
    }

    private boolean initSqlite() {
        if (storage != null && storageReady) {
            return true;
        }
        if (dbExecutor == null) {
            this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CPSMP-Claims-DB");
                t.setDaemon(true);
                return t;
            });
        }
        File dbFile = new File(plugin.getDataFolder(), config.getStorageFile());
        SQLiteClaimStorage sqlite = new SQLiteClaimStorage(dbFile, plugin.getLogger());
        try {
            sqlite.init();
            this.storage = sqlite;
            this.storageReady = true;
            plugin.getLogger().info("[CPSMP] Claims SQLite ready (" + dbFile.getName() + ").");
            return true;
        } catch (ClaimStorageException ex) {
            this.storage = null;
            this.storageReady = false;
            plugin.getLogger().log(Level.SEVERE,
                    "[CPSMP] Claims Speicher nicht verfuegbar: " + ex.getMessage(), ex);
            shutdownExecutorOnly();
            return false;
        }
    }

    private void registerProtectionListener() {
        if (protectionListener != null) {
            return;
        }
        this.protectionListener = new ClaimProtectionListener(plugin, this);
        Bukkit.getPluginManager().registerEvents(protectionListener, plugin);
    }

    private void registerAntiEncasementListener() {
        if (antiEncasementListener != null) {
            return;
        }
        this.antiEncasementListener = new ClaimAntiEncasementListener(plugin, this, antiEncasement);
        Bukkit.getPluginManager().registerEvents(antiEncasementListener, plugin);
    }

    private void unregisterProtectionListener() {
        if (protectionListener != null) {
            HandlerList.unregisterAll(protectionListener);
            protectionListener = null;
        }
    }

    private void unregisterAntiEncasementListener() {
        if (antiEncasementListener != null) {
            HandlerList.unregisterAll(antiEncasementListener);
            antiEncasementListener = null;
        }
    }

    public void reload() {
        visuals.cancelAll();
        abandonPending.clear();
        this.config = new ClaimConfig(plugin.getConfigManager().getClaims());
        if (!config.isEnabled()) {
            unregisterProtectionListener();
            unregisterAntiEncasementListener();
            clearAllClaimVisualsForOnlinePlayers();
            return;
        }
        if (!config.isVisualsEnabled()) {
            clearAllClaimVisualsForOnlinePlayers();
        }
        if (!initSqlite()) {
            return;
        }
        registerProtectionListener();
        registerAntiEncasementListener();
        reloadCacheFromDbAsync();
    }

    private void reloadCacheFromDbAsync() {
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        if (st == null || ex == null) {
            return;
        }
        ex.submit(() -> {
            try {
                List<Claim> all = st.loadAllClaims();
                Map<Long, Set<UUID>> trust = st.loadAllTrustUuids();
                Bukkit.getScheduler().runTask(plugin, () -> cache.rebuild(all, trust));
            } catch (Exception e) {
                plugin.getLogger().warning("[CPSMP] Claims cache: " + e.getMessage());
            }
        });
    }

    public void disable() {
        visuals.cancelAll();
        abandonPending.clear();
        unregisterProtectionListener();
        unregisterAntiEncasementListener();
        if (storage != null && dbExecutor != null) {
            ClaimStorage toClose = storage;
            storage = null;
            try {
                dbExecutor.submit(toClose::close).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                plugin.getLogger().warning("[CPSMP] Claims DB close: " + ex.getMessage());
                try {
                    toClose.close();
                } catch (Exception ignored) {
                }
            }
        }
        shutdownExecutorOnly();
        storageReady = false;
    }

    /**
     * Clears per-player claim outlines (WorldBorder, particles, tasks) for every online player.
     * Used when claims or visuals are turned off in config or on full module reload.
     */
    public void clearAllClaimVisualsForOnlinePlayers() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            visuals.clearPlayer(p);
        }
    }

    /**
     * Deletes a claim by ID when {@code player} is the owner. Runs SQLite work async; on success
     * updates cache, clears any pinned/feet visuals for that claim, and runs {@code onSuccess} on the main thread.
     */
    public void deleteOwnedClaimAsPlayer(Player player, long claimId, Runnable onSuccess) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        Claim c = cache.byId(claimId);
        if (c == null || !c.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.gui-delete-not-owner");
            return;
        }
        int visibleNum = c.ownerClaimNumber();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                st.deleteClaim(claimId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    cache.removeClaim(claimId);
                    visuals.onClaimDeleted(claimId);
                    plugin.getMessageManager().sendPrefixed(player, "claim.gui-deleted",
                            Map.of("claimNumber", Integer.toString(visibleNum)));
                    onSuccess.run();
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.storage-error"));
            }
        });
    }

    private void shutdownExecutorOnly() {
        if (dbExecutor != null) {
            dbExecutor.shutdown();
            try {
                if (!dbExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                    dbExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dbExecutor.shutdownNow();
            }
            dbExecutor = null;
        }
    }

    public void notifyBypass(Player player) {
        if (!config.isBypassActionBar()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = bypassActionbarAt.get(player.getUniqueId());
        if (last != null && now - last < config.getBypassActionBarCooldownMs()) {
            return;
        }
        bypassActionbarAt.put(player.getUniqueId(), now);
        plugin.getMessageManager().sendActionBar(player, "claim.bypass-actionbar");
    }

    public @Nullable Claim claimAt(Location loc) {
        if (loc.getWorld() == null) {
            return null;
        }
        return cache.claimAt(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockZ());
    }

    /**
     * @return true if the player may modify blocks (break/place) at {@code loc}.
     */
    public boolean allowsBlockChange(Player player, Location loc, boolean breaking) {
        if (!isOperational()) {
            return true;
        }
        Claim claim = claimAt(loc);
        if (claim == null) {
            return true;
        }
        if (breaking && !config.isProtectBreak()) {
            return true;
        }
        if (!breaking && !config.isProtectPlace()) {
            return true;
        }
        return canBuild(player, claim);
    }

    public boolean canBuild(Player player, Claim claim) {
        if (ClaimPermission.hasBypass(player)) {
            notifyBypass(player);
            return true;
        }
        if (claim.ownerUuid().equals(player.getUniqueId())) {
            return true;
        }
        return cache.isTrusted(claim.id(), player.getUniqueId());
    }

    public boolean denyInteraction(Player player, Location blockLoc, InteractionKind kind) {
        if (!isOperational()) {
            return false;
        }
        Claim claim = claimAt(blockLoc);
        if (claim == null) {
            return false;
        }
        boolean protect = switch (kind) {
            case CONTAINER -> config.isProtectContainers();
            case DOOR -> config.isProtectDoors();
            case REDSTONE -> config.isProtectRedstone();
            case GENERIC -> false;
        };
        if (!protect) {
            return false;
        }
        if (canBuild(player, claim)) {
            return false;
        }
        return true;
    }

    public enum InteractionKind {
        CONTAINER,
        DOOR,
        REDSTONE,
        GENERIC
    }

    public void filterExplosionBlocks(List<Block> blocks) {
        if (!isOperational() || !config.isProtectExplosions()) {
            return;
        }
        blocks.removeIf(b -> {
            Claim c = claimAt(b.getLocation());
            return c != null;
        });
    }

    public boolean shouldCancelFireSpread(Block toBlock, @Nullable Block fromBlock) {
        if (!isOperational() || !config.isProtectFireSpread()) {
            return false;
        }
        Claim cTo = claimAt(toBlock.getLocation());
        if (cTo == null) {
            return false;
        }
        if (fromBlock == null) {
            return true;
        }
        Claim cFrom = claimAt(fromBlock.getLocation());
        return cFrom == null || cFrom.id() != cTo.id();
    }

    public boolean shouldCancelLiquidFlow(Block from, Block to) {
        if (!isOperational() || !config.isProtectLiquidFlow()) {
            return false;
        }
        Claim cTo = claimAt(to.getLocation());
        if (cTo == null) {
            return false;
        }
        Claim cFrom = claimAt(from.getLocation());
        return cFrom == null || cFrom.id() != cTo.id();
    }

    public boolean denyBucket(Player player, Location targetBlock) {
        if (!isOperational() || !config.isProtectBuckets()) {
            return false;
        }
        Claim claim = claimAt(targetBlock);
        if (claim == null) {
            return false;
        }
        return !canBuild(player, claim);
    }

    // --- Commands -----------------------------------------------------------

    public void createClaim(Player player) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        if (!config.isWorldClaimable(player)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.world-disabled");
            return;
        }
        int limit = ClaimPermission.resolveClaimLimit(player, config);
        if (limit != Integer.MAX_VALUE && cache.countForOwner(player.getUniqueId()) >= limit) {
            plugin.getMessageManager().sendPrefixed(player, "claim.limit-reached");
            return;
        }
        int cx = player.getLocation().getBlockX();
        int cz = player.getLocation().getBlockZ();
        String world = player.getWorld().getName();
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;
        final int w;
        final int d;
        if (config.isUseExplicitDefaultSize()) {
            ClaimCreationBounds bounds = ClaimCreationBounds.fromCenter(cx, cz,
                    config.getDefaultSizeX(), config.getDefaultSizeZ());
            minX = bounds.minX();
            maxX = bounds.maxX();
            minZ = bounds.minZ();
            maxZ = bounds.maxZ();
            w = bounds.widthBlocks();
            d = bounds.depthBlocks();
        } else {
            int rx = config.getDefaultRadiusX();
            int rz = config.getDefaultRadiusZ();
            minX = cx - rx;
            maxX = cx + rx;
            minZ = cz - rz;
            maxZ = cz + rz;
            w = maxX - minX + 1;
            d = maxZ - minZ + 1;
        }
        if (w < config.getMinSizeX() || d < config.getMinSizeZ()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.too-small");
            return;
        }
        if (w > config.getMaxSizeX() || d > config.getMaxSizeZ()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.too-large");
            return;
        }
        if (config.isPreventOverlap() && cache.overlapsAny(world, minX, maxX, minZ, maxZ)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.overlap");
            return;
        }
        UUID owner = player.getUniqueId();
        String ownerName = player.getName();
        long now = System.currentTimeMillis();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                long id = st.insertClaim(owner, ownerName, world, minX, maxX, minZ, maxZ, now);
                Claim cl = st.getClaim(id);
                if (cl == null) {
                    throw new IllegalStateException("inserted claim not readable");
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    cache.putClaim(cl, Set.of());
                    plugin.getMessageManager().sendPrefixed(player, "claim.created",
                            Map.of("claimNumber", Integer.toString(cl.ownerClaimNumber()),
                                    "w", Integer.toString(w),
                                    "d", Integer.toString(d),
                                    "world", world));
                    visuals.showTimedPreview(player, cl);
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[CPSMP] Claim create: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.storage-error"));
            }
        });
    }

    public void showClaimInfo(Player player) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        Claim at = claimAt(player.getLocation());
        if (at == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-in-claim");
            return;
        }
        sendInfoLines(player, at);
        if (isOperational() && config.isVisualsEnabled()) {
            visuals.showTimedPreview(player, at);
        }
    }

    private void sendInfoLines(Player player, Claim at) {
        Set<UUID> tr = cache.trustedSnapshot(at.id());
        List<String> names = tr.stream().map(u -> Bukkit.getOfflinePlayer(u).getName() != null
                ? Bukkit.getOfflinePlayer(u).getName() : u.toString()).sorted().toList();
        String trusted = names.isEmpty() ? "-" : String.join(", ", names);
        plugin.getMessageManager().sendPrefixed(player, "claim.info",
                Map.of(
                        "claimNumber", Integer.toString(at.ownerClaimNumber()),
                        "owner", at.ownerName() != null ? at.ownerName() : at.ownerUuid().toString(),
                        "world", at.worldName(),
                        "w", Integer.toString(at.widthBlocks()),
                        "d", Integer.toString(at.depthBlocks()),
                        "minX", Integer.toString(at.minX()),
                        "maxX", Integer.toString(at.maxX()),
                        "minZ", Integer.toString(at.minZ()),
                        "maxZ", Integer.toString(at.maxZ()),
                        "trusted", trusted
                ));
    }

    public void listOwnClaims(Player player) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        List<Claim> list = cache.listForOwner(player.getUniqueId());
        if (list.isEmpty()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.list-empty");
            return;
        }
        plugin.getMessageManager().sendPrefixed(player, "claim.list-header",
                Map.of("count", Integer.toString(list.size())));
        for (Claim c : list) {
            plugin.getMessageManager().sendPrefixed(player, "claim.list-entry",
                    Map.of(
                            "claimNumber", Integer.toString(c.ownerClaimNumber()),
                            "world", c.worldName(),
                            "cx", Integer.toString(c.centerX()),
                            "cz", Integer.toString(c.centerZ()),
                            "w", Integer.toString(c.widthBlocks()),
                            "d", Integer.toString(c.depthBlocks())
                    ));
        }
    }

    public void trustPlayer(Player player, Player target) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        Claim at = claimAt(player.getLocation());
        if (at == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-in-claim");
            return;
        }
        if (!at.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-owner");
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.trust-self");
            return;
        }
        if (cache.isTrusted(at.id(), target.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.trust-already",
                    Map.of("player", target.getName()));
            return;
        }
        long id = at.id();
        UUID tid = target.getUniqueId();
        String tname = target.getName();
        long now = System.currentTimeMillis();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                st.insertTrust(id, tid, tname, now);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    cache.addTrust(id, tid);
                    plugin.getMessageManager().sendPrefixed(player, "claim.trust-success",
                            Map.of("player", tname));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.storage-error"));
            }
        });
    }

    public void untrustPlayer(Player player, Player target) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        Claim at = claimAt(player.getLocation());
        if (at == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-in-claim");
            return;
        }
        if (!at.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-owner");
            return;
        }
        long id = at.id();
        UUID tid = target.getUniqueId();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                boolean ok = st.deleteTrust(id, tid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ok) {
                        cache.removeTrust(id, tid);
                        plugin.getMessageManager().sendPrefixed(player, "claim.untrust-success",
                                Map.of("player", target.getName()));
                    } else {
                        plugin.getMessageManager().sendPrefixed(player, "claim.untrust-not-found",
                                Map.of("player", target.getName()));
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.storage-error"));
            }
        });
    }

    public void trustList(Player player) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        Claim at = claimAt(player.getLocation());
        if (at == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-in-claim");
            return;
        }
        if (!at.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-owner");
            return;
        }
        Set<UUID> snap = cache.trustedSnapshot(at.id());
        if (snap.isEmpty()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.trustlist-empty");
            return;
        }
        plugin.getMessageManager().sendPrefixed(player, "claim.trustlist-header",
                Map.of("claimNumber", Integer.toString(at.ownerClaimNumber())));
        for (UUID u : snap.stream().sorted().toList()) {
            String name = Bukkit.getOfflinePlayer(u).getName();
            plugin.getMessageManager().sendPrefixed(player, "claim.trustlist-entry",
                    Map.of("player", name != null ? name : u.toString()));
        }
    }

    public void tryAbandon(Player player) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        Claim at = claimAt(player.getLocation());
        if (at == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-in-claim");
            abandonPending.remove(player.getUniqueId());
            return;
        }
        boolean owner = at.ownerUuid().equals(player.getUniqueId());
        if (!owner && !ClaimPermission.hasBypass(player)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.not-owner");
            return;
        }
        long now = System.currentTimeMillis();
        PendingAbandon pend = abandonPending.get(player.getUniqueId());
        if (pend == null || pend.claimId() != at.id() || pend.expiresAtMillis() < now) {
            abandonPending.put(player.getUniqueId(), new PendingAbandon(at.id(), now + 10_000L));
            plugin.getMessageManager().sendPrefixed(player, "claim.abandon-warning",
                    Map.of("claimNumber", Integer.toString(at.ownerClaimNumber())));
            plugin.getMessageManager().sendPrefixed(player, "claim.abandon-confirm");
            return;
        }
        abandonPending.remove(player.getUniqueId());
        long cid = at.id();
        int abandonedVisible = at.ownerClaimNumber();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                st.deleteClaim(cid);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    cache.removeClaim(cid);
                    visuals.onClaimDeleted(cid);
                    plugin.getMessageManager().sendPrefixed(player, "claim.abandoned",
                            Map.of("claimNumber", Integer.toString(abandonedVisible)));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.storage-error"));
            }
        });
    }

    public void adminInfo(CommandSender viewer, OfflinePlayer target) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.disabled");
            return;
        }
        if (!isStorageReady()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.storage-error");
            return;
        }
        List<Claim> list = cache.listForOwner(target.getUniqueId());
        String playerLabel = target.getName() != null ? target.getName() : target.getUniqueId().toString();
        plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-info-header",
                Map.of("player", playerLabel, "count", Integer.toString(list.size())));
        for (Claim c : list) {
            String size = c.widthBlocks() + "x" + c.depthBlocks();
            plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-info-entry",
                    Map.of(
                            "claimNumber", Integer.toString(c.ownerClaimNumber()),
                            "world", c.worldName(),
                            "size", size
                    ));
        }
    }

    public void adminDeleteByOwnerNumber(CommandSender viewer, OfflinePlayer target, int ownerClaimNumber) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.disabled");
            return;
        }
        if (!isStorageReady()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.storage-error");
            return;
        }
        Claim found = cache.byOwnerAndNumber(target.getUniqueId(), ownerClaimNumber);
        if (found == null) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-delete-missing");
            return;
        }
        long globalId = found.id();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                boolean ok = st.deleteClaim(globalId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ok) {
                        cache.removeClaim(globalId);
                        visuals.onClaimDeleted(globalId);
                        plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-delete-success",
                                Map.of("claimNumber", Integer.toString(ownerClaimNumber)));
                    } else {
                        plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-delete-missing");
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(viewer, "claim.storage-error"));
            }
        });
    }

    public void adminDeleteGlobal(CommandSender viewer, long claimId) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.disabled");
            return;
        }
        if (!isStorageReady()) {
            plugin.getMessageManager().sendPrefixed(viewer, "claim.storage-error");
            return;
        }
        Claim snap = cache.byId(claimId);
        String vis = snap != null ? Integer.toString(snap.ownerClaimNumber()) : "?";
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                boolean ok = st.deleteClaim(claimId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (ok) {
                        cache.removeClaim(claimId);
                        visuals.onClaimDeleted(claimId);
                        plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-deleteglobal-success",
                                Map.of("claimNumber", vis, "id", Long.toString(claimId)));
                    } else {
                        plugin.getMessageManager().sendPrefixed(viewer, "claim.admin-delete-missing");
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(viewer, "claim.storage-error"));
            }
        });
    }

    public void adminTeleportToPlayerClaim(Player admin, OfflinePlayer target, int ownerClaimNumber) {
        if (!config.isEnabled()) {
            plugin.getMessageManager().sendPrefixed(admin, "claim.disabled");
            return;
        }
        if (!ClaimPermission.hasAdminClaimTeleport(admin)) {
            plugin.getMessageManager().sendPrefixed(admin, "general.no-permission");
            return;
        }
        if (!isStorageReady()) {
            plugin.getMessageManager().sendPrefixed(admin, "claim.storage-error");
            return;
        }
        Claim c = cache.byOwnerAndNumber(target.getUniqueId(), ownerClaimNumber);
        if (c == null) {
            plugin.getMessageManager().sendPrefixed(admin, "claim.admin-teleport-not-found");
            return;
        }
        World world = Bukkit.getWorld(c.worldName());
        if (world == null) {
            plugin.getMessageManager().sendPrefixed(admin, "claim.admin-teleport-world-missing");
            return;
        }
        int bx = c.centerX();
        int bz = c.centerZ();
        Set<Material> unsafe = ClaimLandingSupport.unsafeMaterials(plugin);
        int chunkX = bx >> 4;
        int chunkZ = bz >> 4;
        plugin.getTeleportAdapter().loadChunk(world, chunkX, chunkZ).thenAccept(ch ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!admin.isOnline()) {
                        return;
                    }
                    Location loc = ClaimLandingSupport.findSafeStanding(world, bx, bz, unsafe);
                    if (loc == null) {
                        plugin.getMessageManager().sendPrefixed(admin, "claim.admin-teleport-unsafe");
                        return;
                    }
                    String tname = target.getName() != null ? target.getName() : target.getUniqueId().toString();
                    plugin.getTeleportService().requestTeleport(admin, loc, 0,
                            p -> plugin.getMessageManager().sendPrefixed(p, "claim.admin-teleport-success",
                                    Map.of("claimNumber", Integer.toString(ownerClaimNumber), "player", tname)),
                            null);
                }));
    }

    public void tryMergeAdjacentOwnedClaims(Player player, @Nullable Runnable onSuccess) {
        if (!isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        if (!player.hasPermission(ClaimPermission.CLAIM_MERGE)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return;
        }
        if (!config.isMergeEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-disabled");
            return;
        }
        Claim foot = claimAt(player.getLocation());
        if (foot == null || !foot.ownerUuid().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-not-in-claim");
            return;
        }
        List<Claim> ownerInWorld = new ArrayList<>();
        for (Claim x : cache.listForOwner(player.getUniqueId())) {
            if (config.isMergeRequireSameWorld()
                    && !x.worldName().equalsIgnoreCase(foot.worldName())) {
                continue;
            }
            ownerInWorld.add(x);
        }
        boolean allowDiag = config.isMergeAllowDiagonalTouch();
        Set<Long> visited = new HashSet<>();
        ArrayDeque<Claim> q = new ArrayDeque<>();
        q.add(foot);
        visited.add(foot.id());
        while (!q.isEmpty()) {
            Claim cur = q.poll();
            for (Claim o : ownerInWorld) {
                if (visited.contains(o.id())) {
                    continue;
                }
                if (ClaimAdjacency.mergeable(cur, o, allowDiag)) {
                    visited.add(o.id());
                    q.add(o);
                }
            }
        }
        List<Claim> cluster = ownerInWorld.stream().filter(c -> visited.contains(c.id())).toList();
        if (cluster.size() < 2) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-not-enough");
            return;
        }
        Claim keeper = cluster.stream()
                .min(Comparator.comparingInt(Claim::ownerClaimNumber).thenComparingLong(Claim::id))
                .orElseThrow();
        List<Long> removeIds = new ArrayList<>();
        for (Claim cl : cluster) {
            if (cl.id() != keeper.id()) {
                removeIds.add(cl.id());
            }
        }
        int minX = cluster.stream().mapToInt(Claim::minX).min().orElseThrow();
        int maxX = cluster.stream().mapToInt(Claim::maxX).max().orElseThrow();
        int minZ = cluster.stream().mapToInt(Claim::minZ).min().orElseThrow();
        int maxZ = cluster.stream().mapToInt(Claim::maxZ).max().orElseThrow();
        int w = maxX - minX + 1;
        int d = maxZ - minZ + 1;
        if (w > config.getMaxSizeX() || d > config.getMaxSizeZ()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-too-large");
            return;
        }
        Set<Long> memberIds = new HashSet<>(visited);
        if (cache.mergedBoundsOverlapForeign(keeper.worldName(), minX, maxX, minZ, maxZ, memberIds)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-overlap");
            return;
        }
        int newOcn = cluster.stream().mapToInt(Claim::ownerClaimNumber).min().orElseThrow();
        long now = System.currentTimeMillis();
        SQLiteClaimStorage st = storage;
        ExecutorService ex = dbExecutor;
        long keeperId = keeper.id();
        ex.submit(() -> {
            try {
                ClaimStorage.MergeClaimsResult res = st.mergeKeepKeeper(keeperId, removeIds,
                        minX, maxX, minZ, maxZ, newOcn, now);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Claim merged = res.keeper();
                    cache.applyMergedKeeper(keeperId, merged, res.trustUuids(), removeIds);
                    for (long rid : removeIds) {
                        visuals.onClaimDeleted(rid);
                    }
                    visuals.clearPlayer(player);
                    plugin.getMessageManager().sendPrefixed(player, "claim.merge-success");
                    if (config.isVisualsEnabled()) {
                        visuals.showTimedPreview(player, merged);
                    }
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[CPSMP] Claim merge: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin,
                        () -> plugin.getMessageManager().sendPrefixed(player, "claim.merge-storage-error"));
            }
        });
    }
}
