package de.deinserver.cpsmp.auction;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * In-memory filter / sort for search-backed browse. Runs only on the
 * Auction House DB executor thread.
 */
public final class AuctionListingSearch {

    private AuctionListingSearch() {
    }

    public static List<AuctionListing> filter(List<AuctionListing> listings, String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(listings);
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return new ArrayList<>(listings);
        }
        List<AuctionListing> out = new ArrayList<>();
        for (AuctionListing listing : listings) {
            if (matches(listing, needle)) {
                out.add(listing);
            }
        }
        return out;
    }

    public static boolean matches(AuctionListing listing, String needleLower) {
        if (listing.sellerName() != null
                && listing.sellerName().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        ItemStack stack = listing.itemStack();
        if (stack == null) {
            return false;
        }
        if (stack.getType().name().toLowerCase(Locale.ROOT).contains(needleLower)) {
            return true;
        }
        String display = plainDisplayName(stack);
        return !display.isEmpty() && display.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String plainDisplayName(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return "";
        }
        try {
            if (meta.displayName() == null) {
                return "";
            }
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static void sort(List<AuctionListing> listings, AuctionBrowseSort sort) {
        Comparator<AuctionListing> cmp = switch (sort) {
            case NEWEST -> Comparator.comparingLong(AuctionListing::createdAt).reversed()
                    .thenComparingLong(AuctionListing::listingId);
            case OLDEST -> Comparator.comparingLong(AuctionListing::createdAt)
                    .thenComparingLong(AuctionListing::listingId);
            case PRICE_ASC -> Comparator.comparingDouble(AuctionListing::price)
                    .thenComparingLong(AuctionListing::listingId);
            case PRICE_DESC -> Comparator.comparingDouble(AuctionListing::price).reversed()
                    .thenComparingLong(AuctionListing::listingId);
            case EXPIRING_SOON -> Comparator.comparingLong(AuctionListing::expiresAt)
                    .thenComparingLong(AuctionListing::listingId);
        };
        listings.sort(cmp);
    }
}
