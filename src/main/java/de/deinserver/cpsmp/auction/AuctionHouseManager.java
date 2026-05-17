package de.deinserver.cpsmp.auction;

import de.deinserver.cpsmp.CPSMPPlugin;
import de.deinserver.cpsmp.auction.gui.AuctionGuiItemKeys;
import de.deinserver.cpsmp.economy.EconomyBridge;
import de.deinserver.cpsmp.economy.EconomyManager;
import de.deinserver.cpsmp.economy.EconomyTransactionResult;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the Auction House backend: live {@link AuctionConfig}, {@link
 * AuctionStorage}, {@link AuctionExpiryService}, and the single-threaded
 * executor used for every database call.
 *
 * <p>Threading rules:
 * <ul>
 *     <li>Every method on {@link AuctionStorage} runs on the dedicated
 *         {@code dbExecutor} - never on the main server thread.</li>
 *     <li>Every Bukkit API call (inventory access, fee withdrawal,
 *         message send) hops back to the main thread before touching
 *         player state.</li>
 *     <li>The public {@code createListing} / {@code cancelListing} /
 *         {@code collectAll} / {@code adminRemoveListing} methods return
 *         {@link CompletableFuture}s that always complete on the main
 *         thread, so command handlers can use the results directly.</li>
 * </ul>
 *
 * <p>Dupe-protection contract for the sell flow:
 * <ol>
 *     <li>The seller's item is cloned on the main thread.</li>
 *     <li>The listing fee is withdrawn via {@link EconomyBridge} on the
 *         main thread.</li>
 *     <li>The original item is removed from the seller's main hand on
 *         the main thread.</li>
 *     <li>The DB insert runs on the executor.</li>
 *     <li>If the insert fails, the cloned item is restored on the main
 *         thread (to the hand if it is empty, otherwise into the
 *         seller's collect storage) and the fee is refunded.</li>
 * </ol>
 * The item is therefore in exactly one place at every observable point
 * in time: either in the seller's hand, in the listings table, or in
 * the collect table.
 */
public final class AuctionHouseManager {

    private static final int EXPIRY_BATCH_LIMIT = 200;

    private final CPSMPPlugin plugin;

    private ExecutorService dbExecutor;
    private AuctionStorage storage;
    private AuctionConfig config;
    private AuctionExpiryService expiryService;

    /**
     * True once {@link #enable()} has finished without storage errors.
     * When false every public operation completes with
     * {@code auction.disabled} so we never partially execute a flow
     * against a half-initialised backend.
     */
    private volatile boolean active;

    /**
     * Sticky reason why the backend is inactive (e.g. SQLite driver
     * missing). Surfaced on {@code /ah admin info}.
     */
    @Nullable
    private volatile String inactiveReason;

    public AuctionHouseManager(CPSMPPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Loads {@code auctionhouse.yml}, opens the storage backend and
     * starts the expiry service. Safe to call multiple times; reload
     * uses {@link #reload()} instead.
     */
    public void enable() {
        this.config = new AuctionConfig(plugin.getConfigManager().getAuction(), plugin.getLogger());
        if (!config.isEnabled()) {
            this.active = false;
            this.inactiveReason = "disabled-in-config";
            plugin.getLogger().info("Auction House disabled in auctionhouse.yml.");
            return;
        }

        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CPSMP-AH-DB");
            t.setDaemon(true);
            return t;
        });

        File dbFile = new File(plugin.getDataFolder(), config.getStorageFile());
        AuctionStorage created = new SQLiteAuctionStorage(dbFile, plugin.getLogger(), config.isDebug());
        try {
            // init() runs on the calling thread (which is main here). For
            // SQLite the table-creation work is sub-millisecond and only
            // happens at startup, so we don't pay the executor-hop cost.
            created.init();
            this.storage = created;
            this.active = true;
            this.inactiveReason = null;
            plugin.getLogger().info("Auction House backend ready (storage="
                    + config.getStorageType() + ").");
        } catch (AuctionStorage.StorageException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Auction House storage init failed; AH is disabled. " + ex.getMessage(), ex);
            this.active = false;
            this.inactiveReason = "storage-error";
            shutdownExecutor();
            return;
        }

        this.expiryService = new AuctionExpiryService(plugin, this);
        this.expiryService.start(config.getExpireCheckIntervalSeconds());
    }

    /**
     * Re-reads {@code auctionhouse.yml} and restarts the expiry service
     * with the new interval. The storage backend itself is not closed
     * or reopened; the SQLite file is unaffected by reloads.
     */
    public void reload() {
        AuctionConfig newConfig = new AuctionConfig(
                plugin.getConfigManager().getAuction(), plugin.getLogger());

        // Hot-swap a config-only change in the simple case (still
        // enabled, same storage type and file). Anything more invasive
        // requires a full restart of the backend.
        boolean canHotSwap = active
                && newConfig.isEnabled()
                && newConfig.getStorageType().equals(config.getStorageType())
                && newConfig.getStorageFile().equals(config.getStorageFile());

        if (canHotSwap) {
            this.config = newConfig;
            if (expiryService != null) {
                expiryService.stop();
            }
            this.expiryService = new AuctionExpiryService(plugin, this);
            this.expiryService.start(config.getExpireCheckIntervalSeconds());
            return;
        }
        disable();
        enable();
    }

    /**
     * Stops the expiry service, drains the executor and closes the
     * storage backend. Safe to call when {@link #active} is false.
     */
    public void disable() {
        if (expiryService != null) {
            expiryService.stop();
            expiryService = null;
        }
        if (dbExecutor != null) {
            // Submit close() through the executor so it runs after any
            // pending work, then shut the executor down.
            AuctionStorage local = this.storage;
            if (local != null) {
                try {
                    dbExecutor.submit(local::close).get(5, TimeUnit.SECONDS);
                } catch (Exception ex) {
                    plugin.getLogger().warning("[AH] Storage close did not finish cleanly: "
                            + ex.getMessage());
                }
            }
            shutdownExecutor();
        }
        this.storage = null;
        this.active = false;
    }

    private void shutdownExecutor() {
        if (dbExecutor == null) return;
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            dbExecutor.shutdownNow();
        }
        dbExecutor = null;
    }

    // -------------------------------------------------------------- public API

    public boolean isActive() {
        return active;
    }

    public AuctionConfig getConfig() {
        return config;
    }

    @Nullable
    public String getInactiveReason() {
        return inactiveReason;
    }

    /**
     * Creates an ACTIVE listing for the item in the seller's main hand.
     * See class-level dupe-protection contract.
     */
    public CompletableFuture<ListingCreateResult> createListing(Player seller, double price) {
        if (!active) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.disabled"));
        }
        ItemStack hand = seller.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir() || hand.getAmount() <= 0) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.invalid-item"));
        }
        Material type = hand.getType();
        if (!type.isItem()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.invalid-item"));
        }
        if (config.getBlockedMaterials().contains(type)) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.blocked-item",
                    Map.of("material", type.name())));
        }
        if (price < config.getMinPrice()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.price-too-low",
                    Map.of("min", formatMoney(config.getMinPrice()))));
        }
        if (price > config.getMaxPrice()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.price-too-high",
                    Map.of("max", formatMoney(config.getMaxPrice()))));
        }
        return createListingFromSnapshot(seller, hand.clone(), price, true);
    }

    /**
     * Creates an ACTIVE listing from an explicit stack (GUI escrow). Does not
     * read, clear, or modify the player's main hand or any other inventory slot.
     */
    public CompletableFuture<ListingCreateResult> createListing(Player seller,
                                                                ItemStack offeredItem,
                                                                double price) {
        if (!active) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.disabled"));
        }
        if (offeredItem == null || offeredItem.getType().isAir() || offeredItem.getAmount() <= 0) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.invalid-item"));
        }
        Material type = offeredItem.getType();
        if (!type.isItem()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.invalid-item"));
        }
        if (AuctionGuiItemKeys.hasInitialized() && AuctionGuiItemKeys.isGuiItem(offeredItem)) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure("auction.invalid-item"));
        }
        if (config.getBlockedMaterials().contains(type)) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.blocked-item",
                    Map.of("material", type.name())));
        }
        if (price < config.getMinPrice()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.price-too-low",
                    Map.of("min", formatMoney(config.getMinPrice()))));
        }
        if (price > config.getMaxPrice()) {
            return CompletableFuture.completedFuture(ListingCreateResult.failure(
                    "auction.price-too-high",
                    Map.of("max", formatMoney(config.getMaxPrice()))));
        }
        return createListingFromSnapshot(seller, offeredItem.clone(), price, false);
    }

    /**
     * Shared listing creation: {@code snapshot} is an isolated clone; when
     * {@code consumeFromMainHand} is {@code true} the main hand is cleared
     * after the fee succeeds (command flow). When {@code false}, no inventory
     * slot is touched (GUI escrow flow).
     */
    private CompletableFuture<ListingCreateResult> createListingFromSnapshot(Player seller,
                                                                             ItemStack snapshot,
                                                                             double price,
                                                                             boolean consumeFromMainHand) {
        final UUID sellerId = seller.getUniqueId();
        final String sellerName = seller.getName();
        CompletableFuture<ListingCreateResult> out = new CompletableFuture<>();
        runDb(() -> storage.countListingsBySellerAndStatus(sellerId, AuctionListingStatus.ACTIVE))
                .whenComplete((count, countEx) -> runOnMain(() -> {
                    if (countEx != null) {
                        logStorage("countListingsBySellerAndStatus", countEx);
                        out.complete(ListingCreateResult.failure("auction.storage-error"));
                        return;
                    }
                    int max = config.getMaxActiveListingsDefault();
                    if (!seller.hasPermission(AuctionPermission.ADMIN) && count >= max) {
                        out.complete(ListingCreateResult.failure(
                                "auction.max-listings-reached",
                                Map.of("max", Integer.toString(max))));
                        return;
                    }

                    if (!seller.isOnline()) {
                        out.complete(ListingCreateResult.failure("auction.invalid-item"));
                        return;
                    }
                    if (consumeFromMainHand) {
                        ItemStack currentHand = seller.getInventory().getItemInMainHand();
                        if (currentHand == null || !currentHand.isSimilar(snapshot)
                                || currentHand.getAmount() != snapshot.getAmount()) {
                            out.complete(ListingCreateResult.failure("auction.invalid-item"));
                            return;
                        }
                    }

                    EconomyManager economyManager = plugin.getEconomyManager();
                    boolean requireEconomy = requireEconomyForAuctionHouse();
                    double fee = config.getListingFee();
                    if ((fee > 0.0D || requireEconomy) && !economyManager.isAvailable()) {
                        out.complete(ListingCreateResult.failure("auction.economy-required"));
                        return;
                    }
                    boolean charged = false;
                    if (fee > 0.0D) {
                        EconomyTransactionResult feeResult = economyManager.getBridge()
                                .withdraw(sellerId, fee, "AH listing fee");
                        if (!feeResult.success()) {
                            out.complete(ListingCreateResult.failure(feeResult.reasonKey()));
                            return;
                        }
                        charged = true;
                    }

                    if (consumeFromMainHand) {
                        seller.getInventory().setItemInMainHand(null);
                    }

                    long now = System.currentTimeMillis();
                    long expires = now + config.getDurationMillis();
                    final boolean finalCharged = charged;
                    runDb(() -> storage.insertListing(
                            sellerId, sellerName, snapshot, price, now, expires))
                            .whenComplete((listingId, insertEx) -> runOnMain(() -> {
                                if (insertEx != null) {
                                    logStorage("insertListing", insertEx);
                                    if (consumeFromMainHand) {
                                        restoreAfterFailure(seller, snapshot, fee, finalCharged);
                                    } else {
                                        restoreAfterFailureWithoutMainHand(seller, snapshot,
                                                fee, finalCharged);
                                    }
                                    out.complete(ListingCreateResult.failure("auction.storage-error"));
                                    return;
                                }
                                if (config.isDebug()) {
                                    plugin.getLogger().info("[AH] Listing #" + listingId
                                            + " created by " + sellerName
                                            + " for " + formatMoney(price));
                                }
                                out.complete(ListingCreateResult.success(listingId, price, snapshot));
                            }));
                }));
        return out;
    }

    /**
     * Cancels an ACTIVE listing. {@code admin} controls whether the
     * caller may cancel listings owned by other players; pass {@code true}
     * for {@code /ah admin remove}.
     */
    public CompletableFuture<CancelResult> cancelListing(UUID requester,
                                                         long listingId,
                                                         boolean admin) {
        if (!active) {
            return CompletableFuture.completedFuture(CancelResult.failure("auction.disabled"));
        }
        CompletableFuture<CancelResult> out = new CompletableFuture<>();
        runDb(() -> storage.getListing(listingId))
                .whenComplete((opt, getEx) -> runOnMain(() -> {
                    if (getEx != null) {
                        logStorage("getListing", getEx);
                        out.complete(CancelResult.failure("auction.storage-error"));
                        return;
                    }
                    if (opt.isEmpty()) {
                        out.complete(CancelResult.failure("auction.listing-not-found"));
                        return;
                    }
                    AuctionListing listing = opt.get();
                    if (!admin && !listing.sellerUuid().equals(requester)) {
                        out.complete(CancelResult.failure("auction.not-your-listing"));
                        return;
                    }
                    if (listing.status() != AuctionListingStatus.ACTIVE) {
                        // Already cancelled / expired / sold - treat
                        // every non-ACTIVE state as "nothing to do" to
                        // stay idempotent against double-clicks.
                        out.complete(CancelResult.failure("auction.listing-not-found"));
                        return;
                    }
                    AuctionListingStatus newStatus = admin
                            ? AuctionListingStatus.REMOVED
                            : AuctionListingStatus.CANCELLED;
                    AuctionCollectReason reason = admin
                            ? AuctionCollectReason.ADMIN_REMOVED
                            : AuctionCollectReason.CANCELLED_LISTING;

                    runDb(() -> {
                        boolean transitioned = storage.transitionListingStatus(
                                listing.listingId(),
                                AuctionListingStatus.ACTIVE, newStatus);
                        if (!transitioned) {
                            return Boolean.FALSE;
                        }
                        storage.insertCollectItem(
                                listing.sellerUuid(),
                                listing.itemStack(),
                                reason,
                                System.currentTimeMillis(),
                                listing.listingId());
                        return Boolean.TRUE;
                    }).whenComplete((ok, txEx) -> runOnMain(() -> {
                        if (txEx != null) {
                            logStorage("cancel/transition", txEx);
                            out.complete(CancelResult.failure("auction.storage-error"));
                            return;
                        }
                        if (!ok) {
                            out.complete(CancelResult.failure("auction.listing-not-found"));
                            return;
                        }
                        out.complete(CancelResult.success(listing));
                    }));
                }));
        return out;
    }

    /**
     * Returns every ACTIVE listing owned by {@code owner}. Used by
     * {@code /ah listings}.
     */
    public CompletableFuture<List<AuctionListing>> getActiveListings(UUID owner) {
        if (!active) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<AuctionListing>> out = new CompletableFuture<>();
        runDb(() -> storage.getListingsBySellerAndStatus(owner, AuctionListingStatus.ACTIVE))
                .whenComplete((list, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("getListingsBySellerAndStatus", ex);
                        out.complete(List.of());
                        return;
                    }
                    out.complete(list);
                }));
        return out;
    }

    /**
     * Hands the player every collect row that fits into their inventory.
     * Partial stacks are split: the slice that fit is delivered, the
     * remainder stays in storage with a smaller amount. Never drops
     * items on the ground; never deletes a row whose contents were not
     * fully delivered.
     */
    public CompletableFuture<CollectResult> collectAll(Player player) {
        if (!active) {
            return CompletableFuture.completedFuture(CollectResult.failure("auction.disabled"));
        }
        UUID owner = player.getUniqueId();
        CompletableFuture<CollectResult> out = new CompletableFuture<>();
        runDb(() -> storage.getCollectItemsForOwner(owner))
                .whenComplete((rawItems, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("getCollectItemsForOwner", ex);
                        out.complete(CollectResult.failure("auction.storage-error"));
                        return;
                    }
                    List<AuctionCollectItem> items = filterGuiMarkedCollectRows(owner, rawItems);
                    if (items.isEmpty()) {
                        out.complete(CollectResult.empty());
                        return;
                    }
                    if (!player.isOnline()) {
                        // Logged off between the query and now; nothing
                        // delivered, nothing changed in storage.
                        out.complete(CollectResult.failure("general.player-only"));
                        return;
                    }
                    PlayerInventory inv = player.getInventory();
                    List<Long> toDelete = new ArrayList<>();
                    List<UpdatePartial> toUpdate = new ArrayList<>();
                    int delivered = 0;
                    int remaining = 0;
                    for (AuctionCollectItem ci : items) {
                        ItemStack candidate = ci.itemStack().clone();
                        int before = candidate.getAmount();
                        Map<Integer, ItemStack> leftover = inv.addItem(candidate);
                        if (leftover.isEmpty()) {
                            delivered++;
                            toDelete.add(ci.collectId());
                        } else {
                            ItemStack rest = leftover.values().iterator().next();
                            if (rest.getAmount() >= before) {
                                // No room at all - row untouched.
                                remaining++;
                            } else {
                                // Partial: update the row with the
                                // unfulfilled remainder.
                                delivered++;
                                remaining++;
                                toUpdate.add(new UpdatePartial(ci.collectId(), rest));
                            }
                        }
                    }
                    final int finalDelivered = delivered;
                    final int finalRemaining = remaining;
                    runDb(() -> {
                        for (Long id : toDelete) {
                            storage.deleteCollectItem(id);
                        }
                        for (UpdatePartial up : toUpdate) {
                            storage.updateCollectItemStack(up.collectId(), up.newStack());
                        }
                        return null;
                    }).whenComplete((ignored, dbEx) -> runOnMain(() -> {
                        if (dbEx != null) {
                            // Items already in inventory; flag the DB
                            // error so an admin can investigate but
                            // still report the delivery to the player.
                            logStorage("collect cleanup", dbEx);
                        }
                        if (finalRemaining == 0) {
                            out.complete(CollectResult.success(finalDelivered));
                        } else {
                            out.complete(CollectResult.partial(finalDelivered, finalRemaining));
                        }
                    }));
                }));
        return out;
    }

    /**
     * Fetches a page of ACTIVE non-expired listings for {@code /ah browse}.
     * Uses {@link AuctionConfig#getGuiBrowseDefaultSort} and no search filter
     * — the same default ordering as the GUI market when no per-player sort
     * override is in play.
     */
    public CompletableFuture<BrowsePage> browseListings(int requestedPage) {
        return browseListings(requestedPage, config.getBrowsePageSize(),
                config.getGuiBrowseDefaultSort(), null);
    }

    /**
     * Same as {@link #browseListings(int)} with an explicit page size.
     */
    public CompletableFuture<BrowsePage> browseListings(int requestedPage, int pageSize) {
        return browseListings(requestedPage, pageSize, config.getGuiBrowseDefaultSort(), null);
    }

    /**
     * Browse / search with sort and optional case-insensitive substring filter
     * (seller name, material name, item display name). All heavy work stays
     * on the DB executor.
     */
    public CompletableFuture<BrowsePage> browseListings(int requestedPage,
                                                        int pageSize,
                                                        AuctionBrowseSort sort,
                                                        @Nullable String searchQuery) {
        if (!active) {
            return CompletableFuture.completedFuture(BrowsePage.empty(requestedPage));
        }
        AuctionBrowseSort effectiveSort = sort != null ? sort : AuctionBrowseSort.NEWEST;
        int effectivePageSize = Math.max(1, pageSize);
        long now = System.currentTimeMillis();
        CompletableFuture<BrowsePage> out = new CompletableFuture<>();
        String q = searchQuery == null ? null : searchQuery.trim();
        final boolean useSearch = q != null && !q.isEmpty();
        runDb(() -> {
            if (config.isDebug()) {
                plugin.getLogger().info("[AH] browse sort=" + effectiveSort + " search="
                        + useSearch + " page=" + requestedPage);
            }
            if (!useSearch) {
                int total = storage.countActiveBrowse(now);
                if (total == 0) {
                    return new BrowsePage(List.of(), 1, 1, 0, effectivePageSize);
                }
                int totalPages = (total + effectivePageSize - 1) / effectivePageSize;
                int page = Math.max(1, Math.min(requestedPage, totalPages));
                int offset = (page - 1) * effectivePageSize;
                List<AuctionListing> rows = storage.getActiveBrowsePage(now, offset,
                        effectivePageSize, effectiveSort);
                return new BrowsePage(rows, page, totalPages, total, effectivePageSize);
            }
            List<AuctionListing> all = storage.getAllActiveBrowseListings(now);
            List<AuctionListing> filtered = AuctionListingSearch.filter(all, q);
            AuctionListingSearch.sort(filtered, effectiveSort);
            int total = filtered.size();
            if (total == 0) {
                return new BrowsePage(List.of(), 1, 1, 0, effectivePageSize);
            }
            int totalPages = (total + effectivePageSize - 1) / effectivePageSize;
            int page = Math.max(1, Math.min(requestedPage, totalPages));
            int from = (page - 1) * effectivePageSize;
            int to = Math.min(from + effectivePageSize, total);
            return new BrowsePage(filtered.subList(from, to), page, totalPages, total,
                    effectivePageSize);
        }).whenComplete((page, ex) -> runOnMain(() -> {
            if (ex != null) {
                logStorage("browseListings", ex);
                out.complete(BrowsePage.empty(requestedPage));
                return;
            }
            out.complete(page);
        }));
        return out;
    }

    /**
     * Removes terminal listing rows older than the configured retention.
     * Never deletes ACTIVE listings or collect storage.
     */
    public CompletableFuture<Integer> adminCleanupOldTerminalListings() {
        if (!active) {
            return CompletableFuture.completedFuture(0);
        }
        long cutoff = System.currentTimeMillis()
                - config.getCleanupOldListingRetentionDays() * 86_400_000L;
        CompletableFuture<Integer> out = new CompletableFuture<>();
        runDb(() -> storage.deleteTerminalListingsOlderThan(cutoff))
                .whenComplete((n, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("adminCleanupOldTerminalListings", ex);
                        out.complete(0);
                        return;
                    }
                    out.complete(n != null ? n : 0);
                }));
        return out;
    }

    /**
     * Single-row variant of {@link #collectAll(Player)} used by the GUI.
     * The dupe-protection contract is identical: full fit deletes the
     * row, partial fit updates the row to the remainder, no fit leaves
     * the row untouched. Items are never dropped, never deleted, never
     * duplicated.
     */
    public CompletableFuture<CollectOneResult> collectOne(Player player, long collectId) {
        if (!active) {
            return CompletableFuture.completedFuture(CollectOneResult.failure("auction.disabled"));
        }
        UUID owner = player.getUniqueId();
        CompletableFuture<CollectOneResult> out = new CompletableFuture<>();
        runDb(() -> storage.getCollectItemsForOwner(owner))
                .whenComplete((items, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("collectOne/list", ex);
                        out.complete(CollectOneResult.failure("auction.storage-error"));
                        return;
                    }
                    AuctionCollectItem target = null;
                    for (AuctionCollectItem ci : items) {
                        if (ci.collectId() == collectId) {
                            target = ci;
                            break;
                        }
                    }
                    if (target == null) {
                        out.complete(CollectOneResult.notFound());
                        return;
                    }
                    if (AuctionGuiItemKeys.hasInitialized() && AuctionGuiItemKeys.isGuiItem(target.itemStack())) {
                        long badId = target.collectId();
                        plugin.getLogger().warning("[AH] Purging GUI-marked collect row " + badId + " for " + owner);
                        runDb(() -> storage.deleteCollectItem(badId))
                                .whenComplete((ok, dbEx) -> runOnMain(() -> {
                                    if (dbEx != null) {
                                        logStorage("collectOne/purge-gui", dbEx);
                                    }
                                    out.complete(CollectOneResult.notFound());
                                }));
                        return;
                    }
                    if (!player.isOnline()) {
                        out.complete(CollectOneResult.failure("general.player-only"));
                        return;
                    }
                    ItemStack candidate = target.itemStack().clone();
                    int before = candidate.getAmount();
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(candidate);
                    if (leftover.isEmpty()) {
                        long id = target.collectId();
                        runDb(() -> storage.deleteCollectItem(id))
                                .whenComplete((ok, dbEx) -> runOnMain(() -> {
                                    if (dbEx != null) {
                                        logStorage("collectOne/delete", dbEx);
                                    }
                                    out.complete(CollectOneResult.delivered());
                                }));
                        return;
                    }
                    ItemStack rest = leftover.values().iterator().next();
                    if (rest.getAmount() >= before) {
                        // No room at all - row untouched, nothing was
                        // moved into the inventory.
                        out.complete(CollectOneResult.inventoryFull());
                        return;
                    }
                    long id = target.collectId();
                    ItemStack remainder = rest.clone();
                    runDb(() -> storage.updateCollectItemStack(id, remainder))
                            .whenComplete((ok, dbEx) -> runOnMain(() -> {
                                if (dbEx != null) {
                                    logStorage("collectOne/update", dbEx);
                                }
                                out.complete(CollectOneResult.partial(
                                        before - remainder.getAmount(),
                                        remainder.getAmount()));
                            }));
                }));
        return out;
    }

    /**
     * Fetches the player's collect rows for the GUI preview. Returns an
     * empty list on storage errors so the GUI can render a clean
     * "nothing to collect" state instead of an error popup.
     */
    public CompletableFuture<List<AuctionCollectItem>> getCollectItems(UUID owner) {
        if (!active) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<AuctionCollectItem>> out = new CompletableFuture<>();
        runDb(() -> storage.getCollectItemsForOwner(owner))
                .whenComplete((items, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("getCollectItems", ex);
                        out.complete(List.of());
                        return;
                    }
                    out.complete(filterGuiMarkedCollectRows(owner, items));
                }));
        return out;
    }

    /**
     * Buys the listing identified by {@code listingId} on behalf of
     * {@code buyer}. See class-level Javadoc for the dupe-protection
     * contract. The flow:
     *
     * <ol>
     *     <li>Pre-flight checks (AH active, economy available, listing
     *         exists, status, expiry, own-listing rule, balance).</li>
     *     <li>Atomic claim via {@link AuctionStorage#markSoldIfActive}
     *         - the SQL UPDATE serialises all racing buyers; only one
     *         wins. The same UPDATE writes buyer UUID + name into the
     *         row, so the SOLD state is fully formed in one round-trip.</li>
     *     <li>Withdraw buyer (main thread). On failure, async revert
     *         the claim via {@link AuctionStorage#revertSoldIfBuyer}.</li>
     *     <li>Deposit seller (main thread, net of {@code sale-tax-percent}).
     *         On failure, try to refund the buyer + revert the claim.
     *         If the refund itself fails, we cannot safely revert -
     *         keep the SOLD state, log SEVERE, and proceed with delivery
     *         so the buyer at least gets the item they paid for.</li>
     *     <li>Delivery (main thread). If the buyer's inventory has room
     *         {@code addItem} is used; otherwise the item is parked in
     *         the buyer's collect storage with reason
     *         {@link AuctionCollectReason#PURCHASED_ITEM_INVENTORY_FULL}.
     *         Items are never dropped.</li>
     * </ol>
     */
    public CompletableFuture<BuyResult> buyListing(Player buyer, long listingId) {
        if (!active) {
            return CompletableFuture.completedFuture(BuyResult.failure("auction.disabled"));
        }
        // Buying always moves money, so an economy bridge is mandatory
        // here regardless of the require-economy-for-auction-house flag
        // used for selling.
        EconomyManager economyManager = plugin.getEconomyManager();
        if (!economyManager.isAvailable()) {
            return CompletableFuture.completedFuture(BuyResult.failure("auction.economy-required"));
        }
        if (listingId <= 0L) {
            return CompletableFuture.completedFuture(BuyResult.failure("auction.buy-not-found"));
        }

        final UUID buyerId = buyer.getUniqueId();
        final String buyerName = buyer.getName();
        CompletableFuture<BuyResult> out = new CompletableFuture<>();

        runDb(() -> storage.getListing(listingId))
                .whenComplete((opt, getEx) -> runOnMain(() -> {
                    if (getEx != null) {
                        logStorage("buy/getListing", getEx);
                        out.complete(BuyResult.failure("auction.buy-storage-error"));
                        return;
                    }
                    if (opt.isEmpty()) {
                        out.complete(BuyResult.failure("auction.buy-not-found"));
                        return;
                    }
                    AuctionListing listing = opt.get();
                    long now = System.currentTimeMillis();
                    if (listing.status() == AuctionListingStatus.SOLD) {
                        out.complete(BuyResult.failure("auction.buy-already-sold"));
                        return;
                    }
                    if (listing.status() != AuctionListingStatus.ACTIVE) {
                        out.complete(BuyResult.failure("auction.buy-not-active"));
                        return;
                    }
                    if (listing.expiresAt() <= now) {
                        out.complete(BuyResult.failure("auction.buy-expired"));
                        return;
                    }
                    if (listing.sellerUuid().equals(buyerId)
                            && !config.isAllowOwnPurchase()) {
                        out.complete(BuyResult.failure("auction.buy-own-listing"));
                        return;
                    }
                    // Balance pre-check. Not authoritative on its own
                    // (the withdraw can still fail if the balance moves
                    // between the check and the withdraw) but cheap
                    // enough to avoid claiming a listing the buyer
                    // obviously cannot afford.
                    EconomyBridge bridge = economyManager.getBridge();
                    if (!bridge.hasBalance(buyerId, listing.price())) {
                        out.complete(BuyResult.failure("auction.buy-not-enough-money"));
                        return;
                    }
                    completeBuyAfterChecks(buyer, buyerId, buyerName, listing, out);
                }));
        return out;
    }

    private void completeBuyAfterChecks(Player buyer,
                                        UUID buyerId,
                                        String buyerName,
                                        AuctionListing listing,
                                        CompletableFuture<BuyResult> out) {
        long now = System.currentTimeMillis();
        runDb(() -> storage.markSoldIfActive(
                listing.listingId(), buyerId, buyerName, now))
                .whenComplete((claimed, claimEx) -> runOnMain(() -> {
                    if (claimEx != null) {
                        logStorage("buy/markSoldIfActive", claimEx);
                        out.complete(BuyResult.failure("auction.buy-storage-error"));
                        return;
                    }
                    if (!Boolean.TRUE.equals(claimed)) {
                        // Someone else won the race, or the listing expired
                        // between the pre-check and the UPDATE.
                        out.complete(BuyResult.failure("auction.buy-already-sold"));
                        return;
                    }

                    EconomyBridge bridge = plugin.getEconomyManager().getBridge();
                    double price = listing.price();
                    EconomyTransactionResult withdraw = bridge.withdraw(
                            buyerId, price, "AH purchase #" + listing.listingId());
                    if (!withdraw.success()) {
                        // Roll the claim back so the seller's listing
                        // is on the market again. revert is best-effort:
                        // if it fails the SOLD row sits there until an
                        // admin deals with it - but the buyer never lost
                        // money in this branch.
                        runDb(() -> storage.revertSoldIfBuyer(listing.listingId(), buyerId))
                                .whenComplete((reverted, revEx) -> {
                                    if (revEx != null) {
                                        logStorage("buy/revertAfterWithdrawFail", revEx);
                                    }
                                });
                        String key = "economy.insufficient-funds".equals(withdraw.reasonKey())
                                ? "auction.buy-not-enough-money"
                                : "auction.buy-economy-failed";
                        out.complete(BuyResult.failure(key));
                        return;
                    }

                    // Sale tax: gross stays with the buyer-debited
                    // economy; only the net sellerPayout is deposited
                    // to the seller. Tax never lands in any account -
                    // it is simply not paid out, which is the standard
                    // behavior of EssentialsX/CMI auction implementations.
                    double tax = roundCents(price * config.getSaleTaxPercent() / 100.0D);
                    double sellerPayout = Math.max(0.0D, roundCents(price - tax));

                    EconomyTransactionResult deposit = bridge.deposit(
                            listing.sellerUuid(), sellerPayout,
                            "AH payout #" + listing.listingId());
                    if (!deposit.success()) {
                        handleSellerDepositFailure(buyer, buyerId, listing, price, tax, sellerPayout, out);
                        return;
                    }

                    deliverPurchasedItem(buyer, buyerId, listing, price, tax, sellerPayout, out);
                }));
    }

    /**
     * Recovery path for "buyer was charged but seller could not be paid".
     * Tries to refund the buyer and put the listing back on the market.
     * If even the refund fails, we keep SOLD + deliver the item: the
     * buyer paid, so they get the goods, and the seller needs admin
     * help to receive their payout.
     */
    private void handleSellerDepositFailure(Player buyer,
                                            UUID buyerId,
                                            AuctionListing listing,
                                            double price,
                                            double tax,
                                            double sellerPayout,
                                            CompletableFuture<BuyResult> out) {
        EconomyBridge bridge = plugin.getEconomyManager().getBridge();
        EconomyTransactionResult refund = bridge.deposit(
                buyerId, price, "AH purchase refund (seller payout failed) #" + listing.listingId());
        if (refund.success()) {
            runDb(() -> storage.revertSoldIfBuyer(listing.listingId(), buyerId))
                    .whenComplete((reverted, revEx) -> {
                        if (revEx != null) {
                            logStorage("buy/revertAfterSellerDepositFail", revEx);
                        }
                    });
            out.complete(BuyResult.failure("auction.seller-payout-failed"));
            return;
        }
        // Refund failed too: do not strand the buyer. They paid in full,
        // so they get the item. Log SEVERE so an admin can resolve the
        // unpaid seller payout manually.
        plugin.getLogger().severe("[AH] Seller payout AND buyer refund failed for listing #"
                + listing.listingId() + " seller=" + listing.sellerName()
                + " buyer=" + buyer.getName()
                + " price=" + formatMoney(price)
                + " payout=" + formatMoney(sellerPayout)
                + " - delivering item to buyer; manual admin intervention required.");
        deliverPurchasedItem(buyer, buyerId, listing, price, tax, sellerPayout, out);
    }

    private void deliverPurchasedItem(Player buyer,
                                      UUID buyerId,
                                      AuctionListing listing,
                                      double price,
                                      double tax,
                                      double sellerPayout,
                                      CompletableFuture<BuyResult> out) {
        if (!buyer.isOnline()) {
            // Buyer logged off between paying and delivery - park the
            // whole item in their collect storage.
            parkPurchaseInCollect(buyer, buyerId, listing, price, tax, sellerPayout, out);
            return;
        }
        ItemStack delivery = listing.itemStack().clone();
        int before = delivery.getAmount();
        Map<Integer, ItemStack> leftover = buyer.getInventory().addItem(delivery);
        if (leftover.isEmpty()) {
            if (config.isDebug()) {
                plugin.getLogger().info("[AH] Buyer " + buyer.getName()
                        + " bought #" + listing.listingId()
                        + " for " + formatMoney(price));
            }
            out.complete(BuyResult.success(listing, price, tax, sellerPayout, false));
            return;
        }
        // Partial or no-room delivery. Park the leftover in collect
        // storage with the buyer-purchase reason. We never call
        // delete-and-retry on the already-delivered slice because the
        // player legitimately owns those items now (they paid full price).
        ItemStack remainder = leftover.values().iterator().next();
        int deliveredAmount = before - remainder.getAmount();
        if (config.isDebug()) {
            plugin.getLogger().info("[AH] Buyer " + buyer.getName()
                    + " bought #" + listing.listingId()
                    + "; " + deliveredAmount + "/" + before
                    + " delivered, remainder to collect storage.");
        }
        ItemStack toCollect = remainder.clone();
        runDb(() -> {
            storage.insertCollectItem(
                    buyerId,
                    toCollect,
                    AuctionCollectReason.PURCHASED_ITEM_INVENTORY_FULL,
                    System.currentTimeMillis(),
                    listing.listingId());
            return null;
        }).whenComplete((ignored, ex) -> runOnMain(() -> {
            if (ex != null) {
                // Catastrophic: buyer paid but we cannot store the
                // leftover. Log SEVERE with the serialised description
                // so an admin can recover it.
                plugin.getLogger().severe("[AH] Could not park purchase remainder for "
                        + buyer.getName() + " listing #" + listing.listingId()
                        + " item=" + toCollect.getType() + " amount=" + toCollect.getAmount()
                        + " - " + ex.getMessage());
            }
            out.complete(BuyResult.success(listing, price, tax, sellerPayout, true));
        }));
    }

    private void parkPurchaseInCollect(Player buyer,
                                       UUID buyerId,
                                       AuctionListing listing,
                                       double price,
                                       double tax,
                                       double sellerPayout,
                                       CompletableFuture<BuyResult> out) {
        ItemStack toCollect = listing.itemStack().clone();
        runDb(() -> {
            storage.insertCollectItem(
                    buyerId,
                    toCollect,
                    AuctionCollectReason.PURCHASED_ITEM_INVENTORY_FULL,
                    System.currentTimeMillis(),
                    listing.listingId());
            return null;
        }).whenComplete((ignored, ex) -> runOnMain(() -> {
            if (ex != null) {
                plugin.getLogger().severe("[AH] Offline buyer " + buyer.getName()
                        + " bought #" + listing.listingId()
                        + " but could not store item: " + ex.getMessage());
            }
            out.complete(BuyResult.success(listing, price, tax, sellerPayout, true));
        }));
    }

    public CompletableFuture<Stats> getStats() {
        CompletableFuture<Stats> out = new CompletableFuture<>();
        if (!active) {
            EconomyBridge bridge = plugin.getEconomyManager().getBridge();
            out.complete(new Stats(
                    false,
                    inactiveReason != null ? inactiveReason : "disabled",
                    config != null ? config.getStorageType() : "-",
                    0, 0, 0, 0, 0,
                    config != null ? config.getSaleTaxPercent() : 0.0D,
                    bridge.providerType().name(),
                    bridge.providerName(),
                    bridge.isAvailable()));
            return out;
        }
        runDb(() -> {
            int activeCount = storage.countListingsByStatus(AuctionListingStatus.ACTIVE);
            int soldCount = storage.countListingsByStatus(AuctionListingStatus.SOLD);
            int expiredCount = storage.countListingsByStatus(AuctionListingStatus.EXPIRED);
            int cancelledCount = storage.countListingsByStatus(AuctionListingStatus.CANCELLED);
            int collect = storage.countCollectItems();
            return new int[]{activeCount, soldCount, expiredCount, cancelledCount, collect};
        }).whenComplete((counts, ex) -> runOnMain(() -> {
            EconomyBridge bridge = plugin.getEconomyManager().getBridge();
            if (ex != null) {
                logStorage("getStats", ex);
                out.complete(new Stats(
                        true, "ok", config.getStorageType(),
                        -1, -1, -1, -1, -1,
                        config.getSaleTaxPercent(),
                        bridge.providerType().name(),
                        bridge.providerName(),
                        bridge.isAvailable()));
                return;
            }
            out.complete(new Stats(
                    true, "ok", config.getStorageType(),
                    counts[0], counts[1], counts[2], counts[3], counts[4],
                    config.getSaleTaxPercent(),
                    bridge.providerType().name(),
                    bridge.providerName(),
                    bridge.isAvailable()));
        }));
        return out;
    }

    private static double roundCents(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    // -------------------------------------------------- package-private helpers

    /**
     * Used by {@link AuctionExpiryService} to drive the expiry batch.
     * The call returns synchronously on the executor thread because
     * the service already hops to the executor; the contract is that
     * callers must already be off the main thread.
     */
    int runExpiryPass() {
        if (!active) return 0;
        long now = System.currentTimeMillis();
        int moved = 0;
        try {
            List<AuctionListing> expired = storage.getExpiredActiveListings(
                    now, EXPIRY_BATCH_LIMIT);
            for (AuctionListing listing : expired) {
                // The transition guard makes this idempotent: if
                // another pass already flipped the status, we skip
                // the collect insert entirely.
                boolean transitioned = storage.transitionListingStatus(
                        listing.listingId(),
                        AuctionListingStatus.ACTIVE,
                        AuctionListingStatus.EXPIRED);
                if (!transitioned) continue;
                storage.insertCollectItem(
                        listing.sellerUuid(),
                        listing.itemStack(),
                        AuctionCollectReason.EXPIRED_LISTING,
                        now,
                        listing.listingId());
                moved++;
            }
        } catch (AuctionStorage.StorageException ex) {
            logStorage("expiry pass", ex);
        }
        return moved;
    }

    ExecutorService dbExecutor() {
        return dbExecutor;
    }

    boolean isDebug() {
        return config != null && config.isDebug();
    }

    // ------------------------------------------------------------- private utils

    /** Submits a DB call to the executor and returns a future for the result. */
    private <T> CompletableFuture<T> runDb(SqlCallable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        if (dbExecutor == null) {
            f.completeExceptionally(new AuctionStorage.StorageException(
                    "DB executor not initialised"));
            return f;
        }
        dbExecutor.submit(() -> {
            try {
                f.complete(work.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /** Runs {@code task} on the main server thread immediately or via the scheduler. */
    private void runOnMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    private void restoreAfterFailure(Player seller,
                                     ItemStack snapshot,
                                     double fee,
                                     boolean charged) {
        // Refund first. If refunding fails we still try to return the
        // item; the player only loses the fee, which is logged below.
        if (charged) {
            EconomyTransactionResult refund = plugin.getEconomyManager().getBridge()
                    .deposit(seller.getUniqueId(), fee, "AH listing fee refund (storage error)");
            if (!refund.success()) {
                plugin.getLogger().severe("[AH] Refund failed for " + seller.getName()
                        + " amount=" + formatMoney(fee) + " reason=" + refund.reasonKey());
            }
        }
        if (seller.isOnline()) {
            PlayerInventory inv = seller.getInventory();
            ItemStack current = inv.getItemInMainHand();
            if (current == null || current.getType().isAir()) {
                inv.setItemInMainHand(snapshot);
                return;
            }
            // Hand re-occupied (another item picked up while we were
            // talking to the DB). Park in collect storage instead.
        }
        runDb(() -> {
            storage.insertCollectItem(
                    seller.getUniqueId(),
                    snapshot,
                    AuctionCollectReason.SYSTEM_RETURN,
                    System.currentTimeMillis(),
                    null);
            return null;
        }).whenComplete((ignored, ex) -> {
            if (ex != null) {
                plugin.getLogger().severe("[AH] Failed to park rescued item for "
                        + seller.getName() + " in collect storage: "
                        + ex.getMessage() + " (item type=" + snapshot.getType()
                        + " amount=" + snapshot.getAmount() + ")");
            }
        });
    }

    /**
     * Same as {@link #restoreAfterFailure(Player, ItemStack, double, boolean)}
     * for GUI listings: returns the snapshot via {@link #safeReturnItemOrCollect}
     * instead of forcing the main hand.
     */
    private void restoreAfterFailureWithoutMainHand(Player seller,
                                                    ItemStack snapshot,
                                                    double fee,
                                                    boolean charged) {
        if (charged) {
            EconomyTransactionResult refund = plugin.getEconomyManager().getBridge()
                    .deposit(seller.getUniqueId(), fee, "AH listing fee refund (storage error)");
            if (!refund.success()) {
                plugin.getLogger().severe("[AH] Refund failed for " + seller.getName()
                        + " amount=" + formatMoney(fee) + " reason=" + refund.reasonKey());
            }
        }
        if (seller.isOnline()) {
            safeReturnItemOrCollect(seller, snapshot.clone());
        } else {
            parkGuiReturnedItem(seller.getUniqueId(), snapshot.clone());
        }
    }

    /**
     * Drops CPSMP GUI chrome rows that were mistakenly parked in collect
     * storage (PDC {@code auction_gui_item}). Legitimate items are never tagged.
     */
    private List<AuctionCollectItem> filterGuiMarkedCollectRows(UUID owner, List<AuctionCollectItem> fromDb) {
        if (fromDb.isEmpty() || !AuctionGuiItemKeys.hasInitialized()) {
            return fromDb;
        }
        List<Long> purgeIds = new ArrayList<>();
        List<AuctionCollectItem> kept = new ArrayList<>();
        for (AuctionCollectItem ci : fromDb) {
            if (AuctionGuiItemKeys.isGuiItem(ci.itemStack())) {
                purgeIds.add(ci.collectId());
            } else {
                kept.add(ci);
            }
        }
        if (!purgeIds.isEmpty()) {
            plugin.getLogger().warning("[AH] Removing " + purgeIds.size()
                    + " orphaned CPSMP-GUI collect row(s) for " + owner);
            runDb(() -> {
                for (Long id : purgeIds) {
                    storage.deleteCollectItem(id);
                }
                return null;
            });
        }
        return kept;
    }

    /**
     * Returns {@code stack} to an online player's inventory (empty main
     * hand preferred, then {@link PlayerInventory#addItem(ItemStack...)}).
     * Any remainder is written to collect storage with {@link
     * AuctionCollectReason#SYSTEM_RETURN}. Does not touch economy.
     *
     * <p>Used by the V2.4 sell GUI when a flow is cancelled or the player
     * disconnects while an item is held in GUI escrow. Listing creation
     * from escrow uses {@link #createListing(Player, ItemStack, double)}
     * (main hand unchanged); {@link #createListing(Player, double)} is
     * for {@code /ah sell} only.
     */
    public void safeReturnItemOrCollect(Player player, ItemStack stack) {
        if (!active || stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        if (AuctionGuiItemKeys.hasInitialized() && AuctionGuiItemKeys.isGuiItem(stack)) {
            plugin.getLogger().warning("[AH] Ignoring GUI-tagged item in safeReturnItemOrCollect for "
                    + (player.isOnline() ? player.getName() : player.getUniqueId().toString()));
            return;
        }
        if (!player.isOnline()) {
            parkGuiReturnedItem(player.getUniqueId(), stack.clone());
            return;
        }
        PlayerInventory inv = player.getInventory();
        ItemStack hand = inv.getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            inv.setItemInMainHand(stack.clone());
            return;
        }
        var left = inv.addItem(stack.clone());
        if (left.isEmpty()) {
            return;
        }
        parkGuiReturnedItem(player.getUniqueId(), left.values().iterator().next());
    }

    private void parkGuiReturnedItem(UUID owner, ItemStack stack) {
        if (AuctionGuiItemKeys.hasInitialized() && AuctionGuiItemKeys.isGuiItem(stack)) {
            plugin.getLogger().warning("[AH] Ignoring GUI-tagged item in parkGuiReturnedItem for " + owner);
            return;
        }
        runDb(() -> {
            storage.insertCollectItem(
                    owner,
                    stack,
                    AuctionCollectReason.SYSTEM_RETURN,
                    System.currentTimeMillis(),
                    null);
            return null;
        }).whenComplete((ignored, ex) -> {
            if (ex != null) {
                plugin.getLogger().severe("[AH] Failed to park GUI-returned item for "
                        + owner + ": " + ex.getMessage());
            }
        });
    }

    private void logStorage(String op, Throwable ex) {
        plugin.getLogger().log(Level.WARNING, "[AH] Storage operation '" + op + "' failed", ex);
    }

    private boolean requireEconomyForAuctionHouse() {
        return plugin.getConfigManager().getEconomy()
                .getBoolean("economy.require-economy-for-auction-house", true);
    }

    private String formatMoney(double amount) {
        EconomyBridge bridge = plugin.getEconomyManager().getBridge();
        try {
            return bridge.format(amount);
        } catch (Throwable t) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }
    }

    public String formatPrice(double amount) {
        return formatMoney(amount);
    }

    // ------------------------------------------------------------ result types

    /**
     * Output of {@link #createListing(Player, double)}. Sealed so the
     * command layer can {@code switch} over the alternatives without a
     * default branch.
     */
    public sealed interface ListingCreateResult {
        record Success(long listingId, double price, ItemStack snapshot)
                implements ListingCreateResult {}
        record Failure(String messageKey, Map<String, String> placeholders)
                implements ListingCreateResult {}

        static ListingCreateResult success(long id, double price, ItemStack snapshot) {
            return new Success(id, price, snapshot);
        }
        static ListingCreateResult failure(String key) {
            return new Failure(key, Map.of());
        }
        static ListingCreateResult failure(String key, Map<String, String> placeholders) {
            return new Failure(key, placeholders);
        }
    }

    public sealed interface CancelResult {
        record Success(AuctionListing listing) implements CancelResult {}
        record Failure(String messageKey) implements CancelResult {}

        static CancelResult success(AuctionListing listing) {
            return new Success(listing);
        }
        static CancelResult failure(String key) {
            return new Failure(key);
        }
    }

    public sealed interface CollectResult {
        record Empty() implements CollectResult {}
        record Success(int delivered) implements CollectResult {}
        record Partial(int delivered, int remaining) implements CollectResult {}
        record Failure(String messageKey) implements CollectResult {}

        static CollectResult empty() {
            return new Empty();
        }
        static CollectResult success(int delivered) {
            return new Success(delivered);
        }
        static CollectResult partial(int delivered, int remaining) {
            return new Partial(delivered, remaining);
        }
        static CollectResult failure(String key) {
            return new Failure(key);
        }
    }

    /** Output of {@link #collectOne(Player, long)}. */
    public sealed interface CollectOneResult {
        record Delivered() implements CollectOneResult {}
        record Partial(int deliveredAmount, int remainingAmount) implements CollectOneResult {}
        record InventoryFull() implements CollectOneResult {}
        record NotFound() implements CollectOneResult {}
        record Failure(String messageKey) implements CollectOneResult {}

        static CollectOneResult delivered() {
            return new Delivered();
        }
        static CollectOneResult partial(int delivered, int remaining) {
            return new Partial(delivered, remaining);
        }
        static CollectOneResult inventoryFull() {
            return new InventoryFull();
        }
        static CollectOneResult notFound() {
            return new NotFound();
        }
        static CollectOneResult failure(String key) {
            return new Failure(key);
        }
    }

    /** Diagnostic snapshot served to {@code /ah admin info} and /cpsmpadmin info. */
    public record Stats(
            boolean active,
            String inactiveReason,
            String storageType,
            int activeListings,
            int soldListings,
            int expiredListings,
            int cancelledListings,
            int collectItems,
            double saleTaxPercent,
            String economyBridge,
            String economyProvider,
            boolean economyAvailable
    ) {}

    /**
     * Output of {@link #browseListings(int)}. {@code page} and
     * {@code totalPages} are 1-indexed; the empty-market state still
     * reports {@code totalPages=1} so the message renders cleanly.
     */
    public record BrowsePage(
            List<AuctionListing> listings,
            int page,
            int totalPages,
            int totalListings,
            int pageSize
    ) {
        static BrowsePage empty(int requestedPage) {
            return new BrowsePage(List.of(), Math.max(1, requestedPage), 1, 0, 0);
        }
    }

    /**
     * Output of {@link #buyListing(Player, long)}. Success carries the
     * full economic summary so the command layer can render a single
     * German confirmation line plus an optional sale-tax info line.
     */
    public sealed interface BuyResult {
        record Success(AuctionListing listing,
                       double price,
                       double tax,
                       double sellerPayout,
                       boolean inventoryFull) implements BuyResult {}
        record Failure(String messageKey, Map<String, String> placeholders) implements BuyResult {}

        static BuyResult success(AuctionListing listing,
                                 double price,
                                 double tax,
                                 double sellerPayout,
                                 boolean inventoryFull) {
            return new Success(listing, price, tax, sellerPayout, inventoryFull);
        }
        static BuyResult failure(String key) {
            return new Failure(key, Map.of());
        }
        static BuyResult failure(String key, Map<String, String> placeholders) {
            return new Failure(key, placeholders);
        }
    }

    /** Internal callable that may throw a {@link AuctionStorage.StorageException}. */
    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws AuctionStorage.StorageException;
    }

    private record UpdatePartial(long collectId, ItemStack newStack) {}
}
