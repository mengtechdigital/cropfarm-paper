package com.cropfarm;

import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Blocks our own crop-seed and seed-bag recipes when the matrix contains
 * another cropfarm-tagged seed, preventing recursive seed-from-seed
 * crafting. The seed-slot ingredient used to be a RecipeChoice.ExactChoice
 * for a vanilla wheat seed, which served the same purpose, but ExactChoice
 * compares the entire ItemStack including data components — so once the
 * StackPlus plugin started stamping a max_stack_size component onto every
 * inventory item, even an unmodified vanilla wheat seed stopped matching
 * and players could not craft any of our seeds. We now use MaterialChoice
 * (ignores components) and police recursion ourselves via this listener.
 */
public final class SeedRecipeGuard implements Listener {

    private final CropFarm plugin;
    private final String pluginNamespace;

    public SeedRecipeGuard(CropFarm plugin) {
        this.plugin = plugin;
        // NamespacedKey lower-cases the plugin name internally; match that here.
        this.pluginNamespace = plugin.getName().toLowerCase();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        Recipe recipe = event.getRecipe();
        if (!(recipe instanceof Keyed keyed)) return;
        if (!pluginNamespace.equals(keyed.getKey().getNamespace())) return;

        NamespacedKey cropTypeKey = plugin.getCropManager().cropTypeKey();
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item == null || item.getType() != Material.WHEAT_SEEDS) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            if (meta.getPersistentDataContainer().has(cropTypeKey, PersistentDataType.STRING)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }
}
