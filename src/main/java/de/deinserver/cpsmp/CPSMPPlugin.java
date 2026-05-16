package de.deinserver.cpsmp;

import de.deinserver.cpsmp.compat.BukkitTeleportAdapter;
import de.deinserver.cpsmp.compat.PaperTeleportAdapter;
import de.deinserver.cpsmp.compat.ServerCompatibility;
import de.deinserver.cpsmp.compat.TeleportAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * CPSMP V1 main class. Builds the dependency graph, registers commands and
 * listeners, and exposes shared helpers used across the plugin (spawn lookup,
 * persistent spawn write, global reload).
 *
 * <p>V1 scope intentionally excludes Auction House, Homes, TPA and Claims.
 * Those are planned for later versions.
 */
public final class CPSMPPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private CooldownManager cooldowns;

    private ServerCompatibility serverCompatibility;
    private TeleportAdapter teleportAdapter;
    private TeleportService teleportService;
    private RTPService rtpService;
    private PortalManager portalManager;
    private PortalListener portalListener;
    private ZoneManager zoneManager;
    private ZoneListener zoneListener;

    @Override
    public void onEnable() {
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        this.messageManager = new MessageManager(this);
        this.messageManager.reload();

        this.cooldowns = new CooldownManager();

        // Detect platform capabilities once and pick the appropriate teleport
        // backend. Paper is preferred; Spigot/CraftBukkit use the sync fallback.
        this.serverCompatibility = ServerCompatibility.detect(getLogger());
        this.teleportAdapter = (serverCompatibility.hasTeleportAsync()
                && serverCompatibility.hasChunkAtAsync())
                ? new PaperTeleportAdapter()
                : new BukkitTeleportAdapter(this);
        getLogger().info("Teleport backend: " + teleportAdapter.name());

        this.teleportService = new TeleportService(this);
        this.teleportService.register();

        this.rtpService = new RTPService(this);
        this.rtpService.reload();

        this.portalManager = new PortalManager(this);
        this.portalManager.load();

        this.zoneManager = new ZoneManager(this);
        this.zoneManager.load();

        this.portalListener = new PortalListener(this);
        this.portalListener.register();

        this.zoneListener = new ZoneListener(this);
        this.zoneListener.register();

        registerCommand("spawn", new SpawnCommand(this));
        registerCommand("smpspawn", new SpawnCommand(this));
        registerCommand("rtp", new RTPCommand(this));
        AdminCommand adminCommand = new AdminCommand(this);
        PluginCommand admin = getCommand("cpsmpadmin");
        if (admin != null) {
            admin.setExecutor(adminCommand);
            admin.setTabCompleter(adminCommand);
        }

        getLogger().info("CPSMP V1 enabled.");
    }

    @Override
    public void onDisable() {
        if (portalListener != null) portalListener.shutdown();
        if (zoneListener != null) zoneListener.shutdown();
        if (teleportService != null) teleportService.shutdown();
        getLogger().info("CPSMP V1 disabled.");
    }

    private void registerCommand(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is not declared in plugin.yml.");
            return;
        }
        command.setExecutor(executor);
    }

    /**
     * Re-reads all configuration files and rebuilds in-memory caches. Called
     * by {@code /cpsmpadmin reload}.
     */
    public void reloadEverything() {
        configManager.reload();
        messageManager.reload();
        rtpService.reload();
        portalManager.load();
        zoneManager.load();
    }

    /**
     * Persists the SMP spawn to config.yml using the player's current location.
     */
    public void persistSpawn(Location location) {
        if (location.getWorld() == null) return;
        ConfigurationSection section = getConfig().getConfigurationSection("spawn.location");
        if (section == null) {
            section = getConfig().createSection("spawn.location");
        }
        section.set("enabled", true);
        LocationSerializer.write(section, location);
        // Mirror the world into the top-level spawn.world for convenience.
        getConfig().set("spawn.world", location.getWorld().getName());
        saveConfig();
    }

    /**
     * Resolves the configured SMP spawn. Falls back to the world spawn of the
     * configured spawn world if no explicit location is saved. Returns null
     * when neither is available (e.g. world is not loaded).
     */
    @Nullable
    public Location resolveSpawnLocation() {
        ConfigurationSection section = getConfig().getConfigurationSection("spawn.location");
        if (section != null && section.getBoolean("enabled", false)) {
            Location parsed = LocationSerializer.read(section);
            if (parsed != null) {
                return parsed;
            }
        }
        String worldName = getConfig().getString("spawn.world", "world");
        World world = Bukkit.getWorld(worldName);
        return world != null ? world.getSpawnLocation() : null;
    }

    // --- Accessors used by other components -------------------------------

    public ConfigManager getConfigManager() { return configManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public CooldownManager getCooldowns() { return cooldowns; }
    public TeleportService getTeleportService() { return teleportService; }
    public RTPService getRtpService() { return rtpService; }
    public PortalManager getPortalManager() { return portalManager; }
    public ZoneManager getZoneManager() { return zoneManager; }
    public ServerCompatibility getServerCompatibility() { return serverCompatibility; }
    public TeleportAdapter getTeleportAdapter() { return teleportAdapter; }
}
