package de.deinserver.cpsmp.claims;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * Per-claim boolean flags (V4.1). Biome flags reserved for a future release.
 */
public enum ClaimFlag {

    PVP("pvp", "claim.flag.pvp", Material.DIAMOND_SWORD),
    MOB_DAMAGE("mob-damage", "claim.flag.mob-damage", Material.BONE),
    MOB_SPAWNING("mob-spawning", "claim.flag.mob-spawning", Material.SPAWNER),
    EXPLOSIONS("explosions", "claim.flag.explosions", Material.TNT),
    FIRE_SPREAD("fire-spread", "claim.flag.fire-spread", Material.FLINT_AND_STEEL),
    LIQUID_FLOW("liquid-flow", "claim.flag.liquid-flow", Material.WATER_BUCKET),
    CONTAINER_ACCESS("container-access", "claim.flag.container-access", Material.CHEST),
    DOOR_ACCESS("door-access", "claim.flag.door-access", Material.OAK_DOOR),
    REDSTONE_ACCESS("redstone-access", "claim.flag.redstone-access", Material.LEVER),
    ENTRY_DISPLAY("entry-display", "claim.flag.entry-display", Material.NAME_TAG),
    BORDER_DISPLAY("border-display", "claim.flag.border-display", Material.BEACON);

    private final String configKey;
    private final String messageKey;
    private final Material iconMaterial;

    ClaimFlag(@NotNull String configKey, @NotNull String messageKey, @NotNull Material iconMaterial) {
        this.configKey = configKey;
        this.messageKey = messageKey;
        this.iconMaterial = iconMaterial;
    }

    public @NotNull String configKey() {
        return configKey;
    }

    public @NotNull String messageKey() {
        return messageKey;
    }

    public @NotNull Material iconMaterial() {
        return iconMaterial;
    }

    public static @NotNull ClaimFlag byConfigKey(@NotNull String key) {
        String k = key.toLowerCase(Locale.ROOT);
        for (ClaimFlag f : values()) {
            if (f.configKey.equals(k)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown claim flag key: " + key);
    }
}
