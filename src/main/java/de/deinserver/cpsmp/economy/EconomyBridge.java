package de.deinserver.cpsmp.economy;

import java.util.UUID;

/**
 * CPSMP's economy abstraction. Every economy-dependent feature (future
 * Auction House, etc.) talks to this interface instead of a specific
 * provider plugin. Implementations live in this package:
 *
 * <ul>
 *     <li>{@link NoEconomyBridge} - no provider available; safe fallback
 *         that always reports unavailability.</li>
 *     <li>{@link VaultEconomyBridge} - delegates to a Vault economy provider.</li>
 * </ul>
 *
 * <p>Design rules:
 * <ul>
 *     <li>All player references are by {@link UUID}. If the underlying
 *         provider requires an {@code OfflinePlayer}, the bridge resolves
 *         it via {@code Bukkit.getOfflinePlayer(UUID)}.</li>
 *     <li>Methods never throw for "no provider"; they return {@code false}
 *         / {@code 0.0} / an unsuccessful {@link EconomyTransactionResult}.</li>
 *     <li>Implementations must be safe to call on the main server thread.
 *         Callers that may be off-thread should hop back via the scheduler
 *         before invoking the bridge.</li>
 * </ul>
 */
public interface EconomyBridge {

    /**
     * Whether economy operations on this bridge can actually move money.
     * {@code false} for {@link NoEconomyBridge} and for bridges whose
     * provider became unavailable after startup.
     */
    boolean isAvailable();

    /** High-level provider family. Used for diagnostics only. */
    EconomyProviderType providerType();

    /**
     * Human-readable provider name, e.g. {@code "EssentialsX"} when Vault
     * is bound to EssentialsX, or {@code "None"} for the null bridge.
     * Used in /cpsmpadmin info.
     */
    String providerName();

    /** True if the player has at least {@code amount} available. */
    boolean hasBalance(UUID playerId, double amount);

    /** Current balance, or {@code 0.0} when unknown. */
    double getBalance(UUID playerId);

    /**
     * Withdraws {@code amount} from the player. {@code reason} is a free-text
     * tag for provider-side audit logs (some providers ignore it).
     */
    EconomyTransactionResult withdraw(UUID playerId, double amount, String reason);

    /** Deposits {@code amount} into the player's account. */
    EconomyTransactionResult deposit(UUID playerId, double amount, String reason);

    /**
     * Formats an amount for display, ideally using the provider's own
     * currency formatting (e.g. {@code "1,000.00 $"}). Falls back to a
     * configurable symbol when no provider is available.
     */
    String format(double amount);
}
