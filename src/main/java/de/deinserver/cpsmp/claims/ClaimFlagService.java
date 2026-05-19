package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Resolves per-claim flag values from cache + config defaults; persists toggles off the main thread.
 */
public final class ClaimFlagService {

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;

    public ClaimFlagService(@NotNull CPSMPPlugin plugin, @NotNull ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public static @NotNull EnumMap<ClaimFlag, Boolean> parseFlagsFromDb(@NotNull Map<String, String> raw) {
        EnumMap<ClaimFlag, Boolean> out = new EnumMap<>(ClaimFlag.class);
        for (Map.Entry<String, String> e : raw.entrySet()) {
            try {
                ClaimFlag flag = ClaimFlag.byConfigKey(e.getKey());
                out.put(flag, parseBool(e.getValue()));
            } catch (IllegalArgumentException ignored) {
                // unknown keys (e.g. future biome flags) ignored in V4.1
            }
        }
        return out;
    }

    public static @NotNull Map<Long, EnumMap<ClaimFlag, Boolean>> parseAllFlagsFromDb(
            @NotNull Map<Long, Map<String, String>> raw) {
        Map<Long, EnumMap<ClaimFlag, Boolean>> out = new HashMap<>();
        for (Map.Entry<Long, Map<String, String>> e : raw.entrySet()) {
            EnumMap<ClaimFlag, Boolean> parsed = parseFlagsFromDb(e.getValue());
            if (!parsed.isEmpty()) {
                out.put(e.getKey(), parsed);
            }
        }
        return out;
    }

    public boolean flagEnabled(@NotNull Claim claim, @NotNull ClaimFlag flag) {
        ClaimFlagsConfig fc = manager.getConfig().getFlags();
        if (!fc.enabled()) {
            return fc.defaultValue(flag);
        }
        Boolean override = manager.getCache().getFlagOverride(claim.id(), flag);
        if (override != null) {
            return override;
        }
        return fc.defaultValue(flag);
    }

    public boolean canEditFlags(@NotNull Player player, @NotNull Claim claim) {
        if (!manager.getConfig().getFlags().enabled()) {
            return false;
        }
        if (player.hasPermission(ClaimPermission.FLAGS_ADMIN)) {
            return true;
        }
        if (!player.hasPermission(ClaimPermission.FLAGS)) {
            return false;
        }
        return claim.ownerUuid().equals(player.getUniqueId());
    }

    public boolean playerMayEditFlag(@NotNull Player player, @NotNull Claim claim, @NotNull ClaimFlag flag) {
        if (!canEditFlags(player, claim)) {
            return false;
        }
        if (player.hasPermission(ClaimPermission.FLAGS_ADMIN)) {
            return true;
        }
        return manager.getConfig().getFlags().playerMayEdit(flag);
    }

    public void toggleFlag(@NotNull Player player, long claimId, @NotNull ClaimFlag flag,
                           @NotNull Runnable onMainSuccess) {
        Claim claim = manager.getCache().byId(claimId);
        if (claim == null) {
            return;
        }
        if (!playerMayEditFlag(player, claim, flag)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-locked");
            return;
        }
        boolean current = flagEnabled(claim, flag);
        boolean next = !current;
        persistFlag(player, claimId, flag, next, onMainSuccess);
    }

    public void persistFlag(@NotNull Player player, long claimId, @NotNull ClaimFlag flag, boolean value,
                            @NotNull Runnable onMainSuccess) {
        ClaimStorage storage = manager.getStorage();
        ExecutorService ex = manager.getDbExecutor();
        if (storage == null || ex == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-storage-error");
            return;
        }
        ClaimFlagsConfig fc = manager.getConfig().getFlags();
        boolean isDefault = value == fc.defaultValue(flag);
        long now = System.currentTimeMillis();
        ex.submit(() -> {
            try {
                if (isDefault) {
                    storage.deleteFlag(claimId, flag.configKey());
                } else {
                    storage.setFlag(claimId, flag.configKey(), boolToStorage(value), now);
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (isDefault) {
                        manager.getCache().removeFlagFromCache(claimId, flag);
                    } else {
                        manager.getCache().setFlagInCache(claimId, flag, value);
                    }
                    onMainSuccess.run();
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[CPSMP] Claim flag save: " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () ->
                        plugin.getMessageManager().sendPrefixed(player, "claim.flags-storage-error"));
            }
        });
    }

    private static boolean parseBool(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return "true".equals(v) || "1".equals(v) || "yes".equals(v);
    }

    private static String boolToStorage(boolean value) {
        return value ? "true" : "false";
    }
}
