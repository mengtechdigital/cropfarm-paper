package com.cropfarm;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads tier definitions and crop definitions from config.yml plus any
 * crops/*.yml files in the plugin data folder, builds seed items + recipes,
 * and exposes global settings. Crop instance tracking lives in TrackedCrops.
 */
public class CropManager {

    /**
     * Categories (= crop YAML filenames, minus extension) that ship
     * disabled by default. Server admins can override per-file or
     * per-crop with `enabled: true` in their YAML. Living in code
     * (not just the JAR YAMLs) so existing servers — whose on-disk
     * YAMLs predate the `enabled` flag — also inherit the default.
     */
    private static final java.util.Set<String> DEFAULT_DISABLED_CATEGORIES = java.util.Set.of(
            "foods", "raw-ores", "endgame", "breeze-trial",
            "pottery", "sniffer", "froglights", "heads"
    );

    /**
     * Specific crop ids that are disabled by default regardless of
     * which YAML they live in. Used to disable rare mining crops
     * (diamond / emerald / netherite-scrap / etc.) that share
     * config.yml with mob-drop crops we want to keep enabled.
     */
    private static final java.util.Set<String> DEFAULT_DISABLED_CROP_IDS = java.util.Set.of(
            "diamond", "emerald", "gold", "iron", "coal",
            "redstone", "lapis_lazuli", "nether_quartz",
            "amethyst_shard", "copper", "echo_shard", "netherite_scrap"
    );

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
    private boolean protectFromAutomation;
    private boolean requireHoeToHarvest;
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
    private String hoeRequiredMessage;
    /** Default until reload() reads config. Guards against any future async reload path. */
    private FeedbackMode feedbackMode = FeedbackMode.ACTIONBAR;

    /** Where transient action feedback (plant/harvest/cap/etc.) is delivered. */
    public enum FeedbackMode { ACTIONBAR, CHAT, BOTH, OFF }

    public CropManager(CropFarm plugin) {
        this.plugin = plugin;
        this.CROP_TYPE_KEY = new NamespacedKey(plugin, "crop_type");
        reload();
    }

    public NamespacedKey cropTypeKey() { return CROP_TYPE_KEY; }

    // ---------------------------------------------------------------
    // Config loading / reload
    // ---------------------------------------------------------------

    /**
     * Strip every recipe this plugin instance has registered. Called from
     * {@link #reload()} (so reloading the YAML doesn't leave duplicates) and
     * from {@link CropFarm#onDisable()} (so the next plugin instance — after
     * a /plugman reload or /reload — doesn't trip over recipes left behind by
     * its predecessor; a fresh instance has an empty cropTypes map and would
     * otherwise be unable to clean up).
     */
    public void unregisterRecipes() {
        // Strip recipes for currently-loaded crops.
        for (String id : cropTypes.keySet()) {
            plugin.getServer().removeRecipe(new NamespacedKey(plugin, "cropfarm_" + id));
        }
        // Defensive: also strip recipes for every bundled JAR crop id, so
        // onDisable on a freshly-loaded instance (cropTypes empty) still
        // clears the keys that the previous instance registered.
        for (String resourcePath : CropFarm.defaultCropFiles()) {
            removeRecipesFromJarYaml(resourcePath);
        }
        removeRecipesFromJarYaml("config.yml");
        // Static seed-bag recipe.
        plugin.getServer().removeRecipe(new NamespacedKey(plugin, "cropfarm_seed_bag"));
    }

    /** Read crop ids from a bundled YAML resource and remove their recipes. */
    private void removeRecipesFromJarYaml(String path) {
        try (InputStream is = plugin.getResource(path)) {
            if (is == null) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            ConfigurationSection crops = y.getConfigurationSection("crops");
            if (crops == null) return;
            for (String id : crops.getKeys(false)) {
                plugin.getServer().removeRecipe(
                        new NamespacedKey(plugin, "cropfarm_" + id.toLowerCase()));
            }
        } catch (IOException ignored) {
            // Best-effort cleanup — never block disable on this.
        }
    }

    public void reload() {
        unregisterRecipes();
        cropTypes.clear();
        tiers.clear();

        plugin.reloadConfig();
        var cfg = plugin.getConfig();

        particles              = cfg.getBoolean("settings.particles", true);
        sounds                 = cfg.getBoolean("settings.sounds", true);
        returnSeedOnEarlyBreak = cfg.getBoolean("settings.return-seed-on-early-break", true);
        allowBonemeal          = cfg.getBoolean("settings.allow-bonemeal", false);
        directToInventory      = cfg.getBoolean("settings.direct-to-inventory", true);
        protectFromAutomation  = cfg.getBoolean("settings.protect-from-automation", true);
        requireHoeToHarvest    = cfg.getBoolean("settings.require-hoe-to-harvest", true);

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
        hoeRequiredMessage     = cfg.getString("settings.hoe-required-message",
                "§c⚠ Use a §6hoe§c to harvest crops!");

        // Where transient feedback (plant/harvest/cap/etc.) is delivered.
        // actionbar = single-line above the hotbar (no chat spam, default),
        // chat = traditional chat line, both = both surfaces, off = silent.
        String fmRaw = cfg.getString("settings.feedback-mode", "actionbar");
        feedbackMode = parseFeedbackMode(fmRaw);

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
            for (String t : new String[]{"common", "uncommon", "rare", "epic", "legendary", "mythic"}) {
                tiers.put(t, Tier.defaultFor(t));
            }
        }

        // ---- Phase 1: bundled defaults from JAR ----
        // This guarantees every bundled crop is loaded even when an existing
        // user file (preserved from a previous install) is missing newer
        // entries. Phase 2 (disk) then overrides any that the user has
        // edited in their plugins/CropFarm/ directory.
        loadJarResource("config.yml");
        for (String f : CropFarm.defaultCropFiles()) {
            loadJarResource(f);
        }

        // ---- Phase 2: user disk files override the JAR baseline ----
        loadCropsFromSection(cfg.getConfigurationSection("crops"), "config.yml",
                cfg.getBoolean("enabled", defaultEnabledForCategory(categoryFromSource("config.yml"))));
        File cropsDir = new File(plugin.getDataFolder(), "crops");
        if (cropsDir.exists() && cropsDir.isDirectory()) {
            File[] files = cropsDir.listFiles((d, n) -> n.toLowerCase().endsWith(".yml"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                    String src = "crops/" + f.getName();
                    loadCropsFromSection(y.getConfigurationSection("crops"), src,
                            y.getBoolean("enabled", defaultEnabledForCategory(categoryFromSource(src))));
                }
            }
        }

        registerSeedBagRecipe();

        plugin.getLogger().info("Loaded " + cropTypes.size() + " crop type(s) across "
                + tiers.size() + " tier(s).");
    }

    /**
     * Register the lone craftable bag recipe — a Tier 1 Burlap Pouch.
     * Higher tiers are not crafted; they're upgraded inside the bag GUI.
     * Recipe shape (3x3):
     *   S L S
     *   W B W
     *   S L S
     * S = string, L = leather, W = wheat seed (vanilla), B = bundle.
     * Cheap entry-tier — meant to be reachable in the first hour of play.
     */
    private void registerSeedBagRecipe() {
        if (plugin.getSeedBag() == null) return; // not yet wired (first reload during onEnable)
        NamespacedKey key = new NamespacedKey(plugin, "cropfarm_seed_bag");
        ItemStack result = plugin.getSeedBag().createStarter();
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape("SLS", "WBW", "SLS");
        recipe.setIngredient('S', Material.STRING);
        recipe.setIngredient('L', Material.LEATHER);
        // MaterialChoice (not ExactChoice) so that items stamped with extra
        // data components by other plugins still match. Recursive crafting
        // (using a cropfarm seed in the W slot) is blocked by SeedRecipeGuard.
        recipe.setIngredient('W', new RecipeChoice.MaterialChoice(Material.WHEAT_SEEDS));
        recipe.setIngredient('B', Material.BUNDLE);
        plugin.getServer().addRecipe(recipe);
    }

    private void loadJarResource(String path) {
        try (InputStream is = plugin.getResource(path)) {
            if (is == null) return;
            YamlConfiguration y = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            // File-level `enabled: false` ships a category off-by-default
            // (rare drops, archaeology, boss loot). Crops still load so they
            // appear in the /cropfarm menu, but recipes aren't registered and
            // planting is blocked.
            String src = "jar:" + path;
            boolean baseline = defaultEnabledForCategory(categoryFromSource(src));
            boolean fileEnabled = y.getBoolean("enabled", baseline);
            loadCropsFromSection(y.getConfigurationSection("crops"), src, fileEnabled);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "Cannot read bundled JAR resource " + path, e);
        }
    }

    private void loadCropsFromSection(ConfigurationSection crops, String sourceName,
                                      boolean fileEnabled) {
        if (crops == null) return;
        String category = categoryFromSource(sourceName);
        for (String id : crops.getKeys(false)) {
            ConfigurationSection s = crops.getConfigurationSection(id);
            if (s == null) continue;
            loadOneCrop(id.toLowerCase(), s, sourceName, category, fileEnabled);
        }
    }

    private static boolean defaultEnabledForCategory(String category) {
        return !DEFAULT_DISABLED_CATEGORIES.contains(category);
    }

    /** Source "jar:crops/blocks.yml" or "crops/blocks.yml" → category "blocks". */
    private static String categoryFromSource(String source) {
        if (source == null) return "ores";
        String name = source;
        if (name.startsWith("jar:")) name = name.substring(4);
        if (name.startsWith("crops/")) name = name.substring(6);
        if (name.endsWith(".yml")) name = name.substring(0, name.length() - 4);
        // The OG crops live in config.yml's `crops:` section — call them "ores"
        // since that's what they thematically are.
        if (name.equals("config")) return "ores";
        return name;
    }

    private void loadOneCrop(String id, ConfigurationSection s, String sourceName, String category,
                              boolean fileEnabled) {
        if (cropTypes.containsKey(id)) {
            // Duplicate is expected: user disk file overriding a JAR default,
            // or a later disk file overriding an earlier one. Silent override —
            // warning was too noisy now that JAR-defaults-first loading runs.
            plugin.getServer().removeRecipe(new NamespacedKey(plugin, "cropfarm_" + id));
        }

        // Normalise pre-1.6.0 tier names so existing configs keep working.
        String rawTier = s.getString("tier", "common").toLowerCase();
        String tierId = (rawTier.equals("mid") || rawTier.equals("medium")) ? "uncommon" : rawTier;
        Tier tier = tiers.getOrDefault(tierId, Tier.defaultFor(tierId));

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
        // Resolution order for `enabled`:
        //   1. Per-crop YAML key (most specific)
        //   2. File-level YAML key (passed in via fileEnabled)
        //   3. Hardcoded crop-id deny-list (rare config.yml ores)
        //   4. Hardcoded category deny-list (already baked into fileEnabled)
        // Existing servers without YAML flags inherit the deny-lists, which
        // is why the disabled-by-default policy actually takes effect on
        // upgrade (just adding a YAML flag to the JAR wouldn't have, since
        // disk YAMLs override JAR YAMLs).
        boolean cropIdDefault = !DEFAULT_DISABLED_CROP_IDS.contains(id) && fileEnabled;
        boolean enabled       = s.getBoolean("enabled", cropIdDefault);

        CropType cropType = new CropType(id, displayName, lore,
                recipeInput, recipeYield, outputs,
                customModelData, growTime, maxPerPlayer, xpMin, xpMax,
                tier.id(), category, enabled);
        cropTypes.put(id, cropType);

        if (!enabled) {
            // Skip recipe registration so disabled crops cannot be crafted
            // into seeds. Existing seeds still appear in inventories but the
            // plant listener will refuse them. The crop stays loaded so the
            // /cropfarm menu can still show it (greyed out) for discovery.
            return;
        }

        NamespacedKey recipeKey = new NamespacedKey(plugin, "cropfarm_" + id);
        // Shaped 1-row recipe: [input][wheat_seed]. The vanilla wheat seed
        // disambiguates from vanilla single-input recipes (e.g. 1 GOLD_INGOT
        // → 9 GOLD_NUGGET, 1 BONE → 3 BONE_MEAL) which our old shapeless
        // recipe was overriding. MaterialChoice on the seed slot accepts
        // any wheat-seed item (even ones stamped with extra data components
        // by plugins like StackPlus); recursive seed-from-seed crafting is
        // blocked by SeedRecipeGuard, which checks the PDC tag instead.
        ShapedRecipe recipe = new ShapedRecipe(recipeKey, createSeed(cropType, recipeYield));
        recipe.shape("IS");
        recipe.setIngredient('I', recipeInput);
        recipe.setIngredient('S', new RecipeChoice.MaterialChoice(Material.WHEAT_SEEDS));
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
            // Disabled crops still produce admin-given seeds (via /cropfarmgive)
            // but they're visibly marked so players see why nothing happens
            // when they try to plant.
            String name = type.isEnabled()
                    ? type.getDisplayName()
                    : "§8§m" + stripColor(type.getDisplayName()) + "§r §c[DISABLED]";
            meta.setDisplayName(name);

            List<String> lore = new ArrayList<>(type.getLore());
            if (!type.isEnabled()) {
                lore.add("");
                lore.add("§c⚠ Disabled by server admin.");
                lore.add("§7Cannot be planted.");
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            meta.getPersistentDataContainer().set(CROP_TYPE_KEY, PersistentDataType.STRING, type.getId());

            // 1.21.4+ resource pack hook: write the crop id into the
            // custom_model_data string list. The pack's items/wheat_seeds.json
            // selector reads strings[0] and matches against `when` cases to
            // pick the per-crop texture/model.
            org.bukkit.inventory.meta.components.CustomModelDataComponent cmd
                    = meta.getCustomModelDataComponent();
            cmd.setStrings(java.util.List.of(type.getId()));
            meta.setCustomModelDataComponent(cmd);

            seed.setItemMeta(meta);
        }
        return seed;
    }

    private static String stripColor(String s) {
        if (s == null) return "";
        return s.replaceAll("(?i)§[0-9A-FK-OR]", "");
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
    public boolean isProtectFromAutomation()  { return protectFromAutomation; }
    public boolean isRequireHoeToHarvest()    { return requireHoeToHarvest; }

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
    public String getHoeRequiredMessage()     { return hoeRequiredMessage; }
    public FeedbackMode getFeedbackMode()     { return feedbackMode; }

    /**
     * Deliver a transient feedback line (plant, harvest, cap-reached, etc.)
     * to the player using the configured feedback mode. Legacy section codes
     * (§a/§l/...) in the message are converted to Adventure components for
     * the action-bar path so colors render identically to chat.
     */
    public void sendFeedback(Player player, String message) {
        if (player == null || message == null || message.isEmpty()) return;
        if (feedbackMode == FeedbackMode.OFF) return;
        if (feedbackMode == FeedbackMode.CHAT || feedbackMode == FeedbackMode.BOTH) {
            player.sendMessage(message);
        }
        if (feedbackMode == FeedbackMode.ACTIONBAR || feedbackMode == FeedbackMode.BOTH) {
            Component component = LegacyComponentSerializer.legacySection().deserialize(message);
            player.sendActionBar(component);
        }
    }

    private FeedbackMode parseFeedbackMode(String raw) {
        if (raw == null) return FeedbackMode.ACTIONBAR;
        switch (raw.trim().toLowerCase()) {
            case "chat":     return FeedbackMode.CHAT;
            case "both":     return FeedbackMode.BOTH;
            case "off":
            case "none":
            case "silent":   return FeedbackMode.OFF;
            case "actionbar":
            case "action-bar":
            case "action_bar":
                             return FeedbackMode.ACTIONBAR;
            default:
                plugin.getLogger().warning("Unknown settings.feedback-mode \"" + raw
                        + "\" — falling back to actionbar.");
                return FeedbackMode.ACTIONBAR;
        }
    }

    public Collection<CropType> getCropTypes() { return Collections.unmodifiableCollection(cropTypes.values()); }
    public CropType getCropType(String id)     { return cropTypes.get(id); }
    public boolean hasCropType(String id)      { return cropTypes.containsKey(id); }
    public Set<String> getCropTypeIds()        { return Collections.unmodifiableSet(cropTypes.keySet()); }

    public Collection<Tier> getTiers()         { return Collections.unmodifiableCollection(tiers.values()); }
    public Tier getTier(String id)             { return id == null ? null : tiers.get(id.toLowerCase()); }
}
