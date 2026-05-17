package de.deinserver.cpsmp.auction;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.MessageManager;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single {@code /ah} entry point. Subcommands (all German-localised
 * via {@code messages.yml}):
 *
 * <ul>
 *     <li>{@code /ah} - help overview</li>
 *     <li>{@code /ah sell <price>} - list the item in main hand</li>
 *     <li>{@code /ah listings} - your own active listings</li>
 *     <li>{@code /ah cancel <id>} - cancel one of your listings</li>
 *     <li>{@code /ah collect} - retrieve everything from collect storage</li>
 *     <li>{@code /ah admin remove <id>} - remove any listing (perm: cpsmp.ah.admin)</li>
 *     <li>{@code /ah admin info} - backend status</li>
 *     <li>{@code /ah admin reload} - reload AH config + messages</li>
 * </ul>
 *
 * <p>V2.1 is intentionally text-only. The premium GUI lands in a later
 * release; this command stays so the backend can be exercised and so
 * console / admins always have a non-GUI path to the same operations.
 */
public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT = List.of(
            "sell", "listings", "cancel", "collect", "admin");
    private static final List<String> ADMIN_ACTIONS = List.of(
            "remove", "info", "reload");

    private final CPSMPPlugin plugin;
    private final AuctionHouseManager auction;

    public AuctionCommand(CPSMPPlugin plugin, AuctionHouseManager auction) {
        this.plugin = plugin;
        this.auction = auction;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        MessageManager messages = plugin.getMessageManager();
        if (!sender.hasPermission(AuctionPermission.BASE)) {
            messages.sendPrefixed(sender, "auction.no-permission");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "sell" -> handleSell(sender, args);
            case "listings" -> handleListings(sender);
            case "cancel" -> handleCancel(sender, args);
            case "collect" -> handleCollect(sender);
            case "admin" -> handleAdmin(sender, args);
            case "help" -> {
                sendHelp(sender);
                yield true;
            }
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    // ----------------------------------------------------------------- /ah help

    private void sendHelp(CommandSender sender) {
        MessageManager m = plugin.getMessageManager();
        m.sendPrefixed(sender, "auction.help");
        m.sendPrefixed(sender, "auction.help-sell");
        m.sendPrefixed(sender, "auction.help-listings");
        m.sendPrefixed(sender, "auction.help-cancel");
        m.sendPrefixed(sender, "auction.help-collect");
        if (sender.hasPermission(AuctionPermission.ADMIN)) {
            m.sendPrefixed(sender, "auction.help-admin");
        }
    }

    // ----------------------------------------------------------------- /ah sell

    private boolean handleSell(CommandSender sender, String[] args) {
        MessageManager m = plugin.getMessageManager();
        if (!(sender instanceof Player player)) {
            m.sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(AuctionPermission.SELL)) {
            m.sendPrefixed(player, "auction.no-permission");
            return true;
        }
        if (args.length < 2) {
            m.sendPrefixed(player, "auction.help-sell");
            return true;
        }
        AuctionPriceParser.Result parsed = AuctionPriceParser.parse(args[1]);
        if (!parsed.ok()) {
            m.sendPrefixed(player, "auction.invalid-price");
            return true;
        }
        auction.createListing(player, parsed.value()).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.ListingCreateResult.Success s -> {
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("id", Long.toString(s.listingId()));
                    placeholders.put("price", auction.formatPrice(s.price()));
                    placeholders.put("item", describeItem(s.snapshot()));
                    placeholders.put("amount", Integer.toString(s.snapshot().getAmount()));
                    m.sendPrefixed(player, "auction.listing-created", placeholders);
                }
                case AuctionHouseManager.ListingCreateResult.Failure f ->
                        m.sendPrefixed(player, f.messageKey(), f.placeholders());
            }
        });
        return true;
    }

    // ------------------------------------------------------------- /ah listings

    private boolean handleListings(CommandSender sender) {
        MessageManager m = plugin.getMessageManager();
        if (!(sender instanceof Player player)) {
            m.sendPrefixed(sender, "general.player-only");
            return true;
        }
        auction.getActiveListings(player.getUniqueId()).thenAccept(list -> {
            if (list.isEmpty()) {
                m.sendPrefixed(player, "auction.listings-empty");
                return;
            }
            m.sendPrefixed(player, "auction.listings-header",
                    Map.of("count", Integer.toString(list.size())));
            long now = System.currentTimeMillis();
            for (AuctionListing listing : list) {
                Map<String, String> ph = new HashMap<>();
                ph.put("id", Long.toString(listing.listingId()));
                ph.put("item", describeItem(listing.itemStack()));
                ph.put("amount", Integer.toString(listing.itemStack().getAmount()));
                ph.put("price", auction.formatPrice(listing.price()));
                ph.put("time", AuctionTimeFormatter.formatRemaining(listing.remainingMillis(now)));
                m.sendPrefixed(player, "auction.listings-row", ph);
            }
        });
        return true;
    }

    // --------------------------------------------------------------- /ah cancel

    private boolean handleCancel(CommandSender sender, String[] args) {
        MessageManager m = plugin.getMessageManager();
        if (!(sender instanceof Player player)) {
            m.sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(AuctionPermission.CANCEL)) {
            m.sendPrefixed(player, "auction.no-permission");
            return true;
        }
        if (args.length < 2) {
            m.sendPrefixed(player, "auction.help-cancel");
            return true;
        }
        Long id = parseListingId(args[1]);
        if (id == null) {
            m.sendPrefixed(player, "auction.listing-not-found");
            return true;
        }
        auction.cancelListing(player.getUniqueId(), id, false).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.CancelResult.Success s ->
                        m.sendPrefixed(player, "auction.listing-cancelled",
                                Map.of("id", Long.toString(s.listing().listingId())));
                case AuctionHouseManager.CancelResult.Failure f ->
                        m.sendPrefixed(player, f.messageKey());
            }
        });
        return true;
    }

    // -------------------------------------------------------------- /ah collect

    private boolean handleCollect(CommandSender sender) {
        MessageManager m = plugin.getMessageManager();
        if (!(sender instanceof Player player)) {
            m.sendPrefixed(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission(AuctionPermission.COLLECT)) {
            m.sendPrefixed(player, "auction.no-permission");
            return true;
        }
        auction.collectAll(player).thenAccept(result -> {
            switch (result) {
                case AuctionHouseManager.CollectResult.Empty ignored ->
                        m.sendPrefixed(player, "auction.collect-empty");
                case AuctionHouseManager.CollectResult.Success s ->
                        m.sendPrefixed(player, "auction.collect-success",
                                Map.of("count", Integer.toString(s.delivered())));
                case AuctionHouseManager.CollectResult.Partial p ->
                        m.sendPrefixed(player, "auction.collect-partial", Map.of(
                                "count", Integer.toString(p.delivered()),
                                "remaining", Integer.toString(p.remaining())
                        ));
                case AuctionHouseManager.CollectResult.Failure f ->
                        m.sendPrefixed(player, f.messageKey());
            }
        });
        return true;
    }

    // ---------------------------------------------------------------- /ah admin

    private boolean handleAdmin(CommandSender sender, String[] args) {
        MessageManager m = plugin.getMessageManager();
        if (!sender.hasPermission(AuctionPermission.ADMIN)) {
            m.sendPrefixed(sender, "auction.no-permission");
            return true;
        }
        if (args.length < 2) {
            m.sendPrefixed(sender, "auction.admin-usage");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "remove" -> handleAdminRemove(sender, args);
            case "info" -> handleAdminInfo(sender);
            case "reload" -> handleAdminReload(sender);
            default -> {
                m.sendPrefixed(sender, "auction.admin-usage");
                yield true;
            }
        };
    }

    private boolean handleAdminRemove(CommandSender sender, String[] args) {
        MessageManager m = plugin.getMessageManager();
        if (args.length < 3) {
            m.sendPrefixed(sender, "auction.admin-usage");
            return true;
        }
        Long id = parseListingId(args[2]);
        if (id == null) {
            m.sendPrefixed(sender, "auction.listing-not-found");
            return true;
        }
        // Use a zero UUID for the requester; the boolean admin flag
        // bypasses the ownership check anyway.
        auction.cancelListing(new java.util.UUID(0L, 0L), id, true)
                .thenAccept(result -> {
                    switch (result) {
                        case AuctionHouseManager.CancelResult.Success s ->
                                m.sendPrefixed(sender, "auction.admin-removed", Map.of(
                                        "id", Long.toString(s.listing().listingId()),
                                        "seller", s.listing().sellerName()
                                ));
                        case AuctionHouseManager.CancelResult.Failure f ->
                                m.sendPrefixed(sender, f.messageKey());
                    }
                });
        return true;
    }

    private boolean handleAdminInfo(CommandSender sender) {
        MessageManager m = plugin.getMessageManager();
        auction.getStats().thenAccept(stats -> {
            m.sendPrefixed(sender, "auction.admin-info-header");
            m.sendPrefixed(sender, "auction.admin-info-state", Map.of(
                    "state", stats.active() ? "AN" : "AUS",
                    "reason", stats.inactiveReason() != null ? stats.inactiveReason() : "-"
            ));
            m.sendPrefixed(sender, "auction.admin-info-storage", Map.of(
                    "type", stats.storageType()
            ));
            m.sendPrefixed(sender, "auction.admin-info-counts", Map.of(
                    "active", Integer.toString(stats.activeListings()),
                    "expired", Integer.toString(stats.expiredListings()),
                    "cancelled", Integer.toString(stats.cancelledListings()),
                    "collect", Integer.toString(stats.collectItems())
            ));
            m.sendPrefixed(sender, "auction.admin-info-economy", Map.of(
                    "bridge", stats.economyBridge(),
                    "provider", stats.economyProvider(),
                    "available", stats.economyAvailable() ? "ja" : "nein"
            ));
        });
        return true;
    }

    private boolean handleAdminReload(CommandSender sender) {
        MessageManager m = plugin.getMessageManager();
        plugin.getConfigManager().reload();
        plugin.getMessageManager().reload();
        auction.reload();
        m.sendPrefixed(sender, "auction.reload-success");
        return true;
    }

    // ------------------------------------------------------- helpers / tab-comp

    @Nullable
    private static Long parseListingId(String raw) {
        try {
            long id = Long.parseLong(raw.trim());
            return id > 0L ? id : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Short human-readable label for an item. Uses the display name from
     * its meta when present (rendered to plain text), otherwise the
     * Bukkit Material name. Never throws on missing meta.
     */
    private static String describeItem(ItemStack stack) {
        if (stack == null) return "-";
        if (stack.hasItemMeta()) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                try {
                    return PlainTextComponentSerializer.plainText()
                            .serialize(meta.displayName() == null
                                    ? net.kyori.adventure.text.Component.empty()
                                    : meta.displayName());
                } catch (Throwable ignored) {
                    // Fall through to material name.
                }
            }
        }
        return stack.getType().name();
    }

    @Override
    @Nullable
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission(AuctionPermission.BASE)) return List.of();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(ROOT);
            if (!sender.hasPermission(AuctionPermission.ADMIN)) {
                options.remove("admin");
            }
            return filter(options, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")
                && sender.hasPermission(AuctionPermission.ADMIN)) {
            return filter(ADMIN_ACTIONS, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>(options.size());
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }
}
