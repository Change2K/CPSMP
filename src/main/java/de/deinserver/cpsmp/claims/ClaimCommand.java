package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
        if (args.length >= 1 && "flags".equalsIgnoreCase(args[0])) {
            String[] flagArgs = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
            ClaimFlagsCommand.runFlagsSubcommand(player, plugin, flagArgs);
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
        if (args.length >= 2 && "merge".equalsIgnoreCase(args[0]) && "all".equalsIgnoreCase(args[1])) {
            ClaimManager cm = plugin.getClaimManager();
            if (cm == null) {
                plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
                return true;
            }
            cm.tryMergeAdjacentOwnedClaims(player, null);
            return true;
        }
        if (args.length == 2) {
            int claimNum;
            try {
                claimNum = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                plugin.getMessageManager().sendPrefixed(player, "claim.admin-teleport-usage");
                return true;
            }
            if (!ClaimPermission.hasAdminClaimTeleport(player)) {
                plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
                return true;
            }
            ClaimManager claims = plugin.getClaimManager();
            if (claims == null) {
                plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
                return true;
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
            claims.adminTeleportToPlayerClaim(player, target, claimNum);
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
