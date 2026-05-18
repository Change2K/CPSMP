package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class UntrustCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public UntrustCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(ClaimPermission.CLAIM_UNTRUST)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessageManager().sendPrefixed(player, "claim.untrust-usage");
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null || !claims.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.trust-offline",
                    Map.of("player", args[0]));
            return true;
        }
        claims.untrustPlayer(player, target);
        return true;
    }
}
