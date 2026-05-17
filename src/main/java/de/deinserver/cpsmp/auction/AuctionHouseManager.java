package de.deinserver.cpsmp.auction;

import de.deinserver.cpsmp.CPSMPPlugin;
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

        // Snapshot the hand so subsequent player actions cannot mutate
        // the value we're about to validate, charge for and persist.
        final ItemStack snapshot = hand.clone();
        final UUID sellerId = seller.getUniqueId();
        final String sellerName = seller.getName();

        // Active-listing count requires the DB; do it on the executor.
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

                    // Re-verify the player is online and still holds the
                    // exact item we validated. Equality uses Bukkit's
                    // isSimilar()+amount check so meta differences caused
                    // by e.g. enchanting in the meantime are caught.
                    if (!seller.isOnline()) {
                        out.complete(ListingCreateResult.failure("auction.invalid-item"));
                        return;
                    }
                    ItemStack currentHand = seller.getInventory().getItemInMainHand();
                    if (currentHand == null || !currentHand.isSimilar(snapshot)
                            || currentHand.getAmount() != snapshot.getAmount()) {
                        out.complete(ListingCreateResult.failure("auction.invalid-item"));
                        return;
                    }

                    // Economy gate. Two layers:
                    //   (a) fee > 0 requires an available bridge.
                    //   (b) the config flag require-economy-for-auction-house
                    //       gates selling entirely.
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
                            // Map the bridge reason key through so the
                            // player sees e.g. "Du hast nicht genug Geld."
                            out.complete(ListingCreateResult.failure(feeResult.reasonKey()));
                            return;
                        }
                        charged = true;
                    }

                    // Atomic hand clear. From here on, the item exists
                    // only in `snapshot` until the DB insert lands.
                    seller.getInventory().setItemInMainHand(null);

                    long now = System.currentTimeMillis();
                    long expires = now + config.getDurationMillis();
                    final boolean finalCharged = charged;
                    runDb(() -> storage.insertListing(
                            sellerId, sellerName, snapshot, price, now, expires))
                            .whenComplete((listingId, insertEx) -> runOnMain(() -> {
                                if (insertEx != null) {
                                    logStorage("insertListing", insertEx);
                                    // Restore the item and refund the
                                    // fee before completing the future
                                    // so the player ends up whole.
                                    restoreAfterFailure(seller, snapshot, fee, finalCharged);
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
                .whenComplete((items, ex) -> runOnMain(() -> {
                    if (ex != null) {
                        logStorage("getCollectItemsForOwner", ex);
                        out.complete(CollectResult.failure("auction.storage-error"));
                        return;
                    }
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

    public CompletableFuture<Stats> getStats() {
        CompletableFuture<Stats> out = new CompletableFuture<>();
        if (!active) {
            EconomyBridge bridge = plugin.getEconomyManager().getBridge();
            out.complete(new Stats(
                    false,
                    inactiveReason != null ? inactiveReason : "disabled",
                    config != null ? config.getStorageType() : "-",
                    0, 0, 0, 0,
                    bridge.providerType().name(),
                    bridge.providerName(),
                    bridge.isAvailable()));
            return out;
        }
        runDb(() -> {
            int activeCount = storage.countListingsByStatus(AuctionListingStatus.ACTIVE);
            int expiredCount = storage.countListingsByStatus(AuctionListingStatus.EXPIRED);
            int cancelledCount = storage.countListingsByStatus(AuctionListingStatus.CANCELLED);
            int collect = storage.countCollectItems();
            return new int[]{activeCount, expiredCount, cancelledCount, collect};
        }).whenComplete((counts, ex) -> runOnMain(() -> {
            EconomyBridge bridge = plugin.getEconomyManager().getBridge();
            if (ex != null) {
                logStorage("getStats", ex);
                out.complete(new Stats(
                        true, "ok", config.getStorageType(),
                        -1, -1, -1, -1,
                        bridge.providerType().name(),
                        bridge.providerName(),
                        bridge.isAvailable()));
                return;
            }
            out.complete(new Stats(
                    true, "ok", config.getStorageType(),
                    counts[0], counts[1], counts[2], counts[3],
                    bridge.providerType().name(),
                    bridge.providerName(),
                    bridge.isAvailable()));
        }));
        return out;
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

    /** Diagnostic snapshot served to {@code /ah admin info} and /cpsmpadmin info. */
    public record Stats(
            boolean active,
            String inactiveReason,
            String storageType,
            int activeListings,
            int expiredListings,
            int cancelledListings,
            int collectItems,
            String economyBridge,
            String economyProvider,
            boolean economyAvailable
    ) {}

    /** Internal callable that may throw a {@link AuctionStorage.StorageException}. */
    @FunctionalInterface
    private interface SqlCallable<T> {
        T call() throws AuctionStorage.StorageException;
    }

    private record UpdatePartial(long collectId, ItemStack newStack) {}
}
