package com.cropfarm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages floating TextDisplay entities anchored above tracked crop blocks.
 *
 * Each entity is tagged in its PersistentDataContainer with the serialised
 * crop location (NAMETAG_KEY) so we can clean up orphans on enable / chunk load.
 */
public class NametagService {

    private final CropFarm plugin;
    private final NamespacedKey NAMETAG_KEY;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacySection();

    public NametagService(CropFarm plugin) {
        this.plugin = plugin;
        this.NAMETAG_KEY = new NamespacedKey(plugin, "nametag_for");
    }

    public NamespacedKey nametagKey() { return NAMETAG_KEY; }

    /**
     * Spawn a nametag for the crop block at this location, if one doesn't already exist.
     * No-op if nametags are disabled.
     */
    public void spawn(Location cropBlock, CropType type) {
        if (!plugin.getCropManager().isNametagEnabled()) return;
        if (cropBlock.getWorld() == null) return;

        // Don't double-spawn
        if (findExisting(cropBlock) != null) {
            update(cropBlock, type);
            return;
        }

        Location anchor = cropBlock.clone().add(0.5, 1.1, 0.5);
        World world = cropBlock.getWorld();
        String text = renderText(cropBlock, type);

        world.spawn(anchor, TextDisplay.class, td -> {
            td.text(legacy.deserialize(text));
            td.setBillboard(Display.Billboard.CENTER);
            td.setViewRange(plugin.getCropManager().getNametagViewRangeMultiplier());
            td.setShadowed(true);
            td.setSeeThrough(false);
            td.setPersistent(true);
            td.getPersistentDataContainer().set(
                    NAMETAG_KEY, PersistentDataType.STRING, TrackedCrops.serialize(cropBlock));
        });
    }

    /** Refresh the text on the existing nametag (if any). */
    public void update(Location cropBlock, CropType type) {
        if (!plugin.getCropManager().isNametagEnabled()) return;
        TextDisplay td = findExisting(cropBlock);
        if (td == null) return;
        td.text(legacy.deserialize(renderText(cropBlock, type)));
        td.setViewRange(plugin.getCropManager().getNametagViewRangeMultiplier());
    }

    /** Remove the nametag for this crop block (if any). */
    public void remove(Location cropBlock) {
        TextDisplay td = findExisting(cropBlock);
        if (td != null) td.remove();
    }

    /**
     * For each tracked crop in the chunk, ensure a nametag entity exists; remove
     * any TextDisplays in the chunk that point at coords no longer tracked.
     */
    public void reconcileChunk(Chunk chunk, TrackedCrops tracked, CropManager cropManager) {
        if (!plugin.getCropManager().isNametagEnabled()) return;

        // Pass 1: remove orphans
        for (Entity e : chunk.getEntities()) {
            if (!(e instanceof TextDisplay td)) continue;
            String marker = td.getPersistentDataContainer()
                    .get(NAMETAG_KEY, PersistentDataType.STRING);
            if (marker == null) continue;
            Location loc = TrackedCrops.deserialize(marker);
            if (loc == null || !tracked.contains(loc) ||
                    cropManager.getCropType(tracked.get(loc).cropId()) == null) {
                td.remove();
            }
        }

        // Pass 2: spawn missing entities for crops in this chunk
        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        String worldName = chunk.getWorld().getName();
        for (var entry : tracked.snapshot()) {
            String key = entry.getKey();
            String[] parts = key.split(",");
            if (parts.length != 4) continue;
            if (!parts[0].equals(worldName)) continue;
            int bx, bz;
            try {
                bx = Integer.parseInt(parts[1]);
                bz = Integer.parseInt(parts[3]);
            } catch (NumberFormatException ex) { continue; }
            if ((bx >> 4) != chunkX || (bz >> 4) != chunkZ) continue;

            Location loc = TrackedCrops.deserialize(key);
            if (loc == null) continue;
            CropType type = cropManager.getCropType(entry.getValue().cropId());
            if (type == null) continue;
            spawn(loc, type);
        }
    }

    /** Bulk cleanup of nametags whose marker no longer matches a tracked crop. */
    public void purgeOrphansInLoadedChunks(TrackedCrops tracked, CropManager cropManager) {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                reconcileChunk(chunk, tracked, cropManager);
            }
        }
    }

    /** Remove every nametag we own across all loaded worlds (called on disable). */
    public void removeAll() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                for (Entity e : chunk.getEntities()) {
                    if (!(e instanceof TextDisplay td)) continue;
                    if (td.getPersistentDataContainer().has(NAMETAG_KEY, PersistentDataType.STRING)) {
                        td.remove();
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private TextDisplay findExisting(Location cropBlock) {
        World world = cropBlock.getWorld();
        if (world == null) return null;
        Location center = cropBlock.clone().add(0.5, 1.1, 0.5);
        // Search in a small box around the expected anchor
        List<Entity> nearby = new ArrayList<>(world.getNearbyEntities(center, 0.6, 1.2, 0.6));
        String marker = TrackedCrops.serialize(cropBlock);
        for (Entity e : nearby) {
            if (!(e instanceof TextDisplay td)) continue;
            String tag = td.getPersistentDataContainer().get(NAMETAG_KEY, PersistentDataType.STRING);
            if (marker.equals(tag)) return td;
        }
        return null;
    }

    private String renderText(Location cropBlock, CropType type) {
        CropManager mgr = plugin.getCropManager();
        BlockData data = cropBlock.getBlock().getBlockData();
        int stage = 0, max = 7;
        if (data instanceof Ageable ageable) {
            stage = ageable.getAge();
            max   = ageable.getMaximumAge();
        }
        boolean ready = stage >= max;
        String template = ready ? mgr.getNametagTextReady() : mgr.getNametagText();
        if (!mgr.isNametagShowProgress() && !ready) {
            template = "{crop}";
        }
        return template
                .replace("{crop}",  type.getDisplayName())
                .replace("{stage}", String.valueOf(stage))
                .replace("{max}",   String.valueOf(max));
    }

    /** Compose Component (kept public for callers wanting raw component). */
    @SuppressWarnings("unused")
    public Component renderComponent(Location cropBlock, CropType type) {
        return legacy.deserialize(renderText(cropBlock, type));
    }
}
