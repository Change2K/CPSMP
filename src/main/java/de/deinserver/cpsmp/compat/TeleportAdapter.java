package de.deinserver.cpsmp.compat;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Compatibility seam for player teleports and chunk loads. The Paper
 * implementation uses native async APIs; the Bukkit/Spigot implementation
 * falls back to synchronous, main-thread calls scheduled through the
 * Bukkit scheduler.
 *
 * <p>All futures complete with main-thread safe values. Callers that need to
 * touch the Bukkit API inside the completion should still hop to the main
 * thread via the scheduler - the Paper async pipeline may complete on a
 * worker thread.
 */
public interface TeleportAdapter {

    /**
     * Teleports the player to {@code destination}. The returned future
     * completes with {@code true} on success and {@code false} when the
     * teleport was rejected (e.g. player went offline, world unloaded).
     *
     * <p>Implementations are responsible for ensuring the destination chunk
     * is loaded before the actual teleport happens.
     */
    CompletableFuture<Boolean> teleport(Player player, Location destination,
                                        PlayerTeleportEvent.TeleportCause cause);

    /**
     * Loads (and if necessary generates) the chunk at the given coordinates.
     * On Paper this resolves on a worker thread; on Spigot the chunk is
     * loaded on the main thread via the scheduler so the Bukkit API is
     * never touched asynchronously.
     */
    CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ);

    /** Human-readable adapter name, used for diagnostics only. */
    String name();
}
