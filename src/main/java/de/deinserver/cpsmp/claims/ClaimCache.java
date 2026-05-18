package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Main-thread claim index: O(n) per world (typical n is small on SMP).
 */
public final class ClaimCache {

    private final Map<Long, Claim> byId = new ConcurrentHashMap<>();
    private final Map<String, List<Claim>> byWorld = new ConcurrentHashMap<>();
    private final Map<Long, Set<UUID>> trust = new ConcurrentHashMap<>();

    public synchronized void rebuild(List<Claim> claims, Map<Long, Set<UUID>> trustMap) {
        byId.clear();
        byWorld.clear();
        trust.clear();
        for (Claim c : claims) {
            byId.put(c.id(), c);
            byWorld.computeIfAbsent(c.worldName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<Long, Set<UUID>> e : trustMap.entrySet()) {
            trust.put(e.getKey(), new HashSet<>(e.getValue()));
        }
    }

    public synchronized void putClaim(Claim c, Set<UUID> trusted) {
        byId.put(c.id(), c);
        byWorld.computeIfAbsent(c.worldName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(c);
        if (!trusted.isEmpty()) {
            trust.put(c.id(), new HashSet<>(trusted));
        }
    }

    public synchronized void removeClaim(long id) {
        Claim removed = byId.remove(id);
        trust.remove(id);
        if (removed != null) {
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
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        return out;
    }

    public synchronized @Nullable Claim byId(long id) {
        return byId.get(id);
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
}
