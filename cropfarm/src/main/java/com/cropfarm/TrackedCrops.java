package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Stores all currently-planted crops keyed by serialised location.
 * Persists to crops.yml between restarts.
 *
 * Format on disk: list of "world,x,y,z|cropId|plantedAtMillis|ownerUuid"
 *   - 2-field entries (locKey|cropId) are migrated to "now" with no owner.
 *   - 3-field entries (no owner) are loaded with null owner and don't count
 *     against any cap (treated as legacy/unowned).
 *
 * Concurrency: all paths into this class run on the main server thread today
 * (event handlers + a BukkitTask scheduled with runTaskTimer). The data
 * structures use ConcurrentHashMap + AtomicInteger so that any future async
 * caller cannot lose increments through a torn read-modify-write.
 */
public class TrackedCrops {

    private final CropFarm plugin;
    private final Map<String, TrackedCrop> crops = new ConcurrentHashMap<>();
    /** Per-player count of planted crops keyed by cropId. AtomicInteger ensures
     *  increments/decrements are not lost across threads. */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, AtomicInteger>> playerCounts = new ConcurrentHashMap<>();

    private File dataFile;
    private YamlConfiguration dataConfig;

    public TrackedCrops(CropFarm plugin) {
        this.plugin = plugin;
        load();
    }

    // ---- Mutation ----

    /**
     * Atomically check the player's cap and, if room, reserve a slot.
     * Caller must immediately follow a successful reserve with {@link #trackReserved}
     * once the block is placed. Returns false if cap was already met.
     *
     * cap of 0 (or owner null) means unlimited and always succeeds.
     */
    public boolean tryReserve(UUID owner, String cropId, int cap) {
        if (owner == null || cap <= 0) return true;
        ConcurrentHashMap<String, AtomicInteger> m =
                playerCounts.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        AtomicInteger counter = m.computeIfAbsent(cropId, k -> new AtomicInteger());
        while (true) {
            int current = counter.get();
            if (current >= cap) return false;
            if (counter.compareAndSet(current, current + 1)) return true;
        }
    }

    /**
     * Record a tracked crop whose slot was already reserved by {@link #tryReserve}.
     * Does NOT touch the per-player counter (that was bumped by tryReserve).
     */
    public void trackReserved(Location loc, String cropId, UUID owner) {
        TrackedCrop prev = crops.put(serialize(loc),
                new TrackedCrop(cropId, System.currentTimeMillis(), owner));
        if (prev != null && prev.owner() != null) {
            // Stale entry overwritten — release the old owner's slot since we kept
            // the new one's reservation already.
            decrement(prev.owner(), prev.cropId());
        }
    }

    /**
     * Unconditional track (no cap check, increments counter). Use only when the
     * caller doesn't care about caps (legacy migration, console /give wired to
     * auto-plant in future, etc.). Plant flow should use tryReserve + trackReserved.
     */
    public void track(Location loc, String cropId, UUID owner) {
        TrackedCrop prev = crops.put(serialize(loc),
                new TrackedCrop(cropId, System.currentTimeMillis(), owner));
        if (prev != null && prev.owner() != null) {
            decrement(prev.owner(), prev.cropId());
        }
        if (owner != null) {
            increment(owner, cropId);
        }
    }

    public void untrack(Location loc) {
        TrackedCrop prev = crops.remove(serialize(loc));
        if (prev != null && prev.owner() != null) {
            decrement(prev.owner(), prev.cropId());
        }
    }

    public TrackedCrop get(Location loc) {
        return crops.get(serialize(loc));
    }

    public boolean contains(Location loc) {
        return crops.containsKey(serialize(loc));
    }

    /** Snapshot of all entries — safe to iterate without locking. */
    public List<Map.Entry<String, TrackedCrop>> snapshot() {
        return new ArrayList<>(crops.entrySet());
    }

    public int size() {
        return crops.size();
    }

    /** Current planted count for a given player + crop type. */
    public int countFor(UUID player, String cropId) {
        if (player == null) return 0;
        ConcurrentHashMap<String, AtomicInteger> m = playerCounts.get(player);
        if (m == null) return 0;
        AtomicInteger v = m.get(cropId);
        return v == null ? 0 : Math.max(0, v.get());
    }

    // ---- Counter helpers ----

    private void increment(UUID owner, String cropId) {
        ConcurrentHashMap<String, AtomicInteger> m =
                playerCounts.computeIfAbsent(owner, k -> new ConcurrentHashMap<>());
        m.computeIfAbsent(cropId, k -> new AtomicInteger()).incrementAndGet();
    }

    private void decrement(UUID owner, String cropId) {
        ConcurrentHashMap<String, AtomicInteger> m = playerCounts.get(owner);
        if (m == null) return;
        AtomicInteger counter = m.get(cropId);
        if (counter == null) return;
        // Floor at 0 in case of double-untrack.
        counter.updateAndGet(v -> Math.max(0, v - 1));
        // We deliberately leave zero counters in place rather than removing them,
        // to avoid the orphaned-map race (decrement-then-increment on the same UUID).
        // Memory cost is negligible; entries are bounded by (players × cropTypes).
    }

    // ---- (De)serialisation ----

    public static String serialize(Location loc) {
        return loc.getWorld().getName()
                + "," + loc.getBlockX()
                + "," + loc.getBlockY()
                + "," + loc.getBlockZ();
    }

    /** Returns null if the world is unknown or string is malformed. */
    public static Location deserialize(String key) {
        String[] parts = key.split(",");
        if (parts.length != 4) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world,
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- Persistence ----

    private void load() {
        dataFile = new File(plugin.getDataFolder(), "crops.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        if (!dataFile.exists()) {
            try { dataFile.createNewFile(); }
            catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Cannot create crops.yml", e); }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        long now = System.currentTimeMillis();
        List<String> entries = dataConfig.getStringList("crops");
        for (String entry : entries) {
            String[] parts = entry.split("\\|");
            if (parts.length < 2) continue;
            String locKey = parts[0];
            String cropId = parts[1];
            long plantedAt = now;
            UUID owner = null;
            if (parts.length >= 3) {
                try { plantedAt = Long.parseLong(parts[2]); }
                catch (NumberFormatException ignored) { /* fall back to now */ }
            }
            if (parts.length >= 4 && !parts[3].isEmpty()) {
                try { owner = UUID.fromString(parts[3]); }
                catch (IllegalArgumentException ignored) { /* leave null */ }
            }
            crops.put(locKey, new TrackedCrop(cropId, plantedAt, owner));
            if (owner != null) {
                increment(owner, cropId);
            }
        }
        plugin.getLogger().info("Loaded " + crops.size() + " persisted crop location(s).");
    }

    public void save() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, TrackedCrop> e : crops.entrySet()) {
            TrackedCrop t = e.getValue();
            String ownerPart = t.owner() == null ? "" : t.owner().toString();
            entries.add(e.getKey() + "|" + t.cropId() + "|" + t.plantedAtMillis() + "|" + ownerPart);
        }
        dataConfig.set("crops", entries);
        try { dataConfig.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Cannot save crops.yml", e); }
    }

    public Map<String, TrackedCrop> unmodifiableView() {
        return Collections.unmodifiableMap(crops);
    }
}
