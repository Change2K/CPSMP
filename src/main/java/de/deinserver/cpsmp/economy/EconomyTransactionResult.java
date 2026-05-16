package de.deinserver.cpsmp.economy;

import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Outcome of a {@link EconomyBridge#withdraw} or {@link EconomyBridge#deposit}
 * call. Immutable. Callers branch on {@link #success()} and use
 * {@link #reasonKey()} as a {@code messages.yml} lookup path to display a
 * configurable German message to the player.
 *
 * <p>{@link #balanceBefore()} and {@link #balanceAfter()} are best-effort:
 * not every provider exposes both values, so callers must tolerate empty
 * optionals.
 *
 * @param success         whether the provider reported the transaction
 *                        as completed successfully
 * @param reasonKey       {@code messages.yml} path describing the outcome
 *                        (e.g. {@code "economy.insufficient-funds"})
 * @param balanceBefore   the balance before the transaction, when known
 * @param balanceAfter    the balance after the transaction, when known
 * @param providerMessage raw text returned by the underlying provider,
 *                        useful for logging; never shown to players
 */
public record EconomyTransactionResult(
        boolean success,
        String reasonKey,
        OptionalDouble balanceBefore,
        OptionalDouble balanceAfter,
        @Nullable String providerMessage
) {

    /** Convenience builder for a success result without provider context. */
    public static EconomyTransactionResult ok(double balanceBefore, double balanceAfter) {
        return new EconomyTransactionResult(
                true,
                "economy.transaction-ok",
                OptionalDouble.of(balanceBefore),
                OptionalDouble.of(balanceAfter),
                null);
    }

    /** Convenience builder for a generic failure. */
    public static EconomyTransactionResult failure(String reasonKey, @Nullable String providerMessage) {
        return new EconomyTransactionResult(
                false,
                reasonKey,
                OptionalDouble.empty(),
                OptionalDouble.empty(),
                providerMessage);
    }
}
