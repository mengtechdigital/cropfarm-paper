package com.cropfarm;

/**
 * Rarity tier definition. Crops without explicit overrides inherit
 * grow time, per-player cap and XP range from their tier.
 */
public record Tier(
        String id,
        int growTimeSeconds,
        int maxPerPlayer,
        int xpMin,
        int xpMax,
        String color
) {

    /**
     * Reasonable fallbacks used when the config omits a tier definition.
     * Aliases the pre-1.6.0 names ("mid"/"medium") to "uncommon" so existing
     * user configs continue loading.
     */
    public static Tier defaultFor(String id) {
        String key = id == null ? "common" : id.toLowerCase();
        return switch (key) {
            case "mythic"          -> new Tier("mythic",     86400, 2,    80, 150, "§d");
            case "legendary"       -> new Tier("legendary",  21600, 8,    25,  50, "§6");
            case "epic"            -> new Tier("epic",        2700, 32,   10,  18, "§5");
            case "rare"            -> new Tier("rare",         900, 64,    4,   7, "§b");
            case "uncommon",
                 "mid", "medium"   -> new Tier("uncommon",     360, 256,   1,   3, "§a");
            default                -> new Tier(key,            180, 1024,  0,   1, "§7");
        };
    }
}
