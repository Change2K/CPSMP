package de.deinserver.cpsmp.compat;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Logger;

/**
 * Detects which server platform CPSMP is running on and which Paper-only
 * APIs are actually available. The detection is reflective and happens
 * exactly once during plugin enable.
 *
 * <p>CPSMP is Paper-first. Paper exposes:
 * <ul>
 *     <li>{@code Player#teleportAsync(Location, TeleportCause)}</li>
 *     <li>{@code World#getChunkAtAsync(int, int)}</li>
 *     <li>{@code Plugin#getPluginMeta()}</li>
 * </ul>
 * When the plugin runs on Spigot or CraftBukkit those methods are absent
 * and the {@link TeleportAdapter} falls back to synchronous, main-thread
 * Bukkit APIs (see {@link BukkitTeleportAdapter}).
 */
public final class ServerCompatibility {

    private final boolean hasTeleportAsync;
    private final boolean hasChunkAtAsync;
    private final boolean hasPluginMeta;
    private final String serverFlavor;

    private ServerCompatibility(boolean hasTeleportAsync,
                                boolean hasChunkAtAsync,
                                boolean hasPluginMeta,
                                String serverFlavor) {
        this.hasTeleportAsync = hasTeleportAsync;
        this.hasChunkAtAsync = hasChunkAtAsync;
        this.hasPluginMeta = hasPluginMeta;
        this.serverFlavor = serverFlavor;
    }

    /**
     * Probes the current runtime for Paper-only entry points. Safe to call
     * exactly once during {@code onEnable}.
     */
    public static ServerCompatibility detect(Logger logger) {
        boolean teleportAsync = methodPresent(Player.class, "teleportAsync",
                org.bukkit.Location.class, org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.class);
        boolean chunkAsync = methodPresent(World.class, "getChunkAtAsync", int.class, int.class);
        boolean pluginMeta = methodPresent(Plugin.class, "getPluginMeta");
        String flavor = detectFlavor();

        if (logger != null) {
            logger.info("Detected server flavor: " + flavor);
            logger.info("Paper async teleport available: " + teleportAsync);
            logger.info("Paper async chunk load available: " + chunkAsync);
        }

        return new ServerCompatibility(teleportAsync, chunkAsync, pluginMeta, flavor);
    }

    private static boolean methodPresent(Class<?> owner, String name, Class<?>... params) {
        try {
            owner.getMethod(name, params);
            return true;
        } catch (NoSuchMethodException ex) {
            return false;
        }
    }

    /**
     * Best-effort server flavor detection. Used purely for logging /
     * {@code /cpsmpadmin info} output; behavior never branches on this.
     */
    private static String detectFlavor() {
        String name = Bukkit.getName();
        String version = Bukkit.getVersion();
        if (version != null && version.toLowerCase().contains("purpur")) return "Purpur";
        if (version != null && version.toLowerCase().contains("paper")) return "Paper";
        if (name != null && name.toLowerCase().contains("paper")) return "Paper";
        if (name != null && name.toLowerCase().contains("spigot")) return "Spigot";
        return name != null ? name : "Unknown";
    }

    public boolean hasTeleportAsync() {
        return hasTeleportAsync;
    }

    public boolean hasChunkAtAsync() {
        return hasChunkAtAsync;
    }

    public boolean hasPluginMeta() {
        return hasPluginMeta;
    }

    public String getServerFlavor() {
        return serverFlavor;
    }

    /**
     * Returns the plugin version through Paper's {@code PluginMeta} when
     * available, otherwise through the legacy {@code PluginDescriptionFile}.
     * Both calls are reflective so the class compiles cleanly against either
     * API surface.
     */
    @SuppressWarnings("deprecation")
    public String getPluginVersion(Plugin plugin) {
        if (hasPluginMeta) {
            try {
                Object meta = Plugin.class.getMethod("getPluginMeta").invoke(plugin);
                if (meta != null) {
                    Object version = meta.getClass().getMethod("getVersion").invoke(meta);
                    if (version != null) {
                        return version.toString();
                    }
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall through to legacy description.
            }
        }
        return plugin.getDescription().getVersion();
    }
}
