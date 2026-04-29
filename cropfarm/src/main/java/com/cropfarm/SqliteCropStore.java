package com.cropfarm;

import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;

/**
 * SQLite-backed persistence for planted crops. One row per (world, x, y, z),
 * keyed by the same serialised location string TrackedCrops uses in-memory.
 *
 * Concurrency: the connection and prepared statements are guarded by the
 * monitor on this instance. Today every caller runs on the main server
 * thread; the synchronization is defensive in case future async paths land.
 *
 * Pragmas:
 *   journal_mode=WAL    — readers don't block writers; crash-safe checkpoints
 *   synchronous=NORMAL  — fsync once per checkpoint (fast + still durable
 *                         enough; tolerates power loss with at most last
 *                         transaction lost, which is acceptable for crops)
 *
 * Driver loading: we instantiate the driver directly via Class.forName +
 * connect(), bypassing DriverManager. This avoids DriverManager's global
 * registry returning another plugin's sqlite-jdbc driver instance.
 *
 * Native library extraction: sqlite-jdbc unpacks platform .so/.dll/.dylib on
 * first use. We point its tmpdir at our plugin data folder so two plugins
 * each bundling sqlite-jdbc don't collide on the same temp filename (a
 * Windows DLL-lock hazard).
 *
 * NOTE: org.sqlite is intentionally NOT shaded-relocated. The native library
 * has JNI symbols hardcoded with the original package name; a relocated
 * class throws UnsatisfiedLinkError on the first native call.
 */
public class SqliteCropStore implements CropStore {

    private final Plugin plugin;
    private final File dbFile;
    private Connection connection;
    private PreparedStatement insertStmt;
    private PreparedStatement deleteStmt;

    public SqliteCropStore(Plugin plugin, File dbFile) {
        this.plugin = plugin;
        this.dbFile = dbFile;
    }

    /**
     * Open the database, creating the schema if needed.
     * Throws SQLException so the caller can decide whether to fail enable.
     */
    public void open() throws SQLException {
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        // Scope native-library extraction to our plugin folder so two plugins
        // bundling sqlite-jdbc don't race on the same temp DLL/SO path.
        File nativeDir = new File(plugin.getDataFolder(), ".native");
        if (!nativeDir.exists()) nativeDir.mkdirs();
        System.setProperty("org.sqlite.tmpdir", nativeDir.getAbsolutePath());

        Driver driver = loadDriver();
        Properties props = new Properties();
        connection = driver.connect("jdbc:sqlite:" + dbFile.getAbsolutePath(), props);
        if (connection == null) {
            throw new SQLException("Driver returned null connection for jdbc:sqlite:"
                    + dbFile.getAbsolutePath());
        }

        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL;");
            s.execute("PRAGMA synchronous=NORMAL;");
            s.execute(
                    "CREATE TABLE IF NOT EXISTS planted_crops (" +
                    "  loc TEXT PRIMARY KEY," +
                    "  crop_id TEXT NOT NULL," +
                    "  planted_at_millis INTEGER NOT NULL," +
                    "  owner_uuid TEXT" +
                    ")");
            s.execute(
                    "CREATE INDEX IF NOT EXISTS idx_owner_crop " +
                    "ON planted_crops(owner_uuid, crop_id)");
        }
        insertStmt = connection.prepareStatement(
                "INSERT INTO planted_crops(loc, crop_id, planted_at_millis, owner_uuid) " +
                "VALUES(?, ?, ?, ?) " +
                "ON CONFLICT(loc) DO UPDATE SET " +
                "  crop_id=excluded.crop_id, " +
                "  planted_at_millis=excluded.planted_at_millis, " +
                "  owner_uuid=excluded.owner_uuid");
        deleteStmt = connection.prepareStatement(
                "DELETE FROM planted_crops WHERE loc=?");
    }

    /**
     * Load org.sqlite.JDBC and instantiate it directly. Each plugin
     * classloader holds its own copy of the driver, so we don't share
     * driver state with other plugins that bundle sqlite-jdbc.
     */
    private static Driver loadDriver() throws SQLException {
        try {
            Class<?> driverClass = Class.forName("org.sqlite.JDBC");
            return (Driver) driverClass.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not on classpath", e);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("Cannot instantiate SQLite JDBC driver", e);
        }
    }

    @Override
    public synchronized void put(String locKey, TrackedCrop crop) {
        try {
            insertStmt.setString(1, locKey);
            insertStmt.setString(2, crop.cropId());
            insertStmt.setLong(3, crop.plantedAtMillis());
            if (crop.owner() == null) {
                insertStmt.setNull(4, java.sql.Types.VARCHAR);
            } else {
                insertStmt.setString(4, crop.owner().toString());
            }
            insertStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to persist crop " + locKey, e);
        }
    }

    @Override
    public synchronized void remove(String locKey) {
        try {
            deleteStmt.setString(1, locKey);
            deleteStmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to remove crop " + locKey, e);
        }
    }

    /**
     * Throws {@link CropStoreException} on read failure. Callers must NOT
     * treat a thrown exception as an empty result — that would mask a
     * corrupt DB and start with no tracked crops.
     */
    @Override
    public synchronized Map<String, TrackedCrop> loadAll() {
        Map<String, TrackedCrop> result = new HashMap<>();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT loc, crop_id, planted_at_millis, owner_uuid FROM planted_crops")) {
            while (rs.next()) {
                String loc      = rs.getString(1);
                String cropId   = rs.getString(2);
                long plantedAt  = rs.getLong(3);
                String ownerStr = rs.getString(4);
                UUID owner = null;
                if (ownerStr != null) {
                    try { owner = UUID.fromString(ownerStr); }
                    catch (IllegalArgumentException ignored) { /* leave null */ }
                }
                result.put(loc, new TrackedCrop(cropId, plantedAt, owner));
            }
        } catch (SQLException e) {
            throw new CropStoreException("Failed to load planted_crops", e);
        }
        return result;
    }

    @Override
    public synchronized void close() {
        try { if (insertStmt != null) insertStmt.close(); } catch (SQLException ignored) { }
        try { if (deleteStmt != null) deleteStmt.close(); } catch (SQLException ignored) { }
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) { }
    }
}
