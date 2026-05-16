package de.deinserver.cpsmp.compat;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.concurrent.CompletableFuture;

/**
 * Paper-native adapter. Uses {@code Player#teleportAsync} and
 * {@code World#getChunkAtAsync} directly. Selected automatically when
 * {@link ServerCompatibility#hasTeleportAsync()} and
 * {@link ServerCompatibility#hasChunkAtAsync()} both report true.
 */
public final class PaperTeleportAdapter implements TeleportAdapter {

    @Override
    public CompletableFuture<Boolean> teleport(Player player, Location destination,
                                               PlayerTeleportEvent.TeleportCause cause) {
        return player.teleportAsync(destination, cause);
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int chunkX, int chunkZ) {
        return world.getChunkAtAsync(chunkX, chunkZ);
    }

    @Override
    public String name() {
        return "PaperAsync";
    }
}
