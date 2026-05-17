package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TpaCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;
    private final TpaKind kind;

    public TpaCommand(CPSMPPlugin plugin, TpaKind kind) {
        this.plugin = plugin;
        this.kind = kind;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(TeleportPermission.TPA_ROOT)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        CpsmpTeleportSubsystem sub = plugin.getTeleportSubsystem();
        TeleportConfig cfg = sub != null ? sub.getTeleportConfig() : new TeleportConfig(plugin);
        if (sub == null || !cfg.isTpaEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.feature-disabled");
            return true;
        }
        if (kind == TpaKind.HERE && !cfg.isTpaHereEnabled()) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.here-disabled");
            return true;
        }
        if (kind == TpaKind.HERE && !player.hasPermission(TeleportPermission.TPA_HERE)) {
            plugin.getMessageManager().sendPrefixed(player, "general.no-permission");
            return true;
        }
        if (args.length < 1) {
            plugin.getMessageManager().sendPrefixed(player,
                    kind == TpaKind.TPA ? "tpa.usage" : "tpa.usage-here");
            return true;
        }
        Player target = resolvePlayer(player, args[0]);
        if (target == null) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.player-not-found");
            return true;
        }
        if (target.equals(player)) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.self");
            return true;
        }
        if (player.getWorld() == null || target.getWorld() == null) {
            return true;
        }
        if (!cfg.canTpaFromWorld(player.getWorld().getName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.disabled-world");
            return true;
        }
        if (!cfg.canTpaFromWorld(target.getWorld().getName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.target-disabled-world");
            return true;
        }
        if (sub.isCombatBlockedForTpa(player)) {
            plugin.getMessageManager().sendPrefixed(player, "tpa.teleport-combat");
            return true;
        }
        if (!CpsmpTeleportSubsystem.bypassTpaCooldown(player)) {
            long rem = plugin.getCooldowns().remainingSeconds("cpsmp_tpa", player.getUniqueId());
            if (rem > 0 && cfg.getTpaCooldownSeconds() > 0) {
                plugin.getMessageManager().sendPrefixed(player, "tpa.cooldown",
                        Map.of("time", Long.toString(rem)));
                return true;
            }
        }
        TpaManager.SendResult r = sub.getTpaManager().trySend(player, target, kind, cfg);
        switch (r) {
            case SELF -> plugin.getMessageManager().sendPrefixed(player, "tpa.self");
            case TARGET_OFFLINE -> plugin.getMessageManager().sendPrefixed(player, "tpa.player-not-found");
            case TARGET_BUSY -> plugin.getMessageManager().sendPrefixed(player, "tpa.target-busy");
            case OK -> {
                plugin.getMessageManager().sendPrefixed(player, "tpa.sent");
                String key = kind == TpaKind.TPA ? "tpa.received" : "tpa.received-here";
                plugin.getMessageManager().sendPrefixed(target, key,
                        Map.of("player", player.getName()));
                if (!CpsmpTeleportSubsystem.bypassTpaCooldown(player) && cfg.getTpaCooldownSeconds() > 0) {
                    plugin.getCooldowns().set("cpsmp_tpa", player.getUniqueId(),
                            cfg.getTpaCooldownSeconds() * 1000L);
                }
            }
        }
        return true;
    }

    @Nullable
    private static Player resolvePlayer(Player sender, String token) {
        Player exact = Bukkit.getPlayerExact(token);
        if (exact != null && sender.canSee(exact)) {
            return exact;
        }
        String lower = token.toLowerCase(Locale.ROOT);
        List<Player> matches = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!sender.canSee(online)) {
                continue;
            }
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(online);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        for (Player p : matches) {
            if (p.getName().equalsIgnoreCase(token)) {
                return p;
            }
        }
        return null;
    }
}
