package de.deinserver.cpsmp;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Non-blocking validation after {@link ConfigManager#load()}. Logs clear
 * German warnings for operators; never throws (production readiness).
 */
public final class ConfigHealthCheck {

    private ConfigHealthCheck() {
    }

    /**
     * Call after {@code configManager.reload()} so {@link CPSMPPlugin#getConfig()}
     * matches the loaded disk state.
     */
    public static void logWarnings(CPSMPPlugin plugin) {
        FileConfiguration cfg = plugin.getConfig();
        if (cfg == null) {
            return;
        }

        int minR = cfg.getInt("rtp.min-radius", 500);
        int maxR = cfg.getInt("rtp.max-radius", 5000);
        if (minR > maxR) {
            plugin.getLogger().warning("[CPSMP] Konfiguration: rtp.min-radius (" + minR
                    + ") ist groesser als rtp.max-radius (" + maxR
                    + "). Die Werte werden bei /rtp automatisch getauscht.");
        }

        long portalTicks = cfg.getLong("portals.check-interval-ticks", 5L);
        if (portalTicks < 1L) {
            plugin.getLogger().warning("[CPSMP] Konfiguration: portals.check-interval-ticks ist "
                    + portalTicks + " (ungueltig). Es wird mindestens 1 Tick verwendet.");
        }

        long portalCd = cfg.getLong("portals.global-cooldown-ms", 4000L);
        if (portalCd < 0L) {
            plugin.getLogger().warning("[CPSMP] Konfiguration: portals.global-cooldown-ms ist negativ. "
                    + "Verhalten kann unerwartet sein.");
        }

        FileConfiguration portals = plugin.getConfigManager().getPortals();
        if (portals != null && portals.getConfigurationSection("portals") == null) {
            plugin.getLogger().warning("[CPSMP] Konfiguration: portals.yml enthaelt keinen Abschnitt "
                    + "'portals'. Keine Portale werden geladen.");
        }

        String spawnWorld = cfg.getString("spawn.world", "world");
        if (spawnWorld != null && Bukkit.getWorld(spawnWorld) == null) {
            plugin.getLogger().warning("[CPSMP] Konfiguration: spawn.world '" + spawnWorld
                    + "' ist derzeit nicht geladen. /smpspawn kann fehlschlagen, bis die Welt existiert.");
        }
    }
}
