package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite persistence for CPSMP claims. All methods are blocking and must run on the
 * dedicated DB executor (never on the main thread).
 */
public final class SQLiteClaimStorage implements ClaimStorage {

    private static final String CREATE_CLAIMS = """
            CREATE TABLE IF NOT EXISTS claims (
                claim_id    INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid  TEXT    NOT NULL,
                owner_name  TEXT,
                world       TEXT    NOT NULL,
                min_x       INTEGER NOT NULL,
                max_x       INTEGER NOT NULL,
                min_z       INTEGER NOT NULL,
                max_z       INTEGER NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL
            )
            """;

    private static final String CREATE_TRUST = """
            CREATE TABLE IF NOT EXISTS claim_trust (
                claim_id    INTEGER NOT NULL,
                trusted_uuid TEXT   NOT NULL,
                trusted_name TEXT,
                created_at   INTEGER NOT NULL,
                PRIMARY KEY (claim_id, trusted_uuid),
                FOREIGN KEY (claim_id) REFERENCES claims(claim_id) ON DELETE CASCADE
            )
            """;

    private static final String IDX_WORLD = """
            CREATE INDEX IF NOT EXISTS idx_claims_world ON claims(world)
            """;
    private static final String IDX_OWNER = """
            CREATE INDEX IF NOT EXISTS idx_claims_owner ON claims(owner_uuid)
            """;
    private static final String IDX_OWNER_NUMBER = """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_claims_owner_ocn ON claims(owner_uuid, owner_claim_number)
            """;

    private static final String CREATE_FLAGS = """
            CREATE TABLE IF NOT EXISTS claim_flags (
                claim_id    INTEGER NOT NULL,
                flag_key    TEXT    NOT NULL,
                flag_value  TEXT    NOT NULL,
                updated_at  INTEGER NOT NULL,
                PRIMARY KEY (claim_id, flag_key),
                FOREIGN KEY (claim_id) REFERENCES claims(claim_id) ON DELETE CASCADE
            )
            """;

    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public SQLiteClaimStorage(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    @Override
    public void init() throws ClaimStorageException {
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            if (!dbFile.getParentFile().mkdirs()) {
                throw new ClaimStorageException("Could not create folder: " + dbFile.getParentFile());
            }
        }
        try {
            String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
            this.connection = DriverManager.getConnection(url);
            this.connection.setAutoCommit(true);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute(CREATE_CLAIMS);
                st.execute(CREATE_TRUST);
                st.execute(CREATE_FLAGS);
                st.execute(IDX_WORLD);
                st.execute(IDX_OWNER);
            }
            migrateOwnerClaimNumberIfNeeded();
        } catch (ClaimStorageException e) {
            throw e;
        } catch (SQLException ex) {
            throw new ClaimStorageException("SQLite open failed: " + ex.getMessage(), ex);
        } catch (Throwable t) {
            throw new ClaimStorageException("SQLite JDBC not available: " + t.getMessage(), t);
        }
    }

    private void migrateOwnerClaimNumberIfNeeded() throws SQLException, ClaimStorageException {
        Connection con = requireConnection();
        if (columnExists(con, "claims", "owner_claim_number")) {
            try (Statement st = con.createStatement()) {
                st.execute(IDX_OWNER_NUMBER);
            } catch (SQLException e) {
                logger.warning("[CPSMP] Claims: Unique-Index owner_claim_number: " + e.getMessage());
            }
            return;
        }
        File backup = new File(dbFile.getParentFile(), "claims.backup-before-owner-claim-number-migration.db");
        try {
            Files.copy(dbFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("[CPSMP] Claims: Backup vor owner_claim_number-Migration: " + backup.getName());
        } catch (IOException e) {
            logger.log(Level.WARNING, "[CPSMP] Claims: Konnte claims.db nicht sichern — Migration abgebrochen.", e);
            throw new ClaimStorageException("Claims-Backup fehlgeschlagen: " + e.getMessage(), e);
        }
        try (Statement st = con.createStatement()) {
            st.execute("ALTER TABLE claims ADD COLUMN owner_claim_number INTEGER");
        }
        String assign = """
                UPDATE claims SET owner_claim_number = (
                    SELECT COUNT(*) FROM claims AS c2
                    WHERE c2.owner_uuid = claims.owner_uuid AND c2.claim_id <= claims.claim_id
                )
                WHERE owner_claim_number IS NULL
                """;
        try (Statement st = con.createStatement()) {
            st.executeUpdate(assign);
        }
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT claim_id, owner_claim_number FROM claims WHERE owner_claim_number IS NULL OR owner_claim_number < 1")) {
            if (rs.next()) {
                logger.warning("[CPSMP] Claims: Migration owner_claim_number unvollstaendig — bitte Datenbank pruefen.");
                throw new ClaimStorageException("owner_claim_number migration incomplete");
            }
        }
        try (Statement st = con.createStatement()) {
            st.execute(IDX_OWNER_NUMBER);
        } catch (SQLException e) {
            logger.log(Level.SEVERE, "[CPSMP] Claims: Unique-Index konnte nicht erstellt werden (Duplikate?).", e);
            throw new ClaimStorageException("Unique index failed: " + e.getMessage(), e);
        }
    }

    private static boolean columnExists(Connection con, String table, String column) throws SQLException {
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "[Claims] SQLite close: " + ex.getMessage());
        }
        connection = null;
    }

    @Override
    public long insertClaim(UUID ownerUuid, String ownerName, String world,
                            int minX, int maxX, int minZ, int maxZ, long now) throws ClaimStorageException {
        Connection con = requireConnection();
        String sql = """
                INSERT INTO claims (owner_uuid, owner_name, owner_claim_number, world, min_x, max_x, min_z, max_z, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try {
            con.setAutoCommit(false);
            int next;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT COALESCE(MAX(owner_claim_number), 0) + 1 AS n FROM claims WHERE owner_uuid = ?")) {
                ps.setString(1, ownerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    next = rs.next() ? Math.max(1, rs.getInt("n")) : 1;
                }
            }
            try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, ownerUuid.toString());
                ps.setString(2, ownerName);
                ps.setInt(3, next);
                ps.setString(4, world);
                ps.setInt(5, minX);
                ps.setInt(6, maxX);
                ps.setInt(7, minZ);
                ps.setInt(8, maxZ);
                ps.setLong(9, now);
                ps.setLong(10, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        con.commit();
                        return keys.getLong(1);
                    }
                }
            }
            con.rollback();
            throw new ClaimStorageException("No generated key for claim insert");
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignored) {
            }
            throw new ClaimStorageException(e.getMessage(), e);
        } finally {
            try {
                con.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    @Override
    public boolean deleteClaim(long claimId) throws ClaimStorageException {
        String sql = "DELETE FROM claims WHERE claim_id = ?";
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public @Nullable Claim findByOwnerAndNumber(UUID ownerUuid, int ownerClaimNumber) throws ClaimStorageException {
        String sql = """
                SELECT claim_id, owner_claim_number, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
                FROM claims WHERE owner_uuid = ? AND owner_claim_number = ?
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setInt(2, ownerClaimNumber);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readClaim(rs);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public int countForOwner(UUID ownerUuid) throws ClaimStorageException {
        String sql = "SELECT COUNT(*) FROM claims WHERE owner_uuid = ?";
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public List<Claim> listForOwner(UUID ownerUuid) throws ClaimStorageException {
        String sql = """
                SELECT claim_id, owner_claim_number, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
                FROM claims WHERE owner_uuid = ? ORDER BY owner_claim_number, claim_id
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Claim> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readClaim(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public List<Claim> loadAllClaims() throws ClaimStorageException {
        String sql = """
                SELECT claim_id, owner_claim_number, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
                FROM claims
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Claim> out = new ArrayList<>();
            while (rs.next()) {
                out.add(readClaim(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public Map<Long, Set<UUID>> loadAllTrustUuids() throws ClaimStorageException {
        String sql = "SELECT claim_id, trusted_uuid FROM claim_trust";
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            Map<Long, Set<UUID>> map = new HashMap<>();
            while (rs.next()) {
                long cid = rs.getLong("claim_id");
                UUID tu = UUID.fromString(rs.getString("trusted_uuid"));
                map.computeIfAbsent(cid, k -> new LinkedHashSet<>()).add(tu);
            }
            return map;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public void insertTrust(long claimId, UUID trustedUuid, String trustedName, long now) throws ClaimStorageException {
        String sql = """
                INSERT OR REPLACE INTO claim_trust (claim_id, trusted_uuid, trusted_name, created_at)
                VALUES (?, ?, ?, ?)
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            ps.setString(2, trustedUuid.toString());
            ps.setString(3, trustedName);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public boolean deleteTrust(long claimId, UUID trustedUuid) throws ClaimStorageException {
        String sql = "DELETE FROM claim_trust WHERE claim_id = ? AND trusted_uuid = ?";
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            ps.setString(2, trustedUuid.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public List<ClaimTrustEntry> listTrust(long claimId) throws ClaimStorageException {
        String sql = """
                SELECT claim_id, trusted_uuid, trusted_name, created_at FROM claim_trust WHERE claim_id = ?
                ORDER BY trusted_name
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ClaimTrustEntry> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ClaimTrustEntry(
                            rs.getLong("claim_id"),
                            UUID.fromString(rs.getString("trusted_uuid")),
                            rs.getString("trusted_name"),
                            rs.getLong("created_at")
                    ));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public @Nullable Claim getClaim(long id) throws ClaimStorageException {
        String sql = """
                SELECT claim_id, owner_claim_number, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
                FROM claims WHERE claim_id = ?
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return readClaim(rs);
                }
                return null;
            }
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public MergeClaimsResult mergeKeepKeeper(long keeperClaimId, List<Long> removeClaimIds,
                                             int newMinX, int newMaxX, int newMinZ, int newMaxZ,
                                             int newOwnerClaimNumber, long now) throws ClaimStorageException {
        Connection con = requireConnection();
        if (removeClaimIds == null || removeClaimIds.isEmpty()) {
            throw new ClaimStorageException("merge: empty remove list");
        }
        for (Long id : removeClaimIds) {
            if (id != null && id == keeperClaimId) {
                throw new ClaimStorageException("merge: remove list contains keeper");
            }
        }
        try {
            con.setAutoCommit(false);
            for (Long rid : removeClaimIds) {
                if (rid == null) {
                    continue;
                }
                String copyTrust = """
                        INSERT OR IGNORE INTO claim_trust (claim_id, trusted_uuid, trusted_name, created_at)
                        SELECT ?, trusted_uuid, trusted_name, created_at FROM claim_trust WHERE claim_id = ?
                        """;
                try (PreparedStatement ps = con.prepareStatement(copyTrust)) {
                    ps.setLong(1, keeperClaimId);
                    ps.setLong(2, rid);
                    ps.executeUpdate();
                }
            }
            String upd = """
                    UPDATE claims SET min_x=?, max_x=?, min_z=?, max_z=?, owner_claim_number=?, updated_at=?
                    WHERE claim_id=?
                    """;
            try (PreparedStatement ps = con.prepareStatement(upd)) {
                ps.setInt(1, newMinX);
                ps.setInt(2, newMaxX);
                ps.setInt(3, newMinZ);
                ps.setInt(4, newMaxZ);
                ps.setInt(5, newOwnerClaimNumber);
                ps.setLong(6, now);
                ps.setLong(7, keeperClaimId);
                if (ps.executeUpdate() != 1) {
                    con.rollback();
                    throw new ClaimStorageException("merge: keeper update failed");
                }
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM claims WHERE claim_id = ?")) {
                for (Long rid : removeClaimIds) {
                    if (rid == null) {
                        continue;
                    }
                    ps.setLong(1, rid);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            con.commit();
            Claim k = getClaim(keeperClaimId);
            if (k == null) {
                throw new ClaimStorageException("merge: keeper missing after commit");
            }
            Set<UUID> tu = new LinkedHashSet<>();
            for (ClaimTrustEntry e : listTrust(keeperClaimId)) {
                tu.add(e.trustedUuid());
            }
            return new MergeClaimsResult(k, tu);
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignored) {
            }
            throw new ClaimStorageException(e.getMessage(), e);
        } finally {
            try {
                con.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private Connection requireConnection() throws ClaimStorageException {
        if (connection == null) {
            throw new ClaimStorageException("SQLite not open");
        }
        return connection;
    }

    @Override
    public Map<Long, Map<String, String>> loadAllFlags() throws ClaimStorageException {
        String sql = "SELECT claim_id, flag_key, flag_value FROM claim_flags";
        Map<Long, Map<String, String>> out = new HashMap<>();
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long claimId = rs.getLong("claim_id");
                String key = rs.getString("flag_key");
                String value = rs.getString("flag_value");
                out.computeIfAbsent(claimId, k -> new HashMap<>()).put(key, value);
            }
            return out;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public Map<String, String> loadFlags(long claimId) throws ClaimStorageException {
        String sql = "SELECT flag_key, flag_value FROM claim_flags WHERE claim_id = ?";
        Map<String, String> out = new HashMap<>();
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("flag_key"), rs.getString("flag_value"));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public void setFlag(long claimId, String key, String value, long now) throws ClaimStorageException {
        String sql = """
                INSERT INTO claim_flags (claim_id, flag_key, flag_value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(claim_id, flag_key) DO UPDATE SET
                    flag_value = excluded.flag_value,
                    updated_at = excluded.updated_at
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            ps.setString(2, key);
            ps.setString(3, value);
            ps.setLong(4, now);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    @Override
    public void deleteFlag(long claimId, String key) throws ClaimStorageException {
        String sql = "DELETE FROM claim_flags WHERE claim_id = ? AND flag_key = ?";
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, claimId);
            ps.setString(2, key);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
        }
    }

    private static Claim readClaim(ResultSet rs) throws SQLException {
        int ocn = rs.getInt("owner_claim_number");
        if (rs.wasNull() || ocn < 1) {
            ocn = 1;
        }
        return new Claim(
                rs.getLong("claim_id"),
                ocn,
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getString("world"),
                rs.getInt("min_x"),
                rs.getInt("max_x"),
                rs.getInt("min_z"),
                rs.getInt("max_z"),
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }
}
