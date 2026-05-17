package de.deinserver.cpsmp.auction;

import de.deinserver.cpsmp.CPSMPPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/**
 * Periodic task that walks the {@code auction_listings} table and moves
 * every expired ACTIVE listing into the seller's collect storage.
 *
 * <p>Runs entirely off-thread (async Bukkit task) and never touches the
 * Bukkit API. Idempotency is delegated to
 * {@link AuctionStorage#transitionListingStatus} - even if two passes
 * race on the same listing only one will flip the status, and only
 * that pass writes the collect row.
 *
 * <p>Lifecycle is driven by {@link AuctionHouseManager}:
 * {@link #start(long)} on enable / reload, {@link #stop()} on disable.
 * Calling {@link #start(long)} on a running service is a no-op.
 */
public final class AuctionExpiryService {

    private final CPSMPPlugin plugin;
    private final AuctionHouseManager manager;

    @Nullable
    private BukkitTask task;

    public AuctionExpiryService(CPSMPPlugin plugin, AuctionHouseManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    /**
     * Starts the periodic expiry pass.
     *
     * @param intervalSeconds seconds between passes; the first pass
     *                        runs after a one-{@code intervalSeconds}
     *                        delay so we don't fight with other plugin
     *                        startup work.
     */
    public synchronized void start(long intervalSeconds) {
        if (task != null) return;
        long ticks = Math.max(20L, intervalSeconds * 20L);
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::runPass,
                ticks,
                ticks);
        if (manager.isDebug()) {
            plugin.getLogger().info("[AH] Expiry service started "
                    + "(every " + intervalSeconds + "s)");
        }
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
            if (manager.isDebug()) {
                plugin.getLogger().info("[AH] Expiry service stopped");
            }
        }
    }

    /**
     * Runs one pass. Called from Bukkit's async scheduler thread but
     * delegates the actual work to the manager's storage executor so
     * SQLite only ever sees calls from a single thread.
     */
    private void runPass() {
        if (!manager.isActive()) return;
        if (manager.dbExecutor() == null) return;
        manager.dbExecutor().submit(() -> {
            int moved = manager.runExpiryPass();
            if (moved > 0 && manager.isDebug()) {
                plugin.getLogger().info("[AH] Expiry pass moved "
                        + moved + " listing(s) into collect storage.");
            }
        });
    }
}
