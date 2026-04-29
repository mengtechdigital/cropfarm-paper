import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates the entire CropFarm resource pack from scratch for Minecraft
 * 1.21.4+ (item model definitions format).
 *
 *   - Downloads the vanilla wheat_seeds.png on first run (cached in build/)
 *   - Tints it once per crop into build/cropfarm-resourcepack/.../{id}_seed.png
 *   - Writes a model JSON for every crop
 *   - Writes the NEW assets/minecraft/items/wheat_seeds.json that selects
 *     a model based on the custom_model_data.strings[0] value.
 *   - Writes pack.mcmeta + pack.png
 *
 * Run:    java tools/GenerateTextures.java
 * Then:   java tools/ZipResourcepack.java
 *
 * Format note (this is what changed from the old pack):
 *   Mojang killed the `overrides` system in models/item/wheat_seeds.json in
 *   Minecraft 1.21.5. The new system uses items/wheat_seeds.json with a
 *   `minecraft:select` model that reads the custom_model_data component's
 *   strings list. The plugin sets strings=[cropId] on each seed item; this
 *   pack's `cases` match against those crop ids.
 *
 * Tint algorithm: per pixel, output = tintColor * (0.45 + 0.55 * luminance).
 * Preserves the seed's shape and shading while remapping its tan colors to
 * the target hue.
 */
public class GenerateTextures {

    static final String VANILLA_SEED_URL =
            "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/" +
            "1.21.1/assets/minecraft/textures/item/wheat_seeds.png";
    static final Path BUILD_DIR = Path.of("build");
    static final Path SRC_PNG   = BUILD_DIR.resolve("wheat_seeds_vanilla.png");
    static final Path RP_DIR    = BUILD_DIR.resolve("cropfarm-resourcepack");

    /**
     * Pack format for Minecraft 1.21.5+. The new item-model-definitions
     * system bumped pack format aggressively; use a wide supported_formats
     * range so the pack still works as the user upgrades.
     *
     *   Min in supported_formats covers 1.21.5 (where the new format became
     *   mandatory). Max=999 is "future-proof up to whatever comes next".
     */
    static final int PACK_FORMAT_MIN = 55;   // Minecraft 1.21.5
    static final int PACK_FORMAT_MAX = 999;  // future-proof

    /** Crop id (without "_seed" suffix) → tint hex. */
    static final Map<String, String> CROPS = new LinkedHashMap<>();

    static {
        // ---- OG ores + mob essences (config.yml) ----
        addCrop("diamond",         "5DECF5");
        addCrop("emerald",         "4DAA56");
        addCrop("gold",            "F8DA13");
        addCrop("iron",            "D8D8D8");
        addCrop("coal",            "555555");
        addCrop("redstone",        "D32F2F");
        addCrop("lapis_lazuli",    "3F51B5");
        addCrop("nether_quartz",   "ECECEC");
        addCrop("amethyst_shard",  "9C27B0");
        addCrop("copper",          "C77B45");
        addCrop("gunpowder",       "8E8E8E");
        addCrop("blaze_rod",       "FFA000");
        addCrop("ender_pearl",     "00BFA5");
        addCrop("slime_ball",      "8BC34A");
        addCrop("string",          "F5F5DC");
        addCrop("bone",            "FAFAFA");
        addCrop("glowstone_dust",  "FDD835");
        addCrop("honeycomb",       "FBC02D");
        addCrop("ghast_tear",      "E1F5FE");
        addCrop("magma_cream",     "FF5722");
        addCrop("prismarine_shard","4DD0E1");
        addCrop("nautilus_shell",  "ECEFF1");
        addCrop("echo_shard",      "00BCD4");
        addCrop("netherite_scrap", "5D4037");

        // ---- crops/mob-drops.yml ----
        addCrop("feather",         "FFFFFF");
        addCrop("leather",         "8B5A2B");
        addCrop("sugar",           "F5F5F5");
        addCrop("ink_sac",         "1C1C1C");
        addCrop("glow_ink_sac",    "5BD4D9");
        addCrop("rotten_flesh",    "6E4A2E");
        addCrop("spider_eye",      "881F1F");
        addCrop("rabbit_hide",     "C0A080");
        addCrop("rabbit_foot",     "F0E0C0");
        addCrop("turtle_scute",    "8FCB6F");
        addCrop("phantom_membrane","6E5C8C");
        addCrop("dragon_breath",   "A45BC4");

        // ---- crops/wool.yml ----
        addCrop("wool",            "F0F0F0");

        // ---- crops/vanilla.yml (foraging) ----
        addCrop("apple",           "DC2A2A");
        addCrop("sweet_berries",   "C81818");
        addCrop("glow_berries",    "F2A93B");
        addCrop("chorus_fruit",    "8B5599");
        addCrop("cocoa_beans",     "7C4A1B");
        addCrop("sea_pickle",      "5C7B1E");
        addCrop("lily_pad",        "4E8C3F");

        // ---- crops/saplings.yml ----
        addCrop("oak_sapling",          "5C8E2F");
        addCrop("spruce_sapling",       "335A24");
        addCrop("birch_sapling",        "C7DB85");
        addCrop("jungle_sapling",       "6BAC2E");
        addCrop("acacia_sapling",       "B0762E");
        addCrop("dark_oak_sapling",     "3A4419");
        addCrop("mangrove_propagule",   "8B3534");
        addCrop("cherry_sapling",       "F2B0C8");
        addCrop("azalea",               "5B903E");
        addCrop("flowering_azalea",     "E78AB8");

        // ---- crops/blocks.yml ----
        addCrop("sand",                 "DCD089");
        addCrop("gravel",               "8C8378");
        addCrop("clay",                 "9DA5B0");
        addCrop("cobblestone",          "7B7B7B");
        addCrop("stone",                "8E8E8E");
        addCrop("deepslate",            "4F4F55");
        addCrop("dirt",                 "8B5A2B");
        addCrop("mud",                  "454143");
        addCrop("granite",              "9D6555");
        addCrop("diorite",              "DEDED5");
        addCrop("andesite",             "8E8E8E");
        addCrop("tuff",                 "6E6F66");
        addCrop("calcite",              "E1E0DA");
        addCrop("netherrack",           "6F2A2A");
        addCrop("soul_sand",            "5A453A");
        addCrop("soul_soil",            "4D3A2F");
        addCrop("basalt",               "4A4548");
        addCrop("blackstone",           "2A252E");
        addCrop("magma_block",          "B8401A");
        addCrop("nether_wart",          "8B1414");
        addCrop("end_stone",            "DCD89A");
        addCrop("purpur_block",         "A968A0");
        addCrop("ice",                  "9CC9F2");
        addCrop("packed_ice",           "82B8E6");
        addCrop("blue_ice",             "73AEE6");
        addCrop("snow_block",           "F4FBFC");
        addCrop("pointed_dripstone",    "85715C");
        addCrop("moss_block",           "5B7A38");
        addCrop("glow_lichen",          "8DA67E");
        addCrop("obsidian",             "1E1224");
        addCrop("crying_obsidian",      "451A56");

        // ---- crops/endgame.yml ----
        addCrop("wither_skeleton_skull","2E2E2E");
        addCrop("totem_of_undying",     "F8C66B");
        addCrop("heart_of_the_sea",     "8DC4D2");
        addCrop("shulker_shell",        "9971A6");
        addCrop("saddle",               "7E411F");
        addCrop("name_tag",             "F2EBD0");
        addCrop("trident",              "5D8B92");
        addCrop("enchanted_golden_apple","FFD86E");
        addCrop("nether_star",          "F4F8F2");
        addCrop("prismarine_crystals",  "BEEEDC");
        addCrop("honey_bottle",         "F4A91A");

        // ---- crops/farm-crops.yml ----
        addCrop("wheat",                "DEB544");
        addCrop("carrot",               "F47B26");
        addCrop("potato",               "C99C58");
        addCrop("beetroot",             "8E2D2A");
        addCrop("pumpkin",              "C57315");
        addCrop("melon",                "61A234");
        addCrop("sugar_cane",           "8FCB6F");
        addCrop("cactus",               "486D26");
        addCrop("bamboo",               "B2C26B");
        addCrop("kelp",                 "365E1F");

        // ---- crops/foods.yml ----
        addCrop("chicken",              "F4C39B");
        addCrop("beef",                 "B14338");
        addCrop("porkchop",             "F1A4A0");
        addCrop("mutton",               "B85447");
        addCrop("rabbit",               "C9847C");
        addCrop("cod",                  "C2A87F");
        addCrop("salmon",               "DD6E54");
        addCrop("tropical_fish",        "E89B45");
        addCrop("pufferfish",           "EFB935");
        addCrop("egg",                  "F4E2C5");

        // ---- crops/mushrooms.yml ----
        addCrop("red_mushroom",         "C72020");
        addCrop("brown_mushroom",       "9D7148");
        addCrop("crimson_fungus",       "B12727");
        addCrop("warped_fungus",        "168880");

        // ---- crops/heads.yml ----
        addCrop("skeleton_skull",       "B7B7B7");
        addCrop("zombie_head",          "5E8030");
        addCrop("creeper_head",         "6CC547");
        addCrop("piglin_head",          "F1A4A0");

        // ---- crops/decoratives.yml ----
        addCrop("sponge",               "E2D659");
        addCrop("cobweb",               "ECECEC");
        addCrop("vine",                 "3F6E1F");
        addCrop("weeping_vines",        "8C1818");
        addCrop("twisting_vines",       "13A39B");
        addCrop("big_dripleaf",         "5C8C30");
        addCrop("small_dripleaf",       "76A33A");
        addCrop("spore_blossom",        "EE6CB6");

        // ---- crops/breeze-trial.yml (1.21) ----
        addCrop("armadillo_scute",      "C58E5A");
        addCrop("breeze_rod",           "8FE0E5");
        addCrop("wind_charge",          "C9E2E0");
        addCrop("trial_key",            "BFD5BC");
        addCrop("ominous_trial_key",    "9F77BD");
        addCrop("ominous_bottle",       "5C2D75");
        addCrop("heavy_core",           "AAB0B8");
        addCrop("mace",                 "8A6B3F");

        // ---- crops/sniffer.yml ----
        addCrop("sniffer_egg",          "85A4A1");
        addCrop("torchflower_seeds",    "F0A03A");
        addCrop("pitcher_pod",          "8E5BB8");

        // ---- crops/froglights.yml ----
        addCrop("ochre_froglight",      "E8C16A");
        addCrop("verdant_froglight",    "97D08D");
        addCrop("pearlescent_froglight","F2B5DA");

        // ---- crops/crafting-essentials.yml ----
        addCrop("stick",                "9C7B49");
        addCrop("paper",                "F2EAD0");
        addCrop("brick",                "9C5642");
        addCrop("nether_brick",         "3A1A1F");
        addCrop("glass",                "DAEAF6");
        addCrop("glass_pane",           "C2DEF0");
        addCrop("charcoal",             "3B3B3B");
        addCrop("snowball",             "FAFCFF");

        // ---- crops/raw-ores.yml ----
        addCrop("raw_iron",             "C8A48B");
        addCrop("raw_gold",             "F0BD3B");
        addCrop("raw_copper",           "B6643E");

        // ---- crops/flowers.yml ----
        addCrop("dandelion",            "F2D24E");
        addCrop("poppy",                "DC2A2A");
        addCrop("oxeye_daisy",          "F0F0E0");
        addCrop("cornflower",           "5876C9");
        addCrop("allium",               "A66BC9");
        addCrop("blue_orchid",          "39B0E5");
        addCrop("azure_bluet",          "DCE0E5");
        addCrop("lily_of_the_valley",   "F8FBF0");
        addCrop("wither_rose",          "1F1F1F");
        addCrop("torchflower",          "F08029");
        addCrop("pitcher_plant",        "8E5BB8");

        // ---- crops/dyes.yml + pottery.yml (weighted; one icon each) ----
        addCrop("dye",                  "C45BC8"); // generic mixed
        addCrop("pottery_sherd",        "9C5642"); // brick-y
    }

    static void addCrop(String id, String hex) {
        CROPS.put(id, hex);
    }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(BUILD_DIR);

        // 1. Ensure vanilla source exists (download once, cached).
        if (!Files.exists(SRC_PNG)) {
            System.out.println("Fetching vanilla wheat_seeds.png …");
            try (var in = URI.create(VANILLA_SEED_URL).toURL().openStream()) {
                Files.copy(in, SRC_PNG);
            }
        }

        // 2. Reset the working pack directory.
        if (Files.exists(RP_DIR)) deleteRecursive(RP_DIR);
        Path texDir   = RP_DIR.resolve("assets/cropfarm/textures/item");
        Path modelDir = RP_DIR.resolve("assets/cropfarm/models/item");
        Path itemsDir = RP_DIR.resolve("assets/minecraft/items");
        Files.createDirectories(texDir);
        Files.createDirectories(modelDir);
        Files.createDirectories(itemsDir);

        // 3. Load source.
        BufferedImage src = ImageIO.read(SRC_PNG.toFile());
        if (src == null) throw new IOException("Cannot read " + SRC_PNG);
        System.out.println("Source: " + src.getWidth() + "x" + src.getHeight());

        // 4. Tint each crop, write PNG + per-crop model JSON.
        for (var e : CROPS.entrySet()) {
            String id = e.getKey();
            String hex = e.getValue();
            ImageIO.write(tint(src, hex), "PNG",
                    texDir.resolve(id + "_seed.png").toFile());
            Files.writeString(modelDir.resolve(id + "_seed.json"),
                    "{\n" +
                    "  \"parent\": \"minecraft:item/generated\",\n" +
                    "  \"textures\": {\n" +
                    "    \"layer0\": \"cropfarm:item/" + id + "_seed\"\n" +
                    "  }\n" +
                    "}\n",
                    StandardCharsets.UTF_8);
        }
        System.out.println("Wrote " + CROPS.size() + " textures + per-crop models.");

        // 5. items/wheat_seeds.json — the new dispatcher.
        //    type=select, property=custom_model_data, reads strings[0],
        //    matches `when` cases. Falls back to vanilla wheat seeds.
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
          .append("  \"model\": {\n")
          .append("    \"type\": \"minecraft:select\",\n")
          .append("    \"property\": \"minecraft:custom_model_data\",\n")
          .append("    \"fallback\": {\n")
          .append("      \"type\": \"minecraft:model\",\n")
          .append("      \"model\": \"minecraft:item/wheat_seeds\"\n")
          .append("    },\n")
          .append("    \"cases\": [\n");
        List<String> rows = new ArrayList<>();
        for (var e : CROPS.entrySet()) {
            String id = e.getKey();
            rows.add("      {\n" +
                    "        \"when\": \"" + id + "\",\n" +
                    "        \"model\": {\n" +
                    "          \"type\": \"minecraft:model\",\n" +
                    "          \"model\": \"cropfarm:item/" + id + "_seed\"\n" +
                    "        }\n" +
                    "      }");
        }
        sb.append(String.join(",\n", rows)).append("\n")
          .append("    ]\n")
          .append("  }\n")
          .append("}\n");
        Files.writeString(itemsDir.resolve("wheat_seeds.json"),
                sb.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote items/wheat_seeds.json with " + CROPS.size() + " cases.");

        // 6. pack.mcmeta + pack.png (64x64 nearest-neighbor upscale of vanilla seed).
        //    supported_formats range covers 1.21.5+ through future minor bumps.
        Files.writeString(RP_DIR.resolve("pack.mcmeta"),
                "{\n" +
                "  \"pack\": {\n" +
                "    \"pack_format\": " + PACK_FORMAT_MIN + ",\n" +
                "    \"supported_formats\": { \"min_inclusive\": " + PACK_FORMAT_MIN
                        + ", \"max_inclusive\": " + PACK_FORMAT_MAX + " },\n" +
                "    \"description\": \"CropFarm — custom seed textures for "
                        + CROPS.size() + " crops.\"\n" +
                "  }\n" +
                "}\n",
                StandardCharsets.UTF_8);
        ImageIO.write(upscale(src, 4), "PNG", RP_DIR.resolve("pack.png").toFile());

        System.out.println();
        System.out.println("Generated " + CROPS.size() + " crops in " + RP_DIR);
        System.out.println("Next: java tools/ZipResourcepack.java");
    }

    /** Multiply-style tint preserving alpha; transparent pixels stay transparent. */
    static BufferedImage tint(BufferedImage src, String hex) {
        int color = Integer.parseInt(hex, 16);
        int tR = (color >> 16) & 0xFF;
        int tG = (color >> 8)  & 0xFF;
        int tB = color & 0xFF;
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = src.getRGB(x, y);
                int a = (rgba >>> 24) & 0xFF;
                if (a == 0) { out.setRGB(x, y, 0); continue; }
                int r = (rgba >> 16) & 0xFF;
                int g = (rgba >> 8)  & 0xFF;
                int b = rgba & 0xFF;
                float lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f;
                float gain = 0.45f + 0.55f * lum;
                int nR = Math.min(255, Math.round(tR * gain));
                int nG = Math.min(255, Math.round(tG * gain));
                int nB = Math.min(255, Math.round(tB * gain));
                out.setRGB(x, y, (a << 24) | (nR << 16) | (nG << 8) | nB);
            }
        }
        return out;
    }

    /** Nearest-neighbor pixel-art upscale (e.g. 16→64 with factor 4). */
    static BufferedImage upscale(BufferedImage src, int factor) {
        int sw = src.getWidth(), sh = src.getHeight();
        BufferedImage out = new BufferedImage(sw * factor, sh * factor, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int rgba = src.getRGB(x, y);
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        out.setRGB(x * factor + dx, y * factor + dy, rgba);
                    }
                }
            }
        }
        return out;
    }

    static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(File::delete);
        }
    }
}
