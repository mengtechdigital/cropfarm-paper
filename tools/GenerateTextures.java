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
 * Generates the entire CropFarm resource pack from scratch:
 *   - Downloads the vanilla wheat_seeds.png on first run (cached in build/)
 *   - Tints it once per crop into build/cropfarm-resourcepack/.../{id}_seed.png
 *   - Writes a model JSON for every crop
 *   - Writes the wheat_seeds.json overrides binding custom_model_data → model
 *   - Writes pack.mcmeta + pack.png
 *
 * Run:    java tools/GenerateTextures.java
 * Then:   java tools/ZipResourcepack.java
 *
 * Tint algorithm: per pixel, output = tintColor * (0.45 + 0.55 * luminance).
 * Preserves the seed's shape and shading while remapping its tan colors to the
 * target hue. Add or recolor crops by editing the addCrop(...) calls below.
 */
public class GenerateTextures {

    static final String VANILLA_SEED_URL =
            "https://raw.githubusercontent.com/InventivetalentDev/minecraft-assets/" +
            "1.21.1/assets/minecraft/textures/item/wheat_seeds.png";
    static final Path BUILD_DIR   = Path.of("build");
    static final Path SRC_PNG     = BUILD_DIR.resolve("wheat_seeds_vanilla.png");
    static final Path RP_DIR      = BUILD_DIR.resolve("cropfarm-resourcepack");

    /** Crop id (without "_seed" suffix) → tint hex. Order = override-list order. */
    static final Map<String, String> CROPS = new LinkedHashMap<>();
    /** Crop id → custom_model_data id (must match config.yml). */
    static final Map<String, Integer> CMD = new LinkedHashMap<>();

    static {
        addCrop("diamond",         1001, "5DECF5");
        addCrop("emerald",         1002, "4DAA56");
        addCrop("gold",            1003, "F8DA13");
        addCrop("iron",            1004, "D8D8D8");
        addCrop("coal",            1005, "555555");
        addCrop("redstone",        1006, "D32F2F");
        addCrop("lapis_lazuli",    1007, "3F51B5");
        addCrop("nether_quartz",   1008, "ECECEC");
        addCrop("amethyst_shard",  1009, "9C27B0");
        addCrop("copper",          1010, "C77B45");
        addCrop("xp_bottle",       1011, "76FF03");
        addCrop("gunpowder",       1012, "8E8E8E");
        addCrop("blaze_rod",       1013, "FFA000");
        addCrop("ender_pearl",     1014, "00BFA5");
        addCrop("slime_ball",      1015, "8BC34A");
        addCrop("string",          1016, "F5F5DC");
        addCrop("bone",            1017, "FAFAFA");
        addCrop("glowstone_dust",  1018, "FDD835");
        addCrop("honeycomb",       1019, "FBC02D");
        addCrop("ghast_tear",      1020, "E1F5FE");
        addCrop("magma_cream",     1021, "FF5722");
        addCrop("prismarine_shard",1022, "4DD0E1");
        addCrop("nautilus_shell",  1023, "ECEFF1");
        addCrop("echo_shard",      1024, "00BCD4");
        addCrop("netherite_scrap", 1025, "5D4037");
    }

    static void addCrop(String id, int cmd, String hex) {
        CROPS.put(id, hex);
        CMD.put(id, cmd);
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
        Path mcDir    = RP_DIR.resolve("assets/minecraft/models/item");
        Files.createDirectories(texDir);
        Files.createDirectories(modelDir);
        Files.createDirectories(mcDir);

        // 3. Load source.
        BufferedImage src = ImageIO.read(SRC_PNG.toFile());
        if (src == null) throw new IOException("Cannot read " + SRC_PNG);
        System.out.println("Source: " + src.getWidth() + "x" + src.getHeight());

        // 4. Tint each crop, write PNG + model JSON.
        for (var e : CROPS.entrySet()) {
            String id = e.getKey();
            String hex = e.getValue();
            ImageIO.write(tint(src, hex), "PNG", texDir.resolve(id + "_seed.png").toFile());
            Files.writeString(modelDir.resolve(id + "_seed.json"),
                    "{\n" +
                    "  \"parent\": \"minecraft:item/generated\",\n" +
                    "  \"textures\": {\n" +
                    "    \"layer0\": \"cropfarm:item/" + id + "_seed\"\n" +
                    "  }\n" +
                    "}\n",
                    StandardCharsets.UTF_8);
            System.out.println("  + " + id + "_seed  (#" + hex + ")");
        }

        // 5. wheat_seeds.json with all overrides.
        StringBuilder sb = new StringBuilder();
        sb.append("{\n")
          .append("  \"parent\": \"minecraft:item/generated\",\n")
          .append("  \"textures\": { \"layer0\": \"minecraft:item/wheat_seeds\" },\n")
          .append("  \"overrides\": [\n");
        List<String> rows = new ArrayList<>();
        for (var e : CMD.entrySet()) {
            rows.add(String.format(
                    "    { \"predicate\": { \"custom_model_data\": %d }, \"model\": \"cropfarm:item/%s_seed\" }",
                    e.getValue(), e.getKey()));
        }
        sb.append(String.join(",\n", rows)).append("\n  ]\n}\n");
        Files.writeString(mcDir.resolve("wheat_seeds.json"), sb.toString(), StandardCharsets.UTF_8);

        // 6. pack.mcmeta + pack.png (64x64 nearest-neighbor upscale of vanilla seed).
        Files.writeString(RP_DIR.resolve("pack.mcmeta"),
                "{\n" +
                "  \"pack\": {\n" +
                "    \"pack_format\": 34,\n" +
                "    \"description\": \"CropFarm — custom seed textures for " + CMD.size() + " crops.\"\n" +
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
