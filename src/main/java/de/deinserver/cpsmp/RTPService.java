package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Picks a random safe surface location inside the configured radius and
 * teleports the player there. Chunk loading goes through the active
 * {@link de.deinserver.cpsmp.compat.TeleportAdapter}, so the search is
 * async on Paper and falls back to safe main-thread loads on Spigot.
 * The actual teleport reuses the standard delayed pipeline from
 * {@link TeleportService}.
 */
public final class RTPService {

    private static final String COOLDOWN_KEY = "rtp";
    /** Permission node that lets a player skip the /rtp cooldown. OP bypasses too. */
    private static final String BYPASS_PERMISSION = "cpsmp.rtp.bypasscooldown";

    private final CPSMPPlugin plugin;
    /** Cached set of unsafe block types (rebuilt on reload). */
    private Set<Material> unsafe = Collections.emptySet();
    /** Cached list of allowed worlds (rebuilt on reload). */
    private List<String> allowedWorlds = Collections.emptyList();

    public RTPService(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload() {
        this.allowedWorlds = plugin.getConfig().getStringList("rtp.allowed-worlds");
        Set<Material> set = new HashSet<>();
        for (String name : plugin.getConfig().getStringList("rtp.unsafe-blocks")) {
            // Material.valueOf may fail when Mojang renames or removes a
            // material in a future Minecraft version; we log and skip rather
            // than fail to enable the plugin. Admins should update config.yml.
            try {
                set.add(Material.valueOf(name.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown unsafe-block material: " + name);
            }
        }
        // Always treat fluids and air-likes as unsafe even if missing from config.
        // These enum constants have been stable for many Minecraft versions; if
        // any of them are ever removed upstream the plugin will fail to load
        // and the constant references will need to be updated here.
        set.add(Material.LAVA);
        set.add(Material.WATER);
        set.add(Material.VOID_AIR);
        this.unsafe = Collections.unmodifiableSet(set);
    }

    /**
     * Entry point for both /rtp and the {@code smp_rtp} portal. Respects
     * cooldown, allowed worlds, and shows German feedback throughout.
     *
     * <p>Players with {@link #BYPASS_PERMISSION} or OP status skip the
     * cooldown check entirely; no cooldown is recorded for them either, so
     * subsequent calls also remain instant.
     */
    public void runRandomTeleport(Player player) {
        World world = player.getWorld();
        if (!allowedWorlds.contains(world.getName())) {
            plugin.getMessageManager().sendPrefixed(player, "rtp.world-not-allowed");
            return;
        }

        boolean bypass = hasRtpCooldownBypass(player);
        if (!bypass) {
            long remaining = plugin.getCooldowns().remainingSeconds(COOLDOWN_KEY, player.getUniqueId());
            if (remaining > 0) {
                plugin.getMessageManager().sendPrefixed(player, "general.cooldown",
                        Map.of("time", Long.toString(remaining)));
                return;
            }
        }

        plugin.getMessageManager().sendActionBar(player, "rtp.searching-actionbar");

        findSafeLocation(world).thenAccept(found -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (found == null) {
                plugin.getMessageManager().sendPrefixed(player, "rtp.failed");
                return;
            }
            plugin.getMessageManager().sendActionBar(player, "rtp.found-actionbar");
            startDelayedTeleport(player, found, bypass);
        }));
    }

    /**
     * @param bypass when true the per-player cooldown is neither set after
     *               start nor cleared on cancel; used for OP and players with
     *               {@link #BYPASS_PERMISSION}.
     */
    private void startDelayedTeleport(Player player, Location destination, boolean bypass) {
        if (!bypass) {
            long cooldownMs = plugin.getConfig().getLong("rtp.cooldown-seconds", 120L) * 1000L;
            plugin.getCooldowns().set(COOLDOWN_KEY, player.getUniqueId(), cooldownMs);
        }

        Consumer<Player> onSuccess = success -> {
            plugin.getMessageManager().sendTitle(success, "rtp.success-title", "rtp.success-subtitle");
        };
        Consumer<Player> onCancel = cancelled -> {
            if (!bypass) {
                // Cancellation should free the cooldown - players who never actually moved.
                plugin.getCooldowns().clear(COOLDOWN_KEY, cancelled.getUniqueId());
            }
        };

        plugin.getTeleportService().requestTeleport(player, destination, onSuccess, onCancel);
    }

    /**
     * Returns true if the player should skip the /rtp cooldown. Server
     * operators always bypass; otherwise the {@link #BYPASS_PERMISSION}
     * permission node is required.
     */
    public boolean hasRtpCooldownBypass(Player player) {
        return player.isOp() || player.hasPermission(BYPASS_PERMISSION);
    }

    /**
     * Asynchronously searches for a safe location in the given world. The
     * future completes with {@code null} if no safe spot is found within the
     * configured attempt budget.
     */
    public CompletableFuture<Location> findSafeLocation(World world) {
        int minRadius = plugin.getConfig().getInt("rtp.min-radius", 500);
        int maxRadius = plugin.getConfig().getInt("rtp.max-radius", 5000);
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.max-attempts", 40));
        boolean useHighest = plugin.getConfig().getBoolean("rtp.use-highest-block", true);

        Location center = world.getSpawnLocation();
        CompletableFuture<Location> result = new CompletableFuture<>();
        attemptOne(world, center, minRadius, maxRadius, attempts, useHighest, result);
        return result;
    }

    private void attemptOne(World world, Location center, int minRadius, int maxRadius,
                            int remainingAttempts, boolean useHighest,
                            CompletableFuture<Location> result) {
        if (remainingAttempts <= 0) {
            result.complete(null);
            return;
        }
        int[] xz = randomXZ(center.getBlockX(), center.getBlockZ(), minRadius, maxRadius);
        int chunkX = xz[0] >> 4;
        int chunkZ = xz[1] >> 4;
        plugin.getTeleportAdapter().loadChunk(world, chunkX, chunkZ).thenAccept(chunk -> {
            // The adapter future may complete on a worker thread (Paper) or on
            // the main thread (Bukkit fallback). Either way, hop to the main
            // thread before touching blocks.
            Bukkit.getScheduler().runTask(plugin, () -> {
                Location candidate = pickFromColumn(world, xz[0], xz[1], useHighest);
                if (candidate != null) {
                    result.complete(candidate);
                } else {
                    attemptOne(world, center, minRadius, maxRadius,
                            remainingAttempts - 1, useHighest, result);
                }
            });
        });
    }

    private int[] randomXZ(int centerX, int centerZ, int minRadius, int maxRadius) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int range = Math.max(1, maxRadius - minRadius);
        int dx = (rnd.nextInt(range) + minRadius) * (rnd.nextBoolean() ? 1 : -1);
        int dz = (rnd.nextInt(range) + minRadius) * (rnd.nextBoolean() ? 1 : -1);
        return new int[] { centerX + dx, centerZ + dz };
    }

    private Location pickFromColumn(World world, int x, int z, boolean useHighest) {
        int y;
        if (useHighest) {
            y = world.getHighestBlockYAt(x, z);
        } else {
            y = (world.getMaxHeight() + world.getMinHeight()) / 2;
        }
        if (y <= world.getMinHeight() + 1 || y >= world.getMaxHeight() - 1) {
            return null;
        }
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        if (!isSafeGround(ground)) return null;
        if (!isPassable(feet)) return null;
        if (!isPassable(head)) return null;

        return new Location(world, x + 0.5, y + 1.0, z + 0.5, 0.0F, 0.0F);
    }

    private boolean isSafeGround(Block block) {
        Material type = block.getType();
        if (type.isAir()) return false;
        if (unsafe.contains(type)) return false;
        // Only solid blocks count as ground.
        return type.isSolid();
    }

    private boolean isPassable(Block block) {
        Material type = block.getType();
        if (unsafe.contains(type)) return false;
        return !type.isSolid() || !type.isOccluding();
    }

    public List<String> getAllowedWorlds() {
        return new ArrayList<>(allowedWorlds);
    }
}
