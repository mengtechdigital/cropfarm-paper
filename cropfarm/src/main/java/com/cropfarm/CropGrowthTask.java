package com.cropfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;

/**
 * Periodic task that advances each tracked crop's age based on time elapsed
 * since planting, replacing vanilla random-tick growth.
 *
 * Runs every TICK_PERIOD ticks (default ~2 seconds). Skips crops whose chunks
 * aren't loaded — they catch up the next time a player visits.
 */
public class CropGrowthTask extends BukkitRunnable {

    private static final long TICK_PERIOD = 40L; // 2 seconds

    private final CropFarm plugin;

    public CropGrowthTask(CropFarm plugin) {
        this.plugin = plugin;
    }

    public void start() {
        runTaskTimer(plugin, TICK_PERIOD, TICK_PERIOD);
    }

    @Override
    public void run() {
        TrackedCrops tracked = plugin.getTrackedCrops();
        CropManager mgr = plugin.getCropManager();
        NametagService nametags = plugin.getNametagService();
        long now = System.currentTimeMillis();

        for (Map.Entry<String, TrackedCrop> entry : tracked.snapshot()) {
            TrackedCrop t = entry.getValue();
            Location loc = TrackedCrops.deserialize(entry.getKey());
            if (loc == null || loc.getWorld() == null) continue;
            if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;

            CropType type = mgr.getCropType(t.cropId());
            Block block = loc.getBlock();
            if (block.getType() != Material.WHEAT) {
                // Block was destroyed by something we didn't intercept (explosion
                // that bypassed our handler, world-edit, op /setblock, farmland-decay
                // physics, etc.). Spawn a compensation seed at the location so the
                // player isn't punished for losing their crop. If the crop type was
                // removed from config, no seed — we don't know what to drop.
                if (type != null) {
                    Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);
                    loc.getWorld().dropItemNaturally(dropLoc, mgr.createSeed(type, 1));
                }
                tracked.untrack(loc);
                nametags.remove(loc);
                continue;
            }
            if (type == null) continue; // tracked but unknown id — wait for player to break

            BlockData data = block.getBlockData();
            if (!(data instanceof Ageable ageable)) continue;

            int max = ageable.getMaximumAge();
            long elapsedMs = now - t.plantedAtMillis();
            int targetAge = (int) Math.min(max,
                    (elapsedMs * max) / Math.max(1L, type.getGrowTimeSeconds() * 1000L));
            if (targetAge < 0) targetAge = 0;

            if (ageable.getAge() != targetAge) {
                ageable.setAge(targetAge);
                block.setBlockData(ageable);
                nametags.update(loc, type);
            }
        }
    }
}
