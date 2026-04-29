package com.cropfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads crop definitions from config.yml, creates seed items + recipes,
 * and exposes global settings. Crop *instance* tracking lives in TrackedCrops.
 */
public class CropManager {

    private final CropFarm plugin;

    /** Key stored in a seed item's PDC to identify which crop type it is. */
    private final NamespacedKey CROP_TYPE_KEY;

    /** All loaded crop types, keyed by their config id. */
    private final Map<String, CropType> cropTypes = new LinkedHashMap<>();

    // ---- Global settings (re-read on reload) ----
    private boolean particles;
    private boolean sounds;
    private boolean returnSeedOnEarlyBreak;
    private boolean allowBonemeal;
    private boolean nametagEnabled;
    private boolean nametagShowProgress;
    private float nametagViewRangeMultiplier;
    private String nametagText;
    private String nametagTextReady;
    private String plantMessage;
    private String harvestMessage;
    private String earlyBreakMessage;
    private String bonemealBlockedMessage;

    public CropManager(CropFarm plugin) {
        this.plugin = plugin;
        this.CROP_TYPE_KEY = new NamespacedKey(plugin, "crop_type");
        reload();
    }

    public NamespacedKey cropTypeKey() { return CROP_TYPE_KEY; }

    // ---------------------------------------------------------------
    // Config loading / reload
    // ---------------------------------------------------------------

    public void reload() {
        for (String id : cropTypes.keySet()) {
            NamespacedKey key = new NamespacedKey(plugin, "cropfarm_" + id);
            plugin.getServer().removeRecipe(key);
        }
        cropTypes.clear();

        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        particles              = cfg.getBoolean("settings.particles", true);
        sounds                 = cfg.getBoolean("settings.sounds", true);
        returnSeedOnEarlyBreak = cfg.getBoolean("settings.return-seed-on-early-break", true);
        allowBonemeal          = cfg.getBoolean("settings.allow-bonemeal", false);

        nametagEnabled         = cfg.getBoolean("settings.nametag-enabled", true);
        nametagShowProgress    = cfg.getBoolean("settings.nametag-show-progress", true);
        int viewRangeBlocks    = Math.max(4, Math.min(64, cfg.getInt("settings.nametag-view-range-blocks", 20)));
        nametagViewRangeMultiplier = viewRangeBlocks / 64f;
        nametagText            = cfg.getString("settings.nametag-text",
                "{crop} §7§o({stage}/{max})");
        nametagTextReady       = cfg.getString("settings.nametag-text-ready",
                "{crop} §a§l✓ Ready!");

        plantMessage           = cfg.getString("settings.plant-message",
                "§a✦ {crop} §aplanted!");
        harvestMessage         = cfg.getString("settings.harvest-message",
                "§b✦ Harvested §f{amount}x {output}§b!");
        earlyBreakMessage      = cfg.getString("settings.early-break-message",
                "§e⚠ Not fully grown! ({stage}/{max}) Seed returned.");
        bonemealBlockedMessage = cfg.getString("settings.bonemeal-blocked-message",
                "§c⚠ Magic seeds cannot be bone-mealed!");

        ConfigurationSection crops = cfg.getConfigurationSection("crops");
        if (crops == null) {
            plugin.getLogger().warning("No 'crops' section found in config.yml!");
            return;
        }

        for (String id : crops.getKeys(false)) {
            ConfigurationSection s = crops.getConfigurationSection(id);
            if (s == null) continue;

            String recipeInputName = s.getString("recipe-input", "");
            Material recipeInput = Material.matchMaterial(recipeInputName);
            if (recipeInput == null) {
                plugin.getLogger().warning("Crop '" + id + "': invalid recipe-input '" + recipeInputName + "' — skipping.");
                continue;
            }

            String outputName = s.getString("output", "");
            Material output = Material.matchMaterial(outputName);
            if (output == null) {
                plugin.getLogger().warning("Crop '" + id + "': invalid output '" + outputName + "' — skipping.");
                continue;
            }

            String displayName  = s.getString("display-name", "§f" + id + " Seed");
            List<String> lore   = s.getStringList("lore");
            int recipeYield     = Math.max(1, Math.min(64, s.getInt("recipe-yield", 4)));
            int minDrops        = Math.max(1, s.getInt("min-drops", 1));
            int maxDrops        = Math.max(minDrops, s.getInt("max-drops", 3));
            int customModelData = s.getInt("custom-model-data", 0);
            int growTimeSeconds = Math.max(10, s.getInt("grow-time-seconds", 600));

            CropType cropType = new CropType(id, displayName, lore,
                    recipeInput, recipeYield, output, minDrops, maxDrops,
                    customModelData, growTimeSeconds);
            cropTypes.put(id, cropType);

            NamespacedKey recipeKey = new NamespacedKey(plugin, "cropfarm_" + id);
            ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, createSeed(cropType, recipeYield));
            recipe.addIngredient(recipeInput);
            plugin.getServer().addRecipe(recipe);

            plugin.getLogger().info("Loaded crop: " + id + " (1x " + recipeInput.name()
                    + " → " + recipeYield + " seeds, drops " + minDrops + "-" + maxDrops + "x "
                    + output.name() + ", grow " + growTimeSeconds + "s)");
        }

        plugin.getLogger().info("Loaded " + cropTypes.size() + " crop type(s).");
    }

    // ---------------------------------------------------------------
    // Seed item helpers
    // ---------------------------------------------------------------

    public ItemStack createSeed(CropType type, int amount) {
        ItemStack seed = new ItemStack(Material.WHEAT_SEEDS, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = seed.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(type.getDisplayName());
            if (!type.getLore().isEmpty()) {
                meta.setLore(type.getLore());
            }
            meta.getPersistentDataContainer().set(CROP_TYPE_KEY, PersistentDataType.STRING, type.getId());
            if (type.getCustomModelData() > 0) {
                meta.setCustomModelData(type.getCustomModelData());
            }
            seed.setItemMeta(meta);
        }
        return seed;
    }

    public CropType getCropTypeFromSeed(ItemStack item) {
        if (item == null || item.getType() != Material.WHEAT_SEEDS) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        String id = meta.getPersistentDataContainer().get(CROP_TYPE_KEY, PersistentDataType.STRING);
        if (id == null) return null;
        return cropTypes.get(id);
    }

    // ---------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------

    public boolean isParticles()              { return particles; }
    public boolean isSounds()                 { return sounds; }
    public boolean isReturnSeedOnEarlyBreak() { return returnSeedOnEarlyBreak; }
    public boolean isAllowBonemeal()          { return allowBonemeal; }

    public boolean isNametagEnabled()         { return nametagEnabled; }
    public boolean isNametagShowProgress()    { return nametagShowProgress; }
    public float getNametagViewRangeMultiplier() { return nametagViewRangeMultiplier; }
    public String getNametagText()            { return nametagText; }
    public String getNametagTextReady()       { return nametagTextReady; }

    public String getPlantMessage()           { return plantMessage; }
    public String getHarvestMessage()         { return harvestMessage; }
    public String getEarlyBreakMessage()      { return earlyBreakMessage; }
    public String getBonemealBlockedMessage() { return bonemealBlockedMessage; }

    public Collection<CropType> getCropTypes() { return Collections.unmodifiableCollection(cropTypes.values()); }
    public CropType getCropType(String id)     { return cropTypes.get(id); }
    public boolean hasCropType(String id)      { return cropTypes.containsKey(id); }
    public Set<String> getCropTypeIds()        { return Collections.unmodifiableSet(cropTypes.keySet()); }
}
