package de.deinserver.cpsmp.auction;

import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite-backed {@link AuctionStorage}. Designed to be the only thread
 * touching the database file - {@link AuctionHouseManager} routes every
 * call through a single-threaded executor, which keeps SQLite happy
 * (no SQLITE_BUSY) without per-method synchronisation.
 *
 * <p>The JDBC driver itself is provided at runtime by Paper's
 * {@code libraries:} loader (see {@code plugin.yml}). On Spigot the
 * driver is not loaded; this class then throws a clean
 * {@link StorageException} from {@link #init()} so the manager can
 * disable the Auction House without bringing down the whole plugin.
 *
 * <p>Item stacks are stored as Base64 strings produced by
 * {@link AuctionItemSerializer}. Corrupt or version-incompatible
 * payloads do not crash queries; they are skipped and logged so an
 * admin can inspect them out-of-band.
 */
public final class SQLiteAuctionStorage implements AuctionStorage {

    private static final String CREATE_LISTINGS = """
            CREATE TABLE IF NOT EXISTS auction_listings (
                listing_id   INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid  TEXT    NOT NULL,
                seller_name  TEXT    NOT NULL,
                item_data    TEXT    NOT NULL,
                price        REAL    NOT NULL,
                created_at   INTEGER NOT NULL,
                expires_at   INTEGER NOT NULL,
                status       TEXT    NOT NULL,
                buyer_uuid   TEXT,
                buyer_name   TEXT
            )
            """;

    private static final String CREATE_LISTINGS_SELLER_IDX =
            "CREATE INDEX IF NOT EXISTS idx_listings_seller_status " +
                    "ON auction_listings(seller_uuid, status)";

    private static final String CREATE_LISTINGS_EXPIRY_IDX =
            "CREATE INDEX IF NOT EXISTS idx_listings_status_expires " +
                    "ON auction_listings(status, expires_at)";

    private static final String CREATE_COLLECT = """
            CREATE TABLE IF NOT EXISTS auction_collect_items (
                collect_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid        TEXT    NOT NULL,
                item_data         TEXT    NOT NULL,
                reason            TEXT    NOT NULL,
                created_at        INTEGER NOT NULL,
                source_listing_id INTEGER
            )
            """;

    private static final String CREATE_COLLECT_OWNER_IDX =
            "CREATE INDEX IF NOT EXISTS idx_collect_owner " +
                    "ON auction_collect_items(owner_uuid)";

    private final File dbFile;
    private final Logger logger;
    private final boolean debug;

    private Connection connection;

    public SQLiteAuctionStorage(File dbFile, Logger logger, boolean debug) {
        this.dbFile = dbFile;
        this.logger = logger;
        this.debug = debug;
    }

    @Override
    public void init() throws StorageException {
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            if (!dbFile.getParentFile().mkdirs()) {
                throw new StorageException("Could not create AH data folder: "
                        + dbFile.getParentFile());
            }
        }
        try {
            // DriverManager auto-discovers org.sqlite.JDBC via the
            // JDBC 4.0 ServiceLoader when the driver is on the classpath
            // (provided by Paper's library loader). No Class.forName here.
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);

            try (Statement st = connection.createStatement()) {
                // Pragmas: WAL gives better concurrency on read,
                // synchronous=NORMAL is the WAL-recommended setting,
                // foreign_keys is purely defensive (we don't use FKs but
                // a future schema change might).
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");

                st.execute(CREATE_LISTINGS);
                st.execute(CREATE_LISTINGS_SELLER_IDX);
                st.execute(CREATE_LISTINGS_EXPIRY_IDX);
                st.execute(CREATE_COLLECT);
                st.execute(CREATE_COLLECT_OWNER_IDX);
            }
            if (debug) {
                logger.info("[AH] SQLite storage ready at " + dbFile.getName());
            }
        } catch (SQLException ex) {
            throw new StorageException(
                    "Failed to open SQLite database " + dbFile.getName() + ": " + ex.getMessage(),
                    ex);
        } catch (Throwable t) {
            // Catches NoClassDefFoundError if the JDBC driver was never
            // loaded (e.g. on Spigot without the libraries: directive).
            throw new StorageException(
                    "SQLite JDBC driver not available: " + t.getMessage(), t);
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "[AH] Failed to close SQLite connection", ex);
        } finally {
            connection = null;
        }
    }

    // ----------------------------------------------------------------- listings

    @Override
    public long insertListing(UUID sellerUuid,
                              String sellerName,
                              ItemStack item,
                              double price,
                              long createdAt,
                              long expiresAt) throws StorageException {
        String sql = """
                INSERT INTO auction_listings(
                    seller_uuid, seller_name, item_data, price,
                    created_at, expires_at, status, buyer_uuid, buyer_name)
                VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL)
                """;
        String payload = serialiseOrThrow(item);
        try (PreparedStatement ps = requireConnection().prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, sellerUuid.toString());
            ps.setString(2, sellerName);
            ps.setString(3, payload);
            ps.setDouble(4, price);
            ps.setLong(5, createdAt);
            ps.setLong(6, expiresAt);
            ps.setString(7, AuctionListingStatus.ACTIVE.name());
            int affected = ps.executeUpdate();
            if (affected != 1) {
                throw new StorageException("insertListing: unexpected affected rows " + affected);
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new StorageException("insertListing: no generated key returned");
            }
        } catch (SQLException ex) {
            throw new StorageException("insertListing failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<AuctionListing> getListing(long listingId) throws StorageException {
        String sql = "SELECT * FROM auction_listings WHERE listing_id = ?";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setLong(1, listingId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                AuctionListing listing = readListing(rs);
                return listing == null ? Optional.empty() : Optional.of(listing);
            }
        } catch (SQLException ex) {
            throw new StorageException("getListing failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<AuctionListing> getListingsBySellerAndStatus(UUID seller,
                                                             AuctionListingStatus status)
            throws StorageException {
        String sql = """
                SELECT * FROM auction_listings
                WHERE seller_uuid = ? AND status = ?
                ORDER BY created_at ASC
                """;
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setString(2, status.name());
            return collectListings(ps);
        } catch (SQLException ex) {
            throw new StorageException("getListingsBySellerAndStatus failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<AuctionListing> getExpiredActiveListings(long now, int limit) throws StorageException {
        String sql = """
                SELECT * FROM auction_listings
                WHERE status = ? AND expires_at <= ?
                ORDER BY expires_at ASC
                LIMIT ?
                """;
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, AuctionListingStatus.ACTIVE.name());
            ps.setLong(2, now);
            ps.setInt(3, Math.max(1, limit));
            return collectListings(ps);
        } catch (SQLException ex) {
            throw new StorageException("getExpiredActiveListings failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean transitionListingStatus(long listingId,
                                           AuctionListingStatus fromStatus,
                                           AuctionListingStatus toStatus)
            throws StorageException {
        String sql = """
                UPDATE auction_listings
                SET status = ?
                WHERE listing_id = ? AND status = ?
                """;
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, toStatus.name());
            ps.setLong(2, listingId);
            ps.setString(3, fromStatus.name());
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("transitionListingStatus failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int countListingsByStatus(AuctionListingStatus status) throws StorageException {
        String sql = "SELECT COUNT(*) FROM auction_listings WHERE status = ?";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new StorageException("countListingsByStatus failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int countListingsBySellerAndStatus(UUID seller,
                                              AuctionListingStatus status) throws StorageException {
        String sql = "SELECT COUNT(*) FROM auction_listings WHERE seller_uuid = ? AND status = ?";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, seller.toString());
            ps.setString(2, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new StorageException("countListingsBySellerAndStatus failed: "
                    + ex.getMessage(), ex);
        }
    }

    // ------------------------------------------------------------- collect items

    @Override
    public long insertCollectItem(UUID ownerUuid,
                                  ItemStack item,
                                  AuctionCollectReason reason,
                                  long createdAt,
                                  Long sourceListingId) throws StorageException {
        String sql = """
                INSERT INTO auction_collect_items(
                    owner_uuid, item_data, reason, created_at, source_listing_id)
                VALUES (?, ?, ?, ?, ?)
                """;
        String payload = serialiseOrThrow(item);
        try (PreparedStatement ps = requireConnection().prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, payload);
            ps.setString(3, reason.name());
            ps.setLong(4, createdAt);
            if (sourceListingId == null) {
                ps.setNull(5, Types.INTEGER);
            } else {
                ps.setLong(5, sourceListingId);
            }
            int affected = ps.executeUpdate();
            if (affected != 1) {
                throw new StorageException("insertCollectItem: unexpected affected rows " + affected);
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
                throw new StorageException("insertCollectItem: no generated key returned");
            }
        } catch (SQLException ex) {
            throw new StorageException("insertCollectItem failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<AuctionCollectItem> getCollectItemsForOwner(UUID owner) throws StorageException {
        String sql = """
                SELECT * FROM auction_collect_items
                WHERE owner_uuid = ?
                ORDER BY created_at ASC
                """;
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<AuctionCollectItem> out = new ArrayList<>();
                while (rs.next()) {
                    AuctionCollectItem item = readCollectItem(rs);
                    if (item != null) {
                        out.add(item);
                    }
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new StorageException("getCollectItemsForOwner failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean deleteCollectItem(long collectId) throws StorageException {
        String sql = "DELETE FROM auction_collect_items WHERE collect_id = ?";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setLong(1, collectId);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("deleteCollectItem failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean updateCollectItemStack(long collectId, ItemStack newItem) throws StorageException {
        String payload = serialiseOrThrow(newItem);
        String sql = "UPDATE auction_collect_items SET item_data = ? WHERE collect_id = ?";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql)) {
            ps.setString(1, payload);
            ps.setLong(2, collectId);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new StorageException("updateCollectItemStack failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int countCollectItems() throws StorageException {
        String sql = "SELECT COUNT(*) FROM auction_collect_items";
        try (PreparedStatement ps = requireConnection().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException ex) {
            throw new StorageException("countCollectItems failed: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------- internals

    private List<AuctionListing> collectListings(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<AuctionListing> out = new ArrayList<>();
            while (rs.next()) {
                AuctionListing listing = readListing(rs);
                if (listing != null) {
                    out.add(listing);
                }
            }
            return out;
        }
    }

    private AuctionListing readListing(ResultSet rs) throws SQLException {
        long id = rs.getLong("listing_id");
        UUID sellerUuid = parseUuidOrNull(rs.getString("seller_uuid"));
        if (sellerUuid == null) {
            logger.warning("[AH] Listing #" + id + " has invalid seller_uuid; skipping.");
            return null;
        }
        ItemStack item;
        try {
            item = AuctionItemSerializer.deserialize(rs.getString("item_data"));
        } catch (IOException | ClassNotFoundException ex) {
            logger.warning("[AH] Listing #" + id + " has unreadable item_data; skipping. ("
                    + ex.getMessage() + ")");
            return null;
        }
        AuctionListingStatus status;
        try {
            status = AuctionListingStatus.valueOf(rs.getString("status"));
        } catch (IllegalArgumentException ex) {
            logger.warning("[AH] Listing #" + id + " has unknown status; skipping.");
            return null;
        }
        UUID buyerUuid = parseUuidOrNull(rs.getString("buyer_uuid"));
        String buyerName = rs.getString("buyer_name");
        return new AuctionListing(
                id,
                sellerUuid,
                rs.getString("seller_name"),
                item,
                rs.getDouble("price"),
                rs.getLong("created_at"),
                rs.getLong("expires_at"),
                status,
                buyerUuid,
                buyerName);
    }

    private AuctionCollectItem readCollectItem(ResultSet rs) throws SQLException {
        long id = rs.getLong("collect_id");
        UUID ownerUuid = parseUuidOrNull(rs.getString("owner_uuid"));
        if (ownerUuid == null) {
            logger.warning("[AH] Collect #" + id + " has invalid owner_uuid; skipping.");
            return null;
        }
        ItemStack item;
        try {
            item = AuctionItemSerializer.deserialize(rs.getString("item_data"));
        } catch (IOException | ClassNotFoundException ex) {
            logger.warning("[AH] Collect #" + id + " has unreadable item_data; skipping. ("
                    + ex.getMessage() + ")");
            return null;
        }
        AuctionCollectReason reason = AuctionCollectReason.fromStringOrDefault(rs.getString("reason"));
        Long sourceId = rs.getLong("source_listing_id");
        if (rs.wasNull()) sourceId = null;
        return new AuctionCollectItem(
                id,
                ownerUuid,
                item,
                reason,
                rs.getLong("created_at"),
                sourceId);
    }

    private String serialiseOrThrow(ItemStack item) throws StorageException {
        try {
            return AuctionItemSerializer.serialize(item);
        } catch (IOException ex) {
            throw new StorageException("ItemStack serialisation failed: " + ex.getMessage(), ex);
        }
    }

    private Connection requireConnection() throws StorageException {
        if (connection == null) {
            throw new StorageException("SQLite connection is not initialised");
        }
        return connection;
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
