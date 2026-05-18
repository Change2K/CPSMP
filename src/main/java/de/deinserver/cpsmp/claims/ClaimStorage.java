package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ClaimStorage {

    void init() throws ClaimStorageException;

    void close();

    long insertClaim(UUID ownerUuid, String ownerName, String world,
                     int minX, int maxX, int minZ, int maxZ, long now) throws ClaimStorageException;

    boolean deleteClaim(long claimId) throws ClaimStorageException;

    @Nullable Claim findByOwnerAndNumber(UUID ownerUuid, int ownerClaimNumber) throws ClaimStorageException;

    int countForOwner(UUID ownerUuid) throws ClaimStorageException;

    List<Claim> listForOwner(UUID ownerUuid) throws ClaimStorageException;

    List<Claim> loadAllClaims() throws ClaimStorageException;

    Map<Long, java.util.Set<UUID>> loadAllTrustUuids() throws ClaimStorageException;

    void insertTrust(long claimId, UUID trustedUuid, String trustedName, long now) throws ClaimStorageException;

    boolean deleteTrust(long claimId, UUID trustedUuid) throws ClaimStorageException;

    List<ClaimTrustEntry> listTrust(long claimId) throws ClaimStorageException;

    @Nullable Claim getClaim(long id) throws ClaimStorageException;

    record MergeClaimsResult(Claim keeper, Set<UUID> trustUuids) {
    }

    MergeClaimsResult mergeKeepKeeper(long keeperClaimId, List<Long> removeClaimIds,
                                      int newMinX, int newMaxX, int newMinZ, int newMaxZ,
                                      int newOwnerClaimNumber, long now) throws ClaimStorageException;
}
