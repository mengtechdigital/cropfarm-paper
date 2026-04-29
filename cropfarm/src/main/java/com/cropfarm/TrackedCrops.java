package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory cache of all currently-planted crops keyed by serialised location.
 * Persists each change synchronously through the supplied {@link CropStore}
 * (SQLite by default — see {@link SqliteCropStore}).
 *
 * Concurrency: all paths run on the main server thread today (event handlers
 * + a BukkitTask scheduled with runTaskTimer). Data structures use
 * ConcurrentHashMap + AtomicInteger so future async callers cannot lose
 * increments through a torn read-modify-write.
 */
public class TrackedCrops {

    private final CropFarm plugin;
    private final CropStore store;
    private final Map<String, TrackedCrop> crops = new ConcurrentHashMap<>();
    /** Per-player count of planted crops keyed by cropId. */
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<String, AtomicInteger>> playerCounts =
            new ConcurrentHashMap<>();

    /**
     * @param initial entries already loaded from the store (or migrated from
     *                legacy YAML). Owners with non-null UUID seed playerCounts.
     */
    public TrackedCrops(CropFarm plugin, CropStore store, Map<String, TrackedCrop> initial) {
        this.plugin = plugin;
        this.store = store;
        for (Map.Entry<String, TrackedCrop> e : initial.entrySet()) {
            crops.put(e.getKey(), e.getValue());
            UUID owner = e.getValue().owner();
            if (owner != null) increment(owner, e.getValue().cropId());
        }
        plugin.getLogger().info("TrackedCrops: " + crops.size() + " crops loaded from store.");
    }

    // ---- Mutation ----

    /**
     * Atomically check the player's cap and, if room, reserve a slot. Caller
     * must immediately follow a successful reserve with {@link #trackReserved}
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
        String key = serialize(loc);
        TrackedCrop entry = new TrackedCrop(cropId, System.currentTimeMillis(), owner);
        TrackedCrop prev = crops.put(key, entry);
        if (prev != null && prev.owner() != null) {
            // Stale entry overwritten — release the old owner's slot.
            decrement(prev.owner(), prev.cropId());
        }
        store.put(key, entry);
    }

    /**
     * Unconditional track (no cap check, increments counter). Use when the
     * caller doesn't go through tryReserve (legacy migration, console-driven
     * planting in future).
     */
    public void track(Location loc, String cropId, UUID owner) {
        String key = serialize(loc);
        TrackedCrop entry = new TrackedCrop(cropId, System.currentTimeMillis(), owner);
        TrackedCrop prev = crops.put(key, entry);
        if (prev != null && prev.owner() != null) {
            decrement(prev.owner(), prev.cropId());
        }
        if (owner != null) {
            increment(owner, cropId);
        }
        store.put(key, entry);
    }

    public void untrack(Location loc) {
        String key = serialize(loc);
        TrackedCrop prev = crops.remove(key);
        if (prev != null && prev.owner() != null) {
            decrement(prev.owner(), prev.cropId());
        }
        store.remove(key);
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
        // Leave zero counters in place to avoid the orphaned-map race
        // (decrement-then-increment on the same UUID). Memory cost is bounded
        // by (players × cropTypes), negligible.
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

    // ---- Persistence passthroughs ----

    /** Force the underlying store to flush. SQLite is auto-flushing; legacy paths use this. */
    public void save() {
        store.flush();
    }

    public Map<String, TrackedCrop> unmodifiableView() {
        return Collections.unmodifiableMap(crops);
    }
}
