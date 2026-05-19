package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Main-thread claim index: O(n) per world (typical n is small on SMP).
 */
public final class ClaimCache {

    private final Map<Long, Claim> byId = new ConcurrentHashMap<>();
    private final Map<String, List<Claim>> byWorld = new ConcurrentHashMap<>();
    private final Map<Long, Set<UUID>> trust = new ConcurrentHashMap<>();
    private final Map<Long, EnumMap<ClaimFlag, Boolean>> flagsByClaimId = new ConcurrentHashMap<>();
    /** Key: owner UUID + visible claim number. */
    private final Map<String, Claim> byOwnerClaimNumber = new ConcurrentHashMap<>();

    private static String ownerNumKey(UUID owner, int num) {
        return owner.toString() + ":" + num;
    }

    public synchronized void rebuild(List<Claim> claims, Map<Long, Set<UUID>> trustMap,
                                     Map<Long, EnumMap<ClaimFlag, Boolean>> flagsMap) {
        byId.clear();
        byWorld.clear();
        trust.clear();
        flagsByClaimId.clear();
        byOwnerClaimNumber.clear();
        for (Claim c : claims) {
            byId.put(c.id(), c);
            byWorld.computeIfAbsent(c.worldName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
            byOwnerClaimNumber.put(ownerNumKey(c.ownerUuid(), c.ownerClaimNumber()), c);
        }
        for (Map.Entry<Long, Set<UUID>> e : trustMap.entrySet()) {
            trust.put(e.getKey(), new HashSet<>(e.getValue()));
        }
        if (flagsMap != null) {
            for (Map.Entry<Long, EnumMap<ClaimFlag, Boolean>> e : flagsMap.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    flagsByClaimId.put(e.getKey(), new EnumMap<>(e.getValue()));
                }
            }
        }
    }

    public synchronized void putClaim(Claim c, Set<UUID> trusted) {
        byId.put(c.id(), c);
        byWorld.computeIfAbsent(c.worldName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
        byOwnerClaimNumber.put(ownerNumKey(c.ownerUuid(), c.ownerClaimNumber()), c);
        if (!trusted.isEmpty()) {
            trust.put(c.id(), new HashSet<>(trusted));
        }
    }

    public synchronized void removeClaim(long id) {
        Claim removed = byId.remove(id);
        trust.remove(id);
        flagsByClaimId.remove(id);
        if (removed != null) {
            byOwnerClaimNumber.remove(ownerNumKey(removed.ownerUuid(), removed.ownerClaimNumber()));
            List<Claim> list = byWorld.get(removed.worldName().toLowerCase(Locale.ROOT));
            if (list != null) {
                list.removeIf(x -> x.id() == id);
            }
        }
    }

    public synchronized void setTrusted(long claimId, Set<UUID> uuids) {
        trust.put(claimId, new HashSet<>(uuids));
    }

    public synchronized void addTrust(long claimId, UUID trusted) {
        trust.computeIfAbsent(claimId, k -> new HashSet<>()).add(trusted);
    }

    public synchronized void removeTrust(long claimId, UUID trusted) {
        Set<UUID> s = trust.get(claimId);
        if (s != null) {
            s.remove(trusted);
        }
    }

    /**
     * Claims in {@code worldName} whose bounds expanded by {@code expandBlocks} contain {@code blockX}/{@code blockZ}.
     */
    public synchronized List<Claim> listNear(String worldName, int blockX, int blockZ, int expandBlocks,
                                             int maxResults, @Nullable Predicate<Claim> filter) {
        if (worldName == null || maxResults <= 0) {
            return List.of();
        }
        List<Claim> list = byWorld.get(worldName.toLowerCase(Locale.ROOT));
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Claim> out = new ArrayList<>();
        for (Claim c : list) {
            if (filter != null && !filter.test(c)) {
                continue;
            }
            if (blockX < c.minX() - expandBlocks || blockX > c.maxX() + expandBlocks
                    || blockZ < c.minZ() - expandBlocks || blockZ > c.maxZ() + expandBlocks) {
                continue;
            }
            out.add(c);
            if (out.size() >= maxResults) {
                break;
            }
        }
        return out;
    }

    public synchronized @Nullable Claim claimAt(String worldName, int blockX, int blockZ) {
        if (worldName == null) {
            return null;
        }
        List<Claim> list = byWorld.get(worldName.toLowerCase(Locale.ROOT));
        if (list == null) {
            return null;
        }
        for (Claim c : list) {
            if (c.containsBlock(blockX, blockZ)) {
                return c;
            }
        }
        return null;
    }

    public synchronized boolean overlapsAny(String worldName, int minX, int maxX, int minZ, int maxZ) {
        if (worldName == null) {
            return false;
        }
        List<Claim> list = byWorld.get(worldName.toLowerCase(Locale.ROOT));
        if (list == null) {
            return false;
        }
        for (Claim c : list) {
            if (c.overlapsXZ(minX, maxX, minZ, maxZ)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int countForOwner(UUID owner) {
        int n = 0;
        for (Claim c : byId.values()) {
            if (c.ownerUuid().equals(owner)) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<Claim> listForOwner(UUID owner) {
        List<Claim> out = new ArrayList<>();
        for (Claim c : byId.values()) {
            if (c.ownerUuid().equals(owner)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> {
            int c = Integer.compare(a.ownerClaimNumber(), b.ownerClaimNumber());
            return c != 0 ? c : Long.compare(a.id(), b.id());
        });
        return out;
    }

    public synchronized @Nullable Claim byId(long id) {
        return byId.get(id);
    }

    public synchronized @Nullable Claim byOwnerAndNumber(UUID ownerUuid, int ownerClaimNumber) {
        return byOwnerClaimNumber.get(ownerNumKey(ownerUuid, ownerClaimNumber));
    }

    public synchronized boolean isTrusted(long claimId, UUID playerId) {
        Set<UUID> s = trust.get(claimId);
        return s != null && s.contains(playerId);
    }

    public synchronized Set<UUID> trustedSnapshot(long claimId) {
        Set<UUID> s = trust.get(claimId);
        if (s == null || s.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new HashSet<>(s));
    }

    public synchronized int claimCount() {
        return byId.size();
    }

    public synchronized @Nullable Boolean getFlagOverride(long claimId, @NotNull ClaimFlag flag) {
        EnumMap<ClaimFlag, Boolean> m = flagsByClaimId.get(claimId);
        return m != null ? m.get(flag) : null;
    }

    public synchronized void setFlagInCache(long claimId, @NotNull ClaimFlag flag, boolean value) {
        flagsByClaimId.computeIfAbsent(claimId, k -> new EnumMap<>(ClaimFlag.class)).put(flag, value);
    }

    public synchronized void removeFlagFromCache(long claimId, @NotNull ClaimFlag flag) {
        EnumMap<ClaimFlag, Boolean> m = flagsByClaimId.get(claimId);
        if (m != null) {
            m.remove(flag);
            if (m.isEmpty()) {
                flagsByClaimId.remove(claimId);
            }
        }
    }

    public synchronized void clearFlags(long claimId) {
        flagsByClaimId.remove(claimId);
    }

    /**
     * True if any claim not in {@code memberIds} overlaps the axis-aligned rectangle.
     */
    public synchronized boolean mergedBoundsOverlapForeign(String worldName, int minX, int maxX, int minZ, int maxZ,
                                                             Set<Long> memberIds) {
        if (worldName == null || memberIds == null) {
            return false;
        }
        List<Claim> list = byWorld.get(worldName.toLowerCase(Locale.ROOT));
        if (list == null) {
            return false;
        }
        for (Claim c : list) {
            if (!c.overlapsXZ(minX, maxX, minZ, maxZ)) {
                continue;
            }
            if (!memberIds.contains(c.id())) {
                return true;
            }
        }
        return false;
    }

    public synchronized void applyMergedKeeper(long keeperId, Claim updatedKeeper, Set<UUID> mergedTrust,
                                               List<Long> removedOtherIds) {
        for (long rid : removedOtherIds) {
            removeClaim(rid);
        }
        Claim old = byId.get(keeperId);
        if (old != null) {
            byOwnerClaimNumber.remove(ownerNumKey(old.ownerUuid(), old.ownerClaimNumber()));
        }
        byId.put(keeperId, updatedKeeper);
        byOwnerClaimNumber.put(ownerNumKey(updatedKeeper.ownerUuid(), updatedKeeper.ownerClaimNumber()), updatedKeeper);
        String wk = updatedKeeper.worldName().toLowerCase(Locale.ROOT);
        List<Claim> list = byWorld.get(wk);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).id() == keeperId) {
                    list.set(i, updatedKeeper);
                    break;
                }
            }
        }
        trust.put(keeperId, new HashSet<>(mergedTrust));
    }
}
