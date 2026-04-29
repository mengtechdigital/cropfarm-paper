package com.cropfarm;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public class CropListener implements Listener {

    private final CropFarm plugin;
    private final Random random = new Random();

    public CropListener(CropFarm plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------
    // Plant
    // ---------------------------------------------------------------

    @EventHandler
    public void onPlant(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.FARMLAND) return;

        ItemStack inHand = event.getItem();
        if (inHand == null || inHand.getType() != Material.WHEAT_SEEDS) return;

        CropManager mgr = plugin.getCropManager();
        CropType type = mgr.getCropTypeFromSeed(inHand);
        if (type == null) return;

        Block above = clicked.getRelative(BlockFace.UP);
        if (above.getType() != Material.AIR) return;

        // Place wheat at age 0 — our scheduled task will advance it on a timer.
        above.setType(Material.WHEAT);
        BlockData data = above.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(0);
            above.setBlockData(ageable);
        }

        plugin.getTrackedCrops().track(above.getLocation(), type.getId());
        plugin.getNametagService().spawn(above.getLocation(), type);

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.CREATIVE) {
            inHand.setAmount(inHand.getAmount() - 1);
        }

        if (mgr.isParticles()) {
            above.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                    above.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3);
        }
        if (mgr.isSounds()) {
            above.getWorld().playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, 1f, 1f);
        }

        String msg = mgr.getPlantMessage().replace("{crop}", type.getDisplayName());
        player.sendMessage(msg);

        event.setCancelled(true);
    }

    // ---------------------------------------------------------------
    // Break / harvest
    // ---------------------------------------------------------------

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.WHEAT) return;

        TrackedCrop tracked = plugin.getTrackedCrops().get(block.getLocation());
        if (tracked == null) return;

        CropManager mgr = plugin.getCropManager();
        CropType type = mgr.getCropType(tracked.cropId());
        // Even if the type was removed from config, still untrack + clean nametag so the world stays tidy.
        plugin.getNametagService().remove(block.getLocation());
        plugin.getTrackedCrops().untrack(block.getLocation());
        if (type == null) return;

        BlockData data = block.getBlockData();
        if (!(data instanceof Ageable ageable)) return;

        Player player = event.getPlayer();

        if (ageable.getAge() < ageable.getMaximumAge()) {
            // Not fully grown — return seed
            event.setDropItems(false);
            if (mgr.isReturnSeedOnEarlyBreak() && player.getGameMode() != GameMode.CREATIVE) {
                ItemStack seed = mgr.createSeed(type, 1);
                Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
                block.getWorld().dropItemNaturally(dropLoc, seed);
            }
            String msg = mgr.getEarlyBreakMessage()
                    .replace("{stage}", String.valueOf(ageable.getAge()))
                    .replace("{max}", String.valueOf(ageable.getMaximumAge()));
            player.sendMessage(msg);
            return;
        }

        // Fully grown — drop output + return one seed so the cycle continues
        event.setDropItems(false);
        int amount = type.getMinDrops()
                + random.nextInt(type.getMaxDrops() - type.getMinDrops() + 1);
        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().dropItemNaturally(dropLoc, new ItemStack(type.getOutput(), amount));

        if (player.getGameMode() != GameMode.CREATIVE) {
            block.getWorld().dropItemNaturally(dropLoc, mgr.createSeed(type, 1));
        }

        if (mgr.isParticles()) {
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, dropLoc, 12, 0.4, 0.4, 0.4);
        }
        if (mgr.isSounds()) {
            block.getWorld().playSound(dropLoc, Sound.BLOCK_CROP_BREAK, 1f, 1.2f);
        }

        String msg = mgr.getHarvestMessage()
                .replace("{amount}", String.valueOf(amount))
                .replace("{output}", type.getOutput().name());
        player.sendMessage(msg);
    }

    // ---------------------------------------------------------------
    // Block vanilla growth on tracked crops (we drive growth ourselves)
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onNaturalGrow(BlockGrowEvent event) {
        if (plugin.getTrackedCrops().contains(event.getBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------
    // Block bonemeal on tracked crops (configurable)
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onFertilize(BlockFertilizeEvent event) {
        if (plugin.getCropManager().isAllowBonemeal()) return;
        if (!plugin.getTrackedCrops().contains(event.getBlock().getLocation())) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (p != null) {
            p.sendMessage(plugin.getCropManager().getBonemealBlockedMessage());
        }
    }

    // Also catch the player's right-click before the fertilize event fires —
    // some servers don't propagate BlockFertilizeEvent for cancelled-but-fired interactions.
    @EventHandler(ignoreCancelled = true)
    public void onBonemealInteract(PlayerInteractEvent event) {
        if (plugin.getCropManager().isAllowBonemeal()) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BONE_MEAL) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        if (!plugin.getTrackedCrops().contains(clicked.getLocation())) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getCropManager().getBonemealBlockedMessage());
    }

    // ---------------------------------------------------------------
    // Reconcile nametags as chunks come into view
    // ---------------------------------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getNametagService().reconcileChunk(
                event.getChunk(), plugin.getTrackedCrops(), plugin.getCropManager());
    }
}
