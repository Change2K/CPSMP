package de.deinserver.cpsmp;

import de.deinserver.cpsmp.auction.AuctionCommand;
import de.deinserver.cpsmp.auction.AuctionHouseManager;
import de.deinserver.cpsmp.compat.BukkitTeleportAdapter;
import de.deinserver.cpsmp.compat.PaperTeleportAdapter;
import de.deinserver.cpsmp.compat.ServerCompatibility;
import de.deinserver.cpsmp.compat.TeleportAdapter;
import de.deinserver.cpsmp.economy.EconomyManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * CPSMP main class. Builds the dependency graph, registers commands and
 * listeners, and exposes shared helpers used across the plugin (spawn lookup,
 * persistent spawn write, global reload).
 *
 * <p>V2.1 adds the Auction House backend ({@link AuctionHouseManager},
 * {@link AuctionCommand}). Homes, TPA and Claims are still intentionally
 * out of scope and planned for later versions.
 */
public final class CPSMPPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private CooldownManager cooldowns;

    private ServerCompatibility serverCompatibility;
    private TeleportAdapter teleportAdapter;
    private EconomyManager economyManager;
    private TeleportService teleportService;
    private RTPService rtpService;
    private PortalManager portalManager;
    private PortalListener portalListener;
    private ZoneManager zoneManager;
    private ZoneListener zoneListener;
    private AuctionHouseManager auctionHouseManager;

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

        // Economy is the foundation for the Auction House. When no
        // provider is registered the manager installs a NoEconomyBridge
        // that fails every transaction with a German reason key; the
        // Auction House then refuses listing creation when an economy
        // is required (auctionhouse.yml / economy.yml).
        this.economyManager = new EconomyManager(this);
        this.economyManager.load();

        // Auction House (V2.1) is initialised after the economy bridge
        // so /ah sell can consult the bridge for listing fees on the
        // very first call. The manager handles its own storage-error
        // fallback (logs SEVERE and disables itself) so AH problems do
        // not interrupt the rest of CPSMP startup.
        this.auctionHouseManager = new AuctionHouseManager(this);
        this.auctionHouseManager.enable();

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

        // /spawn is intentionally NOT registered: the bare /spawn name is
        // reserved for another plugin on the network. CPSMP exposes only
        // /smpspawn for its own spawn teleport.
        registerCommand("smpspawn", new SpawnCommand(this));
        registerCommand("rtp", new RTPCommand(this));
        AdminCommand adminCommand = new AdminCommand(this);
        PluginCommand admin = getCommand("cpsmpadmin");
        if (admin != null) {
            admin.setExecutor(adminCommand);
            admin.setTabCompleter(adminCommand);
        }
        AuctionCommand auctionCommand = new AuctionCommand(this, auctionHouseManager);
        PluginCommand ah = getCommand("ah");
        if (ah != null) {
            ah.setExecutor(auctionCommand);
            ah.setTabCompleter(auctionCommand);
        }

        getLogger().info("CPSMP V2.1 enabled.");
    }

    @Override
    public void onDisable() {
        if (portalListener != null) portalListener.shutdown();
        if (zoneListener != null) zoneListener.shutdown();
        if (teleportService != null) teleportService.shutdown();
        // Disable the Auction House before the rest so it can drain
        // pending DB work and close its SQLite handle cleanly.
        if (auctionHouseManager != null) auctionHouseManager.disable();
        getLogger().info("CPSMP V2.1 disabled.");
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
        if (economyManager != null) {
            economyManager.load();
        }
        // The Auction House reload is non-destructive when possible
        // (hot-swap of the live AuctionConfig and a restart of the
        // expiry task). A change to storage type or file forces a
        // full disable/enable cycle inside the manager.
        if (auctionHouseManager != null) {
            auctionHouseManager.reload();
        }
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
    public EconomyManager getEconomyManager() { return economyManager; }
    public AuctionHouseManager getAuctionHouseManager() { return auctionHouseManager; }
}
