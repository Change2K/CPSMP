package de.deinserver.cpsmp.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;
import java.util.UUID;

/**
 * Vault-backed {@link EconomyBridge}. Delegates to whichever Vault
 * economy provider is registered (EssentialsX Economy, CMI, TheNewEconomy
 * via its Vault hook, XConomy, etc.).
 *
 * <p>This class must only ever be loaded by the JVM when the Vault plugin
 * is present. {@link EconomyManager} guards instantiation with a runtime
 * presence check; if Vault is absent CPSMP never references this class
 * and therefore never tries to resolve {@code net.milkbowl.vault.*}.
 */
public final class VaultEconomyBridge implements EconomyBridge {

    private final Plugin plugin;
    private final Economy economy;

    private VaultEconomyBridge(Plugin plugin, Economy economy) {
        this.plugin = plugin;
        this.economy = economy;
    }

    /**
     * Attempts to look up a registered Vault economy provider via the
     * Bukkit {@code ServicesManager}. Returns {@code null} when no
     * provider is registered or when class resolution fails for any
     * reason - the caller should then fall back to {@link NoEconomyBridge}.
     *
     * <p>Guarded against {@link NoClassDefFoundError} so an unexpected
     * Vault classpath issue cannot crash {@code onEnable}.
     */
    @Nullable
    public static VaultEconomyBridge tryCreate(Plugin plugin) {
        try {
            RegisteredServiceProvider<Economy> rsp =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (rsp == null) {
                return null;
            }
            Economy economy = rsp.getProvider();
            if (economy == null) {
                return null;
            }
            return new VaultEconomyBridge(plugin, economy);
        } catch (Throwable t) {
            // Throwable covers both Exception and Error (e.g.
            // NoClassDefFoundError if a Vault class is unexpectedly missing).
            plugin.getLogger().warning("Vault economy lookup failed: " + t.getMessage());
            return null;
        }
    }

    @Override
    public boolean isAvailable() {
        // Some providers can be unregistered at runtime; re-check the service.
        try {
            return economy.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public EconomyProviderType providerType() {
        return EconomyProviderType.VAULT;
    }

    @Override
    public String providerName() {
        try {
            String name = economy.getName();
            return name != null && !name.isBlank() ? name : "Vault";
        } catch (Throwable t) {
            return "Vault";
        }
    }

    @Override
    public boolean hasBalance(UUID playerId, double amount) {
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            return economy.has(player, amount);
        } catch (Throwable t) {
            logDebug("hasBalance failed", t);
            return false;
        }
    }

    @Override
    public double getBalance(UUID playerId) {
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            return economy.getBalance(player);
        } catch (Throwable t) {
            logDebug("getBalance failed", t);
            return 0.0D;
        }
    }

    @Override
    public EconomyTransactionResult withdraw(UUID playerId, double amount, String reason) {
        if (amount < 0) {
            return EconomyTransactionResult.failure("economy.invalid-amount", null);
        }
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            double balanceBefore = economy.getBalance(player);
            if (!economy.has(player, amount)) {
                return EconomyTransactionResult.failure("economy.insufficient-funds", null);
            }
            EconomyResponse response = economy.withdrawPlayer(player, amount);
            return toResult(response, balanceBefore);
        } catch (Throwable t) {
            logDebug("withdraw failed", t);
            return EconomyTransactionResult.failure("economy.transaction-failed", t.getMessage());
        }
    }

    @Override
    public EconomyTransactionResult deposit(UUID playerId, double amount, String reason) {
        if (amount < 0) {
            return EconomyTransactionResult.failure("economy.invalid-amount", null);
        }
        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
            double balanceBefore = economy.getBalance(player);
            EconomyResponse response = economy.depositPlayer(player, amount);
            return toResult(response, balanceBefore);
        } catch (Throwable t) {
            logDebug("deposit failed", t);
            return EconomyTransactionResult.failure("economy.transaction-failed", t.getMessage());
        }
    }

    @Override
    public String format(double amount) {
        try {
            return economy.format(amount);
        } catch (Throwable t) {
            return String.format("%.2f", amount);
        }
    }

    private EconomyTransactionResult toResult(EconomyResponse response, double balanceBefore) {
        if (response == null) {
            return EconomyTransactionResult.failure("economy.transaction-failed", null);
        }
        if (response.transactionSuccess()) {
            return new EconomyTransactionResult(
                    true,
                    "economy.transaction-ok",
                    OptionalDouble.of(balanceBefore),
                    OptionalDouble.of(response.balance),
                    response.errorMessage);
        }
        // Map Vault's NOT_IMPLEMENTED / FAILURE outcomes onto a single reason
        // key so the German message is consistent across providers.
        String reasonKey = response.errorMessage != null
                && response.errorMessage.toLowerCase().contains("not enough")
                ? "economy.insufficient-funds"
                : "economy.transaction-failed";
        return EconomyTransactionResult.failure(reasonKey, response.errorMessage);
    }

    private void logDebug(String context, Throwable t) {
        plugin.getLogger().fine("[VaultEconomyBridge] " + context + ": " + t.getMessage());
    }
}
