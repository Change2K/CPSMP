package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Shared handler for {@code /plot show}, {@code /cpplot show}, {@code /claim show}.
 * {@code show} and {@code show toggle} both toggle the same feet-follow outline.
 */
public final class ClaimShowExecutor {

    private ClaimShowExecutor() {
    }

    public static void run(@NotNull Player player, @NotNull CPSMPPlugin plugin,
                          @NotNull String commandLabel, @NotNull String[] args) {
        String label = commandLabel.toLowerCase();
        ClaimManager cm = plugin.getClaimManager();
        if ("plot".equals(label) && cm != null && !cm.getConfig().isPlotAliasEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.plot-alias-disabled");
            return;
        }
        if (!ClaimPermission.canShowClaimBorder(player)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.no-permission");
            return;
        }
        if (cm == null || !cm.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (args.length < 1 || !"show".equalsIgnoreCase(args[0])) {
            plugin.getMessageManager().sendPrefixed(player,
                    "plot".equals(label) || "cpplot".equals(label)
                            ? "claim.show-usage-plot" : "claim.show-usage-claim");
            return;
        }
        if (args.length > 2) {
            plugin.getMessageManager().sendPrefixed(player,
                    "plot".equals(label) || "cpplot".equals(label)
                            ? "claim.show-usage-plot" : "claim.show-usage-claim");
            return;
        }
        if (args.length == 2 && !"toggle".equalsIgnoreCase(args[1])) {
            plugin.getMessageManager().sendPrefixed(player,
                    "plot".equals(label) || "cpplot".equals(label)
                            ? "claim.show-usage-plot" : "claim.show-usage-claim");
            return;
        }

        if (!cm.getConfig().isVisualsEnabled()) {
            cm.getVisuals().clearBecauseVisualsDisabled(player);
            plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
            return;
        }

        ClaimVisualService.ToggleFeetResult r = cm.getVisuals().toggleFeetFollowDisplay(player);
        switch (r) {
            case SHOWN -> plugin.getMessageManager().sendPrefixed(player, "claim.show-enabled");
            case HIDDEN -> plugin.getMessageManager().sendPrefixed(player, "claim.show-disabled");
            case NOT_IN_CLAIM -> plugin.getMessageManager().sendPrefixed(player, "claim.show-not-in-claim");
        }
    }
}
