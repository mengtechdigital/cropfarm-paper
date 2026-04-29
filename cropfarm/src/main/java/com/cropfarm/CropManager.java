package com.cropfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads tier definitions and crop definitions from config.yml plus any
 * crops/*.yml files in the plugin data folder, builds seed items + recipes,
 * and exposes global settings. Crop instance tracking lives in TrackedCrops.
 */
public class CropManager {

    private final CropFarm plugin;

    /** Key stored in a seed item's PDC to identify which crop type it is. */
    private final NamespacedKey CROP_TYPE_KEY;

    /** All loaded crop types, keyed by their config id. */
    private final Map<String, CropType> cropTypes = new LinkedHashMap<>();

    /** All loaded tiers, keyed by lower-case tier id. */
    private final Map<String, Tier> tiers = new LinkedHashMap<>();

    // ---- Global settings (re-read on reload) ----
    private boolean particles;
    private boolean sounds;
    private boolean returnSeedOnEarlyBreak;
    private boolean allowBonemeal;
    private boolean directToInventory;
    private boolean nametagEnabled;
    private boolean nametagShowProgress;
    private float nametagViewRangeMultiplier;
    private String nametagText;
    private String nametagTextReady;
    private String plantMessage;
    private String harvestMessage;
    private String earlyBreakMessage;
    private String bonemealBlockedMessage;
    private String capReachedMessage;

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
        // Strip recipes for any previously-loaded crops so a reload doesn't leave duplicates.
        for (String id : cropTypes.keySet()) {
            NamespacedKey key = new NamespacedKey(plugin, "cropfarm_" + id);
            plugin.getServer().removeRecipe(key);
        }
        cropTypes.clear();
        tiers.clear();

        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        particles              = cfg.getBoolean("settings.particles", true);
        sounds                 = cfg.getBoolean("settings.sounds", true);
        returnSeedOnEarlyBreak = cfg.getBoolean("settings.return-seed-on-early-break", true);
        allowBonemeal          = cfg.getBoolean("settings.allow-bonemeal", false);
        directToInventory      = cfg.getBoolean("settings.direct-to-inventory", true);

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
        capReachedMessage      = cfg.getString("settings.cap-reached-message",
                "§c⚠ You've planted the maximum {cap} {crop}§c. Harvest some first.");

        // ---- Tiers ----
        ConfigurationSection tiersSec = cfg.getConfigurationSection("tiers");
        if (tiersSec != null) {
            for (String tid : tiersSec.getKeys(false)) {
                ConfigurationSection ts = tiersSec.getConfigurationSection(tid);
                if (ts == null) continue;
                String key = tid.toLowerCase();
                Tier defaults = Tier.defaultFor(key);
                int gt   = Math.max(10, ts.getInt("grow-time-seconds", defaults.growTimeSeconds()));
                int mpp  = Math.max(0, ts.getInt("max-per-player", defaults.maxPerPlayer()));
                int xmin = Math.max(0, ts.getInt("xp-min", defaults.xpMin()));
                int xmax = Math.max(xmin, ts.getInt("xp-max", defaults.xpMax()));
                String col = ts.getString("color", defaults.color());
                tiers.put(key, new Tier(key, gt, mpp, xmin, xmax, col));
            }
        }
        if (tiers.isEmpty()) {
            for (String t : new String[]{"common", "mid", "rare", "epic"}) {
                tiers.put(t, Tier.defaultFor(t));
            }
        }

        // ---- Crops from main config.yml ----
        loadCropsFromSection(cfg.getConfigurationSection("crops"), "config.yml");

        // ---- Crops from crops/*.yml ----
        File cropsDir = new File(plugin.getDataFolder(), "crops");
        if (cropsDir.exists() && cropsDir.isDirectory()) {
            File[] files = cropsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                    loadCropsFromSection(y.getConfigurationSection("crops"), "crops/" + f.getName());
                }
            }
        }

        plugin.getLogger().info("Loaded " + cropTypes.size() + " crop type(s) across "
                + tiers.size() + " tier(s).");
    }

    private void loadCropsFromSection(ConfigurationSection crops, String sourceName) {
        if (crops == null) return;
        for (String id : crops.getKeys(false)) {
            ConfigurationSection s = crops.getConfigurationSection(id);
            if (s == null) continue;
            loadOneCrop(id.toLowerCase(), s, sourceName);
        }
    }

    private void loadOneCrop(String id, ConfigurationSection s, String sourceName) {
        if (cropTypes.containsKey(id)) {
            plugin.getLogger().warning("Duplicate crop id '" + id + "' in " + sourceName
                    + " — overriding earlier definition.");
            // Strip the previously-registered recipe before re-registering.
            plugin.getServer().removeRecipe(new NamespacedKey(plugin, "cropfarm_" + id));
        }

        String tierId = s.getString("tier", "common");
        Tier tier = tiers.getOrDefault(tierId.toLowerCase(), Tier.defaultFor(tierId));

        String recipeInputName = s.getString("recipe-input", "");
        Material recipeInput = Material.matchMaterial(recipeInputName);
        if (recipeInput == null) {
            plugin.getLogger().warning("Crop '" + id + "' (" + sourceName
                    + "): invalid recipe-input '" + recipeInputName + "' — skipping.");
            return;
        }

        // Outputs: prefer new `outputs:` list; fall back to legacy `output:` + min/max.
        List<CropType.DropEntry> outputs = new ArrayList<>();
        List<Map<?, ?>> outputList = s.getMapList("outputs");
        if (outputList != null && !outputList.isEmpty()) {
            for (Map<?, ?> m : outputList) {
                Object itemObj = m.get("item");
                if (itemObj == null) continue;
                Material mat = Material.matchMaterial(itemObj.toString());
                if (mat == null) {
                    plugin.getLogger().warning("Crop '" + id + "': invalid output item '"
                            + itemObj + "' — skipping entry.");
                    continue;
                }
                int weight = Math.max(1, parseInt(m.get("weight"), 1));
                int min    = Math.max(1, parseInt(m.get("min"),    1));
                int max    = Math.max(min, parseInt(m.get("max"),  min));
                outputs.add(new CropType.DropEntry(mat, weight, min, max));
            }
        }
        if (outputs.isEmpty()) {
            String outputName = s.getString("output", "");
            Material output = Material.matchMaterial(outputName);
            if (output == null) {
                plugin.getLogger().warning("Crop '" + id + "' (" + sourceName
                        + "): no valid output(s) — skipping.");
                return;
            }
            int min = Math.max(1, s.getInt("min-drops", 1));
            int max = Math.max(min, s.getInt("max-drops", min));
            outputs.add(new CropType.DropEntry(output, 1, min, max));
        }

        String displayName  = s.getString("display-name", "§f" + id + " Seed");
        List<String> lore   = s.getStringList("lore");
        int recipeYield     = Math.max(1, Math.min(64, s.getInt("recipe-yield", 4)));
        int customModelData = s.getInt("custom-model-data", 0);
        int growTime        = Math.max(10, s.getInt("grow-time-seconds", tier.growTimeSeconds()));
        int maxPerPlayer    = Math.max(0, s.getInt("max-per-player", tier.maxPerPlayer()));
        int xpMin           = Math.max(0, s.getInt("xp-min", tier.xpMin()));
        int xpMax           = Math.max(xpMin, s.getInt("xp-max", tier.xpMax()));

        CropType cropType = new CropType(id, displayName, lore,
                recipeInput, recipeYield, outputs,
                customModelData, growTime, maxPerPlayer, xpMin, xpMax,
                tier.id());
        cropTypes.put(id, cropType);

        NamespacedKey recipeKey = new NamespacedKey(plugin, "cropfarm_" + id);
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, createSeed(cropType, recipeYield));
        recipe.addIngredient(recipeInput);
        plugin.getServer().addRecipe(recipe);
    }

    private static int parseInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) {
            try { return Integer.parseInt(s.trim()); }
            catch (NumberFormatException e) { return def; }
        }
        return def;
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
    public boolean isDirectToInventory()      { return directToInventory; }

    public boolean isNametagEnabled()         { return nametagEnabled; }
    public boolean isNametagShowProgress()    { return nametagShowProgress; }
    public float getNametagViewRangeMultiplier() { return nametagViewRangeMultiplier; }
    public String getNametagText()            { return nametagText; }
    public String getNametagTextReady()       { return nametagTextReady; }

    public String getPlantMessage()           { return plantMessage; }
    public String getHarvestMessage()         { return harvestMessage; }
    public String getEarlyBreakMessage()      { return earlyBreakMessage; }
    public String getBonemealBlockedMessage() { return bonemealBlockedMessage; }
    public String getCapReachedMessage()      { return capReachedMessage; }

    public Collection<CropType> getCropTypes() { return Collections.unmodifiableCollection(cropTypes.values()); }
    public CropType getCropType(String id)     { return cropTypes.get(id); }
    public boolean hasCropType(String id)      { return cropTypes.containsKey(id); }
    public Set<String> getCropTypeIds()        { return Collections.unmodifiableSet(cropTypes.keySet()); }

    public Collection<Tier> getTiers()         { return Collections.unmodifiableCollection(tiers.values()); }
    public Tier getTier(String id)             { return id == null ? null : tiers.get(id.toLowerCase()); }
}
