package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Helpers for reading and writing {@link Location} objects to a
 * {@link ConfigurationSection}. All methods are world-safe: if a world is not
 * loaded the read methods return {@code null} instead of throwing.
 */
public final class LocationSerializer {

    private LocationSerializer() {
    }

    /**
     * Writes the location into the given section. Existing keys are overwritten.
     */
    public static void write(ConfigurationSection section, Location location) {
        section.set("world", location.getWorld() != null ? location.getWorld().getName() : null);
        section.set("x", location.getX());
        section.set("y", location.getY());
        section.set("z", location.getZ());
        section.set("yaw", (double) location.getYaw());
        section.set("pitch", (double) location.getPitch());
    }

    /**
     * Reads a location from the section. Returns {@code null} when required
     * fields are missing or the world is not loaded.
     */
    @Nullable
    public static Location read(@Nullable ConfigurationSection section) {
        if (section == null) {
            return null;
        }
        String worldName = section.getString("world");
        if (worldName == null || worldName.isBlank()) {
            return null;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        double x = section.getDouble("x");
        double y = section.getDouble("y");
        double z = section.getDouble("z");
        float yaw = (float) section.getDouble("yaw", 0.0D);
        float pitch = (float) section.getDouble("pitch", 0.0D);
        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Reads a location and falls back to the world spawn when the saved entry
     * is missing or invalid but the world is known.
     */
    @Nullable
    public static Location readOrWorldSpawn(@Nullable ConfigurationSection section, @Nullable String fallbackWorld) {
        Location parsed = read(section);
        if (parsed != null) {
            return parsed;
        }
        if (fallbackWorld == null) {
            return null;
        }
        World world = Bukkit.getWorld(fallbackWorld);
        return world != null ? world.getSpawnLocation() : null;
    }
}
