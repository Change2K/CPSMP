package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * In-memory state for the two special zones (Danger / Attack). Backed by
 * {@code zones.yml}. The manager owns the rules (pvp, keep-inventory,
 * allow-build, allow-item-drop) and offers helpers to query them from
 * {@link ZoneListener}.
 */
public final class ZoneManager {

    public enum ZoneKind {
        DANGER("danger", "cpsmp.zone.danger"),
        ATTACK("attack", "cpsmp.zone.attack");

        public final String key;
        public final String permission;

        ZoneKind(String key, String permission) {
            this.key = key;
            this.permission = permission;
        }
    }

    /** Immutable snapshot of one zone's runtime state. */
    public static final class ZoneState {
        public final boolean enabled;
        @Nullable public final String worldName;
        @Nullable public final Location spawn;
        public final boolean pvp;
        public final boolean keepInventory;
        public final boolean allowBuild;
        public final boolean allowItemDrop;

        ZoneState(boolean enabled, @Nullable String worldName, @Nullable Location spawn,
                  boolean pvp, boolean keepInventory, boolean allowBuild, boolean allowItemDrop) {
            this.enabled = enabled;
            this.worldName = worldName;
            this.spawn = spawn;
            this.pvp = pvp;
            this.keepInventory = keepInventory;
            this.allowBuild = allowBuild;
            this.allowItemDrop = allowItemDrop;
        }

        public boolean isConfigured() {
            return enabled && worldName != null && spawn != null;
        }
    }

    private static final ZoneState EMPTY = new ZoneState(false, null, null,
            false, false, true, true);

    private final CPSMPPlugin plugin;
    private final Map<ZoneKind, ZoneState> states = new EnumMap<>(ZoneKind.class);

    public ZoneManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        states.clear();
        FileConfiguration cfg = plugin.getConfigManager().getZones();
        for (ZoneKind kind : ZoneKind.values()) {
            states.put(kind, parse(cfg.getConfigurationSection("zones." + kind.key)));
        }
    }

    private ZoneState parse(@Nullable ConfigurationSection section) {
        if (section == null) {
            return EMPTY;
        }
        boolean enabled = section.getBoolean("enabled", true);
        String worldName = section.getString("world");
        Location spawn = LocationSerializer.read(section.getConfigurationSection("spawn"));
        ConfigurationSection rules = section.getConfigurationSection("rules");
        boolean pvp = rules != null && rules.getBoolean("pvp", true);
        boolean keep = rules != null && rules.getBoolean("keep-inventory", false);
        boolean build = rules != null && rules.getBoolean("allow-build", false);
        boolean drop = rules == null || rules.getBoolean("allow-item-drop", true);
        return new ZoneState(enabled, worldName, spawn, pvp, keep, build, drop);
    }

    public ZoneState get(ZoneKind kind) {
        return states.getOrDefault(kind, EMPTY);
    }

    /**
     * Looks up which zone (if any) the given world belongs to. Zones are
     * matched by their configured world name; one world per zone is supported.
     */
    @Nullable
    public ZoneKind zoneOfWorld(String worldName) {
        for (Map.Entry<ZoneKind, ZoneState> entry : states.entrySet()) {
            ZoneState state = entry.getValue();
            if (state.isConfigured() && worldName.equals(state.worldName)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Teleports the player into the requested zone, honoring permissions and
     * sending the German entry title once they arrive.
     */
    public void teleportToZone(Player player, ZoneKind kind) {
        if (!player.hasPermission(kind.permission)) {
            plugin.getMessageManager().sendPrefixed(player, "zones." + kind.key + ".no-permission");
            return;
        }
        ZoneState state = get(kind);
        if (!state.isConfigured()) {
            plugin.getMessageManager().sendPrefixed(player, "zones." + kind.key + ".not-configured");
            return;
        }
        Location spawn = state.spawn;
        if (spawn == null || spawn.getWorld() == null) {
            plugin.getMessageManager().sendPrefixed(player, "general.world-missing",
                    Map.of("world", state.worldName != null ? state.worldName : "?"));
            return;
        }
        long cooldownMs = plugin.getConfig().getLong("zones.enter-message-cooldown-ms", 8000L);
        player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(success -> {
            if (!Boolean.TRUE.equals(success)) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                // Suppress the redundant "world-change" entry title that ZoneListener would otherwise fire.
                plugin.getCooldowns().set("zone_entry", player.getUniqueId(), cooldownMs);
                plugin.getMessageManager().sendTitle(player,
                        "zones." + kind.key + ".enter-title",
                        "zones." + kind.key + ".enter-subtitle");
                plugin.getMessageManager().sendActionBar(player,
                        "zones." + kind.key + ".enter-actionbar");
            });
        });
    }

    /**
     * Persists the spawn for the given zone and refreshes the cached state.
     */
    public boolean setZoneSpawn(ZoneKind kind, Location location) {
        if (location.getWorld() == null) return false;
        FileConfiguration cfg = plugin.getConfigManager().getZones();
        ConfigurationSection section = cfg.getConfigurationSection("zones." + kind.key);
        if (section == null) {
            section = cfg.createSection("zones." + kind.key);
        }
        section.set("enabled", true);
        section.set("world", location.getWorld().getName());
        ConfigurationSection spawn = section.getConfigurationSection("spawn");
        if (spawn == null) {
            spawn = section.createSection("spawn");
        }
        LocationSerializer.write(spawn, location);
        spawn.set("enabled", true);
        plugin.getConfigManager().saveZones();
        load();
        return true;
    }
}
