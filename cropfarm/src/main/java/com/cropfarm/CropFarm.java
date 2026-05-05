package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;

public class CropFarm extends JavaPlugin {

    /** Bundled default crop files copied to plugins/CropFarm/crops/ on first start. */
    private static final String[] DEFAULT_CROP_FILES = {
            "crops/mob-drops.yml",
            "crops/wool.yml",
            "crops/vanilla.yml",
            "crops/saplings.yml",
            "crops/blocks.yml",
            "crops/endgame.yml",
            "crops/farm-crops.yml",
            "crops/foods.yml",
            "crops/mushrooms.yml",
            "crops/heads.yml",
            "crops/decoratives.yml",
            "crops/breeze-trial.yml",
            "crops/sniffer.yml",
            "crops/froglights.yml",
            "crops/crafting-essentials.yml",
            "crops/raw-ores.yml",
            "crops/flowers.yml",
            "crops/dyes.yml",
            "crops/pottery.yml",
            "crops/concrete.yml",
            "crops/terracotta.yml",
    };

    private CropManager cropManager;
    private TrackedCrops trackedCrops;
    private NametagService nametagService;
    private CropGrowthTask growthTask;
    private CropMenu cropMenu;
    private SqliteCropStore cropStore;
    private CoreProtectHook coreProtect;
    private SeedBag seedBag;
    private SeedBagInventory seedBagInventory;
    private SeedBagListener seedBagListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultCropFiles();

        // SeedBag must exist before CropManager — the bag recipe is registered
        // inside CropManager.reload() and reads plugin.getSeedBag().
        this.seedBag = new SeedBag(this);
        this.cropManager = new CropManager(this);

        // Open SQLite store. If this fails the plugin can't function safely
        // (we'd silently lose plant/break tracking) — disable rather than run
        // half-broken.
        File dbFile = new File(getDataFolder(), "crops.db");
        this.cropStore = new SqliteCropStore(this, dbFile);
        try {
            cropStore.open();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Cannot open crops.db — disabling plugin", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Prime in-memory cache. loadAll() throws on read error rather than
        // returning empty — refusing to start beats silently masking a corrupt
        // database.
        Map<String, TrackedCrop> initial;
        try {
            initial = cropStore.loadAll();
        } catch (CropStoreException e) {
            getLogger().log(Level.SEVERE,
                    "crops.db read failed — refusing to start to protect existing data. "
                            + "Inspect the file or restore a backup, then restart.", e);
            cropStore.close();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.trackedCrops   = new TrackedCrops(this, cropStore, initial);
        this.nametagService = new NametagService(this);
        this.cropMenu       = new CropMenu(this);
        this.coreProtect    = new CoreProtectHook(this);
        coreProtect.tryHook();

        this.seedBagInventory = new SeedBagInventory(this, seedBag);
        this.seedBagListener  = new SeedBagListener(seedBag, seedBagInventory);

        getServer().getPluginManager().registerEvents(new CropListener(this), this);
        getServer().getPluginManager().registerEvents(cropMenu, this);
        getServer().getPluginManager().registerEvents(seedBagListener, this);
        getServer().getPluginManager().registerEvents(new SeedRecipeGuard(this), this);

        // Sweep all currently-loaded chunks: drop orphan nametags + spawn missing ones.
        nametagService.purgeOrphansInLoadedChunks(trackedCrops, cropManager);

        this.growthTask = new CropGrowthTask(this);
        growthTask.start();

        CropFarmCommand cmdHandler = new CropFarmCommand(this);
        registerCommand("cropfarm",       cmdHandler, cmdHandler);
        registerCommand("cropfarmreload", cmdHandler, null);
        registerCommand("cropfarmgive",   cmdHandler, cmdHandler);

        getLogger().info("CropFarm enabled.");
    }

    @Override
    public void onDisable() {
        // Close any open CropMenu inventories so a /reload doesn't leave
        // stale holders that survive into the new classloader and bypass
        // our click-cancellation listener.
        for (Player p : Bukkit.getOnlinePlayers()) {
            var top = p.getOpenInventory().getTopInventory();
            if (top != null && (top.getHolder() instanceof CropMenuHolder
                    || top.getHolder() instanceof SeedBagHolder)) {
                p.closeInventory();
            }
        }
        if (growthTask != null) {
            try { growthTask.cancel(); } catch (IllegalStateException ignored) { }
        }
        if (nametagService != null) {
            nametagService.removeAll();
        }
        // Strip recipes so /plugman reload (or /reload) doesn't make the next
        // plugin instance trip over duplicates left by this one.
        if (cropManager != null) {
            cropManager.unregisterRecipes();
        }
        if (cropStore != null) {
            cropStore.close();
        }
        getLogger().info("CropFarm disabled.");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Crop YAML resources bundled in the JAR. Exposed for CropManager to load as a baseline. */
    public static String[] defaultCropFiles() { return DEFAULT_CROP_FILES.clone(); }

    private void saveDefaultCropFiles() {
        for (String f : DEFAULT_CROP_FILES) {
            File target = new File(getDataFolder(), f);
            if (target.exists()) continue;
            try {
                saveResource(f, false);
            } catch (IllegalArgumentException e) {
                getLogger().warning("Default crop file not bundled in jar: " + f);
            }
        }
    }

    private void registerCommand(String name, CropFarmCommand executor,
                                 org.bukkit.command.TabCompleter completer) {
        var cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("Command '" + name + "' missing from plugin.yml — skipping registration.");
            return;
        }
        cmd.setExecutor(executor);
        if (completer != null) cmd.setTabCompleter(completer);
    }

    public CropManager getCropManager()       { return cropManager; }
    public TrackedCrops getTrackedCrops()     { return trackedCrops; }
    public NametagService getNametagService() { return nametagService; }
    public CropMenu getCropMenu()             { return cropMenu; }
    public CoreProtectHook getCoreProtect()   { return coreProtect; }
    public SeedBag getSeedBag()               { return seedBag; }
    public SeedBagInventory getSeedBagInventory() { return seedBagInventory; }
    public SeedBagListener getSeedBagListener()   { return seedBagListener; }
}
