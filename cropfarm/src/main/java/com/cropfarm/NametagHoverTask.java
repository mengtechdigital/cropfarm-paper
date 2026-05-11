package com.cropfarm;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Polls each online player's target block and shows the nametag for the
 * crop they're looking at while hiding all others.
 *
 * Nametag entities are spawned with {@code setVisibleByDefault(false)}
 * so they are invisible until this task calls {@code showEntity}.
 */
public class NametagHoverTask extends BukkitRunnable {

    private static final long TICK_PERIOD = 4L; // 200 ms
    private static final int REACH = 6;

    private final CropFarm plugin;
    private final Map<UUID, Location> lastTarget = new ConcurrentHashMap<>();

    public NametagHoverTask(CropFarm plugin) {
        this.plugin = plugin;
    }

    public void start() {
        runTaskTimer(plugin, TICK_PERIOD, TICK_PERIOD);
    }

    @Override
    public void run() {
        NametagService nametags = plugin.getNametagService();

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            Location previous = lastTarget.get(uuid);
            Location current = getHoveredCropLocation(player);

            if (previous != null && !previous.equals(current)) {
                TextDisplay td = nametags.findExisting(previous);
                if (td != null) {
                    player.hideEntity(plugin, td);
                }
            }

            if (current != null && !current.equals(previous)) {
                TextDisplay td = nametags.findExisting(current);
                if (td != null) {
                    player.showEntity(plugin, td);
                }
            }

            if (current == null) {
                lastTarget.remove(uuid);
            } else {
                lastTarget.put(uuid, current);
            }
        }

        lastTarget.keySet().removeIf(uuid -> plugin.getServer().getPlayer(uuid) == null);
    }

    private Location getHoveredCropLocation(Player player) {
        Block target = player.getTargetBlockExact(REACH);
        if (target == null) {
            return null;
        }

        Location loc = target.getLocation();
        if (plugin.getTrackedCrops().contains(loc)) {
            return loc;
        }

        if (target.getType() == Material.FARMLAND) {
            Block above = target.getRelative(BlockFace.UP);
            Location aboveLoc = above.getLocation();
            if (plugin.getTrackedCrops().contains(aboveLoc)) {
                return aboveLoc;
            }
        }

        return null;
    }
}
