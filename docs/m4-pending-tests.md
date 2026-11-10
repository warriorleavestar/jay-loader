# M4 (Forge 1.20.1) — needs a real test

Everything below was built and only quick-checked on the development machine (offline account, test game dir `mc/game-forge`, one world
load of 30 s per check). Real testing happens later on test VMs and real machines. Each item: what to run, what passes.

Quick checks already done (2026-11-09, branch `m4-forge-1.20.1`):
- group pairs: A+B1 world OK, B1+B2 world OK, A+B2 stalled → bisect → **ModernFix + YungsApi** (fixed, see 1)
- full 36-mod corpus (`mc/mods-forge-world`): census 0 failures / 674 changed classes, in world 30 s, 60 fps
- ModernFix + YungsApi alone: in world 30 s

## 1. FMLLoader.isProduction() = true

The bridge used to report a userdev launch (`production=false`). ModernFix then turned on its dev-only options
(`mixin.devenv`, `mixin.perf.remove_spawn_chunks`), and remove_spawn_chunks + YungsApi's `prepareLevels` constants
(11 → 0, 441 → 0) never finished "Preparing start region". Now `true`; `-Damber.forge.production=false` is the old answer.

- [ ] Full corpus + a few big packs: no mod takes a dev-only path. Grep the log for "dev environment" / "devenv".
- [ ] Mods that pick a reflection name by `isProduction()` (SRG name in production): runtime is Mojmap, so
      plain `getDeclaredField("f_1234_")` fails unless it goes through ObfuscationReflectionHelper (mapped by ForgeShim) or the
      bake's StringPass rewrote it. Look for NoSuchField/NoSuchMethod in the log on a large pack.
- [ ] Compare against real Forge 1.20.1-47.4.28 with the same pack: ModernFix's option list
      (`config/modernfix-mixins.properties` defaults) should be the same on both.

## 2. Forge JS coremods (bake time, real coremods 5.2.4 + Nashorn 15.4)

`amber.ul.fmlforge.ForgeCoreMods`, called by `Instance.applyForgeCoreMods`; output `game-forge/*-fcm-<key>.jar`.
Forge's 4 scripts: field_to_method, method_redirector (finalizeSpawn), field_to_instanceof (empty), add_bouncer_method
(1.16 target, no-op). Quick check: 10 transformers, 31 target classes, 30 changed; Biome reads go through
getModifiedClimateSettings/getModifiedSpecialEffects; finalizeSpawn calls go through ForgeEventFactory.onFinalizeSpawn.
`-Damber.ul.noForgeCoreMods=true` turns it off for comparison.

- [ ] Biome modifiers: a data-pack `forge:biome_modifier` that changes climate/effects (e.g. temperature, fog colour) shows
      in game (F3 / weather / foliage colour). Compare with `-Damber.ul.noForgeCoreMods=true` (should NOT show).
- [ ] Structure modifiers (`forge:structure_modifier`) take effect.
- [ ] MobSpawnEvent.FinalizeSpawn fires for natural spawns, /summon, spawner, raids, villager/zombie conversion (a test
      mod that counts events).
- [ ] Mod-added fluids in LiquidBlock / BucketItem, mod StairBlock / FlowerPotBlock with deferred suppliers (Create, Farmer's
      Delight): place, pick up, render.
- [ ] A mod that ships its own `META-INF/coremods.json` (none in the 36-mod corpus): it is picked up from the mod jar and
      applied. Its targets must be game classes; a coremod that targets a mod class is NOT handled at bake time
      (would need a runtime pass) — find one in the wild and check.
- [ ] Bake cache: change only the mod set (no coremod mods) → `-fcm-` jar reused; add a coremod mod → new key.

## 3. Launch plugins added by mods at runtime

The stub Launcher now has an empty `LaunchPluginHandler` (and a blackboard). Mods that add a plugin by reflection
(ModernFix `CoreLaunchPluginService` → CapabilityProvider.areCapsCompatible / mfix$skipLazyInit) are picked up by
`ForgeLaunchPlugins.active()` and run after Forge's own plugins, as modlauncher would. Quick check: log line
"模组加入的 launch plugin modernfix".

- [ ] Item stacking with capabilities (ModernFix faster_capabilities on): stack items that carry caps (Sophisticated
      Backpacks upgrades, Create items with caps, Curios); split/merge stacks; no dupes, no "can't stack".
- [ ] Classes loaded BEFORE ModernFix adds its plugin are not re-transformed (same as modlauncher). Check that
      CapabilityProvider is first loaded after the plugin is in (log the order once).

## 4. Mixin checks on the Forge platform

`countInjections` (lenient/census mode) is off on Forge: Mixin's own require / @Group checks decide, as on real Forge.
Before this, isolation removed whole mixin classes that real Forge applies: Quark HumanoidArmorLayerMixin (colour runes),
Create MapRendererMapInstanceMixin (map decorations), ModernFix BlocksMixin / ServerGamePacketListenerImplMixin /
ServerChunkCache_CurrentLoadingMixin / BufferBuilderMixin (all require=0 or @Dynamic). Census is now 0 failures.

- [ ] Quark colour runes on armour render; Create train map decorations on a map.
- [ ] A pack with a genuinely broken mixin (require=1 miss) still gets a census failure and is isolated (make one in a test
      mod).
- [ ] NeoForge / Fabric regressions (not touched by this change, but core changed): mods-5s probes, mixed2 census, Fabric
      world.

## 5. Remaining log errors in the full-corpus run (believed upstream — confirm on real Forge)

- mowziesmobs `models/block/*_grottol.json`: "LLibrary bug not putting particle into textures" (the mod's own JSON)
- naturalist `loot_tables/entities/tortoise`: unknown item `minecraft:turtle_scute` (a 1.20.5 item in 1.20.1 data)
- supplementaries missing textures (tater_in_a_jar, tomatoes, confetti_popper) and sounds (awning_bounce_*);
  naturalist giraffe idle sound
- ForgeConfigSpec "Incorrect key ... corrected" (first run creates the configs), Embeddium MixinTaintDetector
  (informational), "Can't keep up" on first world gen, Realms auth (offline account)
- [ ] Same pack on real Forge 1.20.1-47.4.28: the same errors and nothing else.

## 6. Network

Forge's VersionChecker was on in `mc/game-forge/config/fml.toml` and fetched update JSON for mods (blamejared.com,
github.com) during earlier runs (27 run logs). Set to `versionCheck = false` on 2026-11-09.

- [ ] Decide what Amber does by default for players (Forge's default is on). For test VMs: off.
- [ ] Run with outbound traffic blocked: launch and world load still work (version check and Realms fail quietly).
