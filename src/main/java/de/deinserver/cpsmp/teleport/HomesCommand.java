package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.teleport.gui.HomesGuiManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class HomesCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public HomesCommand(CPSMPPlugin plugin) {
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

        boolean useGui = cfg.isHomesGuiEnabled() && player.hasPermission(TeleportPermission.HOME_GUI);
        sub.listHomes(player.getUniqueId()).whenComplete((homes, ex) -> sub.runSync(() -> {
            if (ex != null) {
                plugin.getMessageManager().sendPrefixed(player, "home.storage-unavailable");
                return;
            }
            if (homes == null || homes.isEmpty()) {
                plugin.getMessageManager().sendPrefixed(player, "home.list-empty");
                return;
            }
            if (useGui) {
                HomesGuiManager gui = sub.getHomesGui();
                if (gui != null) {
                    gui.openHomes(player, homes, 1);
                    return;
                }
            }
            plugin.getMessageManager().sendPrefixed(player, "home.list-header",
                    Map.of("count", Integer.toString(homes.size())));
            for (Home h : homes) {
                plugin.getMessageManager().sendPrefixed(player, "home.list-row",
                        Map.of("name", h.homeName(), "world", h.worldName()));
            }
        }));
        return true;
    }
}
