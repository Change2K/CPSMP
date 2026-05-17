package de.deinserver.cpsmp.teleport;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite storage for homes and /back. All JDBC work must run on a
 * single-thread executor owned by {@link CpsmpTeleportSubsystem}.
 */
public final class SQLiteHomeStorage implements HomeStorage {

    private static final String CREATE_HOMES = """
            CREATE TABLE IF NOT EXISTS player_homes (
                home_id     INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid  TEXT    NOT NULL,
                owner_name  TEXT,
                home_name   TEXT    NOT NULL,
                world       TEXT    NOT NULL,
                x           REAL    NOT NULL,
                y           REAL    NOT NULL,
                z           REAL    NOT NULL,
                yaw         REAL    NOT NULL,
                pitch       REAL    NOT NULL,
                created_at  INTEGER NOT NULL,
                updated_at  INTEGER NOT NULL,
                UNIQUE(owner_uuid, home_name)
            )
            """;
    private static final String IDX_OWNER = """
            CREATE INDEX IF NOT EXISTS idx_homes_owner ON player_homes(owner_uuid)
            """;

    private static final String CREATE_BACK = """
            CREATE TABLE IF NOT EXISTS teleport_back_locations (
                player_uuid TEXT PRIMARY KEY,
                world       TEXT    NOT NULL,
                x           REAL    NOT NULL,
                y           REAL    NOT NULL,
                z           REAL    NOT NULL,
                yaw         REAL    NOT NULL,
                pitch       REAL    NOT NULL,
                saved_at    INTEGER NOT NULL
            )
            """;

    private final File dbFile;
    private final Logger logger;
    private Connection connection;

    public SQLiteHomeStorage(File dbFile, Logger logger) {
        this.dbFile = dbFile;
        this.logger = logger;
    }

    @Override
    public void init() throws HomeStorageException {
        if (dbFile.getParentFile() != null && !dbFile.getParentFile().exists()) {
            if (!dbFile.getParentFile().mkdirs()) {
                throw new HomeStorageException("Could not create folder: " + dbFile.getParentFile());
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
                st.execute(CREATE_HOMES);
                st.execute(IDX_OWNER);
                st.execute(CREATE_BACK);
            }
        } catch (SQLException ex) {
            throw new HomeStorageException("SQLite open failed: " + ex.getMessage(), ex);
        } catch (Throwable t) {
            throw new HomeStorageException("SQLite JDBC not available: " + t.getMessage(), t);
        }
    }

    @Override
    public void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ex) {
            logger.log(Level.WARNING, "[Homes] SQLite close: " + ex.getMessage());
        }
        connection = null;
    }

    private Connection requireConn() throws HomeStorageException {
        if (connection == null) {
            throw new HomeStorageException("SQLite connection closed");
        }
        return connection;
    }

    @Override
    public Home upsertHome(UUID ownerUuid,
                           @Nullable String ownerName,
                           String homeName,
                           String world,
                           double x, double y, double z,
                           float yaw, float pitch,
                           long now) throws HomeStorageException {
        Optional<Home> existing = getHome(ownerUuid, homeName);
        if (existing.isEmpty()) {
            String sql = """
                    INSERT INTO player_homes(
                        owner_uuid, owner_name, home_name, world, x, y, z, yaw, pitch, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = requireConn().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, ownerUuid.toString());
                ps.setString(2, ownerName);
                ps.setString(3, homeName);
                ps.setString(4, world);
                ps.setDouble(5, x);
                ps.setDouble(6, y);
                ps.setDouble(7, z);
                ps.setDouble(8, yaw);
                ps.setDouble(9, pitch);
                ps.setLong(10, now);
                ps.setLong(11, now);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    long id = keys.next() ? keys.getLong(1) : -1L;
                    return new Home(id, ownerUuid, ownerName, homeName, world, x, y, z, yaw, pitch,
                            now, now);
                }
            } catch (SQLException ex) {
                throw new HomeStorageException("upsertHome insert: " + ex.getMessage(), ex);
            }
        } else {
            String sql = """
                    UPDATE player_homes SET owner_name=?, world=?, x=?, y=?, z=?, yaw=?, pitch=?, updated_at=?
                    WHERE owner_uuid=? AND home_name=?
                    """;
            try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
                ps.setString(1, ownerName);
                ps.setString(2, world);
                ps.setDouble(3, x);
                ps.setDouble(4, y);
                ps.setDouble(5, z);
                ps.setDouble(6, yaw);
                ps.setDouble(7, pitch);
                ps.setLong(8, now);
                ps.setString(9, ownerUuid.toString());
                ps.setString(10, homeName);
                ps.executeUpdate();
            } catch (SQLException ex) {
                throw new HomeStorageException("upsertHome update: " + ex.getMessage(), ex);
            }
            Home h = existing.get();
            return new Home(h.homeId(), ownerUuid, ownerName, homeName, world, x, y, z, yaw, pitch,
                    h.createdAt(), now);
        }
    }

    @Override
    public boolean deleteHome(UUID ownerUuid, String homeName) throws HomeStorageException {
        String sql = "DELETE FROM player_homes WHERE owner_uuid=? AND home_name=?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, homeName);
            return ps.executeUpdate() == 1;
        } catch (SQLException ex) {
            throw new HomeStorageException("deleteHome: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<Home> getHome(UUID ownerUuid, String homeName) throws HomeStorageException {
        String sql = "SELECT * FROM player_homes WHERE owner_uuid=? AND home_name=?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            ps.setString(2, homeName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readHome(rs));
            }
        } catch (SQLException ex) {
            throw new HomeStorageException("getHome: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Home> listHomes(UUID ownerUuid) throws HomeStorageException {
        String sql = "SELECT * FROM player_homes WHERE owner_uuid=? ORDER BY home_name COLLATE NOCASE";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                List<Home> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(readHome(rs));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new HomeStorageException("listHomes: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int countHomes(UUID ownerUuid) throws HomeStorageException {
        String sql = "SELECT COUNT(*) FROM player_homes WHERE owner_uuid=?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw new HomeStorageException("countHomes: " + ex.getMessage(), ex);
        }
    }

    @Override
    public boolean saveBackLocation(UUID playerUuid, String world, double x, double y, double z,
                                    float yaw, float pitch, long now) throws HomeStorageException {
        String sql = """
                INSERT INTO teleport_back_locations(player_uuid, world, x, y, z, yaw, pitch, saved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(player_uuid) DO UPDATE SET
                    world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,
                    yaw=excluded.yaw,pitch=excluded.pitch,saved_at=excluded.saved_at
                """;
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, world);
            ps.setDouble(3, x);
            ps.setDouble(4, y);
            ps.setDouble(5, z);
            ps.setDouble(6, yaw);
            ps.setDouble(7, pitch);
            ps.setLong(8, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            throw new HomeStorageException("saveBackLocation: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Optional<BackSnapshot> getBack(UUID playerUuid) throws HomeStorageException {
        String sql = "SELECT * FROM teleport_back_locations WHERE player_uuid=?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new BackSnapshot(
                        rs.getString("world"),
                        rs.getDouble("x"),
                        rs.getDouble("y"),
                        rs.getDouble("z"),
                        (float) rs.getDouble("yaw"),
                        (float) rs.getDouble("pitch"),
                        rs.getLong("saved_at")));
            }
        } catch (SQLException ex) {
            throw new HomeStorageException("getBack: " + ex.getMessage(), ex);
        }
    }

    @Override
    public int deleteAllHomesForPlayer(UUID ownerUuid) throws HomeStorageException {
        String sql = "DELETE FROM player_homes WHERE owner_uuid=?";
        try (PreparedStatement ps = requireConn().prepareStatement(sql)) {
            ps.setString(1, ownerUuid.toString());
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw new HomeStorageException("deleteAllHomesForPlayer: " + ex.getMessage(), ex);
        }
    }

    @Override
    public List<Home> listHomesForAdmin(UUID ownerUuid) throws HomeStorageException {
        return listHomes(ownerUuid);
    }

    @Override
    public boolean adminDeleteHome(UUID ownerUuid, String homeName) throws HomeStorageException {
        return deleteHome(ownerUuid, homeName);
    }

    private static Home readHome(ResultSet rs) throws SQLException {
        return new Home(
                rs.getLong("home_id"),
                UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("owner_name"),
                rs.getString("home_name"),
                rs.getString("world"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                (float) rs.getDouble("yaw"),
                (float) rs.getDouble("pitch"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
