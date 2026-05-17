package de.deinserver.cpsmp.auction;

/**
 * Lifecycle states of an auction listing row in {@code auction_listings}.
 * The status field is the single source of truth for whether a listing
 * is still live, has been sold (V2.2+), has expired naturally, has been
 * cancelled by the seller, or has been administratively removed.
 *
 * <p>Status transitions are append-only in the sense that a listing never
 * returns to {@link #ACTIVE} once it has left that state. The expiry
 * service relies on this to stay idempotent: it only ever moves rows
 * away from {@code ACTIVE}, using a {@code WHERE status='ACTIVE'} guard.
 */
public enum AuctionListingStatus {
    ACTIVE,
    SOLD,
    EXPIRED,
    CANCELLED,
    REMOVED
}
