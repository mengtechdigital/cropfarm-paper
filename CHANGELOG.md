# Changelog

All notable changes to this project are documented here.

## [1.7.0] — 2026-04-29

### Added
- **45 new crops across 8 new files (+ 7 added to existing files)** — catalog grew from 125 → 170 by sweeping the 1.21.x catalog for items casual players want to bulk-farm. Skipped per-color sets (16-color wool/concrete/etc.), one-per-world uniqueness items (dragon egg, elytra), and trivially-craftable items.
  - `crops/breeze-trial.yml` (8) — 1.21 trial chamber: armadillo_scute, breeze_rod, wind_charge, trial_key, ominous_trial_key, ominous_bottle, heavy_core, mace. Mace overrides mythic to 36 hours.
  - `crops/sniffer.yml` (3) — sniffer_egg (epic), torchflower_seeds, pitcher_pod.
  - `crops/froglights.yml` (3) — pearlescent, verdant, ochre. All rare.
  - `crops/crafting-essentials.yml` (8) — stick, paper, brick, nether_brick, glass, glass_pane, charcoal, snowball.
  - `crops/raw-ores.yml` (3) — raw_iron, raw_gold, raw_copper.
  - `crops/flowers.yml` (11) — dandelion, poppy, oxeye_daisy, cornflower, allium, blue_orchid, azure_bluet, lily_of_the_valley, wither_rose, torchflower, pitcher_plant.
  - `crops/dyes.yml` (1 weighted) — single seed yields a random vanilla dye, even-weighted across all 16 colors.
  - `crops/pottery.yml` (1 weighted) — single seed yields a random pattern from the 23 pottery sherds (incl. 1.21 flow + guster).
- **`crops/endgame.yml` gained `nether_star`** — the Wither boss drop, mythic with a 48-hour grow time.
- **`crops/decoratives.yml` extended** with vine, weeping_vines, twisting_vines, big_dripleaf, small_dripleaf, spore_blossom (6 new entries — full lush-cave + Nether-vine coverage).

### Notes
- Compile target stays paper-api 1.21.1 for max back-compat. Materials added in 1.21.4+ (e.g. pale garden / eyeblossom) are not included; if you're on a newer server and want them, drop a custom `crops/pale-garden.yml` into your data folder.
- Items unavailable on older runtimes are gracefully skipped at load time (the loader logs a warning per missing material and continues).

## [1.6.1] — 2026-04-29

### Fixed
- **Tracked crops are now protected from non-player destruction.** Previously, water flow / pistons / explosions / mob trampling / fire would destroy a tracked crop and let vanilla wheat loot drop instead of the custom output — both punishing the player AND enabling automated water-flush farms that defeated the plugin's "no autofarm" pitch. New `settings.protect-from-automation` (default true) cancels:
  - water/lava flow into a tracked crop block (`BlockFromToEvent`)
  - piston push / pull touching a tracked crop (`BlockPistonExtend/RetractEvent`)
  - explosions hitting a tracked crop (`Entity/BlockExplodeEvent` — block is removed from blockList, surrounding terrain still goes)
  - mob/entity-driven block changes — ravager trample, enderman, falling sand landing on the crop, etc. (`EntityChangeBlockEvent`)
  - fire spreading into a crop (`BlockBurnEvent`)
  - farmland fading to dirt under a tracked crop (`BlockFadeEvent`)

  Set `protect-from-automation: false` to revert to vanilla destruction behavior.

### Added
- **Compensation seed on out-of-band destruction.** When a tracked crop's block disappears for any reason the protection didn't catch (op `/setblock`, world-edit, an exotic mod), the next growth-task tick now spawns one seed of that crop type at the location instead of silently un-tracking. Players never get nothing from a lost crop.

## [1.6.0] — 2026-04-29

### Changed
- **Tier system expanded from 4 to 6 tiers** — `common`, `uncommon`, `rare`, `epic`, `legendary`, `mythic`. Old `mid` is auto-aliased to `uncommon` so existing user crops keep working.
- **Per-player caps rebalanced** to a clean progression: `1024 / 256 / 64 / 32 / 8 / 2` from common → mythic.
- **Top-tier grow times now measured in hours/days** to make endgame items feel earned:
  - legendary baseline: **6 hours** (wither_skull, totem, heart_of_the_sea, shulker_shell, saddle, name_tag)
  - mythic baseline: **24 hours**, with two specific overrides:
    - `trident`: **36 hours** (drowned-with-trident is ultra-rare in vanilla)
    - `enchanted_golden_apple` (god apple): **48 hours** — uncraftable since 1.13, full 2-day grow
- **Diamond / emerald downgraded** epic → rare (15-min grow). They're valuable but no longer endgame-gating.
- **All other tier reassignments**: every `mid` becomes `uncommon`; ghast_tear / nautilus_shell / echo_shard / netherite_scrap / dragon_breath stay epic but inherit the new 45-min grow time.
- Removed redundant `grow-time-seconds` overrides from existing crops where the override matched the new tier default.

### Added
- **32 new crops across 5 new files** (catalog grew from 93 → 125):
  - `crops/farm-crops.yml` (10) — wheat, carrot, potato, beetroot, pumpkin, melon, sugar_cane, cactus, bamboo, kelp. Vanilla farm crops without the water/light requirements.
  - `crops/foods.yml` (10) — raw chicken, beef, porkchop, mutton, rabbit, cod, salmon, tropical_fish, pufferfish, egg. Replaces meat-mob and AFK-fishing farms.
  - `crops/mushrooms.yml` (4) — red_mushroom, brown_mushroom, crimson_fungus, warped_fungus.
  - `crops/heads.yml` (4) — skeleton_skull, zombie_head, creeper_head, piglin_head. Replaces charged-creeper-kill setups.
  - `crops/decoratives.yml` (2) — sponge, cobweb.
- **`crops/endgame.yml`** also gained `saddle` and `name_tag` (legendary tier) — both are chest-loot-only in vanilla.

### Notes
- Existing `tier: mid` entries in user crop files continue to load (aliased to `uncommon`). No manual editing required.
- If you want to keep your old grow times, you can re-add `grow-time-seconds:` to any specific crop — overrides still take precedence over tier defaults.

## [1.5.0] — 2026-04-29

### Changed
- **Storage moved from a YAML file to embedded SQLite (`crops.db`)** — every plant, break, and growth-task untrack is now a synchronous indexed write instead of a full-file rewrite at shutdown. Crash-safe (WAL mode). Config files (`config.yml`, `crops/*.yml`) stay YAML — they're for humans to edit.
- **Direct-to-inventory harvest** — fully-grown crops put their output, returned seed, and XP straight into the player's inventory and XP bar instead of spawning dropped item entities and ExperienceOrbs at the crop. For a 100-crop harvest run, entity creation drops from 200+ to 0. Inventory-full leftover spills at the crop block so nothing is ever lost. Toggleable via `settings.direct-to-inventory` (default true).

### Added
- `org.xerial:sqlite-jdbc:3.46.1.0` bundled into the plugin JAR. JAR grows from ~50 KB to ~14 MB. SQLite is embedded — no port, no separate process, no daemon.
- `CropStore` interface + `SqliteCropStore` implementation. Direct driver instantiation (bypasses `DriverManager` global registry). Native library extraction scoped to `plugins/CropFarm/.native/` to avoid Windows DLL-lock collisions across plugins.

### Notes
- If `crops.db` is unreadable on startup, the plugin refuses to start rather than silently masking a corrupt database. Inspect or restore the file before restarting.

## [1.4.0] — 2026-04-29

### Added
- **Tier system** (common / mid / rare / epic) supplying defaults for grow time, per-player cap, and XP per harvest. Each crop has `tier:` and may override any field inline.
- **Per-player planting cap** — default 1000 / 256 / 64 / 32 by tier. Stops one player from carpeting a chunk in 10 000 diamond crops. Op perm `cropfarm.bypass-cap` skips the limit.
- **Auto-XP on harvest** — every fully-grown crop now drops XP orbs scaled to its tier. Replaces the standalone `xp_bottle` crop, which was removed.
- **Weighted multi-output drops** via `outputs:` list. Used by the new random-color `wool` crop.
- **Multi-file crop loader** — drop any `*.yml` into `plugins/CropFarm/crops/` to extend the catalog without editing the main config.
- **`/cropfarm menu`** — paginated chest-based GUI showing every crop grouped by tier, with per-player planted count vs. cap, recipe, drops, and XP. Left-click for chat details, shift-click to take a seed (op).
- **Parent `/cropfarm` command** with `menu` / `reload` / `give` subcommands and `cf` alias. Legacy `/cropfarmreload` and `/cropfarmgive` are retained for scripts and console use.
- **68 new crops across 5 bundled files** (catalog grew from 25 → 93):
  - `crops/mob-drops.yml` (12) — feather, leather, sugar, ink_sac, glow_ink_sac, rotten_flesh, spider_eye, rabbit_hide, rabbit_foot, turtle_scute, phantom_membrane, dragon_breath
  - `crops/wool.yml` (1) — one weighted seed mirroring vanilla sheep spawn colors
  - `crops/vanilla.yml` (7) — apple, sweet_berries, glow_berries, chorus_fruit, cocoa_beans, sea_pickle, lily_pad
  - `crops/saplings.yml` (10) — every 1.21.1 tree variant including mangrove_propagule, cherry, azalea, flowering_azalea
  - `crops/blocks.yml` (31) — sand, gravel, clay, cobble, stone, deepslate, dirt, mud, all five stone variants, all Nether blocks, ice family, dripstone, moss, glow lichen, obsidian, crying_obsidian, end_stone, purpur
  - `crops/endgame.yml` (8) — wither_skeleton_skull, totem_of_undying, heart_of_the_sea, shulker_shell, trident, enchanted_golden_apple, prismarine_crystals, honey_bottle

### Changed
- Removed standalone `xp_bottle` crop — every harvest now drops XP based on tier.
- `crops.yml` persistence format gained an `ownerUuid` field (4th pipe-separated column). Old 2/3-field entries still load — they're treated as legacy/unowned and don't count toward caps.
- Plant and break listeners now respect WorldGuard and other protection plugins (`ignoreCancelled = true`).

### Fixed
- `/cropfarmgive` and the menu's shift-click "take seed" report partial delivery instead of silently dropping items when the target inventory is full.
- Cap enforcement uses an atomic check-and-reserve, so spam-clicking can't push a player past the limit.

## [1.0.0] — 2026-04-29

### Added
- Initial release with 25 craftable seeds (diamond, emerald, gold, iron, coal, redstone, lapis, quartz, amethyst, copper, xp_bottle, gunpowder, blaze_rod, ender_pearl, slime_ball, string, bone, glowstone_dust, honeycomb, ghast_tear, magma_cream, prismarine_shard, nautilus_shell, echo_shard, netherite_scrap).
- Per-crop configurable grow time (`grow-time-seconds`) — replaces vanilla random-tick growth with a predictable timer.
- Bone meal blocked by default for tracked crops (toggle: `settings.allow-bonemeal`).
- Floating `TextDisplay` nametag above each planted crop with stage progress.
- `/cropfarmreload` and `/cropfarmgive <crop> [amount] [player]` commands.
- Persistence to `crops.yml` survives restarts.
- Resource pack with 25 colored seed textures (4 hand-drawn + 21 procedurally generated from vanilla wheat seed).
- Tooling: [`tools/GenerateTextures.java`](tools/GenerateTextures.java) regenerates the resource pack from a single hex-color list.

### Notes
- Built and tested against PaperMC 1.21.1.
- Requires Java 21 to build; Maven wrapper included so no global Maven install needed.
