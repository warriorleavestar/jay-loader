# Loader matrix: the mainstream loaders on 1.20.1 and 1.21.1

Maintainer's request (2026-11-10): cover the mainstream mod loaders on the two big modding versions. Approved order: item 2, then item 1, then item 3.

Already there before this branch:
- 1.21.1: NeoForge base, running NeoForge mods and Fabric mods.
- 1.20.1: Forge base, running Forge mods and Fabric mods.

Version survey (Modrinth public search, 2026-11-10). Mods per version: 1.20.1 35,226 and 1.21.1 33,007, with every other version far below. Modpacks: 1.20.1 7,422 and 1.21.1 5,259. CurseForge counts are still open (they need a CurseForge API key).

Test downloads are listed in `mc/dl-*/manifest.json`, all from the official Modrinth CDN with sha512 checked. They are test data only and never redistributed. Download them with `tools/mrfetch.py <outdir> <slug>:<mc>:<loader> ...`.

## Item 2: NeoForge 1.20.1 mods on Forge 1.20.1 (done)

How common this is (Modrinth):
- Of the ~5,200 mods tagged NeoForge 1.20.1, almost all are the same jar as their Forge build. None of the top 100 lacked a Forge build, and only 2 in a deeper sample of 200 did.
- NeoForge 1.20.1 (47.1.x) is Forge 47.1 forked: same `net.minecraftforge` packages, same `META-INF/mods.toml`.
- What stops such a jar on Forge is a mandatory `[[dependencies.x]]` block with `modId="neoforge"`.
- Example: Outline Effects/Glint Outline, tagged NeoForge-only, already declares `forge`. Its jar is Forge-format.

Change (Forge platform only):
- `MetadataReaders`: a FORGE-kind mod's `neoforge` dependency is read as `forge [47,)`, so Amber's resolver accepts it.
- `ForgeCheck.neoForgeDepsAsForge`: at bake time, the jar's `META-INF/mods.toml` gets the same rewrite, line based, inside dependency blocks only. This is so FML's sorter sees `forge`. The bake stat is `neoforgeDeps`.
- `ForgePlatform.PIPELINE` is now forge-m4-6, so earlier Forge mod bakes are rebuilt.
- Test switch: `-Damber.ul.noNeoForgeAlias=true`.
- Not covered: a NeoForge 1.20.1 mod that asks `ModList.isLoaded("neoforge")` still gets false on Forge. APIs NeoForge added after the fork (47.1.x) are absent too. Neither showed up in the samples.

Results:
- `nf1201`: forgeworldtest with its dependency changed to `neoforge [47.1,)`. Fix on: constructed, joined, in world 30 s.
- `nf1201off` (control, switch on): Amber refuses at once with missing_dep "needs neoforge [47.1,) but it is not installed".
- `nf1201glint`: the real Glint Outline (NeoForge-tagged) with YACL 3.6.6 Forge. FML: 5 mods, 0 errors; in world 30 s.

## Item 1: Forge 1.21.1 mods on NeoForge 1.21.1 (done for small mods)

How common this is (Modrinth, a sample of 360 mods across the download ranking):
- About 90% of mods tagged Forge 1.21.1 also ship a NeoForge build.
- Of the rest, most also ship a Fabric build (which already runs on NeoForge here) or are data packs.
- Truly Forge-only is roughly 2–3%, about 150 mods, almost all small (e.g. dont-cut-tripwire, wildlife-expanded-skunk, create-better-building).
- Plan: a narrow layer (metadata plus the Forge 52 APIs these small mods use), not a full Forge API bridge.

Background: Forge 52 (1.21.1) and NeoForge 21.1 both use Mojang names for the game. What differs is the loader API: Forge keeps `net.minecraftforge.*`, NeoForge moved to `net.neoforged.*` and changed some classes (tick events split into Pre/Post, `DeferredHolder` instead of `RegistryObject`, no `FMLJavaModLoadingContext`).

Change (NeoForge platform only, `ForgeCompat`, pipeline forgecompat-3):
- **Which jars.** A jar is a Forge 52 jar when it has `META-INF/mods.toml`, no `neoforge.mods.toml`, and references `net/minecraftforge/`. Each is converted once, into `<cacheRoot>/forgecompat/<key>-<name>.jar`. The key hashes the pipeline, the shim jar and the mod jar.
- **Class references.** A Forge class that Amber's shim provides goes to `amber/forgecompat/<path below net/minecraftforge/>`. Otherwise rename rules apply, most specific first: `MinecraftForge` → `NeoForge`, `ForgeConfigSpec` → `ModConfigSpec`, `eventbus/api` → `bus/api`, `fml/` → `net/neoforged/fml/`, `Mod$EventBusSubscriber` → `net/neoforged/fml/common/EventBusSubscriber`, and the event, client, common, fluids, items, server, registries and network packages → `net/neoforged/neoforge/...`. Renamed members: `BonemealEvent.getBlock` → `getState`, `getEntity` → `getPlayer`. The Bus enum `FORGE` becomes `GAME`.
- **@Mod class.** It is made public, with public constructors. Forge built it with access checks off, and Simple Harvesting's is package-private. A constructor taking `FMLJavaModLoadingContext` becomes private, and a public `(ModContainer)` constructor calls it through `FMLJavaModLoadingContext.of(container)`.
- **Metadata.** `mods.toml` → `neoforge.mods.toml`: `loaderVersion` `[1,)`; the `forge` dependency → `neoforge [21,)`; `mandatory` → `type`; `MixinConfigs` from the manifest → `[[mixins]]`.
- **Verification.** Every reference into `net/neoforged`, `amber/forgecompat` and `net/minecraftforge` is checked against an index of the platform jars, the baked game and the shim: classes, plus fields and methods through supers, with JDK supers checked by reflection. A jar with any unresolved reference is not loaded. It gets the error `forge_api_missing`, which names the first references; the launch stops there, as with any dependency error.
- **Shim jar (`build/forgecompat.jar`, mod `amber_forgecompat`).** It holds `ModLoadingContext`, `FMLJavaModLoadingContext` (`get`, `of`, `getModEventBus`, `registerConfig`), `IForgeRegistry`, `ForgeRegistries` (+`Keys`), `RegistryObject` (over `DeferredHolder`), `DeferredRegister`, and `TickEvent` (Server/Player/Level/Client with START/END phases, posted from NeoForge's Pre/Post events). It is added only when some jar was converted.
- **Test switch.** `-Damber.ul.noForgeCompat=true` (jars passed through unconverted).
- **FmlBridge fix found on the way.** `neoforgespi.Environment` was never set, because modlauncher's launch does not run here. Any NeoForge mod with a dependency `side="CLIENT"`/`"SERVER"` crashed in `ModSorter` (NPE in `DependencySide.isCorrectSide`). The bridge now sets it to FML's dist before sorting.

Results (`mc/mods-nf-forge1211`: four Forge-only 1.21.1 mods from `mc/dl-forge1211`, plus the NeoForge world-test helper):
- `nff`, converter on: FML lists 8 mods with 0 problems; title screen at 15 s; in world.
  - Daily Expansion's server-side chunk hook runs ("Initialized single center chunk").
  - Soulbinding's server start and stop events fire.
  - Don't Cut Tripwire and Simple Harvesting are constructed.
- `nffc`, control with the converter off: refused at once. Daily Expansion, dontcuttripwire and soulbinding each get missing_dep "needs forge [51,)/[52,)"; exit 2.
- Regressions:
  - census m5s: 11 / 0 / 15, unchanged.
  - census ffp strict: 31 failing / 6 excluded, unchanged.
  - in-world `rgnf` (5-mod NeoForge + Fabric) and `rgw` (Fabric base): see the commit.
- Wildlife Expanded: Skunk is refused with `forge_api_missing`. It needs Forge capabilities, Forge networking and spawn-placement events, which the shim does not provide. That is the boundary of this layer: small mods that use events, registries, configs and ticks.

Not covered yet: Forge capabilities (`ICapabilityProvider`, `LazyOptional`), Forge `SimpleChannel` networking, `ForgeRegistries` beyond the vanilla registries, Forge-only events NeoForge removed, and access transformers in Forge format (`META-INF/accesstransformer.cfg` is read by NeoForge too, but entries naming Forge classes would not match).

## Item 3: Quilt mods on every platform (done for mods using the basic Quilt API)

How common this is (Modrinth, all Quilt-tagged mods, 2026-11-10):
- 1.20.1: 94 of 5,000 Quilt-tagged mods have no Fabric, Forge or NeoForge build (about 2%). Most are small. QSL itself (1.8 M downloads) is one of them.
- 1.21.1: 11 of 4,896 have no other build, all tiny. Several are old 1.19/1.20 builds tagged 1.21.1. QSL has no 1.21.1 release.
- Every Quilt-tagged mod in the top 100 of either version also ships a Fabric build, which Amber already runs.

Background: Quilt mods use intermediary names and Mixin like Fabric mods. What differs is `quilt.mod.json` and, for some, the Quilt APIs (`org.quiltmc.loader.api`, QSL's `org.quiltmc.qsl`). Three Quilt-only mods were sampled:
- Feed the Bees (1.20.1) and Simple End Disable (tagged 1.21.1) are mixin-only. They reference no `org/quiltmc` class, only depending on `quilt_loader` and one QSL module.
- Compost Bin (a real 1.21 build) uses the basic Quilt API: QSL's `init`/`client_init` entrypoints, `ModContainer.metadata().id()`, `@ClientOnly`, and a dependency on `quilted_fabric_api`. Its content goes through Fabric API.

Change (`meta/QuiltCompat`, every platform; runs after discovery, before the resolver):
- **Pipeline.** Quilt mods go through the Fabric pipeline (remap, refmaps, Mixin, the Fabric facade, and the Fabric bridge on Forge/NeoForge), as `LoaderKind.QUILT` already did.
- **`quilt_loader` and `quilt_base`.** Satisfied by Amber. They are not advertised as mod ids, because Fabric mods probe `isModLoaded("quilt_loader")` and would switch to Quilt APIs.
- **QSL modules.** Each maps to the Fabric API module that does the same job: `quilt_resource_loader` → `fabric-resource-loader-v0`, and likewise lifecycle events, command, item group, registry and networking. On Forge/NeoForge, `quilt_resource_loader` is simply satisfied, because the platform loads every mod's resources itself; Feed the Bees on Forge needs no Fabric API.
- **Quilt API subset, in the loader** (`shims/src/org/quiltmc`, plus `personality/QuiltContainer`):
  - `org.quiltmc.loader.api.ModContainer`: `metadata()`, `rootPath()`, `getPath()`.
  - `ModMetadata`: `id()`, `group()`, `name()`, `description()`.
  - `@ClientOnly` and `@DedicatedServerOnly`.
  - QSL's `ModInitializer` (`init`), `ClientModInitializer` (`client_init`) and `DedicatedServerModInitializer` (`server_init`).
  - The Quilt entrypoints run in the same phase as Fabric's `main`/`client`/`server`: right after them, as Quilt Loader does. On NeoForge that is inside its registration window.
  - `FabricFacade` reads Quilt entrypoints from `quilt_loader.entrypoints`, including the single-string form.
- **Everything else in the Quilt API.** A Quilt jar that references any other `org/quiltmc/` class or member is not loaded. The check runs per class and per method/field, against the loader's shims, and compile-time annotations don't count. Such a jar gets the error `quilt_api_missing`, naming the references. Real QSL is handled the same way: its 44 module jars are each refused with that message, instead of crashing.
- **`quilted_fabric_api`.** Mapped to `fabric-api`.
- **Metadata.** The reader now accepts Quilt's `versions` objects (`{"all": [...]}`, `{"any": [...]}`, arrays) and `provides`. QSL's own `quilt.mod.json` used to fail as `bad_metadata`.
- **Test switch.** `-Damber.ul.noQuiltCompat=true`.

Results:
- `q121`, Fabric base 1.21.1, `mods-world` plus Simple End Disable: the mod is found and its mixin applied. The mixin's target method no longer exists in 1.21.1; the jar is a 1.19.2 build mislabelled 1.21.1 on Modrinth. So the strict run stops on the mod's own critical injector, exactly as real Quilt would. With `-Damber.ul.mixinErrors=warn` (`q121w`), the mixin is isolated as `target_method_missing` and the game reaches the world.
- `q121off`, the control with the switch on: refused at once with missing_dep "needs quilt_loader >=0.17.0-"; exit 2.
- `qforge`, Forge 1.20.1 with Feed the Bees and the Forge world test (no Fabric API): in world 30 s. The census on that set shows 0 failing mixins, so the mod's mixins apply on Forge.
- `qqsl`, Forge 1.20.1 with the real QSL 6.3.0 / QFAPI 7.7.0 bundle: refused before launch with 44 × `quilt_api_missing` (exit 2), each naming the QSL classes.
- `qnf`, NeoForge 1.21.1 census (`mods-5s` plus Simple End Disable, with fixes): 12 failing / 1 excluded / 15 fixed. That is the m5s baseline (11 / 0 / 15) plus the mislabelled mod's one broken mixin.
- `qcb`, Fabric base 1.21.1, `mods-world` plus Compost Bin: in world. With a test log config that turns on debug for its logger, its `init` logs "Compost Bin is being registered" under the logger named by `ModContainer.metadata().id()` (`compost_bin`). That shows the Quilt entrypoint ran with Amber's container.
- `qcbnf`, NeoForge 1.21.1, `mods-5s` plus Compost Bin: the census with fixes is 11 / 0 / 15 (the m5s baseline exactly; nothing new from the Quilt mod), and the game reaches the world. `init` runs on `modloading-sync-worker`, inside NeoForge's registration window.
- Regressions, on the final build:
  - census m5s (NeoForge): 11 / 0 / 15, unchanged.
  - census ffp strict (Forge): 31 failing / 6 excluded / 7 fixed / 1 refuted, unchanged. The lenient census is 3 / 2 / 4, rerun last so the isolation file is the lenient one.
  - rgnf, rgw, nff and qforge: in world.
  - `bake_cli_test.py vanilla`: PASS.

Not covered yet:
- The rest of the Quilt Loader API: `QuiltLoader` static calls, versions, config dirs, entrypoint lookup by other mods, Quilt plugins.
- Running real QSL. It needs Quilt Loader internals, and on Forge also everything Fabric API needs there.
- Quilt-only mods that use those APIs, which are refused with `quilt_api_missing`.

Side finding: Fabric API on Forge 1.20.1 still fails in strict mode (a known strict-list item), and in warn mode its Indigo renderer redirect fails on Forge's chunk builder. Neither has anything to do with Quilt; both are on the fabric-forge strict list.

