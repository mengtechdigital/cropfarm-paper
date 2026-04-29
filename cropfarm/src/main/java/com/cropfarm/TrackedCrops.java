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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Stores all currently-planted crops keyed by serialised location.
 * Persists to crops.yml between restarts.
 *
 * Format on disk: list of "world,x,y,z|cropId|plantedAtMillis"
 * Old entries without timestamp are migrated to "now".
 */
public class TrackedCrops {

    private final CropFarm plugin;
    private final Map<String, TrackedCrop> crops = new ConcurrentHashMap<>();
    private File dataFile;
    private YamlConfiguration dataConfig;

    public TrackedCrops(CropFarm plugin) {
        this.plugin = plugin;
        load();
    }

    // ---- Mutation ----

    public void track(Location loc, String cropId) {
        crops.put(serialize(loc), new TrackedCrop(cropId, System.currentTimeMillis()));
    }

    public void untrack(Location loc) {
        crops.remove(serialize(loc));
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
            if (parts.length >= 3) {
                try { plantedAt = Long.parseLong(parts[2]); }
                catch (NumberFormatException ignored) { /* fall back to now */ }
            }
            crops.put(locKey, new TrackedCrop(cropId, plantedAt));
        }
        plugin.getLogger().info("Loaded " + crops.size() + " persisted crop location(s).");
    }

    public void save() {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, TrackedCrop> e : crops.entrySet()) {
            TrackedCrop t = e.getValue();
            entries.add(e.getKey() + "|" + t.cropId() + "|" + t.plantedAtMillis());
        }
        dataConfig.set("crops", entries);
        try { dataConfig.save(dataFile); }
        catch (IOException e) { plugin.getLogger().log(Level.SEVERE, "Cannot save crops.yml", e); }
    }

    public Map<String, TrackedCrop> unmodifiableView() {
        return Collections.unmodifiableMap(crops);
    }
}
