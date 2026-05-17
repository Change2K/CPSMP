package de.deinserver.cpsmp.auction.gui;

import de.deinserver.cpsmp.auction.AuctionCollectItem;
import de.deinserver.cpsmp.auction.AuctionListing;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * Per-player state for the V2.3 Auction House GUI. Implements
 * {@link InventoryHolder} so every CPSMP-owned inventory carries a
 * direct typed reference back to its session - the click listener uses
 * {@code event.getInventory().getHolder() instanceof AuctionGuiSession}
 * to scope its handling and refuses to touch any inventory created by
 * another plugin or by vanilla.
 *
 * <p>A session is created lazily when the player first runs {@code /ah}
 * (or any subcommand that opens a GUI) and is removed when the player
 * closes the active inventory. Each navigation step swaps
 * {@link #currentInventory} for the new screen; the close listener
 * uses identity comparison so the implicit close of the previous
 * inventory (fired by Bukkit when {@code openInventory} is called for
 * the next screen) does not tear the session down prematurely.
 */
public final class AuctionGuiSession implements InventoryHolder {

    public enum Screen {
        MAIN,
        BROWSE,
        LISTINGS,
        COLLECT,
        CONFIRM
    }

    private final UUID playerId;

    @Nullable
    private Inventory currentInventory;

    private Screen currentScreen = Screen.MAIN;
    private int currentPage = 1;

    /**
     * Snapshot of the listings rendered in the most recent BROWSE or
     * LISTINGS screen. Used to map a clicked slot back to a listing ID
     * without re-querying the database.
     */
    @NotNull
    private List<AuctionListing> visibleListings = List.of();

    /**
     * Snapshot of the collect rows rendered in the most recent COLLECT
     * screen. Same role as {@link #visibleListings} for the collect GUI.
     */
    @NotNull
    private List<AuctionCollectItem> visibleCollect = List.of();

    /**
     * Listing under review when the screen is {@link Screen#CONFIRM}.
     * Captured at confirmation time so the listing reference survives
     * the GUI navigation back to BROWSE.
     */
    @Nullable
    private AuctionListing pendingConfirmListing;

    /**
     * Screen to return to after the confirm GUI is dismissed. Defaults
     * to {@link Screen#BROWSE}.
     */
    private Screen confirmReturnScreen = Screen.BROWSE;

    /**
     * Page to restore on the return screen after the confirm GUI is
     * dismissed.
     */
    private int confirmReturnPage = 1;

    public AuctionGuiSession(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    @Override
    @NotNull
    public Inventory getInventory() {
        // Contract: a session is always created together with an
        // inventory, so by the time anyone calls getInventory() the
        // field is populated. The Nullable annotation on the field is
        // just for the brief construction window.
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
}
