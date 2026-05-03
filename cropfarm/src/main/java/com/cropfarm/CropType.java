package com.cropfarm;

import org.bukkit.Material;

import java.util.List;
import java.util.Random;

/**
 * Immutable data class representing one crop type loaded from config.
 * A crop has one recipe input and a weighted list of possible outputs;
 * single-output crops are stored as a one-entry list.
 */
public class CropType {

    /**
     * A single weighted drop entry. weight controls relative chance, minAmount
     * and maxAmount the rolled stack size when this entry is picked.
     */
    public record DropEntry(Material item, int weight, int minAmount, int maxAmount) { }

    private final String id;
    private final String displayName;
    private final List<String> lore;
    private final Material recipeInput;
    private final int recipeYield;
    private final List<DropEntry> outputs;
    /** 0 means no custom model data (uses vanilla wheat seed texture). */
    private final int customModelData;
    /** Total seconds to go from age 0 → fully grown (age 7). */
    private final int growTimeSeconds;
    /** 0 means unlimited. */
    private final int maxPerPlayer;
    private final int xpMin;
    private final int xpMax;
    private final String tier;
    /** Category id derived from source file (e.g. "blocks", "endgame", "ores"). */
    private final String category;
    /**
     * When false: the seed-craft recipe is not registered, planting is
     * blocked, and the seed item visibly carries a disabled marker. Used
     * to ship rare / non-renewable categories off-by-default while still
     * surfacing them in the /cropfarm menu so server admins can see what's
     * available to enable.
     */
    private final boolean enabled;

    public CropType(String id, String displayName, List<String> lore,
                    Material recipeInput, int recipeYield,
                    List<DropEntry> outputs,
                    int customModelData, int growTimeSeconds,
                    int maxPerPlayer, int xpMin, int xpMax,
                    String tier, String category, boolean enabled) {
        this.id = id;
        this.displayName = displayName;
        this.lore = lore;
        this.recipeInput = recipeInput;
        this.recipeYield = recipeYield;
        this.outputs = List.copyOf(outputs);
        this.customModelData = customModelData;
        this.growTimeSeconds = growTimeSeconds;
        this.maxPerPlayer = maxPerPlayer;
        this.xpMin = xpMin;
        this.xpMax = xpMax;
        this.tier = tier;
        this.category = category;
        this.enabled = enabled;
    }

    public String getId()             { return id; }
    public String getDisplayName()    { return displayName; }
    public List<String> getLore()     { return lore; }
    public Material getRecipeInput()  { return recipeInput; }
    public int getRecipeYield()       { return recipeYield; }
    public List<DropEntry> getOutputs() { return outputs; }
    public int getCustomModelData()   { return customModelData; }
    public int getGrowTimeSeconds()   { return growTimeSeconds; }
    public int getMaxPerPlayer()      { return maxPerPlayer; }
    public int getXpMin()             { return xpMin; }
    public int getXpMax()             { return xpMax; }
    public String getTier()           { return tier; }
    public String getCategory()       { return category; }
    public boolean isEnabled()        { return enabled; }

    /** Material used as the GUI / status icon (first declared output, falls back to wheat seeds). */
    public Material getPrimaryOutput() {
        return outputs.isEmpty() ? Material.WHEAT_SEEDS : outputs.get(0).item();
    }

    /** Pick one weighted drop entry. Throws if outputs is empty (loader prevents this). */
    public DropEntry pickOutput(Random r) {
        if (outputs.isEmpty()) {
            throw new IllegalStateException("CropType '" + id + "' has no outputs");
        }
        if (outputs.size() == 1) return outputs.get(0);
        int total = 0;
        for (DropEntry e : outputs) total += Math.max(0, e.weight());
        if (total <= 0) return outputs.get(0);
        int roll = r.nextInt(total);
        int acc = 0;
        for (DropEntry e : outputs) {
            acc += Math.max(0, e.weight());
            if (roll < acc) return e;
        }
        return outputs.get(outputs.size() - 1);
    }
}
