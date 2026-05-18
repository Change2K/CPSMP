package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ClaimCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public ClaimCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (args.length > 0 && "show".equalsIgnoreCase(args[0])) {
            if (args.length >= 2 && !"toggle".equalsIgnoreCase(args[1])) {
                plugin.getMessageManager().sendPrefixed(player, "claim.show-usage-claim");
                return true;
            }
            ClaimShowExecutor.run(player, plugin, command.getName(), args);
            return true;
        }
        if (args.length > 0) {
            plugin.getMessageManager().sendPrefixed(player, "claim.command-usage");
            return true;
        }
        if (!player.hasPermission(ClaimPermission.CLAIM_ROOT)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.no-permission");
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null || !claims.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return true;
        }
        claims.createClaim(player);
        return true;
    }
}
