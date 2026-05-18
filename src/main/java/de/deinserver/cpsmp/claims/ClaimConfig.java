package de.deinserver.cpsmp.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Effective policy loaded from {@code claims.yml}.
 */
public final class ClaimConfig {

    private final boolean enabled;
    private final String storageFile;
    private final Set<String> worldsAllowedLower;
    private final Set<String> worldsBlockedLower;
    private final int defaultRadiusX;
    private final int defaultRadiusZ;
    private final int minSizeX;
    private final int minSizeZ;
    private final int maxSizeX;
    private final int maxSizeZ;
    private final boolean preventOverlap;
    private final int defaultClaimLimit;
    private final boolean protectBreak;
    private final boolean protectPlace;
    private final boolean protectContainers;
    private final boolean protectDoors;
    private final boolean protectRedstone;
    private final boolean protectEntityDamage;
    private final boolean protectItemFrames;
    private final boolean protectArmorStands;
    private final boolean protectVehicles;
    private final boolean protectExplosions;
    private final boolean protectFireSpread;
    private final boolean protectLiquidFlow;
    private final boolean protectBuckets;
    private final boolean visualsEnabled;
    private final String borderParticleRaw;
    private final int visualDurationSeconds;
    private final int visualIntervalTicks;
    private final boolean bypassActionBar;
    private final long bypassActionBarCooldownMs;

    public ClaimConfig(FileConfiguration file) {
        ConfigurationSection root = file.getConfigurationSection("claims");
        if (root == null) {
            this.enabled = false;
            this.storageFile = "claims.db";
            this.worldsAllowedLower = Set.of();
            this.worldsBlockedLower = Set.of();
            this.defaultRadiusX = 8;
            this.defaultRadiusZ = 8;
            this.minSizeX = 5;
            this.minSizeZ = 5;
            this.maxSizeX = 64;
            this.maxSizeZ = 64;
            this.preventOverlap = true;
            this.defaultClaimLimit = 1;
            this.protectBreak = true;
            this.protectPlace = true;
            this.protectContainers = true;
            this.protectDoors = true;
            this.protectRedstone = false;
            this.protectEntityDamage = true;
            this.protectItemFrames = true;
            this.protectArmorStands = true;
            this.protectVehicles = true;
            this.protectExplosions = true;
            this.protectFireSpread = true;
            this.protectLiquidFlow = true;
            this.protectBuckets = true;
            this.visualsEnabled = true;
            this.borderParticleRaw = "END_ROD";
            this.visualDurationSeconds = 8;
            this.visualIntervalTicks = 10;
            this.bypassActionBar = true;
            this.bypassActionBarCooldownMs = 2500;
            return;
        }

        this.enabled = root.getBoolean("enabled", true);
        ConfigurationSection storage = root.getConfigurationSection("storage");
        this.storageFile = storage != null ? storage.getString("file", "claims.db") : "claims.db";

        ConfigurationSection worlds = root.getConfigurationSection("worlds");
        this.worldsAllowedLower = lowerSet(worlds != null ? worlds.getStringList("allowed") : List.of());
        this.worldsBlockedLower = lowerSet(worlds != null ? worlds.getStringList("blocked") : List.of());

        ConfigurationSection creation = root.getConfigurationSection("creation");
        this.defaultRadiusX = Math.max(0, creation != null ? creation.getInt("default-radius-x", 8) : 8);
        this.defaultRadiusZ = Math.max(0, creation != null ? creation.getInt("default-radius-z", 8) : 8);
        this.minSizeX = Math.max(1, creation != null ? creation.getInt("min-size-x", 5) : 5);
        this.minSizeZ = Math.max(1, creation != null ? creation.getInt("min-size-z", 5) : 5);
        this.maxSizeX = Math.max(1, creation != null ? creation.getInt("max-size-x", 64) : 64);
        this.maxSizeZ = Math.max(1, creation != null ? creation.getInt("max-size-z", 64) : 64);
        this.preventOverlap = creation == null || creation.getBoolean("prevent-overlap", true);

        ConfigurationSection limits = root.getConfigurationSection("limits");
        this.defaultClaimLimit = Math.max(0, limits != null ? limits.getInt("default-claim-limit", 1) : 1);

        ConfigurationSection prot = root.getConfigurationSection("protection");
        this.protectBreak = prot == null || prot.getBoolean("block-break", true);
        this.protectPlace = prot == null || prot.getBoolean("block-place", true);
        this.protectContainers = prot == null || prot.getBoolean("containers", true);
        this.protectDoors = prot == null || prot.getBoolean("doors", true);
        this.protectRedstone = prot != null && prot.getBoolean("redstone-interaction", false);
        this.protectEntityDamage = prot == null || prot.getBoolean("entity-damage", true);
        this.protectItemFrames = prot == null || prot.getBoolean("item-frames", true);
        this.protectArmorStands = prot == null || prot.getBoolean("armor-stands", true);
        this.protectVehicles = prot == null || prot.getBoolean("vehicles", true);
        this.protectExplosions = prot == null || prot.getBoolean("explosions", true);
        this.protectFireSpread = prot == null || prot.getBoolean("fire-spread", true);
        this.protectLiquidFlow = prot == null || prot.getBoolean("liquid-flow", true);
        this.protectBuckets = prot == null || prot.getBoolean("buckets", true);

        ConfigurationSection vis = root.getConfigurationSection("visuals");
        this.visualsEnabled = vis == null || vis.getBoolean("enabled", true);
        this.borderParticleRaw = vis != null ? vis.getString("border-particle", "END_ROD") : "END_ROD";
        this.visualDurationSeconds = Math.max(1, vis != null ? vis.getInt("duration-seconds", 8) : 8);
        this.visualIntervalTicks = Math.max(1, vis != null ? vis.getInt("interval-ticks", 10) : 10);

        ConfigurationSection admin = root.getConfigurationSection("admin");
        this.bypassActionBar = admin == null || admin.getBoolean("bypass-actionbar", true);
        this.bypassActionBarCooldownMs = Math.max(500, admin != null ? admin.getLong("bypass-actionbar-cooldown-ms", 2500) : 2500);
    }

    private static Set<String> lowerSet(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            if (s != null && !s.isBlank()) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStorageFile() {
        return storageFile;
    }

    public boolean isWorldClaimable(String worldName) {
        if (worldName == null) {
            return false;
        }
        String w = worldName.toLowerCase(Locale.ROOT);
        if (!worldsAllowedLower.isEmpty() && !worldsAllowedLower.contains(w)) {
            return false;
        }
        return !worldsBlockedLower.contains(w);
    }

    public boolean isWorldClaimable(Player player) {
        if (player.getWorld() == null) {
            return false;
        }
        return isWorldClaimable(player.getWorld().getName());
    }

    public int getDefaultRadiusX() {
        return defaultRadiusX;
    }

    public int getDefaultRadiusZ() {
        return defaultRadiusZ;
    }

    public int getMinSizeX() {
        return minSizeX;
    }

    public int getMinSizeZ() {
        return minSizeZ;
    }

    public int getMaxSizeX() {
        return maxSizeX;
    }

    public int getMaxSizeZ() {
        return maxSizeZ;
    }

    public boolean isPreventOverlap() {
        return preventOverlap;
    }

    public int getDefaultClaimLimit() {
        return defaultClaimLimit;
    }

    public boolean isProtectBreak() {
        return protectBreak;
    }

    public boolean isProtectPlace() {
        return protectPlace;
    }

    public boolean isProtectContainers() {
        return protectContainers;
    }

    public boolean isProtectDoors() {
        return protectDoors;
    }

    public boolean isProtectRedstone() {
        return protectRedstone;
    }

    public boolean isProtectEntityDamage() {
        return protectEntityDamage;
    }

    public boolean isProtectItemFrames() {
        return protectItemFrames;
    }

    public boolean isProtectArmorStands() {
        return protectArmorStands;
    }

    public boolean isProtectVehicles() {
        return protectVehicles;
    }

    public boolean isProtectExplosions() {
        return protectExplosions;
    }

    public boolean isProtectFireSpread() {
        return protectFireSpread;
    }

    public boolean isProtectLiquidFlow() {
        return protectLiquidFlow;
    }

    public boolean isProtectBuckets() {
        return protectBuckets;
    }

    public boolean isVisualsEnabled() {
        return visualsEnabled;
    }

    public String getBorderParticleRaw() {
        return borderParticleRaw;
    }

    public int getVisualDurationSeconds() {
        return visualDurationSeconds;
    }

    public int getVisualIntervalTicks() {
        return visualIntervalTicks;
    }

    public boolean isBypassActionBar() {
        return bypassActionBar;
    }

    public long getBypassActionBarCooldownMs() {
        return bypassActionBarCooldownMs;
    }
}
