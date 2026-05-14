package de.deinserver.cpsmp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Lightweight per-player cooldown registry, scoped by string key. Cooldowns are
 * kept entirely in memory because they are short-lived (seconds to minutes) and
 * are intentionally reset on server restart.
 */
public final class CooldownManager {

    /** Outer key = cooldown key (e.g. "rtp"). Inner key = player UUID. */
    private final Map<String, Map<UUID, Long>> cooldowns = new HashMap<>();

    /**
     * Sets a cooldown for the player. {@code millis} is the duration from now.
     */
    public void set(String key, UUID player, long millis) {
        cooldowns
                .computeIfAbsent(key, k -> new HashMap<>())
                .put(player, System.currentTimeMillis() + millis);
    }

    /**
     * Returns the remaining cooldown in milliseconds, or 0 if no cooldown is
     * active. Expired entries are pruned lazily on access.
     */
    public long remaining(String key, UUID player) {
        Map<UUID, Long> bucket = cooldowns.get(key);
        if (bucket == null) {
            return 0L;
        }
        Long expiresAt = bucket.get(player);
        if (expiresAt == null) {
            return 0L;
        }
        long now = System.currentTimeMillis();
        if (expiresAt <= now) {
            bucket.remove(player);
            return 0L;
        }
        return expiresAt - now;
    }

    public boolean isOnCooldown(String key, UUID player) {
        return remaining(key, player) > 0L;
    }

    /** Convenience: remaining cooldown rounded up to whole seconds. */
    public long remainingSeconds(String key, UUID player) {
        long ms = remaining(key, player);
        return (ms + 999L) / 1000L;
    }

    public void clear(String key, UUID player) {
        Map<UUID, Long> bucket = cooldowns.get(key);
        if (bucket != null) {
            bucket.remove(player);
        }
    }

    public void clearAll(UUID player) {
        cooldowns.values().forEach(bucket -> bucket.remove(player));
    }
}
