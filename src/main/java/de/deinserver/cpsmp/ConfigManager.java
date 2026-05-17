package de.deinserver.cpsmp;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;

/**
 * Loads, saves and re-loads the configuration files used by CPSMP:
 * {@code config.yml}, {@code messages.yml}, {@code portals.yml},
 * {@code zones.yml}, {@code economy.yml} and {@code auctionhouse.yml}.
 * Each external file is created from the bundled default on first
 * launch.
 */
public final class ConfigManager {

    private static final String MESSAGES_FILE = "messages.yml";
    private static final String PORTALS_FILE = "portals.yml";
    private static final String ZONES_FILE = "zones.yml";
    private static final String ECONOMY_FILE = "economy.yml";
    private static final String AUCTION_FILE = "auctionhouse.yml";

    private final CPSMPPlugin plugin;

    private File messagesFile;
    private File portalsFile;
    private File zonesFile;
    private File economyFile;
    private File auctionFile;

    private FileConfiguration messages;
    private FileConfiguration portals;
    private FileConfiguration zones;
    private FileConfiguration economy;
    private FileConfiguration auction;

    public ConfigManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();

        this.messagesFile = ensureFile(MESSAGES_FILE);
        this.portalsFile = ensureFile(PORTALS_FILE);
        this.zonesFile = ensureFile(ZONES_FILE);
        this.economyFile = ensureFile(ECONOMY_FILE);
        this.auctionFile = ensureFile(AUCTION_FILE);

        this.messages = loadWithDefaults(messagesFile, MESSAGES_FILE);
        this.portals = loadWithDefaults(portalsFile, PORTALS_FILE);
        this.zones = loadWithDefaults(zonesFile, ZONES_FILE);
        this.economy = loadWithDefaults(economyFile, ECONOMY_FILE);
        this.auction = loadWithDefaults(auctionFile, AUCTION_FILE);
    }

    public void reload() {
        load();
    }

    private File ensureFile(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        return file;
    }

    private FileConfiguration loadWithDefaults(File file, String resourceName) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        InputStream defaults = plugin.getResource(resourceName);
        if (defaults != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            cfg.options().copyDefaults(true);
        }
        return cfg;
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public FileConfiguration getPortals() {
        return portals;
    }

    public FileConfiguration getZones() {
        return zones;
    }

    public FileConfiguration getEconomy() {
        return economy;
    }

    public FileConfiguration getAuction() {
        return auction;
    }

    public void savePortals() {
        save(portals, portalsFile);
    }

    public void saveZones() {
        save(zones, zonesFile);
    }

    public void saveMessages() {
        save(messages, messagesFile);
    }

    public void saveEconomy() {
        save(economy, economyFile);
    }

    public void saveAuction() {
        save(auction, auctionFile);
    }

    private void save(FileConfiguration cfg, File file) {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save " + file.getName(), e);
        }
    }
}
