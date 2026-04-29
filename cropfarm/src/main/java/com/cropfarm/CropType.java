package com.cropfarm;

import org.bukkit.Material;

import java.util.List;

/**
 * Immutable data class representing one crop type loaded from config.yml.
 */
public class CropType {

    private final String id;
    private final String displayName;
    private final List<String> lore;
    private final Material recipeInput;
    private final int recipeYield;
    private final Material output;
    private final int minDrops;
    private final int maxDrops;
    /** 0 means no custom model data (uses vanilla wheat seed texture). */
    private final int customModelData;
    /** Total seconds to go from age 0 → fully grown (age 7). */
    private final int growTimeSeconds;

    public CropType(String id, String displayName, List<String> lore,
                    Material recipeInput, int recipeYield,
                    Material output, int minDrops, int maxDrops,
                    int customModelData, int growTimeSeconds) {
        this.id = id;
        this.displayName = displayName;
        this.lore = lore;
        this.recipeInput = recipeInput;
        this.recipeYield = recipeYield;
        this.output = output;
        this.minDrops = minDrops;
        this.maxDrops = maxDrops;
        this.customModelData = customModelData;
        this.growTimeSeconds = growTimeSeconds;
    }

    public String getId()             { return id; }
    public String getDisplayName()    { return displayName; }
    public List<String> getLore()     { return lore; }
    public Material getRecipeInput()  { return recipeInput; }
    public int getRecipeYield()       { return recipeYield; }
    public Material getOutput()       { return output; }
    public int getMinDrops()          { return minDrops; }
    public int getMaxDrops()          { return maxDrops; }
    public int getCustomModelData()   { return customModelData; }
    public int getGrowTimeSeconds()   { return growTimeSeconds; }
}
