package de.deinserver.cpsmp.claims;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loaded from {@code claims.flags} in {@code claims.yml}.
 */
public final class ClaimFlagsConfig {

    private final boolean enabled;
    private final EnumMap<ClaimFlag, Boolean> defaults;
    private final EnumMap<ClaimFlag, Boolean> allowedPlayerEdit;

    public ClaimFlagsConfig(@Nullable ConfigurationSection root) {
        if (root == null) {
            this.enabled = true;
            this.defaults = defaultDefaults();
            this.allowedPlayerEdit = defaultAllowedPlayerEdit();
            return;
        }
        this.enabled = root.getBoolean("enabled", true);
        this.defaults = readBoolMap(root.getConfigurationSection("defaults"), defaultDefaults());
        this.allowedPlayerEdit = readBoolMap(root.getConfigurationSection("allowed-player-edit"), defaultAllowedPlayerEdit());
    }

    private static EnumMap<ClaimFlag, Boolean> defaultDefaults() {
        EnumMap<ClaimFlag, Boolean> m = new EnumMap<>(ClaimFlag.class);
        m.put(ClaimFlag.PVP, false);
        m.put(ClaimFlag.MOB_DAMAGE, false);
        m.put(ClaimFlag.MOB_SPAWNING, false);
        m.put(ClaimFlag.EXPLOSIONS, false);
        m.put(ClaimFlag.FIRE_SPREAD, false);
        m.put(ClaimFlag.LIQUID_FLOW, false);
        m.put(ClaimFlag.CONTAINER_ACCESS, false);
        m.put(ClaimFlag.DOOR_ACCESS, false);
        m.put(ClaimFlag.REDSTONE_ACCESS, false);
        m.put(ClaimFlag.ENTRY_DISPLAY, true);
        m.put(ClaimFlag.BORDER_DISPLAY, true);
        return m;
    }

    private static EnumMap<ClaimFlag, Boolean> defaultAllowedPlayerEdit() {
        EnumMap<ClaimFlag, Boolean> m = new EnumMap<>(ClaimFlag.class);
        for (ClaimFlag f : ClaimFlag.values()) {
            m.put(f, true);
        }
        m.put(ClaimFlag.EXPLOSIONS, false);
        return m;
    }

    private static EnumMap<ClaimFlag, Boolean> readBoolMap(@Nullable ConfigurationSection sec,
                                                           EnumMap<ClaimFlag, Boolean> fallback) {
        EnumMap<ClaimFlag, Boolean> out = new EnumMap<>(ClaimFlag.class);
        for (ClaimFlag f : ClaimFlag.values()) {
            boolean def = fallback.getOrDefault(f, false);
            if (sec != null) {
                out.put(f, sec.getBoolean(f.configKey(), def));
            } else {
                out.put(f, def);
            }
        }
        return out;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean defaultValue(@NotNull ClaimFlag flag) {
        return defaults.getOrDefault(flag, false);
    }

    public boolean playerMayEdit(@NotNull ClaimFlag flag) {
        return allowedPlayerEdit.getOrDefault(flag, false);
    }

    public Map<ClaimFlag, Boolean> defaultsSnapshot() {
        return Map.copyOf(defaults);
    }
}
