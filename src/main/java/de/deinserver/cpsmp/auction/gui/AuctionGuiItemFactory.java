package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.auction.AuctionBrowseSort;
import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionCollectReason;
import de.deinserver.cpsmp.auction.AuctionConfig;
import de.deinserver.cpsmp.auction.AuctionHouseManager;
import de.deinserver.cpsmp.auction.AuctionListing;
import de.deinserver.cpsmp.auction.AuctionTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds every {@link ItemStack} that the Auction House GUI shows.
 *
 * <p>Two strict rules apply throughout this class:
 * <ol>
 *     <li>Display items are <em>always</em> clones of the real stored
 *         {@link ItemStack}; the originals from {@link AuctionListing}
 *         and {@link AuctionCollectItem} are never handed out. The
 *         click listener cancels every click anyway, but this prevents
 *         a future bug from leaking a real reference into the player
 *         inventory.</li>
 *     <li>Lore additions only mutate the cloned meta, never the
 *         stored item's meta.</li>
 * </ol>
 *
 * <p>All player-facing strings are loaded from {@code messages.yml}
 * through {@link MessageManager} so the GUI stays fully German and
 * fully configurable.
 */
public final class AuctionGuiItemFactory {

    /**
     * Decoration applied to every component we hand to Bukkit so the
     * default italic style of name/lore components is suppressed.
     */
    private static final TextDecoration.State NO_ITALIC = TextDecoration.State.FALSE;

    private final MessageManager messages;
    private final AuctionHouseManager auction;

    public AuctionGuiItemFactory(MessageManager messages, AuctionHouseManager auction) {
        this.messages = messages;
        this.auction = auction;
    }

    // -------------------------------------------------------------------- filler

    public ItemStack filler(AuctionConfig config) {
        if (!config.isGuiFillerEnabled()) {
            return new ItemStack(Material.AIR);
        }
        ItemStack stack = new ItemStack(config.getGuiFillerMaterial());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String raw = config.getGuiFillerName();
            meta.displayName(deserialize(raw == null ? " " : raw));
            stack.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(stack);
        }
        return stack;
    }

    // ----------------------------------------------------------------- buttons

    public ItemStack mainButtonBrowse() {
        return labelled(Material.EMERALD,
                messages.component("auction.gui.button-browse"),
                List.of(messages.component("auction.gui.button-browse-lore")));
    }

    public ItemStack mainButtonListings() {
        return labelled(Material.WRITABLE_BOOK,
                messages.component("auction.gui.button-listings"),
                List.of(messages.component("auction.gui.button-listings-lore")));
    }

    public ItemStack mainButtonCollect() {
        return labelled(Material.HOPPER,
                messages.component("auction.gui.button-collect"),
                List.of(messages.component("auction.gui.button-collect-lore")));
    }

    public ItemStack mainButtonInfo() {
        return labelled(Material.PAPER,
                messages.component("auction.gui.button-info"),
                List.of(
                        messages.component("auction.gui.button-info-lore-1"),
                        messages.component("auction.gui.button-info-lore-2")
                ));
    }

    public ItemStack closeButton() {
        return labelled(Material.BARRIER,
                messages.component("auction.gui.button-close"),
                List.of());
    }

    public ItemStack browseRefreshButton() {
        return labelled(Material.COMPASS,
                messages.component("auction.gui.button-refresh"),
                List.of(messages.component("auction.gui.button-refresh-lore")));
    }

    public ItemStack browseSortButton(AuctionBrowseSort mode) {
        String sortPlain = PlainTextComponentSerializer.plainText()
                .serialize(messages.component(mode.messageKey()));
        return labelled(Material.REPEATER,
                messages.component("auction.gui.button-sort"),
                List.of(
                        messages.component("auction.gui.current-sort", Map.of("sort", sortPlain)),
                        messages.component("auction.gui.button-sort-lore")
                ));
    }

    /**
     * Center tile when the market has no ACTIVE listings (or no search hits).
     */
    public ItemStack browseEmptyPlaceholder() {
        return labelled(Material.MAP,
                messages.component("auction.gui.no-auctions-title"),
                List.of(messages.component("auction.gui.no-auctions-lore")));
    }

    public ItemStack backButton() {
        return labelled(Material.ARROW,
                messages.component("auction.gui.button-back"),
                List.of());
    }

    public ItemStack previousPageButton(int targetPage) {
        return labelled(Material.SPECTRAL_ARROW,
                messages.component("auction.gui.button-previous-page"),
                List.of(messages.component("auction.gui.button-page-lore",
                        Map.of("page", Integer.toString(targetPage)))));
    }

    public ItemStack nextPageButton(int targetPage) {
        return labelled(Material.SPECTRAL_ARROW,
                messages.component("auction.gui.button-next-page"),
                List.of(messages.component("auction.gui.button-page-lore",
                        Map.of("page", Integer.toString(targetPage)))));
    }

    public ItemStack pageIndicator(int page, int totalPages) {
        return labelled(Material.PAPER,
                messages.component("auction.gui.page-indicator", Map.of(
                        "page", Integer.toString(page),
                        "pages", Integer.toString(totalPages)
                )),
                List.of());
    }

    public ItemStack collectAllButton() {
        return labelled(Material.CHEST_MINECART,
                messages.component("auction.gui.button-collect-all"),
                List.of(messages.component("auction.gui.button-collect-all-lore")));
    }

    public ItemStack confirmButton(AuctionListing listing) {
        return labelled(Material.LIME_STAINED_GLASS_PANE,
                messages.component("auction.gui.button-confirm-buy"),
                List.of(
                        messages.component("auction.gui.lore-price", Map.of(
                                "price", auction.formatPrice(listing.price()))),
                        messages.component("auction.gui.lore-seller", Map.of(
                                "seller", listing.sellerName() == null ? "-" : listing.sellerName())),
                        messages.component("auction.gui.lore-expires", Map.of(
                                "time", AuctionTimeFormatter.formatRemaining(listing.remainingMillis(System.currentTimeMillis())))),
                        Component.empty(),
                        messages.component("auction.gui.button-confirm-buy-lore")
                ));
    }

    public ItemStack mainButtonSell() {
        return labelled(Material.ANVIL,
                messages.component("auction.gui.button-sell"),
                List.of(
                        messages.component("auction.gui.sell-button-lore-1"),
                        messages.component("auction.gui.sell-button-lore-2")
                ));
    }

    public ItemStack sellSetPriceButton() {
        return labelled(Material.LIME_STAINED_GLASS_PANE,
                messages.component("auction.gui.sell-set-price"),
                List.of(messages.component("auction.gui.sell-set-price-lore")));
    }

    public ItemStack sellAbortButton() {
        return labelled(Material.RED_STAINED_GLASS_PANE,
                messages.component("auction.gui.sell-cancel"),
                List.of());
    }

    public ItemStack sellBackButton() {
        return labelled(Material.ARROW,
                messages.component("auction.gui.button-back"),
                List.of());
    }

    /**
     * Decoy item for anvil slot 0 so the rename field accepts a custom
     * name (the price). Not a real economy item - the click listener
     * blocks the player from taking the result stack.
     */
    public ItemStack anvilPriceTemplatePaper() {
        return labelled(Material.PAPER,
                messages.component("auction.gui.sell-enter-price-item"),
                List.of(messages.component("auction.gui.sell-enter-price-hint")));
    }

    public ItemStack sellConfirmPreview(ItemStack escrow, double price, AuctionConfig cfg) {
        ItemStack display = escrow.clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(6);
            lore.add(messages.component("auction.gui.lore-price", Map.of(
                    "price", auction.formatPrice(price))));
            if (cfg.getListingFee() > 0.0D) {
                lore.add(messages.component("auction.gui.sell-confirm-fee", Map.of(
                        "fee", auction.formatPrice(cfg.getListingFee()))));
            } else {
                lore.add(messages.component("auction.gui.sell-confirm-no-fee"));
            }
            if (cfg.getSaleTaxPercent() > 0.0D) {
                lore.add(messages.component("auction.gui.sell-confirm-tax", Map.of(
                        "percent", stripTrailingZero(cfg.getSaleTaxPercent()))));
            }
            lore.add(Component.empty());
            lore.add(messages.component("auction.gui.sell-confirm-hint"));
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(display);
        }
        return display;
    }

    public ItemStack sellConfirmCreateButton() {
        return labelled(Material.LIME_STAINED_GLASS_PANE,
                messages.component("auction.gui.sell-confirm"),
                List.of());
    }

    public ItemStack sellConfirmCancelButton() {
        return labelled(Material.RED_STAINED_GLASS_PANE,
                messages.component("auction.gui.sell-cancel"),
                List.of());
    }

    private static String stripTrailingZero(double v) {
        if (v == (long) v) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }

    public ItemStack cancelButton() {
        return labelled(Material.RED_STAINED_GLASS_PANE,
                messages.component("auction.gui.button-cancel"),
                List.of());
    }

    // --------------------------------------------------------- listing displays

    /**
     * Browse-screen tile: shows the real listed item with German lore
     * containing price, seller and remaining time. If {@code viewer}
     * is the seller and own purchase is disabled, the "Klicke zum
     * Kaufen" line is replaced with an "own listing" note so the
     * player knows the tile is read-only.
     */
    public ItemStack browseTile(AuctionListing listing, UUID viewer, boolean allowOwnPurchase, long now) {
        ItemStack display = listing.itemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(5);
            lore.add(messages.component("auction.gui.lore-price", Map.of(
                    "price", auction.formatPrice(listing.price()))));
            lore.add(messages.component("auction.gui.lore-seller", Map.of(
                    "seller", listing.sellerName() == null ? "-" : listing.sellerName())));
            lore.add(messages.component("auction.gui.lore-expires", Map.of(
                    "time", AuctionTimeFormatter.formatRemaining(listing.remainingMillis(now)))));
            lore.add(Component.empty());
            if (listing.sellerUuid().equals(viewer) && !allowOwnPurchase) {
                lore.add(messages.component("auction.gui.lore-own-listing"));
            } else {
                lore.add(messages.component("auction.gui.lore-click-buy"));
            }
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(display);
        }
        return display;
    }

    /**
     * "My listings" tile: shows the listed item with price + remaining
     * time and a "Klicke zum Entfernen" hint.
     */
    public ItemStack listingsTile(AuctionListing listing, long now) {
        ItemStack display = listing.itemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(4);
            lore.add(messages.component("auction.gui.lore-price", Map.of(
                    "price", auction.formatPrice(listing.price()))));
            lore.add(messages.component("auction.gui.lore-expires", Map.of(
                    "time", AuctionTimeFormatter.formatRemaining(listing.remainingMillis(now)))));
            lore.add(Component.empty());
            lore.add(messages.component("auction.gui.lore-click-cancel"));
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(display);
        }
        return display;
    }

    /**
     * Collect-screen tile: shows the stored item with reason, age and
     * source listing ID (when known) plus a "Klicke zum Abholen" hint.
     */
    public ItemStack collectTile(AuctionCollectItem item, long now) {
        ItemStack display = item.itemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(5);
            lore.add(collectReasonLine(item.reason()));
            long ageMs = Math.max(0L, now - item.createdAt());
            lore.add(messages.component("auction.gui.collect-age-line",
                    Map.of("time", AuctionTimeFormatter.formatRemaining(ageMs))));
            if (item.sourceListingId() != null) {
                lore.add(messages.component("auction.gui.collect-source-line",
                        Map.of("id", Long.toString(item.sourceListingId()))));
            }
            lore.add(Component.empty());
            lore.add(messages.component("auction.gui.lore-click-collect"));
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(display);
        }
        return display;
    }

    /**
     * Center tile of the confirm GUI: a clean clone of the listed item
     * with the confirm-prompt lore.
     */
    public ItemStack confirmPreview(AuctionListing listing, long now) {
        ItemStack display = listing.itemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<Component> lore = new ArrayList<>(4);
            lore.add(messages.component("auction.gui.lore-price", Map.of(
                    "price", auction.formatPrice(listing.price()))));
            lore.add(messages.component("auction.gui.lore-seller", Map.of(
                    "seller", listing.sellerName() == null ? "-" : listing.sellerName())));
            lore.add(messages.component("auction.gui.lore-expires", Map.of(
                    "time", AuctionTimeFormatter.formatRemaining(listing.remainingMillis(now)))));
            lore.add(Component.empty());
            lore.add(messages.component("auction.gui.confirm-preview-hint"));
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(display);
        }
        return display;
    }

    // --------------------------------------------------------- empty-state tile

    public ItemStack emptyStateTile(@NotNull String messageKey) {
        return labelled(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                messages.component(messageKey),
                List.of());
    }

    // ------------------------------------------------------------ low-level

    private Component collectReasonLine(AuctionCollectReason reason) {
        return switch (reason) {
            case CANCELLED_LISTING -> messages.component("auction.gui.collect-reason-cancelled-listing");
            case EXPIRED_LISTING -> messages.component("auction.gui.collect-reason-expired-listing");
            case ADMIN_REMOVED -> messages.component("auction.gui.collect-reason-admin-removed");
            case PURCHASED_ITEM_INVENTORY_FULL -> messages.component("auction.gui.collect-reason-purchased-full");
            case SYSTEM_RETURN -> messages.component("auction.gui.collect-reason-system-return");
        };
    }

    private ItemStack labelled(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyMeta(meta, name, lore);
            stack.setItemMeta(meta);
            AuctionGuiItemKeys.markGuiItem(stack);
        }
        return stack;
    }

    private static void applyMeta(ItemMeta meta, Component name, List<Component> lore) {
        if (name != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, NO_ITALIC));
        }
        if (lore != null) {
            List<Component> normalised = new ArrayList<>(lore.size());
            for (Component c : lore) {
                normalised.add(c == null
                        ? Component.empty()
                        : c.decoration(TextDecoration.ITALIC, NO_ITALIC));
            }
            meta.lore(normalised);
        }
    }

    private Component deserialize(String raw) {
        return messages.miniMessage().deserialize(raw)
                .decoration(TextDecoration.ITALIC, NO_ITALIC);
    }
}
