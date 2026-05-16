package de.deinserver.cpsmp.economy;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

/**
 * Resolves and owns the currently active {@link EconomyBridge}. Selection
 * is performed once during {@code onEnable} and re-evaluated on reload:
 *
 * <ol>
 *     <li>If {@code economy.enabled} is false → {@link NoEconomyBridge}.</li>
 *     <li>If {@code economy.preferred-provider == "vault"} and the Vault
 *         plugin is loaded and a Vault economy provider is registered →
 *         {@link VaultEconomyBridge}.</li>
 *     <li>Otherwise → {@link NoEconomyBridge}.</li>
 * </ol>
 *
 * <p>All branches are non-throwing. If Vault classes cannot be resolved
 * for any reason the manager logs a warning and falls back to the
 * null-object bridge so the server stays up.
 *
 * <p>This class is the only place that decides which bridge implementation
 * exists. Future economy systems (Reserve, etc.) plug in here.
 */
public final class EconomyManager {

    private final CPSMPPlugin plugin;
    private EconomyBridge bridge;

    public EconomyManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
        this.bridge = new NoEconomyBridge(null);
    }

    /** Re-evaluates the active bridge. Safe to call multiple times. */
    public void load() {
        FileConfiguration economyConfig = plugin.getConfigManager().getEconomy();
        boolean enabled = economyConfig == null || economyConfig.getBoolean("economy.enabled", true);
        if (!enabled) {
            this.bridge = new NoEconomyBridge(economyConfig);
            logActive("economy.disabled-in-config");
            return;
        }

        String preferred = economyConfig != null
                ? economyConfig.getString("economy.preferred-provider", "vault")
                : "vault";

        EconomyBridge chosen = null;
        if ("vault".equalsIgnoreCase(preferred)) {
            chosen = tryVault();
        }
        // Reserve / custom providers would slot in here as additional probes.

        if (chosen == null) {
            this.bridge = new NoEconomyBridge(economyConfig);
            logActive(vaultPresent()
                    ? "economy.vault-no-provider"
                    : "economy.vault-not-installed");
            return;
        }
        this.bridge = chosen;
        plugin.getLogger().info(plain("economy.detected",
                Map.of("provider", bridge.providerName())));
    }

    /**
     * Probes for Vault and a registered Vault economy provider. Done in a
     * separate method (and after an explicit {@link #vaultPresent()} check)
     * so the JVM only loads {@link VaultEconomyBridge} - and therefore the
     * Vault classes it references - when Vault is actually installed.
     */
    private EconomyBridge tryVault() {
        if (!vaultPresent()) {
            return null;
        }
        try {
            return VaultEconomyBridge.tryCreate(plugin);
        } catch (Throwable t) {
            // Throwable covers both Exception and Error (including
            // NoClassDefFoundError if a Vault class is unexpectedly missing).
            plugin.getLogger().warning("Vault detected but bridge could not be created: " + t.getMessage());
            return null;
        }
    }

    private boolean vaultPresent() {
        return Bukkit.getPluginManager().getPlugin("Vault") != null;
    }

    private void logActive(String reasonKey) {
        plugin.getLogger().info(plain("economy.none"));
        plugin.getLogger().info(plain(reasonKey));
        plugin.getLogger().info(plain("economy.features-disabled"));
    }

    private String plain(String path) {
        return plugin.getMessageManager().plain(path);
    }

    private String plain(String path, Map<String, String> placeholders) {
        return plugin.getMessageManager().plain(path, placeholders);
    }

    public EconomyBridge getBridge() {
        return bridge;
    }

    public boolean isAvailable() {
        return bridge.isAvailable();
    }
}
