package de.deinserver.cpsmp;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles {@code /smpspawn}: teleports the player to the configured SMP
 * spawn through the standard {@link TeleportService} pipeline (delay +
 * cancel-on-move + cancel-on-damage).
 *
 * <p>Note: CPSMP intentionally does not register {@code /spawn}. That
 * command name is left free for other plugins on the network.
 */
public final class SpawnCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public SpawnCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission("cpsmp.spawn")) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }

        Location target = plugin.resolveSpawnLocation();
        if (target == null) {
            plugin.getMessageManager().sendPrefixed(player, "spawn.not-configured");
            return true;
        }

        plugin.getTeleportService().requestTeleport(player, target,
                success -> {
                    plugin.getMessageManager().sendTitle(success,
                            "spawn.welcome-title", "spawn.welcome-subtitle");
                    plugin.getMessageManager().sendActionBar(success, "spawn.welcome-actionbar");
                },
                null);
        return true;
    }
}
