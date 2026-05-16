package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable description of one portal. Parsed from a single portal section
 * of {@code portals.yml}.
 *
 * <p>Region model (V2):
 * <ul>
 *     <li>A portal owns two independent corner points: {@code pos1} and
 *         {@code pos2}. Both carry their own world name so cross-world
 *         configuration mistakes are detectable.</li>
 *     <li>{@link #contains(Location)} returns true only when the player's
 *         current block coordinate lies inside the inclusive integer
 *         cuboid spanned by the normalized min/max of {@code pos1} and
 *         {@code pos2}. No radius. No distance check. No +1 padding.</li>
 *     <li>A portal is {@link #isValid() valid} only when both corners are
 *         set and reference the same world (and the portal has a target,
 *         when its type is {@link Type#TELEPORT}).</li>
 *     <li>{@code enabled} is admin-controlled and never auto-toggled by
 *         editing corners.</li>
 * </ul>
 *
 * <p>Legacy schema: a portal section may still use {@code region: {world,
 * min: {x,y,z}, max: {x,y,z}}}. {@link #fromSection(String, ConfigurationSection)}
 * transparently migrates that into pos1/pos2 on read; {@link PortalManager}
 * persists in the new schema and clears the legacy keys on the next write.
 */
public final class Portal {

    public enum Type {
        TELEPORT,
        RTP,
        ZONE_DANGER,
        ZONE_ATTACK
    }

    /** Block-precision corner. Immutable. */
    public record BlockCoord(String world, int x, int y, int z) { }

    private final String name;
    private final boolean enabled;
    private final Type type;
    private final long cooldownMs;
    @Nullable private final String sound;
    @Nullable private final String particles;
    @Nullable private final String titleRaw;
    @Nullable private final String subtitleRaw;
    @Nullable private final String actionbarRaw;

    @Nullable private final BlockCoord pos1;
    @Nullable private final BlockCoord pos2;
    @Nullable private final Location target;

    // Derived from pos1/pos2 (0 when either is null).
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final int sizeX, sizeY, sizeZ;
    private final long volume;

    private final boolean sameWorld;
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
                   @Nullable BlockCoord pos1,
                   @Nullable BlockCoord pos2,
                   @Nullable Location target) {
        this.name = name;
        this.enabled = enabled;
        this.type = type;
        this.cooldownMs = cooldownMs;
        this.sound = sound;
        this.particles = particles;
        this.titleRaw = titleRaw;
        this.subtitleRaw = subtitleRaw;
        this.actionbarRaw = actionbarRaw;
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.target = target;

        boolean haveBoth = pos1 != null && pos2 != null;
        this.sameWorld = haveBoth && pos1.world().equals(pos2.world());
        if (haveBoth && sameWorld) {
            this.minX = Math.min(pos1.x(), pos2.x());
            this.minY = Math.min(pos1.y(), pos2.y());
            this.minZ = Math.min(pos1.z(), pos2.z());
            this.maxX = Math.max(pos1.x(), pos2.x());
            this.maxY = Math.max(pos1.y(), pos2.y());
            this.maxZ = Math.max(pos1.z(), pos2.z());
            this.sizeX = maxX - minX + 1;
            this.sizeY = maxY - minY + 1;
            this.sizeZ = maxZ - minZ + 1;
            this.volume = (long) sizeX * sizeY * sizeZ;
        } else {
            this.minX = this.minY = this.minZ = 0;
            this.maxX = this.maxY = this.maxZ = 0;
            this.sizeX = this.sizeY = this.sizeZ = 0;
            this.volume = 0L;
        }

        boolean targetOk = type != Type.TELEPORT || target != null;
        this.valid = haveBoth && sameWorld && targetOk;
    }

    /**
     * Parses one portal section. The result is never null. When the section
     * is malformed or incomplete the returned portal has {@link #isValid()}
     * == false and {@link #contains(Location)} == false.
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

        BlockCoord pos1 = readCorner(section.getConfigurationSection("pos1"));
        BlockCoord pos2 = readCorner(section.getConfigurationSection("pos2"));

        // Legacy migration: pre-V2 portals used a single region block with
        // min/max. Materialize it as pos1/pos2 if the new keys are absent.
        if (pos1 == null && pos2 == null) {
            ConfigurationSection region = section.getConfigurationSection("region");
            if (region != null) {
                String regionWorld = region.getString("world");
                ConfigurationSection minSec = region.getConfigurationSection("min");
                ConfigurationSection maxSec = region.getConfigurationSection("max");
                if (regionWorld != null && !regionWorld.isBlank()
                        && minSec != null && maxSec != null) {
                    pos1 = new BlockCoord(regionWorld,
                            minSec.getInt("x"), minSec.getInt("y"), minSec.getInt("z"));
                    pos2 = new BlockCoord(regionWorld,
                            maxSec.getInt("x"), maxSec.getInt("y"), maxSec.getInt("z"));
                }
            }
        }

        Location target = LocationSerializer.read(section.getConfigurationSection("target"));

        return new Portal(name, enabled, type, cooldownMs,
                sound, particles, titleRaw, subtitleRaw, actionbarRaw,
                pos1, pos2, target);
    }

    @Nullable
    private static BlockCoord readCorner(@Nullable ConfigurationSection section) {
        if (section == null) return null;
        String world = section.getString("world");
        if (world == null || world.isBlank()) return null;
        // Require x/y/z to be explicitly present so missing fields are
        // never silently treated as 0.
        if (!section.isInt("x") && !section.isLong("x") && !section.isDouble("x")) return null;
        if (!section.isInt("y") && !section.isLong("y") && !section.isDouble("y")) return null;
        if (!section.isInt("z") && !section.isLong("z") && !section.isDouble("z")) return null;
        return new BlockCoord(world, section.getInt("x"), section.getInt("y"), section.getInt("z"));
    }

    /**
     * Exact inclusive block-cuboid containment. The player's current block
     * coordinates must lie inside [min, max] on every axis AND inside the
     * portal's configured world. No padding, no radius, no proximity.
     */
    public boolean contains(Location loc) {
        if (!valid || !enabled) {
            return false;
        }
        if (pos1 == null) {
            return false; // valid implies non-null but keep the compiler happy
        }
        World world = loc.getWorld();
        if (world == null || !world.getName().equals(pos1.world())) {
            return false;
        }
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** Whether the configured region's world is currently loaded. */
    public boolean isRegionWorldLoaded() {
        return pos1 != null && Bukkit.getWorld(pos1.world()) != null;
    }

    // --- Accessors -------------------------------------------------------

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

    @Nullable public BlockCoord getPos1() { return pos1; }
    @Nullable public BlockCoord getPos2() { return pos2; }
    public boolean isPos1Set() { return pos1 != null; }
    public boolean isPos2Set() { return pos2 != null; }
    public boolean isCrossWorld() { return pos1 != null && pos2 != null && !sameWorld; }

    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return maxX; }
    public int getMaxY() { return maxY; }
    public int getMaxZ() { return maxZ; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public long getVolume() { return volume; }

    /** Source world for the region, or {@code null} when neither corner is set. */
    @Nullable
    public String getRegionWorld() {
        if (pos1 != null) return pos1.world();
        if (pos2 != null) return pos2.world();
        return null;
    }
}
