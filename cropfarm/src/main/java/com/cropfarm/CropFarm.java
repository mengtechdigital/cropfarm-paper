package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
    };

    private CropManager cropManager;
    private TrackedCrops trackedCrops;
    private NametagService nametagService;
    private CropGrowthTask growthTask;
    private CropMenu cropMenu;
    private SqliteCropStore cropStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveDefaultCropFiles();

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

        // Prime in-memory cache. We MUST distinguish "store read succeeded
        // and is empty" from "store read failed" — otherwise a transient SQL
        // fault would silently re-migrate legacy YAML on top of partial DB
        // data and destroy the operator's backup. loadAll() throws on error.
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

        File legacyYaml = new File(getDataFolder(), "crops.yml");
        if (initial.isEmpty() && legacyYaml.isFile() && legacyYaml.length() > 0) {
            Map<String, TrackedCrop> legacy = readLegacyYamlCrops(legacyYaml);
            if (!legacy.isEmpty()) {
                getLogger().info("Migrating " + legacy.size()
                        + " crop(s) from crops.yml to SQLite...");
                try {
                    cropStore.putAll(legacy);
                } catch (CropStoreException e) {
                    getLogger().log(Level.SEVERE,
                            "Migration failed — original crops.yml left in place. "
                                    + "Plugin will start with no tracked crops; investigate before retrying.", e);
                    initial = new HashMap<>();
                }
                if (!legacy.isEmpty()) {
                    // Timestamped backup name so a future second migration can't
                    // overwrite this one. We never destroy a prior .bak.
                    File backup = new File(getDataFolder(),
                            "crops.yml." + System.currentTimeMillis() + ".bak");
                    if (legacyYaml.renameTo(backup)) {
                        getLogger().info("Migration complete. Old data backed up to "
                                + backup.getName());
                    } else {
                        getLogger().warning("Migration succeeded but could not rename crops.yml — "
                                + "delete or move it manually to avoid re-migration warnings.");
                    }
                    initial = legacy;
                }
            }
        }

        this.trackedCrops   = new TrackedCrops(this, cropStore, initial);
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
        // stale holders that survive into the new classloader and bypass
        // our click-cancellation listener.
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
        if (cropStore != null) {
            cropStore.close();
        }
        getLogger().info("CropFarm disabled.");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

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

    /** One-shot reader for the legacy YAML format. Tolerates 2/3/4-field rows. */
    private static Map<String, TrackedCrop> readLegacyYamlCrops(File file) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        Map<String, TrackedCrop> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String entry : cfg.getStringList("crops")) {
            String[] parts = entry.split("\\|");
            if (parts.length < 2) continue;
            long plantedAt = now;
            UUID owner = null;
            if (parts.length >= 3) {
                try { plantedAt = Long.parseLong(parts[2]); }
                catch (NumberFormatException ignored) { /* fall back to now */ }
            }
            if (parts.length >= 4 && !parts[3].isEmpty()) {
                try { owner = UUID.fromString(parts[3]); }
                catch (IllegalArgumentException ignored) { /* leave null */ }
            }
            out.put(parts[0], new TrackedCrop(parts[1], plantedAt, owner));
        }
        return out;
    }

    public CropManager getCropManager()       { return cropManager; }
    public TrackedCrops getTrackedCrops()     { return trackedCrops; }
    public NametagService getNametagService() { return nametagService; }
    public CropMenu getCropMenu()             { return cropMenu; }
}
