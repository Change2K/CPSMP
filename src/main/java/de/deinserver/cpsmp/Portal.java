package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable description of one portal. Parsed from a single portal section of
 * {@code portals.yml}. Invalid regions or unloaded worlds simply mark the
 * portal as inactive instead of throwing.
 */
public final class Portal {

    public enum Type {
        TELEPORT,
        RTP,
        ZONE_DANGER,
        ZONE_ATTACK
    }

    private final String name;
    private final boolean enabled;
    private final Type type;
    private final long cooldownMs;
    @Nullable
    private final String sound;
    @Nullable
    private final String particles;
    @Nullable
    private final String titleRaw;
    @Nullable
    private final String subtitleRaw;
    @Nullable
    private final String actionbarRaw;

    @Nullable
    private final String regionWorld;
    private final double minX, minY, minZ;
    private final double maxX, maxY, maxZ;

    @Nullable
    private final Location target;

    private final boolean valid;

    private Portal(String name,
                   boolean enabled,
                   Type type,
                   long cooldownMs,
                   @Nullable String sound,
                   @Nullable String particles,
                   @Nullable String titleRaw,
                   @Nullable String subtitleRaw,
                   @Nullable String actionbarRaw,
                   @Nullable String regionWorld,
                   double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ,
                   @Nullable Location target,
                   boolean valid) {
        this.name = name;
        this.enabled = enabled;
        this.type = type;
        this.cooldownMs = cooldownMs;
        this.sound = sound;
        this.particles = particles;
        this.titleRaw = titleRaw;
        this.subtitleRaw = subtitleRaw;
        this.actionbarRaw = actionbarRaw;
        this.regionWorld = regionWorld;
        this.minX = minX; this.minY = minY; this.minZ = minZ;
        this.maxX = maxX; this.maxY = maxY; this.maxZ = maxZ;
        this.target = target;
        this.valid = valid;
    }

    /**
     * Parses one portal section. The result is never null. When the section is
     * malformed the returned portal has {@link #isValid()} = false.
     */
    public static Portal fromSection(String name, ConfigurationSection section) {
        boolean enabled = section.getBoolean("enabled", false);
        Type type;
        try {
            type = Type.valueOf(section.getString("type", "TELEPORT").toUpperCase());
        } catch (IllegalArgumentException ex) {
            type = Type.TELEPORT;
        }
        long cooldownMs = section.getLong("cooldown-ms", 4000L);
        String sound = section.getString("sound");
        String particles = section.getString("particles");
        String titleRaw = section.getString("title");
        String subtitleRaw = section.getString("subtitle");
        String actionbarRaw = section.getString("actionbar");

        ConfigurationSection region = section.getConfigurationSection("region");
        String regionWorld = region != null ? region.getString("world") : null;

        double minX = 0, minY = 0, minZ = 0;
        double maxX = 0, maxY = 0, maxZ = 0;
        boolean validRegion = false;
        if (region != null) {
            ConfigurationSection minSec = region.getConfigurationSection("min");
            ConfigurationSection maxSec = region.getConfigurationSection("max");
            if (minSec != null && maxSec != null) {
                double rawMinX = minSec.getDouble("x");
                double rawMinY = minSec.getDouble("y");
                double rawMinZ = minSec.getDouble("z");
                double rawMaxX = maxSec.getDouble("x");
                double rawMaxY = maxSec.getDouble("y");
                double rawMaxZ = maxSec.getDouble("z");
                // Normalize so min is actually min.
                minX = Math.min(rawMinX, rawMaxX);
                minY = Math.min(rawMinY, rawMaxY);
                minZ = Math.min(rawMinZ, rawMaxZ);
                maxX = Math.max(rawMinX, rawMaxX);
                maxY = Math.max(rawMinY, rawMaxY);
                maxZ = Math.max(rawMinZ, rawMaxZ);
                validRegion = regionWorld != null && !regionWorld.isBlank();
            }
        }

        Location target = LocationSerializer.read(section.getConfigurationSection("target"));

        // Type-specific validity: TELEPORT needs target; others can skip it.
        boolean targetOk = type != Type.TELEPORT || target != null;
        boolean valid = validRegion && targetOk;

        return new Portal(name, enabled, type, cooldownMs,
                sound, particles, titleRaw, subtitleRaw, actionbarRaw,
                regionWorld, minX, minY, minZ, maxX, maxY, maxZ,
                target, valid);
    }

    public boolean contains(Location loc) {
        if (!valid || !enabled) {
            return false;
        }
        World world = loc.getWorld();
        if (world == null || regionWorld == null || !world.getName().equals(regionWorld)) {
            return false;
        }
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();
        // +1 to make the max coordinate inclusive of the full block.
        return x >= minX && x <= maxX + 1
                && y >= minY && y <= maxY + 1
                && z >= minZ && z <= maxZ + 1;
    }

    /** Whether the configured region's world is currently loaded. */
    public boolean isRegionWorldLoaded() {
        return regionWorld != null && Bukkit.getWorld(regionWorld) != null;
    }

    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public boolean isValid() { return valid; }
    public Type getType() { return type; }
    public long getCooldownMs() { return cooldownMs; }
    @Nullable public String getSound() { return sound; }
    @Nullable public String getParticles() { return particles; }
    @Nullable public String getTitleRaw() { return titleRaw; }
    @Nullable public String getSubtitleRaw() { return subtitleRaw; }
    @Nullable public String getActionbarRaw() { return actionbarRaw; }
    @Nullable public Location getTarget() { return target == null ? null : target.clone(); }
    @Nullable public String getRegionWorld() { return regionWorld; }
}
