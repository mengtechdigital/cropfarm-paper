package com.cropfarm;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * Soft-dependency wrapper around the CoreProtect API. Detects whether
 * CoreProtect is installed at enable time; if so, exposes log* methods
 * that proxy to the API. Otherwise every call is a cheap no-op.
 *
 * The actual API types live in CoreProtect's jar (declared in pom.xml as
 * scope=provided), so this class compiles fine whether or not CoreProtect
 * is on the runtime classpath. We catch NoClassDefFoundError defensively
 * around the hook call, and the !available short-circuit makes the cost
 * trivial when CP isn't present.
 */
public class CoreProtectHook {

    private final CropFarm plugin;
    private net.coreprotect.CoreProtectAPI api;
    private boolean available;

    public CoreProtectHook(CropFarm plugin) {
        this.plugin = plugin;
    }

    /** Try to bind the API. Safe to call when CoreProtect isn't installed. */
    public void tryHook() {
        Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (cp == null) return;
        try {
            net.coreprotect.CoreProtect coreProtect = (net.coreprotect.CoreProtect) cp;
            net.coreprotect.CoreProtectAPI a = coreProtect.getAPI();
            if (a == null || !a.isEnabled()) return;
            int v = a.APIVersion();
            if (v < 9) {
                plugin.getLogger().info("CoreProtect API version " + v
                        + " is too old (need >= 9) — logging integration disabled.");
                return;
            }
            this.api = a;
            this.available = true;
            plugin.getLogger().info("Hooked into CoreProtect (API v" + v + ").");
        } catch (NoClassDefFoundError | ClassCastException e) {
            // CoreProtect class not on classpath at runtime, or a different fork.
        }
    }

    public boolean isAvailable() { return available; }

    public void logPlace(Player player, Location loc, Material material) {
        if (!available) return;
        try {
            api.logPlacement(player.getName(), loc, material, null);
        } catch (Throwable t) {
            // Don't let a CP API change crash our plant/break path.
            plugin.getLogger().log(Level.WARNING, "CoreProtect logPlacement failed", t);
        }
    }

    public void logBreak(Player player, Location loc, Material material) {
        if (!available) return;
        try {
            api.logRemoval(player.getName(), loc, material, null);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "CoreProtect logRemoval failed", t);
        }
    }
}
