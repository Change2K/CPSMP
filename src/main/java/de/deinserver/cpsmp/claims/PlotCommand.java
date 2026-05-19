package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PlotCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public PlotCommand(CPSMPPlugin plugin) {
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
            String cmdName = command.getName().toLowerCase();
            if ("plot".equals(cmdName) && plugin.getClaimManager() != null
                    && !plugin.getClaimManager().getConfig().isPlotAliasEnabled()) {
                plugin.getMessageManager().sendPrefixed(player, "claim.plot-alias-disabled");
                return true;
            }
            String[] flagArgs = args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0];
            ClaimFlagsCommand.runFlagsSubcommand(player, plugin, flagArgs);
            return true;
        }
        ClaimShowExecutor.run(player, plugin, command.getName(), args);
        return true;
    }
}
