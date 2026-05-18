package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClaimAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB = List.of("info", "delete", "deleteglobal", "reload");

    private final CPSMPPlugin plugin;

    public ClaimAdminCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission(ClaimPermission.ADMIN)) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "info" -> handleInfo(sender, args);
            case "delete" -> handleDelete(sender, args);
            case "deleteglobal" -> handleDeleteGlobal(sender, args);
            case "reload" -> handleReload(sender);
            default -> {
                plugin.getMessageManager().sendPrefixed(sender, "claim.admin-usage");
                yield true;
            }
        };
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-info-usage");
            return true;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target.getName() == null && !target.hasPlayedBefore()) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-unknown-player",
                    java.util.Map.of("player", args[1]));
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.disabled");
            return true;
        }
        claims.adminInfo(sender, target);
        return true;
    }

    private boolean handleDelete(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-delete-usage");
            return true;
        }
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target.getName() == null && !target.hasPlayedBefore()) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-unknown-player",
                    java.util.Map.of("player", args[1]));
            return true;
        }
        int num;
        try {
            num = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-delete-bad-number",
                    java.util.Map.of("n", args[2]));
            return true;
        }
        if (num < 1) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-delete-bad-number",
                    java.util.Map.of("n", args[2]));
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.disabled");
            return true;
        }
        claims.adminDeleteByOwnerNumber(sender, target, num);
        return true;
    }

    private boolean handleDeleteGlobal(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-deleteglobal-usage");
            return true;
        }
        long id;
        try {
            id = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.admin-delete-bad-id",
                    java.util.Map.of("id", args[1]));
            return true;
        }
        ClaimManager claims = plugin.getClaimManager();
        if (claims == null) {
            plugin.getMessageManager().sendPrefixed(sender, "claim.disabled");
            return true;
        }
        claims.adminDeleteGlobal(sender, id);
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        plugin.getConfigManager().reloadClaims();
        ClaimManager claims = plugin.getClaimManager();
        if (claims != null) {
            claims.reload();
        }
        plugin.getMessageManager().sendPrefixed(sender, "claim.admin-reload-success");
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(ClaimPermission.ADMIN)) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(SUB, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("info")) {
            return filterOnlineNames(sender, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("delete")) {
            return filterOnlineNames(sender, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("delete")) {
            return filterOwnerClaimNumbers(args[1], args[2]);
        }
        return List.of();
    }

    private List<String> filterOwnerClaimNumbers(String playerArg, String numberPrefix) {
        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(playerArg);
        if (!target.hasPlayedBefore() && target.getName() == null) {
            return List.of();
        }
        ClaimManager cm = plugin.getClaimManager();
        if (cm == null || !cm.getConfig().isEnabled()) {
            return List.of();
        }
        List<Claim> list = cm.getCache().listForOwner(target.getUniqueId());
        String p = numberPrefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Claim c : list) {
            String s = Integer.toString(c.ownerClaimNumber());
            if (s.startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> filter(List<String> opts, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : opts) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }

    private static List<String> filterOnlineNames(CommandSender sender, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (sender instanceof Player requester && !requester.canSee(o)) {
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
