package de.deinserver.cpsmp.auction;

import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence contract for the Auction House. The interface is
 * synchronous - every method blocks the calling thread - and is
 * intended to be invoked from {@link AuctionHouseManager}'s background
 * executor, never directly from the main server thread.
 *
 * <p>{@link SQLiteAuctionStorage} is the V2.1 implementation. The
 * interface deliberately stays small so a future MySQL or H2 backend
 * could be added without touching the manager.
 *
 * <p>All write methods are atomic with respect to a single row; they
 * either succeed and return cleanly or throw a {@link StorageException}
 * and leave the row untouched.
 */
public interface AuctionStorage {

    /**
     * Initialises the backend (open connection, create tables, run
     * pragmas). Called once from {@link AuctionHouseManager#enable}.
     */
    void init() throws StorageException;

    /**
     * Closes the backend. Idempotent.
     */
    void close();

    /**
     * Inserts a new ACTIVE listing and returns the generated listing ID.
     *
     * @param sellerUuid seller UUID
     * @param sellerName seller display name at listing time
     * @param item       item to list (defensively cloned by the storage)
     * @param price      sale price
     * @param createdAt  epoch millis
     * @param expiresAt  epoch millis (must be {@code > createdAt})
     * @return the new listing ID
     */
    long insertListing(UUID sellerUuid,
                       String sellerName,
                       ItemStack item,
                       double price,
                       long createdAt,
                       long expiresAt) throws StorageException;

    /** Returns the listing with the given ID, or empty if it does not exist. */
    Optional<AuctionListing> getListing(long listingId) throws StorageException;

    /**
     * Returns all listings owned by {@code seller} that currently match
     * {@code status}. Ordered by {@code created_at} ascending for stable
     * output in {@code /ah listings}.
     */
    List<AuctionListing> getListingsBySellerAndStatus(UUID seller,
                                                      AuctionListingStatus status) throws StorageException;

    /**
     * Returns up to {@code limit} ACTIVE listings whose {@code expires_at}
     * is on-or-before {@code now}. Used by {@link AuctionExpiryService}
     * to drive the expiry batch.
     */
    List<AuctionListing> getExpiredActiveListings(long now, int limit) throws StorageException;

    /**
     * Atomically transitions a listing from {@code fromStatus} to
     * {@code toStatus}. Returns {@code true} only when exactly one row
     * was updated; {@code false} when the row didn't exist or was no
     * longer in {@code fromStatus}.
     *
     * <p>This is the linchpin of the expiry / cancel / admin-remove dupe
     * protection: the manager only writes the follow-up collect row
     * when this method returns {@code true}, so the same listing can
     * never be returned twice.
     */
    boolean transitionListingStatus(long listingId,
                                    AuctionListingStatus fromStatus,
                                    AuctionListingStatus toStatus) throws StorageException;

    int countListingsByStatus(AuctionListingStatus status) throws StorageException;

    int countListingsBySellerAndStatus(UUID seller,
                                       AuctionListingStatus status) throws StorageException;

    /**
     * Inserts a new collect row.
     *
     * @return the generated collect ID
     */
    long insertCollectItem(UUID ownerUuid,
                           ItemStack item,
                           AuctionCollectReason reason,
                           long createdAt,
                           Long sourceListingId) throws StorageException;

    List<AuctionCollectItem> getCollectItemsForOwner(UUID owner) throws StorageException;

    /**
     * Deletes a single collect row. Returns {@code true} when a row was
     * actually deleted; {@code false} when the row was already gone (e.g.
     * a double-click on /ah collect from the player).
     */
    boolean deleteCollectItem(long collectId) throws StorageException;

    /**
     * Replaces the stack on an existing collect row with {@code newItem}.
     * Used by {@code /ah collect} when only part of a stack fit into the
     * player's inventory: the row stays, but with a smaller amount.
     */
    boolean updateCollectItemStack(long collectId, ItemStack newItem) throws StorageException;

    int countCollectItems() throws StorageException;

    /**
     * Wraps storage-layer failures (SQL errors, I/O errors, serialisation
     * errors). The caller logs the message and shows
     * {@code auction.storage-error} to the player.
     */
    final class StorageException extends Exception {
        public StorageException(String message) {
            super(message);
        }

        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
