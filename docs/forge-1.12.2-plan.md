# Forge 1.12.2 base: plan (approved as a later item, not started)

Status 2026-11-11: plan only. Nothing is built.

## Why it's different

Everything Amber supports today (1.20.1 Forge, 1.21.1 NeoForge, Fabric, Quilt) runs on modlauncher or Fabric-style class loading, Java 17/21, Mojang official mappings, and Mixin. 1.12.2 shares none of that:

| | Amber today | Forge 1.12.2 (14.23.5.2860) |
|---|---|---|
| Launcher | our own `amber.ul.Launcher`, one class loader, baked jars | Mojang LaunchWrapper (`net.minecraft.launchwrapper.Launch`) + FML tweakers |
| Code changes | Mixin + coremod JS at bake time; mixins applied at runtime by our Mixin service | `IFMLLoadingPlugin` coremods: arbitrary `IClassTransformer`s that run on every class load, in sort-index order, often reading each other's output |
| Java | 21 (17 for 1.20.1) | 8. Many mods and coremods break on 9+ (URLClassLoader casts, `sun.misc.Unsafe` tricks, Nashorn) |
| Mappings | Mojang official → runtime names | No official mappings. MCP (`mcp_stable_39` / snapshot) names on top of SRG, runtime is SRG (`func_xxx_a`, `field_xxx_a`) on top of notch |
| Mixin | Mixin 0.8.5+ with our service | Optional: MixinBooter / "MixinBootstrap" ship Mixin 0.8.x via a coremod; many big packs depend on it |

## 1. LaunchWrapper/coremod model vs ours

- A 1.12.2 coremod is a black box that sees raw bytes per class. We can't know its output without running it. "Bake ahead" therefore means running the transformers ourselves, offline, over every class, then caching the result. That's the same idea as our NeoForge coremod pass, but with Java code instead of JS and with order dependencies.
- Plan: a `LaunchClassLoader`-compatible shim inside Amber. It loads FML's `CoreModManager`, collects transformers (plus `IFMLLoadingPlugin.getASMTransformerClass`, access transformers and Mixin via MixinBooter), and runs the chain over the game jar at bake time. Output is a baked game jar per (mod set, transformer list) key. At runtime, classes the bake didn't cover (mod classes, generated classes) still go through the live chain. Some transformers rely on runtime state, so the live path must always be available. The bake is a cache, not a replacement.
- Risk: transformers that look at `Launch.blackboard`, call `Class.forName` during transformation, or keep static state between classes. Those need the real class loader during the bake, so the bake runs inside a stub LaunchWrapper environment, not as a plain bytecode pass.

## 2. Java 8 vs 21

- Ship and run 1.12.2 on Java 8 (the only version where the full ecosystem works). Amber's own code would need a Java 8 build of the runtime side (loader core + the 1.12 platform layer). Today `core` is Java 21, so this is a separate `core-legacy` module or a multi-release build. The baking tools can stay Java 21 (they run in their own JVM), and only the runtime part must be Java 8.
- Optional later: a Java 17/21 path like Cleanroom/"lwjgl3ify". It's niche, breaks many coremods, and is not part of the first scope.

## 3. Mappings

- No Mojang mappings exist for 1.12.2. The chain is notch → SRG (MCPConfig/`mcp_config` for 1.12.2) → MCP names (crowd-sourced, `mcp_stable_39-1.12`).
- At runtime, mods are SRG-named (ForgeGradle reobfuscates). Forge's deobf-in-dev does not apply to us. The game runs SRG-named (FML's `FMLDeobfuscatingRemapper` maps notch → SRG at load).
- For us: bake the game notch → SRG once with our tiny-remapper path (as for Forge 1.20.1, which is also SRG-at-runtime). MCP names are only needed for any Fabric/legacy bridge or for readable problem reports. Licence: MCP data has no clear license, so like the current mappings, we fetch it at bake time and never redistribute it.

## 4. How bake-ahead applies

- Applies well: the game jar (deobf to SRG, access transformers, Forge's own binpatches), mod discovery and dependency checks (`mcmod.info` is optional and often wrong, so `@Mod` annotation scan), JiJ-style contained deps (1.12 has the `META-INF/libraries` / `ContainedDeps` manifest attribute), and the transformer chain over the game classes where transformers are deterministic.
- Applies partially: coremod output (cache keyed by the transformer list + mod shas, live fallback always on), Mixin via MixinBooter (we can apply at bake like the NeoForge pass, with the runtime service as fallback).
- Doesn't apply: classes generated at runtime (ASM event handlers, FML's `ASMEventHandler`), and transformers that depend on world/config state.
- Expected gain: big packs (RLCraft has ~180 mods, ~20 coremods) spend most launch time in the transformer chain and FML's ASM data table scan. Baking both could plausibly halve launch time. That needs measuring before we promise anything.

## 5. Fabric bridge on 1.12.2?

- No. Legacy Fabric (1.12.2) and Cleanroom are niche, with few mods and different APIs from modern Fabric. Our Fabric API bridge targets the modern Fabric API, so nothing carries over. Skip it, and revisit only if a real user pack needs it.

## 6. Test corpus

- RLCraft 2.9.3 (~180 mods, heavy coremods: Lycanites, Bountiful Baubles, ...). It's the reference "big pack".
- Two or three more: Enigmatica 2 Expert (tech, MixinBooter + many coremods), SevTech Ages (stages, lots of ASM), and a small control pack (JEI + Baubles + one coremod).
- Fetch from CurseForge (key from HKCU\Environment as today) into gitignored test dirs, hash-verified, never redistributed. Census tool analog: run each pack's transformer chain at bake and report per-transformer failures and timings.
- Success: title screen, then in world 60 s, for the small pack, then E2E, then RLCraft. Plus a control run with the official Forge installer (same Java 8) for timings and crash comparisons.

## 7. Size

Compared with the M4 Forge 1.20.1 work (about 6-8 sessions: game bake, FML personality, mixin service, coremods, census, bridges):

- LaunchWrapper/FML 1.12 personality + bake of game/binpatches/AT: ~3-4 sessions
- Transformer-chain bake with live fallback + cache keys: ~2-3 sessions
- Java 8 runtime split (core-legacy): ~1-2 sessions
- Mappings (MCPConfig 1.12.2, SRG runtime): ~1 session (reuses the 1.20.1 SRG path)
- MixinBooter integration: ~1 session
- Corpus + census + fixes up to RLCraft in world: ~3-5 sessions (the long tail)

Total: roughly 11-16 sessions, about twice M4. The Java 8 split and the coremod chain are the new risk. Everything else reuses existing pieces.

## Order when it starts

1. Recon: download Forge 1.12.2 installer + libraries, run it officially, measure RLCraft launch on Java 8.
2. Java 8 core-legacy skeleton and LaunchWrapper personality to the vanilla+Forge title screen.
3. Small pack in world with the live transformer chain (no bake).
4. Bake the chain + cache, then measure.
5. E2E, then RLCraft.
6. Jay integration (bake interface `platform: "forge-1.12.2"`, Java 8 runtime requirement in the manifest).
