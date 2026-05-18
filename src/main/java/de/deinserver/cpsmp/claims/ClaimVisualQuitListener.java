package de.deinserver.cpsmp.claims;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Always clears per-player claim previews on disconnect (independent of protection listener).
 */
public final class ClaimVisualQuitListener implements Listener {

    private final ClaimVisualService visuals;

    public ClaimVisualQuitListener(@NotNull ClaimVisualService visuals) {
        this.visuals = visuals;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        visuals.clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        visuals.handleWorldChange(event.getPlayer());
    }
}
