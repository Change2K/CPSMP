package de.deinserver.cpsmp.auction;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Typed, immutable snapshot of {@code auctionhouse.yml}. {@link
 * AuctionHouseManager} keeps a single instance live at a time and swaps
 * it out on reload so other components (command, expiry service) never
 * see a half-updated config.
 *
 * <p>All numeric bounds are clamped to safe defaults if the admin enters
 * something pathological (negative durations, infinite prices, etc.).
 * Blocked materials are resolved through {@link Material#matchMaterial}
 * so admins can type either the modern enum name or the legacy
 * lowercase form.
 */
public final class AuctionConfig {

    private final boolean enabled;
    private final String storageType;
    private final String storageFile;

    private final long durationMillis;
    private final int maxActiveListingsDefault;
    private final double minPrice;
    private final double maxPrice;
    private final boolean allowOwnPurchase;

    private final double listingFee;
    private final double saleTaxPercent;

    private final double expensivePurchaseThreshold;

    private final Set<Material> blockedMaterials;

    private final long expireCheckIntervalSeconds;

    private final int browsePageSize;

    private final boolean guiEnabled;
    private final int guiRowsMain;
    private final int guiRowsBrowse;
    private final int guiRowsListings;
    private final int guiRowsCollect;
    private final boolean guiRefreshAfterAction;
    private final boolean guiConfirmationEnabled;
    private final boolean guiFillerEnabled;
    private final Material guiFillerMaterial;
    private final String guiFillerName;

    private final boolean debug;

    public AuctionConfig(FileConfiguration cfg, Logger logger) {
        this.enabled = cfg.getBoolean("enabled", true);

        this.storageType = cfg.getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        this.storageFile = cfg.getString("storage.file", "auctionhouse.db");

        // Listing duration is clamped to [1 hour, 365 days] - protects
        // against admins typing 0 (would expire instantly) or absurdly
        // large numbers that overflow when added to System.currentTimeMillis.
        double rawHours = cfg.getDouble("listings.duration-hours", 72.0D);
        long hoursClamped = (long) Math.max(1.0D, Math.min(rawHours, 24.0D * 365.0D));
        this.durationMillis = hoursClamped * 60L * 60L * 1000L;

        this.maxActiveListingsDefault = Math.max(0,
                cfg.getInt("listings.max-active-listings-default", 5));
        this.minPrice = Math.max(0.0D, cfg.getDouble("listings.min-price", 1.0D));
        this.maxPrice = Math.max(this.minPrice,
                cfg.getDouble("listings.max-price", 1_000_000.0D));
        this.allowOwnPurchase = cfg.getBoolean("listings.allow-own-purchase", false);

        this.listingFee = Math.max(0.0D, cfg.getDouble("fees.listing-fee", 0.0D));
        this.saleTaxPercent = Math.max(0.0D, cfg.getDouble("fees.sale-tax-percent", 5.0D));

        this.expensivePurchaseThreshold = Math.max(0.0D,
                cfg.getDouble("confirmations.expensive-purchase-threshold", 10_000.0D));

        this.blockedMaterials = new HashSet<>();
        List<String> rawBlocked = cfg.getStringList("blocked-items.materials");
        for (String name : rawBlocked) {
            if (name == null || name.isBlank()) continue;
            Material material = Material.matchMaterial(name.trim());
            if (material != null) {
                this.blockedMaterials.add(material);
            } else {
                logger.warning("Unknown blocked-items material: " + name);
            }
        }

        // Expiry interval is clamped to [10 s, 1 h]. Faster than 10 s
        // would just burn CPU; slower than an hour delays return of
        // expired items to the seller's collect storage too much.
        long rawInterval = cfg.getLong("cleanup.expire-check-interval-seconds", 60L);
        this.expireCheckIntervalSeconds = Math.max(10L, Math.min(rawInterval, 3600L));

        // /ah browse pagination. Clamped to [3, 50] to keep the text
        // overview readable in chat. Defaults to 8.
        int rawPageSize = cfg.getInt("browse.page-size", 8);
        this.browsePageSize = Math.max(3, Math.min(rawPageSize, 50));

        // V2.3 GUI settings. Row counts are clamped to Bukkit's legal
        // inventory sizes (1..6 rows = 9..54 slots). The main hub is
        // capped at 3 rows because the Apple-like layout never needs
        // more than five buttons.
        this.guiEnabled = cfg.getBoolean("gui.enabled", true);
        this.guiRowsMain = clampRows(cfg.getInt("gui.rows.main", 3), 1, 3);
        this.guiRowsBrowse = clampRows(cfg.getInt("gui.rows.browse", 6), 3, 6);
        this.guiRowsListings = clampRows(cfg.getInt("gui.rows.listings", 6), 3, 6);
        this.guiRowsCollect = clampRows(cfg.getInt("gui.rows.collect", 6), 3, 6);
        this.guiRefreshAfterAction = cfg.getBoolean("gui.refresh-after-action", true);
        this.guiConfirmationEnabled = cfg.getBoolean("gui.confirmation.enabled", true);
        this.guiFillerEnabled = cfg.getBoolean("gui.filler.enabled", true);
        String fillerRaw = cfg.getString("gui.filler.material", "GRAY_STAINED_GLASS_PANE");
        Material fillerResolved = Material.matchMaterial(fillerRaw != null ? fillerRaw.trim() : "");
        if (fillerResolved == null || !fillerResolved.isItem()) {
            logger.log(Level.WARNING, "Unknown gui.filler.material '" + fillerRaw
                    + "', falling back to GRAY_STAINED_GLASS_PANE.");
            fillerResolved = Material.GRAY_STAINED_GLASS_PANE;
        }
        this.guiFillerMaterial = fillerResolved;
        this.guiFillerName = cfg.getString("gui.filler.name", " ");

        this.debug = cfg.getBoolean("debug", false);
    }

    private static int clampRows(int requested, int min, int max) {
        return Math.max(min, Math.min(requested, max));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getStorageType() {
        return storageType;
    }

    public String getStorageFile() {
        return storageFile;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public int getMaxActiveListingsDefault() {
        return maxActiveListingsDefault;
    }

    public double getMinPrice() {
        return minPrice;
    }

    public double getMaxPrice() {
        return maxPrice;
    }

    public boolean isAllowOwnPurchase() {
        return allowOwnPurchase;
    }

    public double getListingFee() {
        return listingFee;
    }

    public double getSaleTaxPercent() {
        return saleTaxPercent;
    }

    public double getExpensivePurchaseThreshold() {
        return expensivePurchaseThreshold;
    }

    public Set<Material> getBlockedMaterials() {
        return blockedMaterials;
    }

    public long getExpireCheckIntervalSeconds() {
        return expireCheckIntervalSeconds;
    }

    public int getBrowsePageSize() {
        return browsePageSize;
    }

    public boolean isGuiEnabled() {
        return guiEnabled;
    }

    public int getGuiRowsMain() {
        return guiRowsMain;
    }

    public int getGuiRowsBrowse() {
        return guiRowsBrowse;
    }

    public int getGuiRowsListings() {
        return guiRowsListings;
    }

    public int getGuiRowsCollect() {
        return guiRowsCollect;
    }

    public boolean isGuiRefreshAfterAction() {
        return guiRefreshAfterAction;
    }

    public boolean isGuiConfirmationEnabled() {
        return guiConfirmationEnabled;
    }

    public boolean isGuiFillerEnabled() {
        return guiFillerEnabled;
    }

    public Material getGuiFillerMaterial() {
        return guiFillerMaterial;
    }

    public String getGuiFillerName() {
        return guiFillerName;
    }

    public boolean isDebug() {
        return debug;
    }
}
