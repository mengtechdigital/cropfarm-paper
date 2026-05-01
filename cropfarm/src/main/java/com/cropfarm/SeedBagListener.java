package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Glue between Bukkit events and the SeedBag/SeedBagInventory machinery.
 *
 *  - Right-click held bag in air → open GUI
 *  - Bag in inventory at harvest time → grant Cultivation XP to first bag
 *  - InventoryClick / Drag → delegate to SeedBagInventory
 *  - InventoryClose → persist current page to bag PDC
 *  - PlayerDropItem → if dropping the open bag, close GUI first
 */
public class SeedBagListener implements Listener {

    private final SeedBag seedBag;
    private final SeedBagInventory bagInventory;

    public SeedBagListener(SeedBag seedBag, SeedBagInventory bagInventory) {
        this.seedBag = seedBag;
        this.bagInventory = bagInventory;
    }

    // ---------------------------------------------------------------
    // Open via right-click
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = false, priority = EventPriority.HIGH)
    public void onRightClickOpen(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        // RIGHT_CLICK_AIR only — block-clicks on chests/crafting tables/etc.
        // should still open those vanilla GUIs. Players who want to open the
        // bag from inside a build just look up briefly.
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        ItemStack inHand = event.getItem();
        if (inHand == null || !seedBag.isSeedBag(inHand)) return;

        // Cancel + DENY use so vanilla Bundle's last-item-pop behaviour never
        // fires (CropFarm bags are bundle items underneath).
        event.setCancelled(true);
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);

        Player player = event.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();
        bagInventory.open(player, slot, 0);
    }

    // ---------------------------------------------------------------
    // GUI events → delegate
    // ---------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        bagInventory.onClick(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        bagInventory.onDrag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SeedBagHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;
        // Upgrade flow re-opens the GUI; the old inventory close fires
        // synchronously during that openInventory call. Skip the persist
        // step in that case so we don't roll back the just-applied upgrade.
        if (holder.isSuppressCloseSave()) return;
        ItemStack bag = bagInventory.resolveBag(player, holder);
        if (bag == null) return;
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(bag));
        if (def == null) return;
        bagInventory.persistCurrentPage(event.getInventory(), bag, def, holder.getCurrentPage());
        seedBag.refreshDisplay(bag);
        // Force inventory update — Bukkit usually does this anyway, but be explicit.
        player.updateInventory();
    }

    // ---------------------------------------------------------------
    // Drop-while-open: close the GUI so we don't desync against a dropped bag
    // ---------------------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (!(player.getOpenInventory().getTopInventory().getHolder() instanceof SeedBagHolder holder))
            return;
        ItemStack dropped = event.getItemDrop().getItemStack();
        if (!seedBag.isSeedBag(dropped)) return;
        // The dropped bag must be the one we're viewing — match instance id.
        if (holder.getBagInstanceId() == null
                || !holder.getBagInstanceId().equals(seedBag.getInstanceId(dropped))) return;
        // Persist + close. The held GUI is no longer valid since bag is gone.
        SeedBagTier.Definition def = SeedBagTier.get(seedBag.getTier(dropped));
        if (def != null) {
            bagInventory.persistCurrentPage(player.getOpenInventory().getTopInventory(),
                    dropped, def, holder.getCurrentPage());
            seedBag.refreshDisplay(dropped);
        }
        player.closeInventory();
    }

    // ---------------------------------------------------------------
    // Harvest XP: piggyback on the existing CropListener via a public hook.
    //
    // Rather than coupling CropListener to SeedBag directly, CropFarm.java
    // calls grantXpForHarvest(player, cropTier) from CropListener.onBreak
    // after a successful fully-grown harvest. Keeps SeedBag concerns out of
    // the crop break path.
    // ---------------------------------------------------------------

    public void grantXpForHarvest(Player player, String cropTierId) {
        if (player == null) return;
        ItemStack bag = findFirstBag(player);
        if (bag == null) return;
        int amount = SeedBagTier.xpForCropTier(cropTierId);
        seedBag.addXp(bag, amount);
    }

    private ItemStack findFirstBag(Player player) {
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (seedBag.isSeedBag(s)) return s;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        if (seedBag.isSeedBag(off)) return off;
        return null;
    }

    // ---------------------------------------------------------------
    // Plugin disable: force-close any open bag GUIs so contents persist.
    // ---------------------------------------------------------------

    public void closeAllOnDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            var top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof SeedBagHolder) {
                p.closeInventory();
            }
        }
    }
}
