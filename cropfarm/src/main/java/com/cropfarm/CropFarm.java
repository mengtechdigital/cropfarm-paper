package com.cropfarm;

import org.bukkit.plugin.java.JavaPlugin;

public class CropFarm extends JavaPlugin {

    private CropManager cropManager;
    private TrackedCrops trackedCrops;
    private NametagService nametagService;
    private CropGrowthTask growthTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.cropManager    = new CropManager(this);
        this.trackedCrops   = new TrackedCrops(this);
        this.nametagService = new NametagService(this);

        getServer().getPluginManager().registerEvents(new CropListener(this), this);

        // Sweep all currently-loaded chunks: drop orphan nametags + spawn missing ones.
        nametagService.purgeOrphansInLoadedChunks(trackedCrops, cropManager);

        this.growthTask = new CropGrowthTask(this);
        growthTask.start();

        CropFarmCommand cmdHandler = new CropFarmCommand(this);
        if (getCommand("cropfarmreload") != null) {
            getCommand("cropfarmreload").setExecutor(cmdHandler);
        }
        if (getCommand("cropfarmgive") != null) {
            getCommand("cropfarmgive").setExecutor(cmdHandler);
            getCommand("cropfarmgive").setTabCompleter(cmdHandler);
        }

        getLogger().info("CropFarm enabled.");
    }

    @Override
    public void onDisable() {
        if (growthTask != null) {
            try { growthTask.cancel(); } catch (IllegalStateException ignored) { }
        }
        if (nametagService != null) {
            nametagService.removeAll();
        }
        if (trackedCrops != null) {
            trackedCrops.save();
        }
        getLogger().info("CropFarm disabled.");
    }

    public CropManager getCropManager()       { return cropManager; }
    public TrackedCrops getTrackedCrops()     { return trackedCrops; }
    public NametagService getNametagService() { return nametagService; }
}
