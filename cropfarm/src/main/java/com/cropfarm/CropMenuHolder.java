package com.cropfarm;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder so CropMenu's click listener can recognise its own inventories
 * without relying on titles (which players might localise or customise).
 */
public class CropMenuHolder implements InventoryHolder {

    private final int page;
    private Inventory inventory;

    public CropMenuHolder(int page) {
        this.page = page;
    }

    public int getPage() {
        return page;
    }

    void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
