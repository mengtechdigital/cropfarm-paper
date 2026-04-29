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

    /** Reasonable fallbacks used when the config omits a tier definition. */
    public static Tier defaultFor(String id) {
        String key = id == null ? "common" : id.toLowerCase();
        return switch (key) {
            case "epic"            -> new Tier("epic",   1500, 32,   7, 15, "§5");
            case "rare"            -> new Tier("rare",    720, 64,   3,  7, "§b");
            case "mid", "medium"   -> new Tier("mid",     360, 256,  1,  3, "§e");
            default                -> new Tier(key,       180, 1000, 0,  1, "§7");
        };
    }
}
