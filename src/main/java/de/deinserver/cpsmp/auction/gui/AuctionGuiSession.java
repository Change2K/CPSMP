package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.auction.AuctionBrowseSort;
import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionListing;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Per-player state for the Auction House GUI. Implements
 * {@link InventoryHolder} so every CPSMP-owned inventory carries a
 * direct typed reference back to its session.
 */
public final class AuctionGuiSession implements InventoryHolder {

    public enum Screen {
        MAIN,
        BROWSE,
        LISTINGS,
        COLLECT,
        /** Buying another player's listing (existing V2.3 flow). */
        CONFIRM,
        /** V2.4: place item + choose price via anvil. */
        SELL,
        /** V2.4: confirm listing before {@code createListing}. */
        SELL_CONFIRM
    }

    private final UUID playerId;

    @Nullable
    private Inventory currentInventory;

    private Screen currentScreen = Screen.MAIN;
    private int currentPage = 1;

    /**
     * When {@code true}, the next {@link InventoryCloseEvent} for a
     * session holder inventory is the expected teardown of the sell GUI
     * right before {@code Player#openAnvil} &mdash; escrow has already
     * been moved out of the closed inventory and must not be returned
     * from the closed slots.
     */
    private boolean sellToAnvilTransition;

    /**
     * Player has the sell GUI's item in escrow while the virtual anvil
     * is open for price entry.
     */
    private boolean awaitingAnvilPrice;

    /**
     * Item removed from the sell input slot pending listing creation
     * (held outside any inventory while the anvil / confirm GUIs are
     * open).
     */
    @Nullable
    private ItemStack pendingSellEscrow;

    /**
     * Price parsed from the anvil rename field, awaiting the confirm GUI.
     */
    @Nullable
    private Double pendingSellConfirmPrice;

    @NotNull
    private List<AuctionListing> visibleListings = List.of();

    @NotNull
    private List<AuctionCollectItem> visibleCollect = List.of();

    @Nullable
    private AuctionListing pendingConfirmListing;

    private Screen confirmReturnScreen = Screen.BROWSE;

    private int confirmReturnPage = 1;

    /**
     * Browse screen: explicit null means “use {@link AuctionConfig#getGuiBrowseDefaultSort}”.
     */
    @Nullable
    private AuctionBrowseSort browseSort;

    @Nullable
    private String browseSearchFilter;

    /**
     * Incremented on each browse load start; async
     * {@link java.util.concurrent.CompletableFuture} callbacks ignore stale
     * generations so out-of-order DB completions never repopulate the GUI
     * with an older sort or page.
     */
    private int browseFetchGeneration;

    public AuctionGuiSession(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        if (currentInventory == null) {
            throw new IllegalStateException("AuctionGuiSession has no inventory yet");
        }
        return currentInventory;
    }

    void setCurrentInventory(@Nullable Inventory currentInventory) {
        this.currentInventory = currentInventory;
    }

    public Screen getCurrentScreen() {
        return currentScreen;
    }

    public void setCurrentScreen(Screen currentScreen) {
        this.currentScreen = currentScreen;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int currentPage) {
        this.currentPage = Math.max(1, currentPage);
    }

    public boolean consumeSellToAnvilTransition() {
        if (!sellToAnvilTransition) {
            return false;
        }
        sellToAnvilTransition = false;
        return true;
    }

    public void setSellToAnvilTransition(boolean sellToAnvilTransition) {
        this.sellToAnvilTransition = sellToAnvilTransition;
    }

    public boolean isAwaitingAnvilPrice() {
        return awaitingAnvilPrice;
    }

    public void setAwaitingAnvilPrice(boolean awaitingAnvilPrice) {
        this.awaitingAnvilPrice = awaitingAnvilPrice;
    }

    @Nullable
    public ItemStack getPendingSellEscrow() {
        return pendingSellEscrow;
    }

    public void setPendingSellEscrow(@Nullable ItemStack pendingSellEscrow) {
        this.pendingSellEscrow = pendingSellEscrow;
    }

    /**
     * Clears escrow and returns the previous reference (caller owns the
     * stack).
     */
    @Nullable
    public ItemStack takePendingSellEscrow() {
        ItemStack s = pendingSellEscrow;
        pendingSellEscrow = null;
        return s;
    }

    @Nullable
    public Double getPendingSellConfirmPrice() {
        return pendingSellConfirmPrice;
    }

    public void setPendingSellConfirmPrice(@Nullable Double pendingSellConfirmPrice) {
        this.pendingSellConfirmPrice = pendingSellConfirmPrice;
    }

    public void clearSellFlowState() {
        sellToAnvilTransition = false;
        awaitingAnvilPrice = false;
        pendingSellEscrow = null;
        pendingSellConfirmPrice = null;
    }

    @NotNull
    public List<AuctionListing> getVisibleListings() {
        return visibleListings;
    }

    public void setVisibleListings(@NotNull List<AuctionListing> visibleListings) {
        this.visibleListings = visibleListings;
    }

    @NotNull
    public List<AuctionCollectItem> getVisibleCollect() {
        return visibleCollect;
    }

    public void setVisibleCollect(@NotNull List<AuctionCollectItem> visibleCollect) {
        this.visibleCollect = visibleCollect;
    }

    @Nullable
    public AuctionListing getPendingConfirmListing() {
        return pendingConfirmListing;
    }

    public void setPendingConfirmListing(@Nullable AuctionListing pendingConfirmListing) {
        this.pendingConfirmListing = pendingConfirmListing;
    }

    public Screen getConfirmReturnScreen() {
        return confirmReturnScreen;
    }

    public void setConfirmReturnScreen(Screen confirmReturnScreen) {
        this.confirmReturnScreen = confirmReturnScreen;
    }

    public int getConfirmReturnPage() {
        return confirmReturnPage;
    }

    public void setConfirmReturnPage(int confirmReturnPage) {
        this.confirmReturnPage = Math.max(1, confirmReturnPage);
    }

    public AuctionBrowseSort effectiveBrowseSort(AuctionBrowseSort configDefault) {
        return browseSort != null ? browseSort : configDefault;
    }

    @Nullable
    public AuctionBrowseSort getBrowseSortOverride() {
        return browseSort;
    }

    public void setBrowseSort(@Nullable AuctionBrowseSort browseSort) {
        this.browseSort = browseSort;
    }

    public void cycleBrowseSort(AuctionBrowseSort configDefault) {
        AuctionBrowseSort cur = effectiveBrowseSort(configDefault);
        this.browseSort = cur.next();
    }

    @Nullable
    public String getBrowseSearchFilter() {
        return browseSearchFilter;
    }

    public void setBrowseSearchFilter(@Nullable String query) {
        if (query == null || query.isBlank()) {
            this.browseSearchFilter = null;
        } else {
            this.browseSearchFilter = query.trim();
        }
    }

    /**
     * Call at the start of each browse load; the returned value must match
     * {@link #getBrowseFetchGeneration()} when the load completes.
     */
    int beginBrowseFetch() {
        return ++browseFetchGeneration;
    }

    int getBrowseFetchGeneration() {
        return browseFetchGeneration;
    }
}
