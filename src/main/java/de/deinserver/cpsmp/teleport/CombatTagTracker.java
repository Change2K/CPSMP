package de.deinserver.cpsmp.teleport;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class CombatTagTracker {

    private final ConcurrentHashMap<UUID, Long> until = new ConcurrentHashMap<>();

    boolean isTagged(UUID id) {
        Long u = until.get(id);
        return u != null && u > System.currentTimeMillis();
    }

    void tag(UUID id, int seconds) {
        until.put(id, System.currentTimeMillis() + Math.max(1, seconds) * 1000L);
    }
}
