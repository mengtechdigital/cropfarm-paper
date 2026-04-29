# Changelog

All notable changes to this project are documented here.

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
