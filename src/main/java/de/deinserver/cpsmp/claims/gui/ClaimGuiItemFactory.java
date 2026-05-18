package de.deinserver.cpsmp.claims.gui;

import de.deinserver.cpsmp.MessageManager;
import de.deinserver.cpsmp.claims.Claim;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClaimGuiItemFactory {

    private static final TextDecoration.State NO_ITALIC = TextDecoration.State.FALSE;

    private final MessageManager messages;

    public ClaimGuiItemFactory(MessageManager messages) {
        this.messages = messages;
    }

    public ItemStack filler() {
        ItemStack stack = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.space());
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.FILLER);
        }
        return stack;
    }

    public ItemStack emptyState() {
        ItemStack stack = new ItemStack(Material.STRUCTURE_VOID);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-empty-title").decoration(TextDecoration.ITALIC, NO_ITALIC));
            meta.lore(List.of(messages.component("claim.gui-empty-lore").decoration(TextDecoration.ITALIC, NO_ITALIC)));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.EMPTY_STATE);
        }
        return stack;
    }

    public ItemStack claimRow(@NotNull Claim claim, int trustedCount) {
        ItemStack stack = new ItemStack(Material.GOLDEN_SHOVEL);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            int cx = claim.centerX();
            int cz = claim.centerZ();
            meta.displayName(messages.component("claim.gui-item-name",
                    Map.of("id", Long.toString(claim.id()))).decoration(TextDecoration.ITALIC, NO_ITALIC));
            List<Component> lore = new ArrayList<>(10);
            lore.add(messages.component("claim.gui-lore-world", Map.of("world", claim.worldName())));
            lore.add(messages.component("claim.gui-lore-position",
                    Map.of("x", Integer.toString(cx), "z", Integer.toString(cz))));
            lore.add(messages.component("claim.gui-lore-size",
                    Map.of("w", Integer.toString(claim.widthBlocks()), "d", Integer.toString(claim.depthBlocks()))));
            lore.add(messages.component("claim.gui-lore-trusted",
                    Map.of("n", Integer.toString(trustedCount))));
            lore.add(Component.empty());
            lore.add(messages.component("claim.gui-lore-left"));
            lore.add(messages.component("claim.gui-lore-right"));
            lore.add(messages.component("claim.gui-lore-delete"));
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, NO_ITALIC)).toList());
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagClaimRow(stack, claim.id());
        }
        return stack;
    }

    public ItemStack prevPage() {
        ItemStack stack = new ItemStack(Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-page-prev").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.PREV_PAGE);
        }
        return stack;
    }

    public ItemStack nextPage() {
        ItemStack stack = new ItemStack(Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-page-next").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.NEXT_PAGE);
        }
        return stack;
    }

    public ItemStack detailsInfo(@NotNull Claim claim, @NotNull String ownerName, int trustedCount,
                                 @NotNull String trustedList) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            int cx = claim.centerX();
            int cz = claim.centerZ();
            meta.displayName(messages.component("claim.gui-details-info-title",
                    Map.of("id", Long.toString(claim.id()))).decoration(TextDecoration.ITALIC, NO_ITALIC));
            List<Component> lore = new ArrayList<>();
            lore.add(messages.component("claim.gui-details-owner", Map.of("owner", ownerName)));
            lore.add(messages.component("claim.gui-lore-world", Map.of("world", claim.worldName())));
            lore.add(messages.component("claim.gui-lore-position",
                    Map.of("x", Integer.toString(cx), "z", Integer.toString(cz))));
            lore.add(messages.component("claim.gui-lore-size",
                    Map.of("w", Integer.toString(claim.widthBlocks()), "d", Integer.toString(claim.depthBlocks()))));
            lore.add(messages.component("claim.gui-details-trusted-line",
                    Map.of("n", Integer.toString(trustedCount), "list", trustedList)));
            lore.add(Component.empty());
            meta.lore(lore.stream().map(c -> c.decoration(TextDecoration.ITALIC, NO_ITALIC)).toList());
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagClaimRow(stack, claim.id());
        }
        return stack;
    }

    public ItemStack btnShowBorder(long claimId) {
        ItemStack stack = new ItemStack(Material.BEACON);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-btn-show").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagClaimButton(stack, ClaimGuiKeys.Kind.BTN_SHOW_BORDER, claimId);
        }
        return stack;
    }

    public ItemStack btnDelete(long claimId) {
        ItemStack stack = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-btn-delete").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagClaimButton(stack, ClaimGuiKeys.Kind.BTN_OPEN_DELETE, claimId);
        }
        return stack;
    }

    public ItemStack btnBack() {
        ItemStack stack = new ItemStack(Material.IRON_DOOR);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-btn-back").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.BTN_BACK);
        }
        return stack;
    }

    public ItemStack btnDeleteConfirm(long claimId) {
        ItemStack stack = new ItemStack(Material.RED_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-delete-confirm").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagClaimButton(stack, ClaimGuiKeys.Kind.DEL_CONFIRM, claimId);
        }
        return stack;
    }

    public ItemStack btnDeleteCancel() {
        ItemStack stack = new ItemStack(Material.LIME_CONCRETE);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(messages.component("claim.gui-delete-cancel").decoration(TextDecoration.ITALIC, NO_ITALIC));
            stack.setItemMeta(meta);
            ClaimGuiKeys.tagKind(stack, ClaimGuiKeys.Kind.DEL_CANCEL);
        }
        return stack;
    }
}
