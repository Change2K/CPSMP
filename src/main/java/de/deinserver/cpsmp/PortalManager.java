package de.deinserver.cpsmp;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the parsed portal definitions and persists edits made via the admin
 * command. The required portals
 * ({@code lobby_to_smp}, {@code smp_rtp}, {@code smp_to_danger_zone},
 * {@code smp_to_attack_zone}) are guaranteed to exist after {@link #load()}
 * even when {@code portals.yml} is partially missing.
 */
public final class PortalManager {

    public enum Corner { POS1, POS2, TARGET }

    /** Portal keys that the rest of the plugin expects to be defined. */
    public static final List<String> REQUIRED = List.of(
            "lobby_to_smp",
            "smp_rtp",
            "smp_to_danger_zone",
            "smp_to_attack_zone"
    );

    private final CPSMPPlugin plugin;
    private final Map<String, Portal> portals = new LinkedHashMap<>();

    public PortalManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        portals.clear();
        FileConfiguration cfg = plugin.getConfigManager().getPortals();
        ConfigurationSection root = cfg.getConfigurationSection("portals");
        if (root == null) {
            plugin.getLogger().warning("portals.yml is empty - using defaults");
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            Portal portal = Portal.fromSection(key, section);
            portals.put(key, portal);
            if (portal.isEnabled() && !portal.isValid()) {
                plugin.getLogger().warning("Portal '" + key + "' is enabled but invalid - check region/target.");
            }
        }
    }

    @Nullable
    public Portal get(String name) {
        return portals.get(name);
    }

    public boolean has(String name) {
        return portals.containsKey(name);
    }

    public List<Portal> all() {
        return new ArrayList<>(portals.values());
    }

    public List<Portal> enabledValid() {
        List<Portal> out = new ArrayList<>();
        for (Portal portal : portals.values()) {
            if (portal.isEnabled() && portal.isValid()) {
                out.add(portal);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public long countEnabledValid() {
        return enabledValid().size();
    }

    /**
     * Sets one corner of a portal in {@code portals.yml} and reloads the
     * in-memory portal. Returns true if the portal section exists.
     */
    public boolean setCorner(String portalName, Corner corner, Location location) {
        FileConfiguration cfg = plugin.getConfigManager().getPortals();
        ConfigurationSection section = cfg.getConfigurationSection("portals." + portalName);
        if (section == null) {
            return false;
        }
        if (location.getWorld() == null) {
            return false;
        }
        switch (corner) {
            case POS1 -> {
                ConfigurationSection region = ensureSection(section, "region");
                region.set("world", location.getWorld().getName());
                ConfigurationSection min = ensureSection(region, "min");
                min.set("x", location.getBlockX());
                min.set("y", location.getBlockY());
                min.set("z", location.getBlockZ());
            }
            case POS2 -> {
                ConfigurationSection region = ensureSection(section, "region");
                if (region.getString("world") == null) {
                    region.set("world", location.getWorld().getName());
                }
                ConfigurationSection max = ensureSection(region, "max");
                max.set("x", location.getBlockX());
                max.set("y", location.getBlockY());
                max.set("z", location.getBlockZ());
            }
            case TARGET -> {
                ConfigurationSection target = ensureSection(section, "target");
                LocationSerializer.write(target, location);
            }
        }
        // Enable the portal automatically once both pos1 and pos2 are configured for a TELEPORT portal.
        section.set("enabled", true);
        plugin.getConfigManager().savePortals();
        // Re-parse just this portal.
        portals.put(portalName, Portal.fromSection(portalName, section));
        return true;
    }

    private ConfigurationSection ensureSection(ConfigurationSection parent, String name) {
        ConfigurationSection child = parent.getConfigurationSection(name);
        if (child == null) {
            child = parent.createSection(name);
        }
        return child;
    }
}
