package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class TpDenyCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public TpDenyCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(TeleportPermission.TPA_DENY)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        CpsmpTeleportSubsystem sub = plugin.getTeleportSubsystem();
        if (sub == null || !sub.getTeleportConfig().isTpaEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.feature-disabled");
            return true;
        }
        if (sub.getTpaManager().peekIncoming(player.getUniqueId()) == null) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.no-request");
            return true;
        }
        sub.getTpaManager().deny(player.getUniqueId());
        return true;
    }
}
