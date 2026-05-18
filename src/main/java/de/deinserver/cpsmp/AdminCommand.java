package de.deinserver.cpsmp;

import de.deinserver.cpsmp.claims.ClaimConfig;
import de.deinserver.cpsmp.claims.ClaimManager;
import de.deinserver.cpsmp.teleport.CpsmpTeleportSubsystem;
import de.deinserver.cpsmp.teleport.Home;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
import java.util.Map;

/**
 * The single {@code /cpsmpadmin} entry point. Subcommands:
 * <ul>
 *     <li>{@code setspawn} - persists the SMP spawn to the configured world</li>
 *     <li>{@code setportal <name> <pos1|pos2|target>} - edits a portal corner
 *         (never auto-enables the portal)</li>
 *     <li>{@code portal <name> <enable|disable|reset|info>} - explicit
 *         portal lifecycle and inspection</li>
 *     <li>{@code setzone <danger|attack>} - sets the zone's spawn</li>
 *     <li>{@code reload} - re-loads all configs</li>
 *     <li>{@code info} - prints a short status summary</li>
 * </ul>
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of(
            "setspawn", "setportal", "portal", "setzone", "reload", "info", "homes", "refreshmessages");
    private static final List<String> CORNERS = List.of("pos1", "pos2", "target");
    private static final List<String> PORTAL_ACTIONS = List.of(
            "enable", "disable", "reset", "info");
    private static final List<String> ZONES = List.of("danger", "attack");
    private static final List<String> HOMES_ACTIONS = List.of("info", "delete", "reload");
    private static final List<String> REFRESH_MESSAGES_ACTIONS = List.of("gui");

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
            case "portal" -> handlePortal(sender, args);
            case "setzone" -> handleSetZone(sender, args);
            case "reload" -> handleReload(sender);
            case "info" -> handleInfo(sender);
            case "homes" -> handleHomes(sender, args);
            case "refreshmessages" -> handleRefreshMessages(sender, args);
            default -> {
                plugin.getMessageManager().sendPrefixed(sender, "admin.usage");
                yield true;
            }
        };
    }

    private boolean handleRefreshMessages(CommandSender sender, String[] args) {
        if (args.length < 2 || !"gui".equalsIgnoreCase(args[1])) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.refreshmessages-usage");
            return true;
        }
        String backup = MessagesGuiStyleMigration.refreshGuiKeysForced(plugin,
                plugin.getConfigManager().getMessagesFile());
        if (backup == null) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.refreshmessages-gui-failed");
            return true;
        }
        plugin.getConfigManager().reloadMessages();
        plugin.getMessageManager().reload();
        plugin.getMessageManager().sendPrefixed(sender, "admin.refreshmessages-gui-backup-created",
                Map.of("file", backup));
        plugin.getMessageManager().sendPrefixed(sender, "admin.refreshmessages-gui-success");
        return true;
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
        PortalManager.CornerSetResult result =
                plugin.getPortalManager().setCorner(portalName, corner, player.getLocation());
        if (!result.ok()) {
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
        // Setup-safety follow-up (cross-world / incomplete / large-region).
        if (result.warningKey() != null) {
            Map<String, String> placeholders = result.warningPlaceholders() != null
                    ? result.warningPlaceholders() : Map.of();
            plugin.getMessageManager().sendPrefixed(player, result.warningKey(), placeholders);
        }
        // Always show the live size if both corners are present.
        Portal updated = result.portal();
        if (updated != null && updated.isPos1Set() && updated.isPos2Set() && !updated.isCrossWorld()) {
            plugin.getMessageManager().sendPrefixed(player, "admin.portal-size", Map.of(
                    "sizeX", Integer.toString(updated.getSizeX()),
                    "sizeY", Integer.toString(updated.getSizeY()),
                    "sizeZ", Integer.toString(updated.getSizeZ())
            ));
        }
        return true;
    }

    private boolean handlePortal(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-action-usage");
            return true;
        }
        String portalName = args[1];
        if (!plugin.getPortalManager().has(portalName)) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "enable" -> handlePortalEnable(sender, portalName, true);
            case "disable" -> handlePortalEnable(sender, portalName, false);
            case "reset" -> handlePortalReset(sender, portalName);
            case "info" -> handlePortalInfo(sender, portalName);
            default -> {
                plugin.getMessageManager().sendPrefixed(sender, "admin.portal-action-usage");
                yield true;
            }
        };
    }

    private boolean handlePortalEnable(CommandSender sender, String portalName, boolean enable) {
        Portal current = plugin.getPortalManager().get(portalName);
        if (current == null) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        // Guard against enabling an incomplete or cross-world portal.
        if (enable && (!current.isPos1Set() || !current.isPos2Set() || current.isCrossWorld())) {
            String key = current.isCrossWorld() ? "admin.portal-cross-world" : "admin.portal-incomplete";
            plugin.getMessageManager().sendPrefixed(sender, key);
            return true;
        }
        Portal updated = plugin.getPortalManager().setEnabled(portalName, enable);
        if (updated == null) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        plugin.getMessageManager().sendPrefixed(sender,
                enable ? "admin.portal-enabled" : "admin.portal-disabled",
                Map.of("portal", portalName));
        return true;
    }

    private boolean handlePortalReset(CommandSender sender, String portalName) {
        Portal updated = plugin.getPortalManager().reset(portalName);
        if (updated == null) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        plugin.getMessageManager().sendPrefixed(sender, "admin.portal-reset",
                Map.of("portal", portalName));
        return true;
    }

    private boolean handlePortalInfo(CommandSender sender, String portalName) {
        Portal p = plugin.getPortalManager().get(portalName);
        if (p == null) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.portal-unknown",
                    Map.of("portal", portalName));
            return true;
        }
        MessageManager m = plugin.getMessageManager();
        m.sendPrefixed(sender, "admin.portal-info-header", Map.of("portal", portalName));
        m.sendPrefixed(sender, "admin.portal-info-enabled",
                Map.of("state", p.isEnabled() ? "AN" : "AUS"));
        m.sendPrefixed(sender, p.isValid()
                ? "admin.portal-valid"
                : "admin.portal-invalid");
        m.sendPrefixed(sender, "admin.portal-info-type",
                Map.of("type", p.getType().name()));
        m.sendPrefixed(sender, "admin.portal-info-world",
                Map.of("world", p.getRegionWorld() != null ? p.getRegionWorld() : "-"));
        m.sendPrefixed(sender, "admin.portal-info-pos1",
                Map.of("pos", formatCorner(p.getPos1())));
        m.sendPrefixed(sender, "admin.portal-info-pos2",
                Map.of("pos", formatCorner(p.getPos2())));
        if (p.isPos1Set() && p.isPos2Set() && !p.isCrossWorld()) {
            m.sendPrefixed(sender, "admin.portal-info-bounds", Map.of(
                    "min", p.getMinX() + "/" + p.getMinY() + "/" + p.getMinZ(),
                    "max", p.getMaxX() + "/" + p.getMaxY() + "/" + p.getMaxZ()
            ));
            m.sendPrefixed(sender, "admin.portal-size", Map.of(
                    "sizeX", Integer.toString(p.getSizeX()),
                    "sizeY", Integer.toString(p.getSizeY()),
                    "sizeZ", Integer.toString(p.getSizeZ())
            ));
            long largeThreshold = plugin.getConfig()
                    .getLong("portal-setup.large-region-warning-volume", 100L);
            if (p.getVolume() > largeThreshold) {
                m.sendPrefixed(sender, "admin.portal-large-region",
                        Map.of("size", Long.toString(p.getVolume())));
            }
        }
        // Target (only meaningful for TELEPORT) or zone description.
        Location target = p.getTarget();
        if (target != null && target.getWorld() != null) {
            String formatted = target.getWorld().getName() + " " +
                    target.getBlockX() + "/" + target.getBlockY() + "/" + target.getBlockZ();
            m.sendPrefixed(sender, "admin.portal-info-target",
                    Map.of("target", formatted));
        } else if (p.getType() == Portal.Type.TELEPORT) {
            m.sendPrefixed(sender, "admin.portal-info-target", Map.of("target", "-"));
        }
        return true;
    }

    private String formatCorner(@Nullable Portal.BlockCoord corner) {
        if (corner == null) return "-";
        return corner.world() + " " + corner.x() + "/" + corner.y() + "/" + corner.z();
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

    private boolean handleHomes(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendPrefixed(sender, "admin.homes-usage");
            return true;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("cpsmp.reload")) {
                plugin.getMessageManager().sendPrefixed(sender, "general.no-permission");
                return true;
            }
            plugin.getConfigManager().reloadTeleports();
            CpsmpTeleportSubsystem ts = plugin.getTeleportSubsystem();
            if (ts != null) {
                ts.reload();
            }
            plugin.getMessageManager().sendPrefixed(sender, "admin.homes-reload-success");
            return true;
        }
        if (sub.equals("info")) {
            if (args.length < 3) {
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-info-usage");
                return true;
            }
            CpsmpTeleportSubsystem ts = plugin.getTeleportSubsystem();
            if (ts == null || !ts.isStorageReady()) {
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-storage-down");
                return true;
            }
            String pname = args[2];
            Player online = Bukkit.getPlayerExact(pname);
            java.util.UUID uuid;
            if (online != null) {
                uuid = online.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer off = Bukkit.getOfflinePlayer(pname);
                uuid = off.getUniqueId();
            }
            java.util.UUID idf = uuid;
            ts.listHomes(idf).whenComplete((homes, ex) -> ts.runSync(() -> {
                if (ex != null) {
                    plugin.getMessageManager().sendPrefixed(sender, "admin.homes-storage-down");
                    return;
                }
                int c = homes != null ? homes.size() : 0;
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-info-header",
                        Map.of("player", pname, "count", Integer.toString(c)));
                if (homes == null || homes.isEmpty()) {
                    return;
                }
                for (Home h : homes) {
                    plugin.getMessageManager().sendPrefixed(sender, "admin.homes-info-row",
                            Map.of("name", h.homeName(), "world", h.worldName()));
                }
            }));
            return true;
        }
        if (sub.equals("delete")) {
            if (args.length < 4) {
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-delete-usage");
                return true;
            }
            CpsmpTeleportSubsystem ts = plugin.getTeleportSubsystem();
            if (ts == null || !ts.isStorageReady()) {
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-storage-down");
                return true;
            }
            String pname = args[2];
            String homeName = args[3];
            Player online = Bukkit.getPlayerExact(pname);
            java.util.UUID uuid;
            if (online != null) {
                uuid = online.getUniqueId();
            } else {
                @SuppressWarnings("deprecation")
                OfflinePlayer off = Bukkit.getOfflinePlayer(pname);
                uuid = off.getUniqueId();
            }
            java.util.UUID idf = uuid;
            ts.adminDeleteHome(idf, homeName).whenComplete((ok, ex) -> ts.runSync(() -> {
                if (ex != null || !Boolean.TRUE.equals(ok)) {
                    plugin.getMessageManager().sendPrefixed(sender, "admin.homes-delete-miss",
                            Map.of("player", pname, "home", homeName));
                    return;
                }
                plugin.getMessageManager().sendPrefixed(sender, "admin.homes-delete-ok",
                        Map.of("player", pname, "home", homeName));
            }));
            return true;
        }
        plugin.getMessageManager().sendPrefixed(sender, "admin.homes-usage");
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
        // Auction House line. Showing only the cheap synchronous fields
        // (active flag, storage type) here keeps /cpsmpadmin info
        // instant; counts are available via /ah admin info.
        de.deinserver.cpsmp.auction.AuctionHouseManager ah = plugin.getAuctionHouseManager();
        if (ah != null) {
            String state = ah.isActive() ? "AN" : "AUS";
            String reason = ah.getInactiveReason() != null ? ah.getInactiveReason() : "-";
            String storage = ah.getConfig() != null ? ah.getConfig().getStorageType() : "-";
            messages.sendPrefixed(sender, "admin.info-auction", Map.of(
                    "state", state,
                    "storage", storage,
                    "reason", reason
            ));
        }
        ClaimManager cm = plugin.getClaimManager();
        if (cm != null) {
            ClaimConfig cc = cm.getConfig();
            String cState = !cc.isEnabled() ? "AUS" : (cm.isStorageReady() ? "AN" : "FEHLER");
            int cCount = cm.isStorageReady() ? cm.getCache().claimCount() : 0;
            messages.sendPrefixed(sender, "admin.info-claims", Map.of(
                    "state", cState,
                    "count", Integer.toString(cCount)
            ));
        }
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
                case "setportal", "portal" -> {
                    return filter(plugin.getPortalManager().all().stream()
                            .map(Portal::getName).toList(), args[1]);
                }
                case "setzone" -> {
                    return filter(ZONES, args[1]);
                }
                case "homes" -> {
                    return filter(HOMES_ACTIONS, args[1]);
                }
                case "refreshmessages" -> {
                    return filter(REFRESH_MESSAGES_ACTIONS, args[1]);
                }
                default -> { /* fall through */ }
            }
        }
        if (args.length == 3) {
            String head = args[0].toLowerCase(Locale.ROOT);
            if (head.equals("setportal")) return filter(CORNERS, args[2]);
            if (head.equals("portal")) return filter(PORTAL_ACTIONS, args[2]);
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
