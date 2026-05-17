package de.deinserver.cpsmp.auction;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a row in {@code auction_listings}. The status is
 * captured by value at read-time; mutation always goes through
 * {@link AuctionStorage} and re-fetches the row.
 *
 * <p>{@code itemStack} is a defensively-cloned stack; callers may modify
 * it without affecting the storage layer.
 *
 * @param listingId   primary key in {@code auction_listings} (AUTOINCREMENT)
 * @param sellerUuid  UUID of the seller (the player who ran {@code /ah sell})
 * @param sellerName  display name of the seller at listing time; used
 *                    for read-only views in /ah listings and admin info
 * @param itemStack   the listed item (cloned)
 * @param price       sale price in the active economy currency
 * @param createdAt   epoch millis when the row was inserted
 * @param expiresAt   epoch millis after which AuctionExpiryService will
 *                    move this listing to the seller's collect storage
 * @param status      current lifecycle state (see {@link AuctionListingStatus})
 * @param buyerUuid   buyer UUID, {@code null} until a V2.2 sale fills it in
 * @param buyerName   buyer display name at sale time, {@code null} until
 *                    a V2.2 sale fills it in
 */
public record AuctionListing(
        long listingId,
        UUID sellerUuid,
        String sellerName,
        ItemStack itemStack,
        double price,
        long createdAt,
        long expiresAt,
        AuctionListingStatus status,
        @Nullable UUID buyerUuid,
        @Nullable String buyerName
) {

    /**
     * Milliseconds remaining until {@link #expiresAt}, relative to
     * {@code now}. Negative values are clamped to 0 so callers can
     * blindly format the result.
     */
    public long remainingMillis(long now) {
        long delta = expiresAt - now;
        return Math.max(delta, 0L);
    }
}
