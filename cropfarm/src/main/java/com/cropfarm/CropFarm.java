package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CropFarm extends JavaPlugin {

    private CropManager cropManager;
    private TrackedCrops trackedCrops;
    private NametagService nametagService;
    private CropGrowthTask growthTask;
    private CropMenu cropMenu;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.cropManager    = new CropManager(this);
        this.trackedCrops   = new TrackedCrops(this);
        this.nametagService = new NametagService(this);
        this.cropMenu       = new CropMenu(this);

        getServer().getPluginManager().registerEvents(new CropListener(this), this);
        getServer().getPluginManager().registerEvents(cropMenu, this);

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
        // stale holders that would survive into the new classloader and
        // bypass our click-cancellation listener.
        for (Player p : Bukkit.getOnlinePlayers()) {
            var top = p.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof CropMenuHolder) {
                p.closeInventory();
            }
        }
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
}
