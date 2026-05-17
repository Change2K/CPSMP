package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.PluginCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * Logs German hints when primary command names are owned by another plugin.
 * <p>Bukkit resolves duplicate command registrations by plugin load order;
 * CPSMP additionally registers {@code cp*}-prefixed aliases in {@code plugin.yml}
 * as a guaranteed fallback without hijacking other plugins' commands.</p>
 */
public final class CommandOwnershipDiagnostics {

    private CommandOwnershipDiagnostics() {
    }

    private static final String[] PRIMARY_COMMANDS = {
            "sethome", "home", "homes", "delhome",
            "tpa", "tpaccept", "tpdeny", "tpahere", "back"
    };

    public static void log(@NotNull CPSMPPlugin plugin) {
        boolean anyConflict = false;
        for (String cmd : PRIMARY_COMMANDS) {
            PluginCommand pc = plugin.getServer().getPluginCommand(cmd);
            if (pc != null && pc.getPlugin() != plugin) {
                anyConflict = true;
                plugin.getLogger().warning(plugin.getMessageManager().plain("admin.log.command-conflict",
                        Map.of("cmd", cmd, "owner", pc.getPlugin().getName())));
            }
        }
        if (anyConflict) {
            plugin.getLogger().warning(plugin.getMessageManager().plain("admin.log.command-fallback-active"));
        }
    }
}
