package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionConfig;
import de.deinserver.cpsmp.auction.AuctionHouseManager;
import de.deinserver.cpsmp.auction.AuctionListing;
import de.deinserver.cpsmp.auction.AuctionTimeFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
        }
        return stack;
    }

    // ----------------------------------------------------------------- buttons

    public ItemStack mainButtonBrowse() {
        return labelled(Material.EMERALD,
                messages.component("auction.gui.button-browse"),
                List.of(line("Klicke, um den Markt zu durchstoebern.", NamedTextColor.GRAY)));
    }

    public ItemStack mainButtonListings() {
        return labelled(Material.WRITABLE_BOOK,
                messages.component("auction.gui.button-listings"),
                List.of(line("Deine aktiven Angebote.", NamedTextColor.GRAY)));
    }

    public ItemStack mainButtonCollect() {
        return labelled(Material.HOPPER,
                messages.component("auction.gui.button-collect"),
                List.of(line("Items, die fuer dich bereitliegen.", NamedTextColor.GRAY)));
    }

    public ItemStack mainButtonInfo() {
        return labelled(Material.PAPER,
                messages.component("auction.gui.button-info"),
                List.of(
                        line("Verkaufe Items per /ah sell <Preis>.", NamedTextColor.GRAY),
                        line("Kaufe Items ueber den Markt.", NamedTextColor.GRAY)
                ));
    }

    public ItemStack closeButton() {
        return labelled(Material.BARRIER,
                messages.component("auction.gui.button-close"),
                List.of());
    }

    public ItemStack backButton() {
        return labelled(Material.ARROW,
                messages.component("auction.gui.button-back"),
                List.of());
    }

    public ItemStack previousPageButton(int targetPage) {
        return labelled(Material.SPECTRAL_ARROW,
                messages.component("auction.gui.button-previous-page"),
                List.of(line("Seite " + targetPage, NamedTextColor.GRAY)));
    }

    public ItemStack nextPageButton(int targetPage) {
        return labelled(Material.SPECTRAL_ARROW,
                messages.component("auction.gui.button-next-page"),
                List.of(line("Seite " + targetPage, NamedTextColor.GRAY)));
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
                List.of(line("Alle bereitliegenden Items abholen.", NamedTextColor.GRAY)));
    }

    public ItemStack confirmButton(AuctionListing listing) {
        return labelled(Material.LIME_STAINED_GLASS_PANE,
                messages.component("auction.gui.button-confirm-buy"),
                List.of(
                        messages.component("auction.gui.lore-price", Map.of(
                                "price", auction.formatPrice(listing.price()))),
                        messages.component("auction.gui.lore-seller", Map.of(
                                "seller", listing.sellerName() == null ? "-" : listing.sellerName()))
                ));
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
            lore.add(line("Grund: " + germanReason(item), NamedTextColor.GRAY));
            long ageMs = Math.max(0L, now - item.createdAt());
            lore.add(line("Alter: " + AuctionTimeFormatter.formatRemaining(ageMs),
                    NamedTextColor.GRAY));
            if (item.sourceListingId() != null) {
                lore.add(line("Aus Angebot #" + item.sourceListingId(), NamedTextColor.DARK_GRAY));
            }
            lore.add(Component.empty());
            lore.add(messages.component("auction.gui.lore-click-collect"));
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
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
            applyMeta(meta, null, lore);
            display.setItemMeta(meta);
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

    private static String germanReason(AuctionCollectItem item) {
        return switch (item.reason()) {
            case CANCELLED_LISTING -> "Zurueckgezogenes Angebot";
            case EXPIRED_LISTING -> "Abgelaufenes Angebot";
            case ADMIN_REMOVED -> "Vom Admin entfernt";
            case PURCHASED_ITEM_INVENTORY_FULL -> "Gekauftes Item";
            case SYSTEM_RETURN -> "System-Rueckgabe";
        };
    }

    private ItemStack labelled(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyMeta(meta, name, lore);
            stack.setItemMeta(meta);
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

    private static Component line(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, NO_ITALIC);
    }

    private Component deserialize(String raw) {
        return messages.miniMessage().deserialize(raw)
                .decoration(TextDecoration.ITALIC, NO_ITALIC);
    }
}
