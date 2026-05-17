package de.deinserver.cpsmp.teleport;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for {@link Home} rows and optional /back snapshots.
 */
public interface HomeStorage {

    void init() throws HomeStorageException;

    void close();

    Home upsertHome(UUID ownerUuid,
                    @Nullable String ownerName,
                    String homeName,
                    String world,
                    double x, double y, double z,
                    float yaw, float pitch,
                    long now) throws HomeStorageException;

    boolean deleteHome(UUID ownerUuid, String homeName) throws HomeStorageException;

    Optional<Home> getHome(UUID ownerUuid, String homeName) throws HomeStorageException;

    List<Home> listHomes(UUID ownerUuid) throws HomeStorageException;

    int countHomes(UUID ownerUuid) throws HomeStorageException;

    boolean saveBackLocation(UUID playerUuid, String world, double x, double y, double z,
                             float yaw, float pitch, long now) throws HomeStorageException;

    Optional<BackSnapshot> getBack(UUID playerUuid) throws HomeStorageException;

    int deleteAllHomesForPlayer(UUID ownerUuid) throws HomeStorageException;

    List<Home> listHomesForAdmin(UUID ownerUuid) throws HomeStorageException;

    boolean adminDeleteHome(UUID ownerUuid, String homeName) throws HomeStorageException;
}
