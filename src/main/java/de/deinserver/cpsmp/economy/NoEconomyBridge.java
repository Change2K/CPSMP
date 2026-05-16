package de.deinserver.cpsmp.economy;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.UUID;

/**
 * Null-object bridge used when no economy provider is available. Every
 * write operation returns a clean {@link EconomyTransactionResult#failure}
 * with a {@code messages.yml} reason key, so callers can show a German
 * message without special-casing the bridge type.
 *
 * <p>{@link #format(double)} uses {@code economy.fallback-currency-symbol}
 * from {@code economy.yml} so console / debug output still looks reasonable.
 */
public final class NoEconomyBridge implements EconomyBridge {

    private final String fallbackSymbol;

    public NoEconomyBridge(FileConfiguration economyConfig) {
        String symbol = economyConfig != null
                ? economyConfig.getString("economy.fallback-currency-symbol", "$")
                : "$";
        this.fallbackSymbol = symbol;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public EconomyProviderType providerType() {
        return EconomyProviderType.NONE;
    }

    @Override
    public String providerName() {
        return "None";
    }

    @Override
    public boolean hasBalance(UUID playerId, double amount) {
        return false;
    }

    @Override
    public double getBalance(UUID playerId) {
        return 0.0D;
    }

    @Override
    public EconomyTransactionResult withdraw(UUID playerId, double amount, String reason) {
        return EconomyTransactionResult.failure("economy.unavailable", null);
    }

    @Override
    public EconomyTransactionResult deposit(UUID playerId, double amount, String reason) {
        return EconomyTransactionResult.failure("economy.unavailable", null);
    }

    @Override
    public String format(double amount) {
        return String.format(Locale.ROOT, "%.2f %s", amount, fallbackSymbol);
    }
}
