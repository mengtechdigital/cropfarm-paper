# cropfarm-paper

A PaperMC plugin that turns ores, mob drops, blocks, vanilla growables, and rare endgame items into **plantable crops**. Designed for casual players who want the items without the 120-hour redstone autofarm.

Comes with a matching **resource pack** that gives every original seed its own colored texture.

> Built for **Paper 1.21.x**, requires **Java 21+**.

---

## Highlights

- **93 craftable seeds out of the box** — every ore, every mob drop worth grinding, every block builders need in bulk, plus aspirational endgame items (wither skulls, totems, god apples, shulker shells, tridents).
- **Tier system** (common / mid / rare / epic) — supplies sane defaults for grow time, per-player planting cap, and XP per harvest. Each crop can override any field inline.
- **Per-player planting cap** — default 1000 / 256 / 64 / 32 crops by tier. Stops one player from carpeting a chunk in 10 000 diamond crops. Op perm `cropfarm.bypass-cap` ignores the limit.
- **Auto-XP on harvest** — every fully-grown crop drops XP orbs scaled to its tier (0–1 common, 1–3 mid, 3–7 rare, 7–15 epic).
- **Per-crop timed growth** — light, water, and weather no longer matter. Each crop has a configurable `grow-time-seconds`.
- **Weighted multi-output drops** — for the random-color `wool` crop and any future loot-table style crops you define.
- **Multi-file crop loader** — drop any `*.yml` into `plugins/CropFarm/crops/` to extend the catalog without touching `config.yml`.
- **Embedded SQLite storage** for runtime tracking — synchronous WAL-mode writes, crash-safe, no separate database process. Config files stay YAML for human editing.
- **Direct-to-inventory harvest** — crops, returned seed, and XP go straight into the player; no dropped-item entities or XP orbs spawned (massive entity-lag reduction on large farms). Toggleable.
- **`/cropfarm menu` GUI** — paginated chest browser showing every crop grouped by tier, with planted-vs-cap counter, recipe, drops, and XP per harvest. Left-click for chat details, shift-click to take a seed (op).
- **Floating nametag above each plant** — `§b✦ Diamond Seed §7§o(3/7)` while growing, `§a§l✓ Ready!` when done. Native `TextDisplay` entities — no resource pack required.
- **Returns one seed on harvest** so the cycle continues automatically.
- **Returns the seed on early break** — never lose a seed by accident (configurable).
- **Optional resource pack** — vanilla wheat-seed shape, recolored per crop. The 25 original seeds get distinct icons; new 1.4.0 crops use vanilla wheat-seed appearance until pack is regenerated.

---

## Install

### Server side

1. Grab `CropFarm.jar` from the [latest release](https://github.com/mengtechdigital/cropfarm-paper/releases/latest), drop it into your server's `plugins/` folder.
2. Restart the server. It generates:
   - `plugins/CropFarm/config.yml` (settings, tiers, original 24 ore + mob crops)
   - `plugins/CropFarm/crops/*.yml` (additional 68 crops across mob-drops, wool, vanilla, saplings, blocks, endgame)
   - `plugins/CropFarm/crops.db` (SQLite — auto-managed planted-crop tracking)
   - `plugins/CropFarm/.native/` (sqlite-jdbc unpacks its platform native lib here)
3. Tweak any of the config files to taste, then run `/cropfarm reload`.

> **Upgrading from 1.4.x:** the plugin auto-migrates your old `crops.yml` into `crops.db` on first start of 1.5.0, then renames the YAML to `crops.yml.<timestamp>.bak` for safe-keeping. No manual steps needed.

### Resource pack (optional, recommended)

You have two paths.

**A. Server-pushed pack** (everyone gets it on join):
1. Grab `cropfarm-resourcepack.zip` from the [latest release](https://github.com/mengtechdigital/cropfarm-paper/releases/latest).
2. Compute the SHA-1 of the zip — `sha1sum cropfarm-resourcepack.zip`.
3. Edit `server.properties`:
   ```properties
   resource-pack=https://github.com/mengtechdigital/cropfarm-paper/releases/download/<tag>/cropfarm-resourcepack.zip
   resource-pack-sha1=<the-sha1>
   resource-pack-prompt=§b✦ §fCropFarm pack adds custom seed textures.
   require-resource-pack=true
   ```
4. Restart the server. Joining players see a download prompt and the pack auto-applies.

**B. Per-player install:** drop the zip into `.minecraft/resourcepacks/` and enable it in Options → Resource Packs.

> Without the pack, all seeds look like vanilla wheat seeds. Everything still works — the GUI menu uses the *output material* as the icon so crops are always identifiable in `/cropfarm menu`.

---

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/cropfarm` (or `/cf`) | `cropfarm.menu` (true) | Open the menu |
| `/cropfarm menu [page]` | `cropfarm.menu` (true) | Open a specific page of the menu |
| `/cropfarm reload` | `cropfarm.reload` (op) | Reload `config.yml` + `crops/*.yml` |
| `/cropfarm give <crop> [n] [player]` | `cropfarm.give` (op) | Give seeds |
| `/cropfarmreload` | `cropfarm.reload` (op) | Legacy alias for `/cropfarm reload` |
| `/cropfarmgive <crop> [n] [player]` | `cropfarm.give` (op) | Legacy alias for `/cropfarm give` |
| (perm only) | `cropfarm.bypass-cap` (op) | Skip the per-player planting cap |

Tab-completion is supported for subcommands, crop ids, and player names.

---

## Tiers (default values, all overridable)

| Tier | Grow time | Cap per player | XP on harvest | Color |
|---|---|---|---|---|
| **common** | 3 min | 1000 | 0–1 | §7 gray |
| **mid** | 6 min | 256 | 1–3 | §e yellow |
| **rare** | 12 min | 64 | 3–7 | §b aqua |
| **epic** | 25 min | 32 | 7–15 | §5 purple |

Edit the `tiers:` section of `config.yml` to retune all of these globally, or override individual fields per crop.

---

## Crop catalog

| Group | File | Crops |
|---|---|---|
| Original ores + mob essences | `config.yml` | diamond, emerald, gold, iron, coal, redstone, lapis_lazuli, nether_quartz, amethyst_shard, copper, gunpowder, blaze_rod, ender_pearl, slime_ball, string, bone, glowstone_dust, honeycomb, ghast_tear, magma_cream, prismarine_shard, nautilus_shell, echo_shard, netherite_scrap |
| Mob drops | `crops/mob-drops.yml` | feather, leather, sugar, ink_sac, glow_ink_sac, rotten_flesh, spider_eye, rabbit_hide, rabbit_foot, turtle_scute, phantom_membrane, dragon_breath |
| Wool (random color) | `crops/wool.yml` | wool |
| Vanilla growables | `crops/vanilla.yml` | apple, sweet_berries, glow_berries, chorus_fruit, cocoa_beans, sea_pickle, lily_pad |
| Saplings (10 trees) | `crops/saplings.yml` | oak_sapling, spruce_sapling, birch_sapling, jungle_sapling, acacia_sapling, dark_oak_sapling, mangrove_propagule, cherry_sapling, azalea, flowering_azalea |
| Block generators | `crops/blocks.yml` | sand, gravel, clay (→ clay_balls), cobblestone, stone, deepslate, dirt, mud, granite, diorite, andesite, tuff, calcite, netherrack, soul_sand, soul_soil, basalt, blackstone, magma_block, nether_wart, end_stone, purpur_block, ice, packed_ice, blue_ice, snow_block, pointed_dripstone, moss_block, glow_lichen, obsidian, crying_obsidian |
| Endgame | `crops/endgame.yml` | wither_skeleton_skull, totem_of_undying, heart_of_the_sea, shulker_shell, trident, enchanted_golden_apple, prismarine_crystals, honey_bottle |

**To add or change a crop**: edit any of these files (or drop a new `crops/*.yml`), then run `/cropfarm reload`. Every field is documented inline.

---

## How it works

1. Craft seeds with the recipe defined in config (default: **1 raw item → 4 seeds**).
2. Right-click farmland to plant. The wheat block appears at age 0.
3. A scheduled task advances the crop's growth stage on a per-crop timer. **Bone meal does nothing** (unless re-enabled).
4. When fully grown (stage 7), break the crop to receive the harvested item plus **one returned seed** plus **XP based on the crop's tier**.
5. Break early? You get the seed back, no loss, no XP.
6. The per-player cap is enforced at plant time — when you hit it, you'll see a chat message and the seed stays in your hand.

Each planted crop has a floating `TextDisplay` nametag billboarded toward the player, showing the seed's name and current stage. Configurable view-range, text templates, and an enabled toggle.

---

## Build from source

You need **Java 21+**. Maven is bundled via the wrapper — no separate install needed.

### Plugin JAR

```bash
git clone https://github.com/mengtechdigital/cropfarm-paper.git
cd cropfarm-paper/cropfarm
./mvnw clean package
```

Output: `cropfarm/target/CropFarm.jar`.

### Resource pack

The 25 original seed textures are generated procedurally from the vanilla `wheat_seeds.png` by a small Java tool. New 1.4.0 crops are not yet in the pack; they fall back to the vanilla wheat-seed icon (the `/cropfarm menu` shows their output material so they're still identifiable).

```bash
# from repo root — outputs go to build/
java tools/GenerateTextures.java   # writes PNGs, model JSONs, pack.mcmeta, pack.png
java tools/ZipResourcepack.java    # packs build/cropfarm-resourcepack/ into build/cropfarm-resourcepack.zip
```

To change a crop's color, edit the `addCrop(...)` call in [`tools/GenerateTextures.java`](tools/GenerateTextures.java) and re-run. To add a brand-new crop, also add it to the relevant config file with a matching `custom-model-data` value.

---

## Repo layout

```
cropfarm/                       # plugin source (Maven project)
  src/main/java/com/cropfarm/   # 11 Java sources
  src/main/resources/
    config.yml                  # settings + tiers + 24 original crops
    plugin.yml
    crops/                      # bundled default crop catalogs
      mob-drops.yml
      wool.yml
      vanilla.yml
      saplings.yml
      blocks.yml
      endgame.yml
  pom.xml                       # PaperMC 1.21.1 + compile target Java 21
  mvnw, mvnw.cmd                # Maven wrapper

tools/
  GenerateTextures.java         # generates the resource pack from vanilla wheat_seeds.png
  ZipResourcepack.java          # zips build/cropfarm-resourcepack/ into the final .zip

build/                          # gitignored — all generated artifacts live here
```

---

## Releasing

1. Bump version in [`cropfarm/pom.xml`](cropfarm/pom.xml) and [`cropfarm/src/main/resources/plugin.yml`](cropfarm/src/main/resources/plugin.yml).
2. `cd cropfarm && ./mvnw clean package`
3. (If textures or color list changed) `java tools/GenerateTextures.java && java tools/ZipResourcepack.java`
4. Update [`CHANGELOG.md`](CHANGELOG.md).
5. Commit, tag, push:
   ```bash
   git commit -am "Release v1.x.x"
   git tag v1.x.x
   git push --follow-tags
   ```
6. On GitHub → Releases → Draft new release → pick the tag → upload both:
   - `cropfarm/target/CropFarm.jar`
   - `build/cropfarm-resourcepack.zip`
7. Compute new SHA-1 of the resource pack zip (`sha1sum build/cropfarm-resourcepack.zip`) and update your server's `server.properties` (`resource-pack-sha1=...`).

---

## License

MIT — see [LICENSE](LICENSE). The vanilla `wheat_seeds.png` source texture used to generate the seed icons is © Mojang AB; use of vanilla Minecraft assets is permitted for resource-pack purposes under Mojang's terms.
