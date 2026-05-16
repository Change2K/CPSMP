package de.deinserver.cpsmp.compat;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;

/**
 * Bukkit/Spigot fallback adapter. Used when the runtime does not expose
 * Paper's async APIs. All Bukkit calls are routed through the scheduler
 * so the Bukkit API is never accessed off the main thread.
 *
 * <p>Behavior contract:
 * <ul>
 *     <li>The destination chunk is loaded before the teleport.</li>
 *     <li>The actual teleport runs on the next server tick on the main
 *         thread.</li>
 *     <li>The returned future is completed on the main thread once the
 *         teleport finishes.</li>
 * </ul>
 */
public final class BukkitTeleportAdapter implements TeleportAdapter {

    private final Plugin plugin;

    public BukkitTeleportAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Boolean> teleport(Player player, Location destination,
                                               PlayerTeleportEvent.TeleportCause cause) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        runOnMain(() -> {
            if (!player.isOnline()) {
                result.complete(false);
                return;
            }
            World world = destination.getWorld();
            if (world == null) {
                result.complete(false);
                return;
            }
            // Ensure the destination chunk is fully loaded before teleporting.
            // chunk.load(true) generates the chunk if missing.
            Chunk chunk = world.getChunkAt(destination.getBlockX() >> 4,
                    destination.getBlockZ() >> 4);
            if (!chunk.isLoaded()) {
                chunk.load(true);
            }
            try {
                boolean ok = player.teleport(destination, cause);
                result.complete(ok);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ) {
        CompletableFuture<Chunk> result = new CompletableFuture<>();
        runOnMain(() -> {
            try {
                Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                if (!chunk.isLoaded()) {
                    chunk.load(true);
                }
                result.complete(chunk);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    @Override
    public String name() {
        return "BukkitSync";
    }

    /**
     * Runs the task on the main thread. If we are already on the main
     * thread the task executes inline so callers see the same one-tick
     * semantics regardless of where they invoked from.
     */
    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
