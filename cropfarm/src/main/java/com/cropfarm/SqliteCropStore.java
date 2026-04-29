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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * SQLite-backed persistence for planted crops. One row per (world, x, y, z),
 * keyed by the same serialised location string TrackedCrops uses in-memory.
 *
 * Async write architecture:
 *   put() and remove() enqueue an op to {@link #writeQueue} and return
 *   immediately — the main server thread never blocks on disk I/O.
 *   A dedicated background thread drains the queue, batching consecutive
 *   ops into a single transaction for throughput. On {@link #close()} we
 *   stop the worker, then synchronously drain anything still pending so a
 *   graceful shutdown never loses writes.
 *
 *   Trade-off: a hard server crash (kill -9, OOM) could lose ops still in
 *   the queue at the moment of death — at most a few seconds of plants /
 *   breaks. Acceptable for this use case; the cap counter and growth task
 *   resync from in-memory + (eventually consistent) DB state on restart.
 *
 * Pragmas:
 *   journal_mode=WAL    — readers don't block writers; crash-safe checkpoints
 *   synchronous=NORMAL  — fsync once per checkpoint (fast + still durable
 *                         enough; tolerates power loss with at most last
 *                         transaction lost)
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

    /** Max ops pulled from the queue and committed in a single transaction. */
    private static final int BATCH_MAX = 256;
    /** How long the writer blocks waiting for the next op before re-checking the running flag. */
    private static final long POLL_MS = 200L;

    private sealed interface WriteOp {
        record Insert(String key, TrackedCrop crop) implements WriteOp { }
        record Delete(String key) implements WriteOp { }
    }

    private final Plugin plugin;
    private final File dbFile;

    private Connection connection;
    private PreparedStatement insertStmt;
    private PreparedStatement deleteStmt;

    private final LinkedBlockingQueue<WriteOp> writeQueue = new LinkedBlockingQueue<>();
    private Thread writerThread;
    private volatile boolean running = true;

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

        // Start the async writer.
        writerThread = new Thread(this::writerLoop, "CropFarm-DB-Writer");
        writerThread.setDaemon(false); // ensure JVM waits for clean drain on exit
        writerThread.start();
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
    public void put(String locKey, TrackedCrop crop) {
        writeQueue.offer(new WriteOp.Insert(locKey, crop));
    }

    @Override
    public void remove(String locKey) {
        writeQueue.offer(new WriteOp.Delete(locKey));
    }

    /**
     * Throws {@link CropStoreException} on read failure. Callers must NOT
     * treat a thrown exception as an empty result — that would mask a
     * corrupt DB and start with no tracked crops.
     *
     * Synchronous: callers expect a complete picture, so we block on any
     * pending writes by draining the queue first.
     */
    @Override
    public synchronized Map<String, TrackedCrop> loadAll() {
        drainQueueOnce();
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
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try { writerThread.join(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        // Defensive: drain anything left if the worker exited early.
        drainQueueOnce();
        try { if (insertStmt != null) insertStmt.close(); } catch (SQLException ignored) { }
        try { if (deleteStmt != null) deleteStmt.close(); } catch (SQLException ignored) { }
        try { if (connection != null && !connection.isClosed()) connection.close(); }
        catch (SQLException ignored) { }
    }

    // ---------------------------------------------------------------
    // Writer worker
    // ---------------------------------------------------------------

    private void writerLoop() {
        while (running) {
            try {
                WriteOp first = writeQueue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                applyBatch(first);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                plugin.getLogger().log(Level.SEVERE,
                        "Unhandled exception in CropFarm DB writer — continuing", t);
            }
        }
        // Final drain on shutdown — pull anything left and commit.
        drainQueueOnce();
    }

    /** Pull the first op + as many as we can up to BATCH_MAX, commit in one tx. */
    private synchronized void applyBatch(WriteOp first) {
        if (first == null) return;
        java.util.List<WriteOp> batch = new java.util.ArrayList<>(BATCH_MAX);
        batch.add(first);
        writeQueue.drainTo(batch, BATCH_MAX - 1);

        boolean prevAutoCommit = true;
        try {
            prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            for (WriteOp op : batch) applyOne(op);
            connection.commit();
        } catch (SQLException ex) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            plugin.getLogger().log(Level.SEVERE,
                    "Batch DB write failed (" + batch.size() + " ops) — rolled back", ex);
        } finally {
            try { connection.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) { }
        }
    }

    /** Synchronously drain whatever's in the queue right now (used by close + loadAll). */
    private void drainQueueOnce() {
        WriteOp op = writeQueue.poll();
        while (op != null) {
            applyBatch(op);
            op = writeQueue.poll();
        }
    }

    private void applyOne(WriteOp op) throws SQLException {
        switch (op) {
            case WriteOp.Insert ins -> {
                insertStmt.setString(1, ins.key());
                insertStmt.setString(2, ins.crop().cropId());
                insertStmt.setLong(3, ins.crop().plantedAtMillis());
                if (ins.crop().owner() == null) {
                    insertStmt.setNull(4, java.sql.Types.VARCHAR);
                } else {
                    insertStmt.setString(4, ins.crop().owner().toString());
                }
                insertStmt.executeUpdate();
            }
            case WriteOp.Delete del -> {
                deleteStmt.setString(1, del.key());
                deleteStmt.executeUpdate();
            }
        }
    }
}
