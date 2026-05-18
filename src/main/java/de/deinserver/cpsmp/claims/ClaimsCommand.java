package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ClaimsCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public ClaimsCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(ClaimPermission.CLAIM_LIST)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.no-permission");
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null || !claims.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return true;
        }
        claims.listOwnClaims(player);
        return true;
    }
}
