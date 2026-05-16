package de.deinserver.cpsmp.economy;

/**
 * High-level family of an economy bridge. Used purely for diagnostics and
 * for /cpsmpadmin info output; CPSMP code branches on {@link EconomyBridge}
 * behavior rather than this enum.
 *
 * <p>{@link #RESERVE} is reserved for a future Reserve API bridge and is
 * not currently wired up. {@link #CUSTOM} is reserved for downstream
 * extensions that may register their own bridge implementation.
 */
public enum EconomyProviderType {
    NONE,
    VAULT,
    RESERVE,
    CUSTOM
}
