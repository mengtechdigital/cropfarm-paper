# Changelog

All notable changes to this project are documented here.

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
