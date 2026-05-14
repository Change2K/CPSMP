package de.deinserver.cpsmp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Thin command shim that delegates the actual work to {@link RTPService}.
 * Keeps the executor easy to reason about and the service unit-testable.
 */
public final class RTPCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public RTPCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission("cpsmp.rtp")) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        plugin.getRtpService().runRandomTeleport(player);
        return true;
    }
}
