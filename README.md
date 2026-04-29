# cropfarm-paper

A PaperMC plugin that turns ores, mob drops, and rare nether/ocean resources into **plantable crops**. Designed to replace giant XP grinders, creeper farms, and AFK mob farms with peaceful, low-lag farming.

Comes with a matching **resource pack** that gives every seed its own colored texture.

> Built for **Paper 1.21.x**, requires **Java 21+**.

---

## Highlights

- **25 craftable seeds** — diamond, emerald, gold, iron, coal, redstone, lapis, quartz, amethyst, copper, XP bottle, gunpowder, blaze rod, ender pearl, slime ball, string, bone, glowstone, honeycomb, ghast tear, magma cream, prismarine, nautilus shell, echo shard, netherite scrap.
- **Per-crop timed growth** — light level, water and weather no longer matter. Each crop has a configurable `grow-time-seconds` (2 minutes for coal, 30 minutes for netherite).
- **Bone meal blocked by default** — magic seeds resist bone meal, so wait times are real. Toggle via config.
- **Floating nametag above each plant** — `§b✦ Diamond Seed §7§o(3/7)` while growing, `§a§l✓ Ready!` when done. Uses Paper-native `TextDisplay` entities — no resource pack required for the nametag itself.
- **Returns one seed on harvest** — the cycle continues automatically.
- **Returns the seed on early break** — never lose a seed by accident (configurable).
- **Optional resource pack** — vanilla wheat-seed shape, recolored per crop. Players see distinct icons for every seed in inventory and on the ground.

---

## Install

### Server side

1. Grab `CropFarm.jar` from the [latest release](https://github.com/mengtechdigital/cropfarm-paper/releases/latest), drop it into your server's `plugins/` folder.
2. Restart the server. It generates `plugins/CropFarm/config.yml` and `plugins/CropFarm/crops.yml`.
3. Edit `config.yml` to taste, then run `/cropfarmreload`.

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

> Without the pack, all seeds look like vanilla wheat seeds. Everything still works — they're just visually identical.

---

## Commands & permissions

| Command | Permission | Description |
|---|---|---|
| `/cropfarmreload` | `cropfarm.reload` (op) | Reload `config.yml` without restart |
| `/cropfarmgive <crop> [amount] [player]` | `cropfarm.give` (op) | Give seeds to yourself or another player |

Tab-completion is supported for crop ids and player names.

---

## Crop list

Tier defines the rough grow time. All values configurable in [`cropfarm/src/main/resources/config.yml`](cropfarm/src/main/resources/config.yml).

| Tier | Grow time | Crops |
|---|---|---|
| **Common** | 2–3 min | coal, copper, string, bone |
| **Mid** | 4–8 min | iron, gold, redstone, lapis, quartz, amethyst, gunpowder, glowstone |
| **Rare** | 10–15 min | diamond, emerald, blaze rod, ender pearl, slime, honeycomb, magma cream, prismarine, xp bottle |
| **Epic** | 20–30 min | ghast tear, nautilus shell, echo shard, netherite scrap |

To add or change a crop, edit the `crops:` section of `config.yml` and run `/cropfarmreload`. Five additional templates (sugar, feather, leather, ink sac, glow ink sac) are included commented-out.

---

## How it works

1. Craft seeds with the recipe defined in `config.yml` (default: **1 raw item → 4 seeds**).
2. Right-click farmland to plant. The wheat block appears at age 0.
3. A scheduled task advances the crop's growth stage on a per-crop timer. **Bone meal does nothing** (unless re-enabled in config).
4. When fully grown (stage 7), break the crop to receive the harvested item plus **one returned seed** so the cycle continues.
5. Break early? You get the seed back, no loss.

Each planted crop has a floating `TextDisplay` nametag billboarded toward the player, showing the seed's name and current stage. Configurable view-range (default 20 blocks), text templates, and an enabled toggle.

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

The 25 seed textures are generated procedurally from the vanilla `wheat_seeds.png` by a small Java tool (the source PNG is fetched on demand from the [InventivetalentDev mirror](https://github.com/InventivetalentDev/minecraft-assets)).

```bash
# from repo root — outputs go to build/
java tools/GenerateTextures.java   # writes PNGs, model JSONs, pack.mcmeta, pack.png
java tools/ZipResourcepack.java    # packs build/cropfarm-resourcepack/ into build/cropfarm-resourcepack.zip
```

To change a crop's color, edit the `addCrop(...)` call in [`tools/GenerateTextures.java`](tools/GenerateTextures.java) and re-run. To add a brand-new crop, also add it to `config.yml` with a matching `custom-model-data` value.

---

## Repo layout

```
cropfarm/                 # plugin source (Maven project)
  src/main/java/...       # Java sources
  src/main/resources/     # plugin.yml + default config.yml
  pom.xml                 # PaperMC 1.21.1 + compile target Java 21
  mvnw, mvnw.cmd          # Maven wrapper

tools/
  GenerateTextures.java   # generates the resource pack from vanilla wheat_seeds.png
  ZipResourcepack.java    # zips build/cropfarm-resourcepack/ into the final .zip

build/                    # gitignored — all generated artifacts live here
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
