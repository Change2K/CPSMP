package de.deinserver.cpsmp.claims;

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

public final class ClaimsCommandsTabCompleter implements TabCompleter {

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                  @NotNull String alias, @NotNull String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        boolean showCommands = name.equals("plot") || name.equals("cpplot");
        if (showCommands) {
            if (!(sender instanceof Player requester) || !ClaimPermission.canShowClaimBorder(requester)) {
                return List.of();
            }
            if (args.length == 1) {
                String p = args[0].toLowerCase(Locale.ROOT);
                if ("show".startsWith(p)) {
                    return List.of("show");
                }
                return List.of();
            }
            if (args.length == 2 && "show".equalsIgnoreCase(args[0])) {
                String p = args[1].toLowerCase(Locale.ROOT);
                if ("toggle".startsWith(p)) {
                    return List.of("toggle");
                }
                return List.of();
            }
            return List.of();
        }
        boolean trust = name.equals("trust") || name.equals("cptrust");
        boolean untrust = name.equals("untrust") || name.equals("cpuntrust");
        if (!trust && !untrust) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }
        if (!(sender instanceof Player requester)) {
            return List.of();
        }
        if (trust && !requester.hasPermission(ClaimPermission.CLAIM_TRUST)) {
            return List.of();
        }
        if (untrust && !requester.hasPermission(ClaimPermission.CLAIM_UNTRUST)) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
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
