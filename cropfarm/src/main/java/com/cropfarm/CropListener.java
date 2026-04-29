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
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class CropListener implements Listener {

    private final CropFarm plugin;
    private final Random random = new Random();
    /** Per-player throttle for the held-seed hint (right-click fires twice on
     *  the same swing in some Paper builds, plus players spam it). */
    private final Map<UUID, Long> hintCooldown = new HashMap<>();
    private static final long HINT_COOLDOWN_MS = 2000L;

    public CropListener(CropFarm plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------
    // Plant
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
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

        Player player = event.getPlayer();

        // Per-player cap: atomic check-and-reserve. Bypass for op perm.
        boolean bypass = player.hasPermission("cropfarm.bypass-cap");
        int cap = bypass ? 0 : type.getMaxPerPlayer();
        if (!plugin.getTrackedCrops().tryReserve(player.getUniqueId(), type.getId(), cap)) {
            String msg = mgr.getCapReachedMessage()
                    .replace("{crop}", type.getDisplayName())
                    .replace("{cap}",  String.valueOf(type.getMaxPerPlayer()))
                    .replace("{count}", String.valueOf(
                            plugin.getTrackedCrops().countFor(player.getUniqueId(), type.getId())));
            player.sendMessage(msg);
            event.setCancelled(true);
            return;
        }

        // Place wheat at age 0 — our scheduled task will advance it on a timer.
        // applyPhysics=false skips the synchronous neighbor-update tick that
        // would otherwise let vanilla pop the block before we get a chance to
        // track it (e.g., low-light pop, water-adjacent placement). Subsequent
        // physics events are caught by protect-from-automation listeners.
        above.setType(Material.WHEAT, false);
        BlockData data = above.getBlockData();
        if (data instanceof Ageable ageable) {
            ageable.setAge(0);
            above.setBlockData(ageable, false);
        }

        // Reservation already incremented the counter; trackReserved just records the entry.
        plugin.getTrackedCrops().trackReserved(above.getLocation(), type.getId(), player.getUniqueId());
        plugin.getNametagService().spawn(above.getLocation(), type);
        plugin.getCoreProtect().logPlace(player, above.getLocation(), Material.WHEAT);

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

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();

        // Farmland directly under a tracked crop: vanilla physics would pop the
        // wheat above as untagged drops (a plain WHEAT_SEEDS item), so the player
        // appears to randomly receive a vanilla seed instead of their custom one.
        // Pre-empt that pop-off by removing our crop ourselves.
        if (block.getType() == Material.FARMLAND) {
            Block above = block.getRelative(BlockFace.UP);
            if (above.getType() == Material.WHEAT) {
                handleSupportLost(above, event.getPlayer());
            }
            return;
        }

        if (block.getType() != Material.WHEAT) return;

        TrackedCrop tracked = plugin.getTrackedCrops().get(block.getLocation());
        if (tracked == null) return;

        CropManager mgr = plugin.getCropManager();
        CropType type = mgr.getCropType(tracked.cropId());
        // Even if the type was removed from config, untrack + clean nametag so the world stays tidy.
        plugin.getNametagService().remove(block.getLocation());
        plugin.getTrackedCrops().untrack(block.getLocation());
        plugin.getCoreProtect().logBreak(event.getPlayer(), block.getLocation(), Material.WHEAT);
        if (type == null) return;

        BlockData data = block.getBlockData();
        if (!(data instanceof Ageable ageable)) return;

        Player player = event.getPlayer();

        if (ageable.getAge() < ageable.getMaximumAge()) {
            // Not fully grown — return seed (no XP).
            event.setDropItems(false);
            if (mgr.isReturnSeedOnEarlyBreak() && player.getGameMode() != GameMode.CREATIVE) {
                ItemStack seed = mgr.createSeed(type, 1);
                deliver(player, block.getLocation(), seed, mgr.isDirectToInventory());
            }
            String msg = mgr.getEarlyBreakMessage()
                    .replace("{stage}", String.valueOf(ageable.getAge()))
                    .replace("{max}", String.valueOf(ageable.getMaximumAge()));
            player.sendMessage(msg);
            return;
        }

        // Fully grown — yield weighted output + return one seed so the cycle continues.
        event.setDropItems(false);
        CropType.DropEntry chosen = type.pickOutput(random);
        int spread = Math.max(0, chosen.maxAmount() - chosen.minAmount());
        int amount = chosen.minAmount() + (spread > 0 ? random.nextInt(spread + 1) : 0);
        Location dropLoc = block.getLocation().add(0.5, 0.5, 0.5);

        boolean direct = mgr.isDirectToInventory();
        deliver(player, block.getLocation(), new ItemStack(chosen.item(), amount), direct);

        if (player.getGameMode() != GameMode.CREATIVE) {
            deliver(player, block.getLocation(), mgr.createSeed(type, 1), direct);
        }

        // XP — direct-grant when configured, else spawn an orb at the crop.
        int xpSpread = Math.max(0, type.getXpMax() - type.getXpMin());
        int xp = type.getXpMin() + (xpSpread > 0 ? random.nextInt(xpSpread + 1) : 0);
        if (xp > 0) {
            if (direct) {
                player.giveExp(xp);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.6f);
            } else {
                ExperienceOrb orb = block.getWorld().spawn(dropLoc, ExperienceOrb.class);
                orb.setExperience(xp);
            }
        }

        if (mgr.isParticles()) {
            block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, dropLoc, 12, 0.4, 0.4, 0.4);
        }
        if (mgr.isSounds()) {
            block.getWorld().playSound(dropLoc, Sound.BLOCK_CROP_BREAK, 1f, 1.2f);
        }

        String msg = mgr.getHarvestMessage()
                .replace("{amount}", String.valueOf(amount))
                .replace("{output}", humanize(chosen.item().name()));
        player.sendMessage(msg);
    }

    /**
     * Player broke the farmland under a tracked crop. Untrack, replace the
     * wheat with air so vanilla pop-off can't drop an untagged WHEAT_SEEDS,
     * and return a tagged seed to the player (no harvest output, no XP — same
     * outcome as breaking an immature crop).
     */
    private void handleSupportLost(Block wheatBlock, Player player) {
        TrackedCrop tracked = plugin.getTrackedCrops().get(wheatBlock.getLocation());
        if (tracked == null) return;

        CropManager mgr = plugin.getCropManager();
        CropType type = mgr.getCropType(tracked.cropId());

        plugin.getNametagService().remove(wheatBlock.getLocation());
        plugin.getTrackedCrops().untrack(wheatBlock.getLocation());
        plugin.getCoreProtect().logBreak(player, wheatBlock.getLocation(), Material.WHEAT);
        wheatBlock.setType(Material.AIR);

        if (type != null && player.getGameMode() != GameMode.CREATIVE) {
            deliver(player, wheatBlock.getLocation(), mgr.createSeed(type, 1), mgr.isDirectToInventory());
        }
    }

    /**
     * Try to put the stack into the player's inventory; spill any leftover at
     * the crop block as a drop. When direct delivery is disabled or the player
     * is in spectator (no usable inventory), drops at the crop block instead.
     */
    private static void deliver(Player player, Location cropLoc, ItemStack stack, boolean direct) {
        if (stack == null || stack.getAmount() <= 0) return;
        Location dropLoc = cropLoc.clone().add(0.5, 0.5, 0.5);
        if (!direct || player.getGameMode() == GameMode.SPECTATOR) {
            cropLoc.getWorld().dropItemNaturally(dropLoc, stack);
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        if (leftover.isEmpty()) return;
        // Inventory full: spill remainder at the crop so nothing is lost.
        for (ItemStack rest : leftover.values()) {
            cropLoc.getWorld().dropItemNaturally(dropLoc, rest);
        }
    }

    /** Convert "GOLD_INGOT" → "Gold Ingot" for harvest chat messages. */
    private static String humanize(String enumName) {
        if (enumName == null || enumName.isEmpty()) return "";
        String[] words = enumName.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return sb.toString();
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
    // Held-seed right-click hint
    //
    // Right-click empty air (or any non-farmland block) with a tagged seed
    // shows a one-line discovery message: tier, grow time, planted/cap,
    // recipe-input. Helps players who don't know /cropfarm menu exists.
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onSeedHint(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        // For block clicks: skip if it's farmland — onPlant handles those.
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block clicked = event.getClickedBlock();
            if (clicked != null && clicked.getType() == Material.FARMLAND) return;
        }

        ItemStack inHand = event.getItem();
        if (inHand == null || inHand.getType() != Material.WHEAT_SEEDS) return;
        CropManager mgr = plugin.getCropManager();
        CropType type = mgr.getCropTypeFromSeed(inHand);
        if (type == null) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = hintCooldown.get(player.getUniqueId());
        if (last != null && now - last < HINT_COOLDOWN_MS) return;
        hintCooldown.put(player.getUniqueId(), now);

        int planted = plugin.getTrackedCrops().countFor(player.getUniqueId(), type.getId());
        String capPart = type.getMaxPerPlayer() > 0
                ? planted + "§7/§f" + type.getMaxPerPlayer()
                : String.valueOf(planted);
        Tier tier = mgr.getTier(type.getTier());
        String tierColor = tier == null ? "§7" : tier.color();
        String tierLabel = type.getTier() == null ? "—" : type.getTier();

        player.sendMessage(type.getDisplayName()
                + " §8| " + tierColor + tierLabel
                + " §8| §f" + formatGrowTime(type.getGrowTimeSeconds()) + " §7grow"
                + " §8| §f" + capPart + " §7planted");
        player.sendMessage("§8Right-click §6farmland §8to plant. "
                + "Recipe: §f1 " + humanize(type.getRecipeInput().name())
                + " §7→§f " + type.getRecipeYield() + " seeds.");
    }

    private static String formatGrowTime(int seconds) {
        if (seconds < 60) return seconds + " sec";
        if (seconds < 3600) return (seconds / 60) + " min";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    // ---------------------------------------------------------------
    // Reconcile nametags as chunks come into view
    // ---------------------------------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.getNametagService().reconcileChunk(
                event.getChunk(), plugin.getTrackedCrops(), plugin.getCropManager());
    }

    // ---------------------------------------------------------------
    // Protection from non-player destruction
    //
    // Player breaks go through onBreak above. Everything else (water,
    // pistons, explosions, mob trampling, fire) would normally drop
    // vanilla wheat + seeds because the wheat block has no idea it's
    // a custom crop — only the seed item carries our PDC tag. Allowing
    // this also enables automated water-flush farms which defeat the
    // plugin's casual-no-redstone design.
    //
    // When protect-from-automation is on (default), we cancel these
    // events for tracked crops AND for the farmland directly under
    // them (so the crop doesn't auto-detach when its support fades).
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onWaterFlow(BlockFromToEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        if (isProtected(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        for (Block b : event.getBlocks()) {
            if (isProtected(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        for (Block b : event.getBlocks()) {
            if (isProtected(b)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        event.blockList().removeIf(this::isProtected);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!plugin.getCropManager().isProtectFromAutomation()) return;
        if (isProtected(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * True if the block is itself a tracked crop, OR is farmland directly
     * supporting one (so we keep the crop's substrate intact too).
     *
     * Performance: BlockFromToEvent fires on every water-flow tick across
     * every loaded chunk. The per-chunk index short-circuit means events in
     * chunks with no crops cost only one ConcurrentHashMap lookup instead
     * of two Location-string serialisations.
     */
    private boolean isProtected(Block b) {
        TrackedCrops tracked = plugin.getTrackedCrops();
        if (tracked.size() == 0) return false;
        if (!tracked.hasCropsInChunk(b.getWorld().getName(), b.getX() >> 4, b.getZ() >> 4)) {
            return false;
        }
        if (tracked.contains(b.getLocation())) return true;
        if (b.getType() == Material.FARMLAND) {
            Block above = b.getRelative(BlockFace.UP);
            return tracked.contains(above.getLocation());
        }
        return false;
    }
}
