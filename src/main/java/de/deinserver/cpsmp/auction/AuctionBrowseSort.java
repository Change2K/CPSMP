package de.deinserver.cpsmp.auction;

import java.util.Locale;

/**
 * Browse / market ordering. GUI cycles through these modes; SQL-backed
 * browse uses matching {@code ORDER BY} clauses where no search filter
 * is active.
 */
public enum AuctionBrowseSort {

    NEWEST,
    OLDEST,
    PRICE_ASC,
    PRICE_DESC,
    EXPIRING_SOON;

    public AuctionBrowseSort next() {
        return switch (this) {
            case NEWEST -> OLDEST;
            case OLDEST -> PRICE_ASC;
            case PRICE_ASC -> PRICE_DESC;
            case PRICE_DESC -> EXPIRING_SOON;
            case EXPIRING_SOON -> NEWEST;
        };
    }

    /** MiniMessage / messages.yml branch under {@code auction.sort-*}. */
    public String messageKey() {
        return switch (this) {
            case NEWEST -> "auction.sort-newest";
            case OLDEST -> "auction.sort-oldest";
            case PRICE_ASC -> "auction.sort-price-asc";
            case PRICE_DESC -> "auction.sort-price-desc";
            case EXPIRING_SOON -> "auction.sort-expiring";
        };
    }

    /**
     * Parses {@code auctionhouse.yml} {@code gui.browse.default-sort}
     * ({@code newest}, {@code oldest}, ...).
     */
    public static AuctionBrowseSort fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEWEST;
        }
        String n = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (n) {
            case "newest" -> NEWEST;
            case "oldest" -> OLDEST;
            case "price_asc", "priceasc" -> PRICE_ASC;
            case "price_desc", "pricedesc" -> PRICE_DESC;
            case "expiring", "expiring_soon", "expiringsoon" -> EXPIRING_SOON;
            default -> NEWEST;
        };
    }
}
