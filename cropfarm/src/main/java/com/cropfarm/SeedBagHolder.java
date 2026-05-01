package com.cropfarm;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Marker holder so SeedBagInventory can recognise its own inventories without
 * relying on titles. Also carries which page is currently visible plus a
 * reference to the owning player + the bag-item's hotbar slot, so the click
 * handler can re-resolve the live ItemStack on every interaction.
 *
 * Why we store the slot, not the ItemStack: ItemStacks are value-equal
 * snapshots; Bukkit copies them across many APIs, so a stale reference would
 * silently disconnect from the player's actual bag. By looking up via slot
 * each time we always operate on the live, mutable instance.
 */
public class SeedBagHolder implements InventoryHolder {

    private final UUID ownerId;
    private final int bagSlot;          // inventory slot the bag was held in when opened
    private final UUID bagInstanceId;   // PDC-stamped per-bag id, used to detect mismatch
    private int currentPage;            // 0-indexed
    private Inventory inventory;
    /**
     * Set to true right before an upgrade re-opens the GUI. The old
     * inventory's onClose would otherwise persist the pre-consume contents
     * back into the bag's PDC, undoing the upgrade's ingredient consumption.
     */
    private boolean suppressCloseSave;

    public SeedBagHolder(Player owner, int bagSlot, UUID bagInstanceId, int currentPage) {
        this.ownerId = owner.getUniqueId();
        this.bagSlot = bagSlot;
        this.bagInstanceId = bagInstanceId;
        this.currentPage = currentPage;
    }

    public UUID getOwnerId()       { return ownerId; }
    public int getBagSlot()        { return bagSlot; }
    public UUID getBagInstanceId() { return bagInstanceId; }
    public int getCurrentPage()    { return currentPage; }

    public void setCurrentPage(int page) { this.currentPage = page; }

    public boolean isSuppressCloseSave() { return suppressCloseSave; }
    public void setSuppressCloseSave(boolean value) { this.suppressCloseSave = value; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
