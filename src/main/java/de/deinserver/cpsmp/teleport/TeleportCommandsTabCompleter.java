package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TeleportCommandsTabCompleter implements TabCompleter {

    private final CPSMPPlugin plugin;

    public TeleportCommandsTabCompleter(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player requester)) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length != 1) {
            return List.of();
        }
        boolean here = name.equals("tpahere") || name.equals("cptpahere");
        boolean tpaCmd = name.equals("tpa") || name.equals("cptpa");
        if (here) {
            if (!requester.hasPermission(TeleportPermission.TPA_HERE)) {
                return List.of();
            }
        } else if (tpaCmd) {
            if (!requester.hasPermission(TeleportPermission.TPA_ROOT)) {
                return List.of();
            }
        } else {
            return List.of();
        }
        return filterPlayerNames(requester, args[0]);
    }

    private List<String> filterPlayerNames(Player requester, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!requester.canSee(o)) {
                continue;
            }
            String n = o.getName();
            if (n.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(n);
            }
        }
        return out;
    }
}
