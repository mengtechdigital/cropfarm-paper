package com.cropfarm;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Core seed-bag item factory and on-item state accessors.
 *
 * Each bag is a {@link Material#BUNDLE} stack tagged with our PDC keys:
 *   bag_id    — UUID identifying THIS specific bag (per-instance)
 *   bag_tier  — 1..MAX_TIER
 *   bag_xp    — accumulated cultivation XP
 *   bag_page_<n> — base64-encoded BukkitObjectOutputStream of ItemStack[]
 *
 * State lives entirely on the ItemStack — no external storage. That makes
 * bags transferable (drop, gift, /clear) without orphaning data.
 *
 * Concurrency: meta operations follow Bukkit's "get → mutate → set" pattern.
 * `applyMutation` centralises that so callers can't forget to call setItemMeta.
 */
public class SeedBag {

    private final CropFarm plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey tierKey;
    private final NamespacedKey xpKey;
    /** Page keys are constructed lazily — `bag_page_0`, `bag_page_1`, etc. */
    private final String pageKeyBase = "bag_page_";

    public SeedBag(CropFarm plugin) {
        this.plugin = plugin;
        this.idKey   = new NamespacedKey(plugin, "bag_id");
        this.tierKey = new NamespacedKey(plugin, "bag_tier");
        this.xpKey   = new NamespacedKey(plugin, "bag_xp");
    }

    public NamespacedKey idKey()   { return idKey; }
    public NamespacedKey tierKey() { return tierKey; }
    public NamespacedKey xpKey()   { return xpKey; }

    private NamespacedKey pageKey(int pageIndex) {
        return new NamespacedKey(plugin, pageKeyBase + pageIndex);
    }

    // ---------------------------------------------------------------
    // Identification
    // ---------------------------------------------------------------

    /** True if the stack is a CropFarm seed bag (bundle with our tier tag). */
    public boolean isSeedBag(ItemStack item) {
        if (item == null || item.getType() != Material.BUNDLE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(tierKey, PersistentDataType.INTEGER);
    }

    public int getTier(ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return 1;
        Integer t = meta.getPersistentDataContainer().get(tierKey, PersistentDataType.INTEGER);
        if (t == null) return 1;
        return Math.max(1, Math.min(SeedBagTier.MAX_TIER, t));
    }

    public int getXp(ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return 0;
        Integer xp = meta.getPersistentDataContainer().get(xpKey, PersistentDataType.INTEGER);
        return xp == null ? 0 : Math.max(0, xp);
    }

    public UUID getInstanceId(ItemStack bag) {
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return null;
        String s = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (s == null) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    // ---------------------------------------------------------------
    // Page contents (read / write)
    // ---------------------------------------------------------------

    /**
     * Return the page contents as a fixed-size array sized to the tier's
     * page-slots count (so caller can place directly into a 45-slot grid).
     * Returns an array of nulls if the page hasn't been written yet.
     */
    public ItemStack[] readPage(ItemStack bag, int pageIndex) {
        SeedBagTier.Definition def = SeedBagTier.get(getTier(bag));
        int size = def == null ? SeedBagTier.PAGE_SLOTS : def.slotsOnPage(pageIndex);

        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return new ItemStack[size];
        String encoded = meta.getPersistentDataContainer()
                .get(pageKey(pageIndex), PersistentDataType.STRING);
        if (encoded == null || encoded.isEmpty()) return new ItemStack[size];
        return decodePage(encoded, size);
    }

    /** Persist the given contents into the bag's PDC for the given page. */
    public void writePage(ItemStack bag, int pageIndex, ItemStack[] contents) {
        applyMutation(bag, meta ->
                meta.getPersistentDataContainer().set(
                        pageKey(pageIndex), PersistentDataType.STRING, encodePage(contents)));
    }

    /**
     * Sum item counts across every page. Used by the display name.
     * Skips empties so the count is "stored seeds" not "stored slot count".
     */
    public int totalStoredCount(ItemStack bag) {
        SeedBagTier.Definition def = SeedBagTier.get(getTier(bag));
        if (def == null) return 0;
        int total = 0;
        for (int p = 0; p < def.pages(); p++) {
            ItemStack[] page = readPage(bag, p);
            for (ItemStack s : page) {
                if (s != null && !s.getType().isAir()) total += s.getAmount();
            }
        }
        return total;
    }

    /**
     * All non-null seed stacks across every page. Used by the upgrade
     * ingredient check and the help-card "exemplar" reporter.
     */
    public List<ItemStack> allStoredSeeds(ItemStack bag) {
        SeedBagTier.Definition def = SeedBagTier.get(getTier(bag));
        if (def == null) return List.of();
        List<ItemStack> out = new ArrayList<>();
        for (int p = 0; p < def.pages(); p++) {
            for (ItemStack s : readPage(bag, p)) {
                if (s != null && !s.getType().isAir()) out.add(s);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------
    // XP / tier mutators
    // ---------------------------------------------------------------

    public void addXp(ItemStack bag, int amount) {
        if (amount <= 0) return;
        SeedBagTier.Definition def = SeedBagTier.get(getTier(bag));
        if (def == null || def.isTerminal()) return;
        int cap = def.xpToUpgrade();
        int current = getXp(bag);
        if (current >= cap) return; // already maxed; no spillover
        int newXp = Math.min(cap, current + amount);
        applyMutation(bag, meta -> meta.getPersistentDataContainer()
                .set(xpKey, PersistentDataType.INTEGER, newXp));
        refreshDisplay(bag);
    }

    /**
     * Set the bag's tier. Caller is responsible for the upgrade gating
     * (XP, ingredients) — this just mutates the PDC. Resets XP to 0 since
     * the new tier has its own XP track.
     */
    public void setTier(ItemStack bag, int newTier) {
        applyMutation(bag, meta -> {
            meta.getPersistentDataContainer().set(
                    tierKey, PersistentDataType.INTEGER,
                    Math.max(1, Math.min(SeedBagTier.MAX_TIER, newTier)));
            meta.getPersistentDataContainer().set(xpKey, PersistentDataType.INTEGER, 0);
        });
        refreshDisplay(bag);
    }

    // ---------------------------------------------------------------
    // Construction / display
    // ---------------------------------------------------------------

    /** Create a brand-new tier-1 bag (the only craftable / starter form). */
    public ItemStack createStarter() {
        return create(1);
    }

    /** Create a bag at the given tier (admin / debug use). */
    public ItemStack create(int tier) {
        ItemStack bag = new ItemStack(Material.BUNDLE);
        applyMutation(bag, meta -> {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(idKey,   PersistentDataType.STRING,  UUID.randomUUID().toString());
            pdc.set(tierKey, PersistentDataType.INTEGER,
                    Math.max(1, Math.min(SeedBagTier.MAX_TIER, tier)));
            pdc.set(xpKey,   PersistentDataType.INTEGER, 0);
        });
        refreshDisplay(bag);
        return bag;
    }

    /**
     * Re-render the display name + lore from current PDC state. Called after
     * any mutation that changes what the player sees on the held item:
     * tier change, xp gain, after closing the GUI (count refresh).
     */
    public void refreshDisplay(ItemStack bag) {
        SeedBagTier.Definition def = SeedBagTier.get(getTier(bag));
        if (def == null) return;
        int xp = getXp(bag);
        int stored = totalStoredCount(bag);

        String name = def.colorCode() + "✦ " + def.displayName()
                + " §7§o(" + stored + "§7§o/§f§o" + def.totalSlots() + "§7§o)";

        List<String> lore = new ArrayList<>();
        lore.add("§8Tier §f" + def.tier() + "§8/§f" + SeedBagTier.MAX_TIER);
        if (def.isTerminal()) {
            lore.add("§5§lTerminal tier §7— max storage reached.");
        } else {
            int cap = def.xpToUpgrade();
            lore.add("§8XP §f" + xp + "§7/§f" + cap
                    + " §8(§7" + percent(xp, cap) + "§8)");
        }
        lore.add("");
        lore.add("§7Right-click in air to open.");
        lore.add("§7Stores §fCropFarm seeds only§7.");

        applyMutation(bag, meta -> {
            meta.setDisplayName(name);
            meta.setLore(lore);
        });
    }

    // ---------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------

    private interface MetaMutator { void mutate(ItemMeta meta); }

    private void applyMutation(ItemStack bag, MetaMutator m) {
        ItemMeta meta = bag.getItemMeta();
        if (meta == null) return;
        m.mutate(meta);
        bag.setItemMeta(meta);
    }

    private String encodePage(ItemStack[] contents) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos)) {
            out.writeInt(contents.length);
            for (ItemStack s : contents) {
                out.writeObject(s == null ? null : s);
            }
            out.flush();
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            // PDC writes are infallible in normal use; if this fails, the bag
            // contents would be silently lost, so log loudly and rethrow so
            // the caller surfaces the failure rather than swallowing it.
            plugin.getLogger().log(Level.SEVERE, "Failed to serialise bag page", e);
            throw new IllegalStateException("Bag serialise failed", e);
        }
    }

    private ItemStack[] decodePage(String encoded, int expectedSize) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(Base64.getDecoder().decode(encoded));
             BukkitObjectInputStream in = new BukkitObjectInputStream(bais)) {
            int len = in.readInt();
            ItemStack[] out = new ItemStack[expectedSize];
            for (int i = 0; i < len; i++) {
                Object obj = in.readObject();
                if (i < expectedSize && obj instanceof ItemStack s) out[i] = s;
            }
            return out;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException e) {
            // Corrupt page: log + return empty so the player can still use the
            // bag instead of getting hard-stuck on a broken bag (bad enough
            // to lose contents, worse to brick the bag entirely).
            plugin.getLogger().warning("Corrupt seed-bag page — resetting to empty: "
                    + e.getMessage());
            return new ItemStack[expectedSize];
        }
    }

    private static String percent(int num, int den) {
        if (den <= 0) return "100%";
        int pct = (int) Math.floor((num * 100.0) / den);
        return Math.max(0, Math.min(100, pct)) + "%";
    }
}
