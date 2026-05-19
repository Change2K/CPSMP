package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which claim a player is standing in and shows the entry display on claim changes.
 */
public final class ClaimEntryDisplayService {

    static final long NO_CLAIM_ID = -1L;

    private final CPSMPPlugin plugin;
    private final ClaimManager manager;
    private final Map<UUID, Long> trackedClaimId = new ConcurrentHashMap<>();

    public ClaimEntryDisplayService(CPSMPPlugin plugin, ClaimManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * Called when the player's block column (X/Z/world) may have changed.
     */
    public void onPlayerBlockColumnChange(@NotNull Player player, @NotNull Location to) {
        if (!manager.getConfig().isEnabled()) {
            return;
        }
        ClaimEntryDisplayConfig cfg = manager.getConfig().getEntryDisplay();
        if (!cfg.enabled()) {
            return;
        }
        Claim at = manager.claimAt(to);
        long newId = at != null ? at.id() : NO_CLAIM_ID;
        UUID pid = player.getUniqueId();
        long previousId = trackedClaimId.getOrDefault(pid, NO_CLAIM_ID);
        if (newId == previousId) {
            return;
        }
        trackedClaimId.put(pid, newId);
        if (at == null) {
            return;
        }
        if (!shouldShow(player, at, cfg)) {
            return;
        }
        showEntryDisplay(player, at, cfg);
    }

    public boolean shouldShow(@NotNull Player player, @NotNull Claim claim, @NotNull ClaimEntryDisplayConfig cfg) {
        if (manager.getConfig().getFlags().enabled()
                && !manager.flagEnabled(claim, ClaimFlag.ENTRY_DISPLAY)) {
            return false;
        }
        boolean owner = claim.ownerUuid().equals(player.getUniqueId());
        if (owner) {
            return cfg.showToOwner();
        }
        if (manager.getCache().isTrusted(claim.id(), player.getUniqueId())) {
            return cfg.showToTrusted();
        }
        return cfg.showToVisitors();
    }

    public void showEntryDisplay(@NotNull Player player, @NotNull Claim claim, @NotNull ClaimEntryDisplayConfig cfg) {
        String ownerName = resolveOwnerDisplayName(claim);
        Map<String, String> ph = Map.of("owner", ownerName);
        if (cfg.useTitle()) {
            plugin.getMessageManager().sendTitle(
                    player,
                    "claim.entry-title",
                    "claim.entry-subtitle",
                    ph,
                    cfg.fadeInTicks(),
                    cfg.stayTicks(),
                    cfg.fadeOutTicks());
        }
        if (cfg.useActionbar()) {
            plugin.getMessageManager().sendActionBar(player, "claim.entry-actionbar", ph);
        }
    }

    private static @NotNull String resolveOwnerDisplayName(@NotNull Claim claim) {
        String name = claim.ownerName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String offline = Bukkit.getOfflinePlayer(claim.ownerUuid()).getName();
        return offline != null && !offline.isBlank() ? offline : claim.ownerUuid().toString();
    }

    public void clearPlayer(@NotNull UUID playerId) {
        trackedClaimId.remove(playerId);
    }

    public void clearAll() {
        trackedClaimId.clear();
    }
}
