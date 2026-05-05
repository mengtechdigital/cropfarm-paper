package com.cropfarm;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Static catalog of Seed Bag tiers — capacity, XP gate to upgrade, and the
 * inventory ingredients required to unlock the next tier.
 *
 * Six tiers, each strictly more capacity than the last:
 *   T1 Burlap Pouch     —   9 slots,  1 page  — entry tier (no XP gate)
 *   T2 Linen Satchel    —  27 slots,  1 page  —    50 XP + uncommon-seed gate
 *   T3 Hempen Sack      —  45 slots,  1 page  —   200 XP + rare-seed gate
 *   T4 Reinforced Bag   —  90 slots,  2 pages —   800 XP + collection-gate
 *   T5 Enchanted Bag    — 135 slots,  3 pages — 2,500 XP + collection-gate
 *   T6 Worldspun Bag    — 180 slots,  4 pages — 7,500 XP + every-seed gate
 *
 * The collection-gate tiers (T4+) require *exemplars*: at least one of every
 * CropFarm seed in the relevant crop-tier band. These are evaluated against
 * the live registry at click-time, so adding new crops automatically extends
 * the requirement (intentional — endgame is a moving target tied to content).
 */
public final class SeedBagTier {

    /** How many slots a single chest-GUI page exposes for storage. */
    public static final int PAGE_SLOTS = 45;

    /** Total chest-GUI inventory size — 5 rows of storage + 1 row of nav. */
    public static final int GUI_SIZE = 54;

    /** Where the nav row starts (last 9 slots). */
    public static final int NAV_OFFSET = 45;

    /** Number of distinct tiers (1..MAX_TIER inclusive). */
    public static final int MAX_TIER = 6;

    /**
     * Crop-tier id buckets used by the upgrade ingredient logic. Names match
     * Tier ids in tiers section of config.yml.
     */
    public enum CropTierBand {
        COMMON, UNCOMMON, RARE, EPIC, LEGENDARY, MYTHIC;
        public String configId() { return name().toLowerCase(); }
    }

    /**
     * One required ingredient for an upgrade. Either a vanilla material with
     * a fixed amount, OR a CropFarm seed quota: "at least N seeds of this
     * crop tier band" (countCropTier non-null).
     */
    public record Ingredient(
            Material vanilla,            // null if this is a seed-tier requirement
            int amount,
            CropTierBand countCropTier,  // null if vanilla
            boolean exemplar             // true: need 1 of EVERY crop in countCropTier
    ) {
        public static Ingredient vanilla(Material m, int amount) {
            return new Ingredient(m, amount, null, false);
        }
        public static Ingredient anyOfTier(CropTierBand band, int amount) {
            return new Ingredient(null, amount, band, false);
        }
        public static Ingredient oneOfEvery(CropTierBand band) {
            return new Ingredient(null, 1, band, true);
        }
    }

    /** Tier definition record. */
    public record Definition(
            int tier,
            String displayName,
            String colorCode,
            int totalSlots,
            int pages,
            int xpToUpgrade,
            List<Ingredient> upgradeIngredients
    ) {
        public boolean isTerminal() { return tier >= MAX_TIER; }

        /** Slots actually addressable on a given 0-indexed page. */
        public int slotsOnPage(int pageIndex) {
            int remaining = totalSlots - (pageIndex * PAGE_SLOTS);
            return Math.max(0, Math.min(PAGE_SLOTS, remaining));
        }
    }

    private static final Map<Integer, Definition> TIERS = buildCatalog();

    private static Map<Integer, Definition> buildCatalog() {
        Map<Integer, Definition> m = new java.util.LinkedHashMap<>();

        m.put(1, new Definition(1, "Burlap Pouch", "§6",
                9, 1, 50,
                List.of(
                        Ingredient.vanilla(Material.STRING, 16),
                        Ingredient.vanilla(Material.LEATHER, 4),
                        Ingredient.anyOfTier(CropTierBand.COMMON, 8)
                )));

        m.put(2, new Definition(2, "Linen Satchel", "§e",
                27, 1, 200,
                List.of(
                        Ingredient.vanilla(Material.IRON_INGOT, 8),
                        Ingredient.vanilla(Material.LEATHER, 8),
                        Ingredient.anyOfTier(CropTierBand.UNCOMMON, 16)
                )));

        m.put(3, new Definition(3, "Hempen Sack", "§a",
                45, 1, 800,
                List.of(
                        Ingredient.vanilla(Material.DIAMOND, 4),
                        Ingredient.vanilla(Material.GOLD_INGOT, 8),
                        Ingredient.anyOfTier(CropTierBand.RARE, 24)
                )));

        m.put(4, new Definition(4, "Reinforced Bag", "§b",
                90, 2, 2500,
                List.of(
                        Ingredient.vanilla(Material.NETHERITE_INGOT, 1),
                        Ingredient.oneOfEvery(CropTierBand.COMMON),
                        Ingredient.oneOfEvery(CropTierBand.UNCOMMON),
                        Ingredient.anyOfTier(CropTierBand.EPIC, 32)
                )));

        m.put(5, new Definition(5, "Enchanted Bag", "§d",
                135, 3, 7500,
                List.of(
                        Ingredient.vanilla(Material.NETHER_STAR, 1),
                        Ingredient.oneOfEvery(CropTierBand.RARE),
                        Ingredient.oneOfEvery(CropTierBand.EPIC),
                        Ingredient.anyOfTier(CropTierBand.LEGENDARY, 16)
                )));

        m.put(6, new Definition(6, "Worldspun Bag", "§5",
                180, 4, -1, // terminal — no further upgrade
                List.of()));

        return java.util.Collections.unmodifiableMap(m);
    }

    /** Get the definition for a tier (1..MAX_TIER). Returns null if out of range. */
    public static Definition get(int tier) {
        return TIERS.get(tier);
    }

    /** Definition for the tier above {@code tier}, or null at terminal. */
    public static Definition next(int tier) {
        return TIERS.get(tier + 1);
    }

    /** XP awarded per harvest based on the crop's tier id. */
    public static int xpForCropTier(String cropTierId) {
        if (cropTierId == null) return 1;
        return switch (cropTierId.toLowerCase()) {
            case "common"    -> 1;
            case "uncommon"  -> 1;
            case "rare"      -> 2;
            case "epic"      -> 4;
            case "legendary" -> 8;
            case "mythic"    -> 16;
            default          -> 1;
        };
    }

    /** All tiers in order (T1..T6) — used by the help guide. */
    public static List<Definition> all() {
        return new ArrayList<>(TIERS.values());
    }

    private SeedBagTier() { /* static catalog only */ }
}
