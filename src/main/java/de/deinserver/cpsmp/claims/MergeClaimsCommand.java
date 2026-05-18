package de.deinserver.cpsmp.claims;

import de.deinserver.cpsmp.CPSMPPlugin;
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

public final class MergeClaimsCommand implements CommandExecutor, TabCompleter {

    private final CPSMPPlugin plugin;

    public MergeClaimsCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (args.length != 1 || !"all".equalsIgnoreCase(args[0])) {
            plugin.getMessageManager().sendPrefixed(player, "claim.merge-usage");
            return true;
        }
        ClaimManager cm = plugin.getClaimManager();
        if (cm == null) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return true;
        }
        cm.tryMergeAdjacentOwnedClaims(player, null);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        if ("all".startsWith(p)) {
            return List.of("all");
        }
        return List.of();
    }
}
