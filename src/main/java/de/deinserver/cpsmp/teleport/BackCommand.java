package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class BackCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public BackCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(TeleportPermission.BACK)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        CpsmpTeleportSubsystem sub = plugin.getTeleportSubsystem();
        TeleportConfig cfg = sub != null ? sub.getTeleportConfig() : new TeleportConfig(plugin);
        if (sub == null || !cfg.isBackEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "back.feature-disabled");
            return true;
        }
        sub.teleportPlayerBack(player);
        return true;
    }
}
