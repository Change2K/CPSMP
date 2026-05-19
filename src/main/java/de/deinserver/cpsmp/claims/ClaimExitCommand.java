package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * /claimexit — safe teleport to just outside the current own (or trusted) claim.
 */
public final class ClaimExitCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public ClaimExitCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(ClaimPermission.CLAIM_EXIT)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        ClaimManager cm = plugin.getClaimManager();
        if (cm == null || !cm.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return true;
        }
        cm.getClaimExit().requestExit(player);
        return true;
    }
}
