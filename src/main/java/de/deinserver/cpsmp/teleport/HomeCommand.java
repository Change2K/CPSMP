package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class HomeCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public HomeCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(TeleportPermission.HOME_ROOT)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        CpsmpTeleportSubsystem sub = plugin.getTeleportSubsystem();
        TeleportConfig cfg = sub != null ? sub.getTeleportConfig() : new TeleportConfig(plugin);
        if (sub == null || !cfg.isHomesEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "home.feature-disabled");
            return true;
        }
        if (!sub.isStorageReady()) {
            plugin.getMessageManager().sendPrefixed(player, "home.storage-unavailable");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessageManager().sendPrefixed(player, "home.home-usage");
            return true;
        }
        String rawName = String.join(" ", args);
        if (!HomeNameValidator.isValid(rawName, cfg.getHomeNameMaxLength())) {
            plugin.getMessageManager().sendPrefixed(player, "home.name-invalid");
            return true;
        }
        String homeName = HomeNameValidator.normalize(rawName);
        sub.getHome(player.getUniqueId(), homeName).whenComplete((home, ex) -> sub.runSync(() -> {
            if (ex != null || home == null) {
                plugin.getMessageManager().sendPrefixed(player, "home.not-found");
                return;
            }
            sub.teleportPlayerToHome(player, home);
        }));
        return true;
    }
}
