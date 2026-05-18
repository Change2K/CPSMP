package de.deinserver.cpsmp.claims;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
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
    private final ClaimVisualMode visualMode;
    private final ClaimVisualMode fallbackVisualMode;
    private final boolean allowWorldborderMode;
    private final boolean particlesPrimaryEnabled;
    private final boolean particlesFallbackEnabled;
    private final boolean showOnceIsToggle;
    private final boolean clearBorderOnWorldChange;
    private final String borderParticleRaw;
    private final int visualDurationSeconds;
    private final int visualIntervalTicks;
    private final int visualRefreshIntervalTicks;
    private final int maxVisibleParticlesPerTick;
    private final boolean particleStatic;
    private final Color dustPrimaryColor;
    private final Color dustSecondaryColor;
    private final List<Double> particleYOffsetRel;
    private final int particleLineStepBlocks;
    private final int showRadiusBlocks;
    private final boolean displayPrimaryEnabled;
    private final String displayLineMaterialName;
    private final String displayCornerMaterialName;
    private final List<Double> displayYOffsetRel;
    private final int displayLineStepBlocks;
    private final int maxDisplayEntitiesPerPlayer;
    private final float displayScale;
    private final boolean mergeEnabled;
    private final boolean mergeRequireSameWorld;
    private final boolean mergeAllowDiagonalTouch;
    private final boolean mergeRequireStandingInOwnedClaim;
    private final boolean plotAliasEnabled;
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
            this.defaultClaimLimit = 4;
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
            this.visualMode = ClaimVisualMode.DISPLAY;
            this.fallbackVisualMode = ClaimVisualMode.PARTICLES;
            this.allowWorldborderMode = false;
            this.particlesPrimaryEnabled = true;
            this.particlesFallbackEnabled = true;
            this.showOnceIsToggle = true;
            this.clearBorderOnWorldChange = true;
            this.showRadiusBlocks = 15;
            this.displayPrimaryEnabled = true;
            this.displayLineMaterialName = "LIGHT_BLUE_STAINED_GLASS";
            this.displayCornerMaterialName = "GOLD_BLOCK";
            this.displayYOffsetRel = List.of(0.1, 1.1);
            this.displayLineStepBlocks = 2;
            this.maxDisplayEntitiesPerPlayer = 300;
            this.displayScale = 0.15f;
            this.borderParticleRaw = "DUST";
            this.visualDurationSeconds = 20;
            this.visualIntervalTicks = 20;
            this.visualRefreshIntervalTicks = 20;
            this.maxVisibleParticlesPerTick = 120;
            this.particleStatic = true;
            this.dustPrimaryColor = parseHexColor("#f6d365");
            this.dustSecondaryColor = parseHexColor("#fda085");
            this.particleYOffsetRel = List.of(0.2, 1.2);
            this.particleLineStepBlocks = 1;
            this.mergeEnabled = true;
            this.mergeRequireSameWorld = true;
            this.mergeAllowDiagonalTouch = false;
            this.mergeRequireStandingInOwnedClaim = true;
            this.plotAliasEnabled = true;
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
        this.defaultClaimLimit = Math.max(0, limits != null ? limits.getInt("default-claim-limit", 4) : 4);

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
        this.allowWorldborderMode = vis != null && vis.getBoolean("allow-worldborder-mode", false);
        this.visualMode = ClaimVisualMode.fromConfig(vis != null ? vis.getString("mode", "display") : "display");
        this.fallbackVisualMode = ClaimVisualMode.fromConfig(vis != null ? vis.getString("fallback-mode", "particles") : "particles");
        this.particlesPrimaryEnabled = readParticlesPrimaryEnabled(vis);
        this.particlesFallbackEnabled = readParticlesFallbackEnabled(vis);
        this.showOnceIsToggle = vis != null && vis.getBoolean("show-once-is-toggle", true);
        this.clearBorderOnWorldChange = vis == null || vis.getBoolean("clear-on-world-change", true);
        this.showRadiusBlocks = Math.max(1, vis != null ? vis.getInt("show-radius-blocks", 15) : 15);
        ConfigurationSection dvis = vis != null ? vis.getConfigurationSection("display") : null;
        this.displayPrimaryEnabled = dvis == null || dvis.getBoolean("enabled", true);
        this.displayLineMaterialName = dvis != null ? dvis.getString("material", "LIGHT_BLUE_STAINED_GLASS") : "LIGHT_BLUE_STAINED_GLASS";
        this.displayCornerMaterialName = dvis != null ? dvis.getString("corner-material", "GOLD_BLOCK") : "GOLD_BLOCK";
        this.displayYOffsetRel = readDisplayYOffsetRel(dvis);
        this.displayLineStepBlocks = Math.max(1, dvis != null ? dvis.getInt("line-step-blocks", 2) : 2);
        this.maxDisplayEntitiesPerPlayer = Math.max(16, dvis != null ? dvis.getInt("max-display-entities-per-player", 300) : 300);
        this.displayScale = Math.max(0.05f, dvis != null ? (float) dvis.getDouble("scale", 0.15D) : 0.15f);
        ConfigurationSection pvis = vis != null ? vis.getConfigurationSection("particles") : null;
        if (pvis != null) {
            String particleKey = pvis.getString("particle", null);
            if (particleKey == null || particleKey.isBlank()) {
                particleKey = pvis.getString("border-particle",
                        vis != null ? vis.getString("border-particle", "DUST") : "DUST");
            }
            this.borderParticleRaw = particleKey;
            this.visualIntervalTicks = Math.max(1, pvis.getInt("interval-ticks",
                    vis != null ? vis.getInt("interval-ticks", 20) : 20));
            this.maxVisibleParticlesPerTick = Math.max(8, pvis.getInt("max-visible-particles-per-tick",
                    vis != null ? vis.getInt("max-visible-particles-per-tick", 120) : 120));
            this.particleStatic = pvis.getBoolean("static", true);
            this.dustPrimaryColor = parseHexColor(pvis.getString("color", "#f6d365"));
            this.dustSecondaryColor = parseHexColor(pvis.getString("secondary-color", "#fda085"));
            this.particleYOffsetRel = readYOffsetList(pvis.getList("y-offsets"));
            this.particleLineStepBlocks = Math.max(1, pvis.getInt("line-step-blocks", 1));
        } else if (vis != null) {
            this.borderParticleRaw = vis.getString("border-particle", "DUST");
            this.visualIntervalTicks = Math.max(1, vis.getInt("interval-ticks", 20));
            this.maxVisibleParticlesPerTick = Math.max(8, vis.getInt("max-visible-particles-per-tick", 120));
            this.particleStatic = true;
            this.dustPrimaryColor = parseHexColor("#f6d365");
            this.dustSecondaryColor = parseHexColor("#fda085");
            this.particleYOffsetRel = List.of(0.2, 1.2);
            this.particleLineStepBlocks = 1;
        } else {
            this.borderParticleRaw = "DUST";
            this.visualIntervalTicks = 20;
            this.maxVisibleParticlesPerTick = 120;
            this.particleStatic = true;
            this.dustPrimaryColor = parseHexColor("#f6d365");
            this.dustSecondaryColor = parseHexColor("#fda085");
            this.particleYOffsetRel = List.of(0.2, 1.2);
            this.particleLineStepBlocks = 1;
        }
        this.visualDurationSeconds = Math.max(1, vis != null ? vis.getInt("duration-seconds", 20) : 20);
        this.visualRefreshIntervalTicks = Math.max(1, vis != null
                ? vis.getInt("refresh-interval-ticks", vis.getInt("toggle-interval-ticks", 20))
                : 20);

        ConfigurationSection plotAlias = root.getConfigurationSection("plot-alias");
        this.plotAliasEnabled = plotAlias == null || plotAlias.getBoolean("enabled", true);

        ConfigurationSection merge = root.getConfigurationSection("merge");
        this.mergeEnabled = merge == null || merge.getBoolean("enabled", true);
        this.mergeRequireSameWorld = merge == null || merge.getBoolean("require-same-world", true);
        this.mergeAllowDiagonalTouch = merge != null && merge.getBoolean("allow-diagonal-touch", false);
        this.mergeRequireStandingInOwnedClaim = merge == null || merge.getBoolean("require-standing-in-owned-claim", true);

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

    private static boolean readParticlesPrimaryEnabled(ConfigurationSection vis) {
        if (vis == null) {
            return true;
        }
        ConfigurationSection p = vis.getConfigurationSection("particles");
        if (p != null) {
            return p.getBoolean("enabled", true);
        }
        return true;
    }

    private static List<Double> readDisplayYOffsetRel(@Nullable ConfigurationSection dvis) {
        if (dvis == null) {
            return List.of(0.1, 1.1);
        }
        List<?> raw = dvis.getList("y-offsets");
        if (raw == null || raw.isEmpty()) {
            return List.of(0.1, 1.1);
        }
        return readYOffsetList(raw);
    }

    private static List<Double> readYOffsetList(@Nullable List<?> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of(0.2, 1.2);
        }
        List<Double> out = new ArrayList<>();
        for (Object o : raw) {
            if (o instanceof Number n) {
                out.add(n.doubleValue());
            }
        }
        return out.isEmpty() ? List.of(0.2, 1.2) : List.copyOf(out);
    }

    private static @Nullable Color parseHexColor(@Nullable String hex) {
        if (hex == null || hex.isBlank()) {
            return Color.fromRGB(246, 211, 101);
        }
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (h.length() != 6) {
            return Color.fromRGB(246, 211, 101);
        }
        try {
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return Color.fromRGB(r, g, b);
        } catch (NumberFormatException ex) {
            return Color.fromRGB(246, 211, 101);
        }
    }

    private static boolean readParticlesFallbackEnabled(ConfigurationSection vis) {
        if (vis == null) {
            return true;
        }
        ConfigurationSection p = vis.getConfigurationSection("particles");
        if (p != null) {
            if (p.contains("enabled-as-fallback")) {
                return p.getBoolean("enabled-as-fallback", true);
            }
            return p.getBoolean("enabled", true);
        }
        return true;
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

    public ClaimVisualMode getVisualMode() {
        return visualMode;
    }

    public ClaimVisualMode getFallbackVisualMode() {
        return fallbackVisualMode;
    }

    /**
     * When false (default), per-player {@link org.bukkit.entity.Player#setWorldBorder} is never used for claim outlines
     * because it restricts movement. Requires explicit opt-in.
     */
    public boolean isAllowWorldborderMode() {
        return allowWorldborderMode;
    }

    public boolean isParticlesPrimaryEnabled() {
        return particlesPrimaryEnabled;
    }

    public boolean isParticlesFallbackEnabled() {
        return particlesFallbackEnabled;
    }

    public boolean isShowOnceIsToggle() {
        return showOnceIsToggle;
    }

    public boolean isClearBorderOnWorldChange() {
        return clearBorderOnWorldChange;
    }

    public int getVisualRefreshIntervalTicks() {
        return visualRefreshIntervalTicks;
    }

    public int getMaxVisibleParticlesPerTick() {
        return maxVisibleParticlesPerTick;
    }

    public boolean isParticleStatic() {
        return particleStatic;
    }

    public Color getDustPrimaryColor() {
        return dustPrimaryColor;
    }

    public Color getDustSecondaryColor() {
        return dustSecondaryColor;
    }

    public List<Double> getParticleYOffsetRel() {
        return particleYOffsetRel;
    }

    public int getParticleLineStepBlocks() {
        return particleLineStepBlocks;
    }

    public int getShowRadiusBlocks() {
        return showRadiusBlocks;
    }

    public boolean isDisplayPrimaryEnabled() {
        return displayPrimaryEnabled;
    }

    public String getDisplayLineMaterialName() {
        return displayLineMaterialName;
    }

    public String getDisplayCornerMaterialName() {
        return displayCornerMaterialName;
    }

    public List<Double> getDisplayYOffsetRel() {
        return displayYOffsetRel;
    }

    public int getDisplayLineStepBlocks() {
        return displayLineStepBlocks;
    }

    public int getMaxDisplayEntitiesPerPlayer() {
        return maxDisplayEntitiesPerPlayer;
    }

    public float getDisplayScale() {
        return displayScale;
    }

    public boolean isMergeEnabled() {
        return mergeEnabled;
    }

    public boolean isMergeRequireSameWorld() {
        return mergeRequireSameWorld;
    }

    public boolean isMergeAllowDiagonalTouch() {
        return mergeAllowDiagonalTouch;
    }

    public boolean isMergeRequireStandingInOwnedClaim() {
        return mergeRequireStandingInOwnedClaim;
    }

    public boolean isPlotAliasEnabled() {
        return plotAliasEnabled;
    }

    public boolean isBypassActionBar() {
        return bypassActionBar;
    }

    public long getBypassActionBarCooldownMs() {
        return bypassActionBarCooldownMs;
    }
}
