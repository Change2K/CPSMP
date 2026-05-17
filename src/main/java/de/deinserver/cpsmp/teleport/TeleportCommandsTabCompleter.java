package de.deinserver.cpsmp.teleport;

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

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (sender instanceof Player requester
                && args.length == 1
                && (name.equals("tpa") || name.equals("cptpa") || name.equals("tpahere")
                || name.equals("cptpahere"))) {
            return filterPlayerNames(requester, args[0]);
        }
        return List.of();
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
