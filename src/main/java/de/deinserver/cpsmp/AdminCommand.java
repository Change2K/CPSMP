package de.deinserver.cpsmp;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single {@code /cpsmpadmin} entry point. Subcommands:
 * <ul>
 *     <li>{@code setspawn} - persists the SMP spawn to the configured world</li>
 *     <li>{@code setportal <name> <pos1|pos2|target>} - edits a portal corner</li>
 *     <li>{@code setzone <danger|attack>} - sets the zone's spawn</li>
 *     <li>{@code reload} - re-loads all configs</li>
 *     <li>{@code info} - prints a short status summary</li>
 * </ul>
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of("setspawn", "setportal", "setzone", "reload", "info");
    private static final List<String> CORNERS = List.of("pos1", "pos2", "target");
    private static final List<String> ZONES = List.of("danger", "attack");

    private final CPSMPPlugin plugin;

    public AdminCommand(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cpsmp.admin")) {
            plugin.getMessageManager().sendPrefixed(sender, "general.no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "setspawn" -> handleSetSpawn(sender);
            case "setportal" -> handleSetPortal(sender, args);
            case "setzone" -> handleSetZone(sender, args);
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender);
            default -> {
                plugin.getMessageManager().sendPrefixed(sender, "admin.usage");
                yield true;
            }
        };
    }

    private boolean handleSetSpawn(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        plugin.persistSpawn(player.getLocation());
        plugin.getMessageManager().sendPrefixed(player, "admin.spawn-set");
        return true;
    }

    private boolean handleSetPortal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (args.length < 3) {
            plugin.getMessageManager().sendPrefixed(player, "admin.portal-usage");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getPortalManager().has(portalName)) {
            plugin.getMessageManager().sendPrefixed(player, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        PortalManager.Corner corner;
        switch (args[2].toLowerCase(Locale.ROOT)) {
            case "pos1" -> corner = PortalManager.Corner.POS1;
            case "pos2" -> corner = PortalManager.Corner.POS2;
            case "target" -> corner = PortalManager.Corner.TARGET;
            default -> {
                plugin.getMessageManager().sendPrefixed(player, "admin.portal-usage");
                return true;
            }
        }
        boolean ok = plugin.getPortalManager().setCorner(portalName, corner, player.getLocation());
        if (!ok) {
            plugin.getMessageManager().sendPrefixed(player, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        switch (corner) {
            case POS1 -> plugin.getMessageManager().sendPrefixed(player, "admin.portal-set-pos1",
                    Map.of("portal", portalName));
            case POS2 -> plugin.getMessageManager().sendPrefixed(player, "admin.portal-set-pos2",
                    Map.of("portal", portalName));
            case TARGET -> plugin.getMessageManager().sendPrefixed(player, "admin.portal-set-target",
                    Map.of("portal", portalName));
        }
        return true;
    }

    private boolean handleSetZone(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendPrefixed(player, "admin.zone-usage");
            return true;
        }
        ZoneManager.ZoneKind kind;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "danger" -> kind = ZoneManager.ZoneKind.DANGER;
            case "attack" -> kind = ZoneManager.ZoneKind.ATTACK;
            default -> {
                plugin.getMessageManager().sendPrefixed(player, "admin.zone-usage");
                return true;
            }
        }
        plugin.getZoneManager().setZoneSpawn(kind, player.getLocation());
        plugin.getMessageManager().sendPrefixed(player, "admin.zone-set",
                Map.of("zone", kind.key));
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("cpsmp.reload")) {
            plugin.getMessageManager().sendPrefixed(sender, "general.no-permission");
            return true;
        }
        plugin.reloadEverything();
        plugin.getMessageManager().sendPrefixed(sender, "general.reload-success");
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        MessageManager messages = plugin.getMessageManager();
        // Plugin version goes through ServerCompatibility so we use Paper's
        // PluginMeta when present and fall back to PluginDescriptionFile on
        // Spigot/CraftBukkit.
        String version = plugin.getServerCompatibility().getPluginVersion(plugin);
        messages.sendPrefixed(sender, "admin.info-header",
                Map.of("version", version));
        Location spawn = plugin.resolveSpawnLocation();
        messages.sendPrefixed(sender, "admin.info-spawn",
                Map.of("state", spawn != null ? "OK" : "NICHT GESETZT"));
        messages.sendPrefixed(sender, "admin.info-portals",
                Map.of("count", Long.toString(plugin.getPortalManager().countEnabledValid())));
        boolean dangerOk = plugin.getZoneManager().get(ZoneManager.ZoneKind.DANGER).isConfigured();
        boolean attackOk = plugin.getZoneManager().get(ZoneManager.ZoneKind.ATTACK).isConfigured();
        messages.sendPrefixed(sender, "admin.info-zones", Map.of(
                "danger", dangerOk ? "OK" : "OFFEN",
                "attack", attackOk ? "OK" : "OFFEN"
        ));
        messages.sendPrefixed(sender, "admin.info-platform", Map.of(
                "platform", plugin.getServerCompatibility().getServerFlavor(),
                "backend", plugin.getTeleportAdapter().name()
        ));
        // Economy line: shows the active bridge type (None/Vault/Reserve/Custom)
        // and the resolved provider name (e.g. "EssentialsX", "None").
        de.deinserver.cpsmp.economy.EconomyBridge bridge =
                plugin.getEconomyManager().getBridge();
        messages.sendPrefixed(sender, "admin.info-economy", Map.of(
                "bridge", bridge.providerType().name(),
                "provider", bridge.providerName()
        ));
        return true;
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("cpsmp.admin")) return List.of();
        if (args.length == 1) return filter(ROOT, args[0]);
        if (args.length == 2) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "setportal" -> {
                    return filter(plugin.getPortalManager().all().stream()
                            .map(Portal::getName).toList(), args[1]);
                }
                case "setzone" -> {
                    return filter(ZONES, args[1]);
                }
                default -> { /* fall through */ }
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("setportal")) {
            return filter(CORNERS, args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
