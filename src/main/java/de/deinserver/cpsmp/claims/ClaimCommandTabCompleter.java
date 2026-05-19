package de.deinserver.cpsmp.claims;

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

/**
 * Tab completion for {@code /claim} and {@code /cpclaim}: show subcommands, admin teleport targets, claim numbers.
 */
public final class ClaimCommandTabCompleter implements TabCompleter {

    private final CPSMPPlugin plugin;

    public ClaimCommandTabCompleter(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player requester)) {
            return List.of();
        }
        if (args.length == 1) {
            String p = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            if ("show".startsWith(p)) {
                out.add("show");
            }
            if ("merge".startsWith(p)) {
                out.add("merge");
            }
            if ("flags".startsWith(p) && (requester.hasPermission(ClaimPermission.FLAGS)
                    || requester.hasPermission(ClaimPermission.FLAGS_ADMIN))) {
                out.add("flags");
            }
            if (ClaimPermission.hasAdminClaimTeleport(requester)) {
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (!requester.canSee(o)) {
                        continue;
                    }
                    String n = o.getName();
                    if (n != null && n.toLowerCase(Locale.ROOT).startsWith(p)) {
                        out.add(n);
                    }
                }
            }
            return out.stream().distinct().toList();
        }
        if (args.length == 2) {
            if ("show".equalsIgnoreCase(args[0]) && ClaimPermission.canShowClaimBorder(requester)) {
                String p = args[1].toLowerCase(Locale.ROOT);
                if ("toggle".startsWith(p)) {
                    return List.of("toggle");
                }
                return List.of();
            }
            if ("merge".equalsIgnoreCase(args[0])) {
                String p = args[1].toLowerCase(Locale.ROOT);
                if ("all".startsWith(p)) {
                    return List.of("all");
                }
                return List.of();
            }
            if ("flags".equalsIgnoreCase(args[0])
                    && (requester.hasPermission(ClaimPermission.FLAGS)
                    || requester.hasPermission(ClaimPermission.FLAGS_ADMIN))) {
                ClaimManager cm = plugin.getClaimManager();
                if (cm == null) {
                    return List.of();
                }
                String p = args[1].toLowerCase(Locale.ROOT);
                List<String> nums = new ArrayList<>();
                for (Claim c : cm.getCache().listForOwner(requester.getUniqueId())) {
                    String s = Integer.toString(c.ownerClaimNumber());
                    if (s.startsWith(p)) {
                        nums.add(s);
                    }
                }
                return nums;
            }
            if (ClaimPermission.hasAdminClaimTeleport(requester)) {
                ClaimManager cm = plugin.getClaimManager();
                if (cm == null || !cm.isStorageReady()) {
                    return List.of();
                }
                Player target = Bukkit.getPlayerExact(args[0]);
                if (target == null) {
                    return List.of();
                }
                String p = args[1].toLowerCase(Locale.ROOT);
                List<String> nums = new ArrayList<>();
                for (var c : cm.getCache().listForOwner(target.getUniqueId())) {
                    String s = Integer.toString(c.ownerClaimNumber());
                    if (s.startsWith(p)) {
                        nums.add(s);
                    }
                }
                return nums;
            }
        }
        if (args.length == 3 && "merge".equalsIgnoreCase(args[0])) {
            String p = args[2].toLowerCase(Locale.ROOT);
            if ("all".startsWith(p)) {
                return List.of("all");
            }
        }
        return List.of();
    }
}
