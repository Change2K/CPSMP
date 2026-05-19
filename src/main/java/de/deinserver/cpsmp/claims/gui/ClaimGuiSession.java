package de.deinserver.cpsmp.claims.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Holder for Claims / Plots GUI screens.
 */
public final class ClaimGuiSession implements InventoryHolder {

    public enum Screen {
        LIST,
        DETAILS,
        DELETE_CONFIRM,
        FLAGS
    }

    private final UUID playerId;
    private @Nullable Inventory inventory;
    private Screen screen = Screen.LIST;
    private int listPage;
    private long detailsClaimId = -1L;
    private long pendingDeleteClaimId = -1L;
    private long flagsClaimId = -1L;

    public ClaimGuiSession(@NotNull UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public Screen screen() {
        return screen;
    }

    public void setScreen(Screen screen) {
        this.screen = screen;
    }

    public int listPage() {
        return listPage;
    }

    public void setListPage(int listPage) {
        this.listPage = Math.max(0, listPage);
    }

    public long detailsClaimId() {
        return detailsClaimId;
    }

    public void setDetailsClaimId(long detailsClaimId) {
        this.detailsClaimId = detailsClaimId;
    }

    public long pendingDeleteClaimId() {
        return pendingDeleteClaimId;
    }

    public void setPendingDeleteClaimId(long pendingDeleteClaimId) {
        this.pendingDeleteClaimId = pendingDeleteClaimId;
    }

    public long flagsClaimId() {
        return flagsClaimId;
    }

    public void setFlagsClaimId(long flagsClaimId) {
        this.flagsClaimId = flagsClaimId;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("inventory not set");
        }
        return inventory;
    }

    public void attachInventory(@NotNull Inventory inventory) {
        this.inventory = inventory;
    }

    public @Nullable Inventory attachedInventory() {
        return inventory;
    }

    public void clearInventoryRef() {
        this.inventory = null;
    }
}
