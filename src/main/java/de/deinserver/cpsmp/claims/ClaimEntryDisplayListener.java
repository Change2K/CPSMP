package de.deinserver.cpsmp.claims;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Detects claim enter/leave via block-column changes (no SQLite, uses {@link ClaimCache} only).
 */
public final class ClaimEntryDisplayListener implements Listener {

    private final ClaimEntryDisplayService entryDisplay;
    private final ClaimManager manager;

    public ClaimEntryDisplayListener(@NotNull ClaimEntryDisplayService entryDisplay,
                                     @NotNull ClaimManager manager) {
        this.entryDisplay = entryDisplay;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!manager.getConfig().isEnabled()
                || !manager.getConfig().getEntryDisplay().enabled()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockZ() == to.getBlockZ()
                && sameWorld(from, to)) {
            return;
        }
        entryDisplay.onPlayerBlockColumnChange(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!manager.getConfig().isEnabled()
                || !manager.getConfig().getEntryDisplay().enabled()) {
            return;
        }
        Location to = event.getTo();
        if (to != null) {
            entryDisplay.onPlayerBlockColumnChange(event.getPlayer(), to);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        entryDisplay.clearPlayer(event.getPlayer().getUniqueId());
    }

    private static boolean sameWorld(@NotNull Location a, @NotNull Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return a.getWorld() == b.getWorld();
        }
        return a.getWorld().getUID().equals(b.getWorld().getUID());
    }
}
