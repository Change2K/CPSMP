package de.deinserver.cpsmp.auction;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a row in {@code auction_collect_items}. Collect
 * storage is the holding area for items that must be returned to a
 * player later (cancelled listing, expired listing, admin-removed
 * listing, etc.). Players retrieve them via {@code /ah collect}.
 *
 * <p>The item is a defensively-cloned stack; callers may modify it
 * without affecting the storage layer.
 *
 * @param collectId        primary key in {@code auction_collect_items}
 *                         (AUTOINCREMENT)
 * @param ownerUuid        UUID of the player the item belongs to
 * @param itemStack        the item to be returned (cloned)
 * @param reason           why the item ended up here; the enum is stored
 *                         as a string so future reasons round-trip safely
 * @param createdAt        epoch millis when the row was inserted
 * @param sourceListingId  ID of the listing that produced this collect
 *                         row, when applicable; {@code null} for
 *                         system-initiated rows
 */
public record AuctionCollectItem(
        long collectId,
        UUID ownerUuid,
        ItemStack itemStack,
        AuctionCollectReason reason,
        long createdAt,
        @Nullable Long sourceListingId
) {
}
