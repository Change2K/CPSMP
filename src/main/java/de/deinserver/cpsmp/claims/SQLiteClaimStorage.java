package de.deinserver.cpsmp.claims;

import org.jetbrains.annotations.Nullable;

import java.io.File;
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
                st.execute(IDX_WORLD);
                st.execute(IDX_OWNER);
            }
        } catch (SQLException ex) {
            throw new ClaimStorageException("SQLite open failed: " + ex.getMessage(), ex);
        } catch (Throwable t) {
            throw new ClaimStorageException("SQLite JDBC not available: " + t.getMessage(), t);
        }
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
        String sql = """
                INSERT INTO claims (owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Connection con = requireConnection();
        try (PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, ownerName);
            ps.setString(3, world);
            ps.setInt(4, minX);
            ps.setInt(5, maxX);
            ps.setInt(6, minZ);
            ps.setInt(7, maxZ);
            ps.setLong(8, now);
            ps.setLong(9, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
            throw new ClaimStorageException("No generated key for claim insert");
        } catch (SQLException e) {
            throw new ClaimStorageException(e.getMessage(), e);
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
                SELECT claim_id, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
                FROM claims WHERE owner_uuid = ? ORDER BY claim_id
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
                SELECT claim_id, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
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
                SELECT claim_id, owner_uuid, owner_name, world, min_x, max_x, min_z, max_z, created_at, updated_at
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

    private Connection requireConnection() throws ClaimStorageException {
        if (connection == null) {
            throw new ClaimStorageException("SQLite not open");
        }
        return connection;
    }

    private static Claim readClaim(ResultSet rs) throws SQLException {
        return new Claim(
                rs.getLong("claim_id"),
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
