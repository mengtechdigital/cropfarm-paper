package com.cropfarm;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marker holder so CropMenu's click listener can recognise its own inventories
 * without relying on titles, plus carries the menu state (mode, current
 * category, current page) so the click handler can route correctly.
 */
public class CropMenuHolder implements InventoryHolder {

    public enum Mode { MAIN, CATEGORY }

    private final Mode mode;
    private final String category;  // null in MAIN mode
    private final int page;          // 0-indexed; ignored in MAIN mode
    private Inventory inventory;

    public CropMenuHolder(Mode mode, String category, int page) {
        this.mode = mode;
        this.category = category;
        this.page = page;
    }

    public Mode getMode()       { return mode; }
    public String getCategory() { return category; }
    public int getPage()        { return page; }

    void setInventory(Inventory inventory) { this.inventory = inventory; }

    @Override
    public Inventory getInventory() { return inventory; }
}
