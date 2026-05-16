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
 *
 * <p>Setup safety contract (V2):
 * <ul>
 *     <li>Setting a corner never auto-enables a portal. Admins enable
 *         explicitly via {@link #setEnabled(String, boolean)}.</li>
 *     <li>The legacy {@code region.min/max} schema is migrated to the
 *         {@code pos1}/{@code pos2} schema on first write.</li>
 *     <li>{@link #setCorner(String, Corner, Location)} returns a
 *         {@link CornerSetResult} so callers can show targeted German
 *         warnings (other corner missing, cross-world, large region).</li>
 * </ul>
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
     * Persists a single corner of {@code portalName} in the new
     * {@code pos1/pos2/target} schema, removes the legacy {@code region}
     * block on first edit, and re-parses the portal. Never toggles
     * {@code enabled}.
     */
    public CornerSetResult setCorner(String portalName, Corner corner, Location location) {
        FileConfiguration cfg = plugin.getConfigManager().getPortals();
        ConfigurationSection section = cfg.getConfigurationSection("portals." + portalName);
        if (section == null) {
            return CornerSetResult.unknown();
        }
        if (location.getWorld() == null) {
            return CornerSetResult.unknown();
        }

        switch (corner) {
            case POS1, POS2 -> writeCorner(section, corner == Corner.POS1 ? "pos1" : "pos2", location);
            case TARGET -> {
                ConfigurationSection target = ensureSection(section, "target");
                LocationSerializer.write(target, location);
            }
        }

        // Clean up the legacy region: {world, min, max} block if it still exists.
        // Once both corners exist in the new schema the legacy block is redundant.
        if (corner != Corner.TARGET && section.isConfigurationSection("region")) {
            section.set("region", null);
        }

        plugin.getConfigManager().savePortals();
        Portal updated = Portal.fromSection(portalName, section);
        portals.put(portalName, updated);

        long largeThreshold = plugin.getConfig()
                .getLong("portal-setup.large-region-warning-volume", 100L);

        return CornerSetResult.fromPortal(updated, largeThreshold);
    }

    private void writeCorner(ConfigurationSection portalSection, String key, Location loc) {
        // Always overwrite the whole sub-section so stale fields cannot
        // poison the state after schema changes.
        portalSection.set(key, null);
        ConfigurationSection corner = portalSection.createSection(key);
        corner.set("world", loc.getWorld() != null ? loc.getWorld().getName() : null);
        corner.set("x", loc.getBlockX());
        corner.set("y", loc.getBlockY());
        corner.set("z", loc.getBlockZ());
    }

    private ConfigurationSection ensureSection(ConfigurationSection parent, String name) {
        ConfigurationSection child = parent.getConfigurationSection(name);
        if (child == null) {
            child = parent.createSection(name);
        }
        return child;
    }

    /**
     * Toggles {@code enabled} for the named portal. Returns the updated
     * portal or {@code null} if the name is unknown. Persists the change.
     */
    @Nullable
    public Portal setEnabled(String portalName, boolean enabled) {
        FileConfiguration cfg = plugin.getConfigManager().getPortals();
        ConfigurationSection section = cfg.getConfigurationSection("portals." + portalName);
        if (section == null) {
            return null;
        }
        section.set("enabled", enabled);
        plugin.getConfigManager().savePortals();
        Portal updated = Portal.fromSection(portalName, section);
        portals.put(portalName, updated);
        return updated;
    }

    /**
     * Clears pos1/pos2 (and the legacy region block) and disables the
     * portal. Target and presentation fields are preserved.
     */
    @Nullable
    public Portal reset(String portalName) {
        FileConfiguration cfg = plugin.getConfigManager().getPortals();
        ConfigurationSection section = cfg.getConfigurationSection("portals." + portalName);
        if (section == null) {
            return null;
        }
        section.set("enabled", false);
        section.set("pos1", null);
        section.set("pos2", null);
        section.set("region", null);
        plugin.getConfigManager().savePortals();
        Portal updated = Portal.fromSection(portalName, section);
        portals.put(portalName, updated);
        return updated;
    }

    // --- Result type -----------------------------------------------------

    /**
     * Outcome of {@link #setCorner}. The {@code warning*} fields carry a
     * {@code messages.yml} key plus placeholders so the caller can render
     * a localized German message without re-deriving state.
     */
    public record CornerSetResult(
            boolean ok,
            @Nullable Portal portal,
            @Nullable String warningKey,
            @Nullable Map<String, String> warningPlaceholders
    ) {
        public static CornerSetResult unknown() {
            return new CornerSetResult(false, null, null, null);
        }

        public static CornerSetResult fromPortal(Portal portal, long largeThreshold) {
            // Priority: cross-world > other corner missing > large-region.
            if (portal.isCrossWorld()) {
                return new CornerSetResult(true, portal, "admin.portal-cross-world", null);
            }
            if (!portal.isPos1Set() || !portal.isPos2Set()) {
                return new CornerSetResult(true, portal, "admin.portal-incomplete", null);
            }
            if (portal.getVolume() > largeThreshold) {
                return new CornerSetResult(true, portal, "admin.portal-large-region",
                        Map.of("size", Long.toString(portal.getVolume())));
            }
            return new CornerSetResult(true, portal, null, null);
        }
    }
}
