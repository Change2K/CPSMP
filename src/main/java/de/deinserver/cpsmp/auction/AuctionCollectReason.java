package de.deinserver.cpsmp.auction;

/**
 * Why an item ended up in a player's collect storage. Stored as a string
 * in {@code auction_collect_items.reason} so unknown values from a newer
 * CPSMP release can still be loaded into memory without crashing the
 * collect query (see {@link #fromStringOrDefault(String)}).
 */
public enum AuctionCollectReason {
    /** Seller used {@code /ah cancel}. */
    CANCELLED_LISTING,
    /** AuctionExpiryService moved an expired listing back to its seller. */
    EXPIRED_LISTING,
    /** Admin used {@code /ah admin remove}. */
    ADMIN_REMOVED,
    /**
     * V2.2+: a buyer purchased an item but their inventory was full at the
     * moment of delivery. Reserved; not produced by V2.1 code paths.
     */
    PURCHASED_ITEM_INVENTORY_FULL,
    /**
     * Catch-all for items the system returns for any other reason (e.g.
     * a failed transaction rollback). Reserved; not produced by V2.1
     * code paths.
     */
    SYSTEM_RETURN;

    /**
     * Lenient parse for values read from the database. Returns
     * {@link #SYSTEM_RETURN} when the stored value is unknown so the row
     * is still usable - the item itself is never lost just because the
     * reason string was written by a newer version of the plugin.
     */
    public static AuctionCollectReason fromStringOrDefault(String raw) {
        if (raw == null) return SYSTEM_RETURN;
        try {
            return AuctionCollectReason.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return SYSTEM_RETURN;
        }
    }
}
