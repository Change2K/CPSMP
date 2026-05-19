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

/**
 * Opens the claim flags GUI ({@code /claimflags}, {@code /claim flags}, {@code /plot flags}).
 */
public final class ClaimFlagsCommand implements CommandExecutor, TabCompleter {

    private final CPSMPPlugin plugin;

    public ClaimFlagsCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * @param args command args after the {@code flags} token (may be empty or claim number).
     */
    public static void runFlagsSubcommand(@NotNull Player player, @NotNull CPSMPPlugin plugin, @NotNull String[] args) {
        ClaimManager cm = plugin.getClaimManager();
        if (cm == null || !cm.getConfig().isEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (!cm.getConfig().getFlags().enabled()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.disabled");
            return;
        }
        if (!player.hasPermission(ClaimPermission.FLAGS) && !player.hasPermission(ClaimPermission.FLAGS_ADMIN)) {
            plugin.getMessageManager().sendPrefixed(player, "claim.no-permission");
            return;
        }
        if (!cm.isOperational()) {
            plugin.getMessageManager().sendPrefixed(player, "claim.storage-error");
            return;
        }
        if (args.length > 1) {
            plugin.getMessageManager().sendPrefixed(player, "claim.flags-usage");
            return;
        }
        long claimId;
        if (args.length == 0) {
            Claim at = cm.claimAt(player.getLocation());
            if (at == null) {
                plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-in-claim");
                return;
            }
            claimId = at.id();
        } else {
            int num;
            try {
                num = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                plugin.getMessageManager().sendPrefixed(player, "claim.flags-usage");
                return;
            }
            if (num < 1) {
                plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-found");
                return;
            }
            Claim owned = cm.getCache().byOwnerAndNumber(player.getUniqueId(), num);
            if (owned == null) {
                plugin.getMessageManager().sendPrefixed(player, "claim.flags-not-found");
                return;
            }
            claimId = owned.id();
        }
        plugin.getClaimGuiManager().openFlags(player, claimId);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        runFlagsSubcommand(player, plugin, args);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }
        ClaimManager cm = plugin.getClaimManager();
        if (cm == null) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (Claim c : cm.getCache().listForOwner(player.getUniqueId())) {
            String s = Integer.toString(c.ownerClaimNumber());
            if (s.startsWith(prefix)) {
                out.add(s);
            }
        }
        return out;
    }
}
