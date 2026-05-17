package de.deinserver.cpsmp.teleport;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class SetHomeCommand implements CommandExecutor {

    private final CPSMPPlugin plugin;

    public SetHomeCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(TeleportPermission.HOME_SET)) {
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
        if (args.length < 1) {
            plugin.getMessageManager().sendPrefixed(player, "home.set-usage");
            return true;
        }
        String rawName = String.join(" ", args);
        if (!HomeNameValidator.isValid(rawName, cfg.getHomeNameMaxLength())) {
            plugin.getMessageManager().sendPrefixed(player, "home.name-invalid");
            return true;
        }
        String homeName = HomeNameValidator.normalize(rawName);
        if (player.getWorld() == null) {
            return true;
        }
        if (!cfg.canSetHomeInWorld(player.getWorld().getName(), plugin)) {
            plugin.getMessageManager().sendPrefixed(player, "home.set-disabled-world");
            return true;
        }
        if (sub.isCombatBlockedForHomes(player)) {
            plugin.getMessageManager().sendPrefixed(player, "home.teleport-combat");
            return true;
        }
        if (!player.isOp() && !player.hasPermission(TeleportPermission.HOME_BYPASS_COOLDOWN)) {
            long rem = plugin.getCooldowns().remainingSeconds("cpsmp_sethome", player.getUniqueId());
            if (rem > 0 && cfg.getSethomeCooldownSeconds() > 0) {
                plugin.getMessageManager().sendPrefixed(player, "home.sethome-cooldown",
                        Map.of("time", Long.toString(rem)));
                return true;
            }
        }

        int limit = CpsmpTeleportSubsystem.resolveHomeLimit(player, cfg);
        sub.countHomes(player.getUniqueId()).whenComplete((count, ex) -> sub.runSync(() -> {
            if (ex != null) {
                plugin.getMessageManager().sendPrefixed(player, "home.storage-unavailable");
                return;
            }
            sub.getHome(player.getUniqueId(), homeName).whenComplete((existing, ex2) -> sub.runSync(() -> {
                if (ex2 != null) {
                    plugin.getMessageManager().sendPrefixed(player, "home.storage-unavailable");
                    return;
                }
                int effective = count != null ? count : 0;
                if (existing == null && effective >= limit) {
                    plugin.getMessageManager().sendPrefixed(player, "home.limit-reached");
                    return;
                }
                sub.upsertHome(player, homeName).whenComplete((home, ex3) -> sub.runSync(() -> {
                    if (ex3 != null || home == null) {
                        plugin.getMessageManager().sendPrefixed(player, "home.storage-unavailable");
                        return;
                    }
                    if (!player.isOp() && !player.hasPermission(TeleportPermission.HOME_BYPASS_COOLDOWN)
                            && cfg.getSethomeCooldownSeconds() > 0) {
                        plugin.getCooldowns().set("cpsmp_sethome", player.getUniqueId(),
                                cfg.getSethomeCooldownSeconds() * 1000L);
                    }
                    plugin.getMessageManager().sendPrefixed(player, "home.set-success",
                            Map.of("name", homeName));
                }));
            }));
        }));
        return true;
    }
}
