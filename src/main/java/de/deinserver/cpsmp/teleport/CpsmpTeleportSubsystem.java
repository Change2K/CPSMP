package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.teleport.gui.HomesGuiClickListener;
import de.deinserver.cpsmp.teleport.gui.HomesGuiItemKeys;
import de.deinserver.cpsmp.teleport.gui.HomesGuiManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * V3.0 Homes, TPA and optional /back: config, SQLite, GUIs, combat tagging.
 * Does not modify player inventories.
 */
public final class CpsmpTeleportSubsystem {

    private final CPSMPPlugin plugin;
    private TeleportConfig teleportConfig;
    private @Nullable ExecutorService dbExecutor;
    private @Nullable HomeStorage storage;
    private final TpaManager tpaManager;
    private final CombatTagTracker combatTag;
    private final CombatTagListener combatListener;
    private @Nullable HomesGuiManager homesGui;
    private @Nullable HomesGuiClickListener homesGuiListener;
    private boolean storageReady;
    private final Map<UUID, CpsmpTeleportKind> trackedTeleport = new ConcurrentHashMap<>();

    public enum CpsmpTeleportKind {
        HOME,
        TPA,
        BACK
    }

    public CpsmpTeleportSubsystem(CPSMPPlugin plugin) {
        this.plugin = plugin;
        this.teleportConfig = new TeleportConfig(plugin);
        this.tpaManager = new TpaManager(plugin);
        this.combatTag = new CombatTagTracker();
        this.combatListener = new CombatTagListener();
    }

    public void enable() {
        this.teleportConfig = new TeleportConfig(plugin);
        HomesGuiItemKeys.init(plugin);

        if (teleportConfig.isWorldInventoryWarnExternal()) {
            warnExternalInventoryPlugins();
        }

        boolean needSql = teleportConfig.isHomesEnabled() || teleportConfig.isBackEnabled();

        if (needSql) {
            this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "CPSMP-Teleport-DB");
                t.setDaemon(true);
                return t;
            });
            File dbFile = new File(plugin.getDataFolder(), teleportConfig.getStorageFile());
            SQLiteHomeStorage sqlite = new SQLiteHomeStorage(dbFile, plugin.getLogger());
            try {
                sqlite.init();
                this.storage = sqlite;
                this.storageReady = true;
                plugin.getLogger().info("[CPSMP] Homes/TPA SQLite ready (" + dbFile.getName() + ").");
            } catch (HomeStorageException ex) {
                this.storage = null;
                this.storageReady = false;
                plugin.getLogger().log(Level.SEVERE,
                        "[CPSMP] Homes/TPA Speicher nicht verfuegbar: " + ex.getMessage(), ex);
                shutdownExecutorOnly();
            }
        } else {
            this.storage = null;
            this.storageReady = false;
        }

        registerHomesGuiIfNeeded();

        Bukkit.getPluginManager().registerEvents(combatListener, plugin);

        if (teleportConfig.isTpaEnabled()) {
            tpaManager.startExpiryTicker();
        }
    }

    private void registerHomesGuiIfNeeded() {
        if (teleportConfig.isHomesEnabled() && storageReady) {
            this.homesGui = new HomesGuiManager(plugin, this);
            this.homesGuiListener = new HomesGuiClickListener(homesGui);
            Bukkit.getPluginManager().registerEvents(homesGuiListener, plugin);
        }
    }

    private void warnExternalInventoryPlugins() {
        if (plugin.getServer().getPluginManager().getPlugin("Multiverse-Inventories") != null
                || plugin.getServer().getPluginManager().getPlugin("PerWorldInventory") != null
                || plugin.getServer().getPluginManager().getPlugin("MyWorlds") != null) {
            String msg = plugin.getConfigManager().getMessages() != null
                    ? plugin.getConfigManager().getMessages().getString(
                            "admin.log.external-inventory-detected")
                    : null;
            if (msg != null && !msg.isBlank()) {
                plugin.getLogger().warning(msg);
            }
            String policy = plugin.getConfigManager().getMessages() != null
                    ? plugin.getConfigManager().getMessages().getString("admin.log.inventory-policy")
                    : null;
            if (policy != null && !policy.isBlank()) {
                plugin.getLogger().warning(policy);
            }
        }
    }

    public void reload() {
        trackedTeleport.clear();
        tpaManager.clearAll();
        tpaManager.stopExpiryTicker();
        if (homesGui != null) {
            homesGui.closeAll();
            homesGui = null;
        }
        if (homesGuiListener != null) {
            HandlerList.unregisterAll(homesGuiListener);
            homesGuiListener = null;
        }
        this.teleportConfig = new TeleportConfig(plugin);
        registerHomesGuiIfNeeded();
        if (teleportConfig.isTpaEnabled()) {
            tpaManager.startExpiryTicker();
        }
        if (teleportConfig.isWorldInventoryWarnExternal()) {
            warnExternalInventoryPlugins();
        }
    }

    public void unregisterListeners() {
        HandlerList.unregisterAll(combatListener);
        if (homesGuiListener != null) {
            HandlerList.unregisterAll(homesGuiListener);
        }
    }

    public void disable() {
        tpaManager.clearAll();
        tpaManager.stopExpiryTicker();
        unregisterListeners();
        trackedTeleport.clear();
        if (homesGui != null) {
            homesGui.closeAll();
            homesGui = null;
        }
        homesGuiListener = null;

        if (storage != null && dbExecutor != null) {
            HomeStorage toClose = storage;
            storage = null;
            try {
                dbExecutor.submit(toClose::close).get(5, TimeUnit.SECONDS);
            } catch (Exception ex) {
                plugin.getLogger().warning("[CPSMP] Homes DB close: " + ex.getMessage());
                try {
                    toClose.close();
                } catch (Exception ignored) {
                }
            }
        }
        shutdownExecutorOnly();
        storageReady = false;
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

    public TeleportConfig getTeleportConfig() {
        return teleportConfig;
    }

    public boolean isStorageReady() {
        return storageReady && storage != null;
    }

    public @Nullable HomesGuiManager getHomesGui() {
        return homesGui;
    }

    public TpaManager getTpaManager() {
        return tpaManager;
    }

    public boolean isCombatBlockedForHomes(Player player) {
        return combatTag.isTagged(player.getUniqueId()) && teleportConfig.isCombatBlocksHomes();
    }

    public boolean isCombatBlockedForTpa(Player player) {
        return combatTag.isTagged(player.getUniqueId()) && teleportConfig.isCombatBlocksTpa();
    }

    public void recordBackIfEnabled(Player player, Location from) {
        if (!teleportConfig.isBackEnabled() || !isStorageReady()) {
            return;
        }
        if (!player.isOnline() || from.getWorld() == null) {
            return;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        if (st == null || ex == null) {
            return;
        }
        UUID u = player.getUniqueId();
        String w = from.getWorld().getName();
        double x = from.getX();
        double y = from.getY();
        double z = from.getZ();
        float yaw = from.getYaw();
        float pitch = from.getPitch();
        long now = System.currentTimeMillis();
        ex.submit(() -> {
            try {
                st.saveBackLocation(u, w, x, y, z, yaw, pitch, now);
            } catch (HomeStorageException e) {
                plugin.getLogger().warning("[CPSMP] Back speichern: " + e.getMessage());
            }
        });
    }

    public CompletableFuture<List<Home>> listHomes(UUID owner) {
        CompletableFuture<List<Home>> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(List.of());
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.listHomes(owner));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<Integer> countHomes(UUID owner) {
        CompletableFuture<Integer> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(0);
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.countHomes(owner));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<@Nullable Home> getHome(UUID owner, String name) {
        CompletableFuture<Home> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(null);
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.getHome(owner, name).orElse(null));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<Boolean> deleteHome(UUID owner, String name) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(false);
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.deleteHome(owner, name));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<Boolean> adminDeleteHome(UUID owner, String name) {
        CompletableFuture<Boolean> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(false);
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.adminDeleteHome(owner, name));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<Home> upsertHome(Player player, String name) {
        CompletableFuture<Home> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.completeExceptionally(new HomeStorageException("storage down"));
            return out;
        }
        Location loc = player.getLocation();
        if (loc.getWorld() == null) {
            out.completeExceptionally(new IllegalStateException("no world"));
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        long now = System.currentTimeMillis();
        ex.submit(() -> {
            try {
                out.complete(st.upsertHome(
                        player.getUniqueId(),
                        player.getName(),
                        name,
                        loc.getWorld().getName(),
                        loc.getX(), loc.getY(), loc.getZ(),
                        loc.getYaw(), loc.getPitch(),
                        now));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public CompletableFuture<@Nullable BackSnapshot> getBack(UUID player) {
        CompletableFuture<BackSnapshot> out = new CompletableFuture<>();
        if (!isStorageReady()) {
            out.complete(null);
            return out;
        }
        HomeStorage st = storage;
        ExecutorService ex = dbExecutor;
        ex.submit(() -> {
            try {
                out.complete(st.getBack(player).orElse(null));
            } catch (Exception e) {
                out.completeExceptionally(e);
            }
        });
        return out;
    }

    public void runSync(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    /**
     * Teleports a player to a stored home with Homes delay, feedback and cooldowns.
     */
    public void teleportPlayerToHome(Player player, Home home) {
        TeleportConfig cfg = teleportConfig;
        if (!player.isOnline() || !cfg.isHomesEnabled() || !isStorageReady()) {
            return;
        }
        if (isCombatBlockedForHomes(player)) {
            plugin.getMessageManager().sendPrefixed(player, "home.teleport-combat");
            return;
        }
        if (!cfg.canHomesInWorld(player.getWorld().getName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "home.teleport-disabled-world");
            return;
        }
        if (!cfg.canHomesInWorld(home.worldName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "home.teleport-disabled-world");
            return;
        }
        var loc = home.toLocation();
        if (loc == null) {
            plugin.getMessageManager().sendPrefixed(player, "general.world-missing",
                    java.util.Map.of("world", home.worldName()));
            return;
        }
        if (!bypassHomeCooldown(player)) {
            long remain = plugin.getCooldowns().remainingSeconds("cpsmp_home_tp", player.getUniqueId());
            if (remain > 0) {
                plugin.getMessageManager().sendPrefixed(player, "home.cooldown",
                        java.util.Map.of("time", Long.toString(remain)));
                return;
            }
        }
        plugin.getMessageManager().sendPrefixed(player, "home.teleport-start");
        recordBackIfEnabled(player, player.getLocation().clone());
        int delay = cfg.getHomeTeleportDelaySeconds();
        beginTrackedTeleport(player, CpsmpTeleportKind.HOME);
        plugin.getTeleportService().requestTeleport(player, loc, delay,
                p -> {
                    endTrackedTeleport(p);
                    if (!bypassHomeCooldown(p) && cfg.getHomeTeleportCooldownSeconds() > 0) {
                        plugin.getCooldowns().set("cpsmp_home_tp", p.getUniqueId(),
                                cfg.getHomeTeleportCooldownSeconds() * 1000L);
                    }
                    plugin.getMessageManager().sendPrefixed(p, "home.teleport-success");
                },
                this::endTrackedTeleport);
    }

    /**
     * /back teleport using the same world rules and delay pipeline as Homes.
     */
    public void teleportPlayerBack(Player player) {
        TeleportConfig cfg = teleportConfig;
        if (!player.isOnline() || !cfg.isBackEnabled() || !isStorageReady()) {
            return;
        }
        if (isCombatBlockedForHomes(player)) {
            plugin.getMessageManager().sendPrefixed(player, "back.teleport-combat");
            return;
        }
        if (!cfg.canHomesInWorld(player.getWorld().getName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "back.disabled-world");
            return;
        }
        getBack(player.getUniqueId()).whenComplete((snap, ex) -> runSync(() -> {
            if (ex != null || snap == null) {
                plugin.getMessageManager().sendPrefixed(player, "back.no-location");
                return;
            }
            Location loc = snap.toLocation();
            if (loc == null) {
                plugin.getMessageManager().sendPrefixed(player, "general.world-missing",
                        java.util.Map.of("world", snap.worldName()));
                return;
            }
            if (!cfg.canHomesInWorld(snap.worldName(), plugin)) {
                plugin.getMessageManager().sendPrefixed(player, "back.disabled-world");
                return;
            }
            if (!bypassBackCooldown(player)) {
                long remain = plugin.getCooldowns().remainingSeconds("cpsmp_back", player.getUniqueId());
                if (remain > 0) {
                    plugin.getMessageManager().sendPrefixed(player, "back.cooldown",
                            java.util.Map.of("time", Long.toString(remain)));
                    return;
                }
            }
            plugin.getMessageManager().sendPrefixed(player, "back.teleport-start");
            recordBackIfEnabled(player, player.getLocation().clone());
            int delay = cfg.getHomeTeleportDelaySeconds();
            beginTrackedTeleport(player, CpsmpTeleportKind.BACK);
            plugin.getTeleportService().requestTeleport(player, loc, delay,
                    p -> {
                        endTrackedTeleport(p);
                        if (!bypassBackCooldown(p) && cfg.getBackCooldownSeconds() > 0) {
                            plugin.getCooldowns().set("cpsmp_back", p.getUniqueId(),
                                    cfg.getBackCooldownSeconds() * 1000L);
                        }
                        plugin.getMessageManager().sendPrefixed(p, "back.teleport-success");
                    },
                    this::endTrackedTeleport);
        }));
    }

    public void beginTrackedTeleport(Player player, CpsmpTeleportKind kind) {
        trackedTeleport.put(player.getUniqueId(), kind);
    }

    public void endTrackedTeleport(@Nullable Player player) {
        if (player != null) {
            trackedTeleport.remove(player.getUniqueId());
        }
    }

    private void maybeCancelTrackedTeleportOnCombat(Player player) {
        CpsmpTeleportKind kind = trackedTeleport.get(player.getUniqueId());
        if (kind == null || !plugin.getTeleportService().isPending(player.getUniqueId())) {
            return;
        }
        boolean block = switch (kind) {
            case HOME -> teleportConfig.isCombatBlocksHomes();
            case TPA -> teleportConfig.isCombatBlocksTpa();
            case BACK -> teleportConfig.isCombatBlocksHomes();
        };
        if (!block) {
            return;
        }
        plugin.getTeleportService().cancelWithMessage(player, "teleport.cancelled-combat");
        endTrackedTeleport(player);
    }

    private static boolean bypassBackCooldown(Player player) {
        return player.isOp() || player.hasPermission(TeleportPermission.HOME_BYPASS_COOLDOWN);
    }

    private static boolean bypassHomeCooldown(Player player) {
        return player.isOp() || player.hasPermission(TeleportPermission.HOME_BYPASS_COOLDOWN);
    }

    public static boolean bypassTpaCooldown(Player player) {
        return player.isOp() || player.hasPermission(TeleportPermission.TPA_BYPASS_COOLDOWN);
    }

    public static int resolveHomeLimit(Player player, TeleportConfig cfg) {
        if (player.hasPermission(TeleportPermission.HOMES_UNLIMITED)) {
            return Integer.MAX_VALUE;
        }
        int limit = Math.max(0, cfg.getHomesDefaultLimit());
        for (int n : new int[]{1, 3, 5, 10}) {
            if (player.hasPermission("cpsmp.homes." + n)) {
                limit = Math.max(limit, n);
            }
        }
        return limit;
    }

    private final class CombatTagListener implements Listener {
        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void onPvp(EntityDamageByEntityEvent event) {
            if (!(event.getEntity() instanceof Player victim)) {
                return;
            }
            if (!(event.getDamager() instanceof Player attacker)) {
                return;
            }
            int sec = teleportConfig.getCombatTagSeconds();
            combatTag.tag(victim.getUniqueId(), sec);
            combatTag.tag(attacker.getUniqueId(), sec);
            maybeCancelTrackedTeleportOnCombat(victim);
            maybeCancelTrackedTeleportOnCombat(attacker);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent event) {
            tpaManager.cancelForQuit(event.getPlayer().getUniqueId());
        }
    }
}
