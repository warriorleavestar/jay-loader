# Interface between the universal loader and Amber's bake framework (v1.3.1)

v1 2026-11-04 (draft); v1.1 2026-11-09; v1.2 2026-11-09; v1.3 2026-11-10; v1.3.1 2026-11-11; v1.3.2 2026-11-12; **v1.3.3 2026-11-12** (quick play; maintenance mode). Written for the Amber launcher side. The loader is still a prototype. `amber.ul.Bake version` reports `"interface":"1.3"`.

## Bake-time mixin pass (NeoForge platform, 2026-11-11)

Before this, Amber only fixed Fabric mixins that fail on the NeoForge-patched game after a census of that exact mod set (the fixes are keyed by every mod's sha). A new mod set (any Jay instance) got no fixes. For example, Fabric API's screen-tick mixin targets `Minecraft.lambda$tick$32`, which NeoForge's patches renumber to `lambda$tick$35`, so the launch crashed.

Now `bake-instance`, `warm` and every launch run a static pass for each baked Fabric/Quilt mod. It is cached under `<cacheRoot>/lambda-retarget/` per mod jar, game and pass version, so it runs once:
- **Lambdas.** A selector naming a game lambda is matched against the patched class. It matches by outer method, descriptor and body (call/field references, then the opcode sequence), never by the number, and is retargeted. If the name is gone and there is no unique match, or the match lacks the injector's @At point, you get `mixin_lambda_unresolved` at bake time. Mixins that the Fabric API bridge manifest vetoes or strips are skipped.
- **Other moved targets.** An injector whose method or @At(INVOKE/FIELD) point is gone from the patched method goes through the census fixer's analysis: moved into a new method or lambda, delegating overload, call replaced, instance-to-static. If nothing fits, you get `mixin_target_changed` (warning).

Test switch: `-Damber.ul.noLambdaRetarget=true`.

## Sandboxed launch (loader 0.3.2)

Jay plans to run the game in a sandbox that can write only the instance directory. A launch from a state file (`amber.ul.Launcher --state …`) now writes only under `<instanceDir>/.amber-ul/` (plus the game's own `gameDir`):

| What | Before | Now |
| --- | --- | --- |
| Class cache | `<cacheRoot>/classcache/` | `<instanceDir>/.amber-ul/classcache/` |
| Mixin isolation files | `<cacheRoot>/../mixin-isolation/` (outside the cache root) | `<instanceDir>/.amber-ul/mixin-isolation/`; an older file next to the cache root is still read |
| `changed-classes*.txt` (debug lists) | `<cacheRoot>/` | `<instanceDir>/.amber-ul/` |
| A cache product missing at launch (mod set changed after `warm`, cache cleaned, corrupt graph) | built into `cacheRoot` / `mappingCache` | built into `<instanceDir>/.amber-ul/cache-overlay/{cache,mappings}/`, same relative path |

- The shared caches (`cacheRoot`, `mappingCache`), the platform install, the game jar and libraries, the mappings and the loader package are only read. They can be read-only for the game process.
- Files in the overlay are used until the shared cache has them again. `warm` builds the shared copy as before (a launch that needed the overlay still reports `launch_rebaked`). Jay may delete `cache-overlay/` at any time.
- `bake-instance`, `warm` and `gc` are unchanged. They write `cacheRoot` and `mappingCache` and must run outside the sandbox (or with those directories writable).
- The loader itself opens no network connection and does not use `user.home`, `java.io.tmpdir`, `%APPDATA%` or `%LOCALAPPDATA%`. Every temp file sits next to its output.
- On Jay's side: point `-Dorg.lwjgl.system.SharedLibraryExtractPath` and `-Djna.tmpdir` into the instance (LWJGL and JNA extract native libraries to `java.io.tmpdir` otherwise), and use the instance as `--gameDir` (logs, config, saves, crash reports).
- Dev and census runs without `--state` keep the old layout. `-Damber.ul.launchOverlay=false` turns the overlay off for a state launch.
- Test: `python -I tools/bake_cli_test.py sandbox`. It bakes and warms into a fresh shared cache, removes one baked mod, then launches with `user.home` and `java.io.tmpdir` pointing under a regular file. The launch must reach the title screen and leave the shared caches byte-for-byte unchanged. The rebuilt jar must be in the overlay and the class cache in the instance.

## Changelog

**v1.3.1 (2026-11-11).** Additive. `interface` stays `"1.3"`, so consumers that check it keep working. `protocol` stays 1. New:
- `version` reports `"interfaceRevision":"1.3.1"`.
- `version` and the package `manifest.json` carry `accepts`: one entry per platform and game version, `{platform, mc, available, accepts, missingBridges}`.
  - `accepts` lists the mod ecosystems that platform loads in this build. The names are Modrinth loader names (`fabric`, `quilt`, `forge`, `neoforge`), with the platform's own ecosystem first.
  - It is derived from the bridge jars actually present. Forge/NeoForge need their FML bridge to run at all; without it, `available` is `false` and `accepts` is `[]`. Fabric/Quilt mods there need the Fabric API bridge, and Forge 1.21.1 mods on NeoForge need `forgecompat`.
  - A core-only package (no bridges) therefore accepts only `fabric`/`quilt` on the vanilla platform.
  - Planned platforms (none at the moment) would be listed with `available: false`, `accepts: []`, the bridge they will need in `missingBridges`, and `planned: true`; `requirements` / `bake-instance` answer them with the problem `platform_unavailable`.
- The existing `platforms` map is unchanged.
- New problem codes from the bake-time mixin pass on NeoForge: `mixin_lambda_unresolved` (error) and `mixin_target_changed` (warning). See "Bake-time mixin pass".
- Released as loader 0.3.1.

**v1.3 (2026-11-10).** Additive, `protocol` stays 1 (cache layout unchanged; old caches and states stay valid). Answers the loader-side requests from the Jay integration (Jay PR #139).
- **Loader libraries come from the build.** `requirements` no longer carries a hand-written `loader-lib` list (it said asm 9.7.1 / mixin 0.15.4; the loader runs with asm 9.10.1 / mixin 0.17.5). The build generates `amber-ul/loader-libs.json` from the real class path (`tools/loader_libs.py`), and `requirements` lists exactly those jars, each with `group`, `artifact`, `version`, `url` (Maven Central or maven.fabricmc.net), `sha1`, `sha256`, `size`, `path` (`libs/<file>`). `version.libs` comes from the same list (keyed by Maven artifact: `asm`, `sponge-mixin`, `mixinextras-fabric`, …).
- **Packaged layout.** `tools/package.py` (after `build.sh`) writes `build/dist/amber-ul-<loaderVersion>/` and the same as a `.zip`: `loader.jar`, `libs/`, `bridges/` (all five bridge jars, including the Forge ones) and `manifest.json` (versions, `javaMin`, entry classes, class path, sha256/sha1/size of every file). See [Packaged layout](#packaged-layout-v13).
- **Java.** `version.javaMin` per platform (all 21 today) and `version.javaRuntime` (the JVM running the stage); `requirements` result `javaMin`; `bake-instance` refuses a request whose `javaMajor` is below the platform's minimum with error `java_too_old` (exit 1); the launch block carries `javaMin`. The baked launch needs that version whatever the game version's json asks for (Forge 1.20.1's json says 17). A JVM older than 21 cannot run the loader at all (it fails with `UnsupportedClassVersionError` before any JSON); check `manifest.javaMin` before starting any stage.
- **Rebuilt or reused.** `bake-instance` and `warm` results carry `cache: {state, stateKeyChanged, bakeMisses, newCacheFiles, warm}`. `state` is `new` (no earlier state for this instance), `rebuilt` or `reused`; `warm: true` means run `warm` again.
- **Runtime problems as JSON lines.** A launch writes `launch.problemsFile` (`<instanceDir>/.amber-ul/runtime-problems.jsonl`), and prints the same objects on stdout behind the prefix `@amber-ul/problem `. Stable codes: `mixin_incompatible`, `mixin_semantic_risk`, `fabric_api_bridged`, `fabric_api_bridge_version`, `fabric_init_not_run`, `launch_rebaked`, plus the dependency codes. See [Stage 4](#stage-4-launch-time).
- **Bridge jars** are looked up in `bridges/` next to the loader jar (packaged), else next to it (dev build).
- **Fix (Forge + Fabric mods).** The Fabric bridge's mod-remapper graph is now part of what `bake-instance`/`warm` build and list in `cacheFiles` on Forge too. Before, an all-hits bake or warm dropped it, so `gc` listed it as unreferenced. A launch from a state now keys that graph with the state's `libraries`, not the JVM class path, so it maps the cached graph in instead of building a second one.

**v1.2 (2026-11-09).** Additive: two new stages, two new state fields; every v1/v1.1 request is unchanged.
- **`warm`** (stage 3): builds or verifies everything a launch needs from the caches (mapping graphs, remapped game, baked mods, platform jars, coremod outputs) without starting the game, and records it in the state.
- **`gc`**: lists cache entries that no live state uses, inside one cache root (and optionally the mapping cache). Dry run by default; deletes only with `"apply": true`.
- **State file:** `libraries` (so `warm` can rebuild the request from the state alone) and `cacheFiles` (every cache product the instance uses, absolute paths), written by `bake-instance` and `warm`.
- Check `version.interface >= 1.2` before calling `warm` or `gc`; older loaders answer `unknown_stage`.
- The Bake process no longer writes log files into its working directory: Forge's coremod engine logged to `./logs/` through Forge's own log4j config; the CLI now uses a console-only config (warnings on stderr) unless the caller sets one.

**v1.1 (2026-11-09).** Additive: every v1 request is still valid and gives the v1 result (vanilla/Fabric base, see the compatibility note).
- **Platforms.** `bake-instance` takes an optional `platform` object: `vanilla` (default), `neoforge` (1.21.1, with the Fabric API bridge when Fabric mods are present), `forge` (Forge 1.20.1-47.x, M4). `version` lists the game versions per platform; `requirements` takes an optional `platform` and refuses an unsupported pair (`incompatible_loader`). The state file records the platform; the launch block carries what the platform needs on the class path and in the JVM arguments.
- **Mapping cache.** Compiled mapping graphs live in a mapping cache keyed by tableKey (`<mappingCache>/<mc>/graph/<tableKey>.amap` + `.report.json`). Both bake stages take an optional `mappingCache` directory (default `<cacheRoot>/mappings`); `version` reports the composer and key format.
- **Official mapping tables checked before use.** Every mapping input is checked by sha1 + size (Forge: against the caller's manifest when given, else the loader's bundled requirements table); a file that is missing from it or differs is refused.
- **Forge requirements.** `requirements` for `1.20.1` returns the three official tables the Forge platform needs (Mojang client mappings, Fabric intermediary, Forge MCPConfig) plus the loader libraries.
- **Forge production flag.** `FMLLoader.isProduction()` is `true` (what a player's launch is). `platform.production: false` turns on Forge's development behaviour; for debugging only.
- **Launch-plugin host (Forge).** Mods that add a modlauncher `ILaunchPluginService` at runtime (ModernFix) are hosted by the loader. Nothing for Amber to do; listed so the launcher side knows Forge's own launcher is not involved.
- **Deterministic bake outputs.** The same inputs give byte-identical baked jars (fixed entry times, sorted entries), so cache keys that hash earlier outputs are stable.
- `loaderVersion` 0.3.1. `protocol` stays 1: the cache layout under `cacheRoot` is unchanged, and old caches stay valid.

**Compatibility note for the launcher side.**
- A v1 caller needs no change for the vanilla/Fabric base. New fields in results and the state file can be ignored.
- To use NeoForge or Forge, send `platform` in `bake-instance`, and launch with the returned `launch.classpath` and `launch.jvmArgs` exactly as given. They now contain platform jars, `--add-opens`/`--add-exports` flags and `-Damber.ul.mappingCache=…`. Do not rebuild the class path yourself.
- Check `version.interface >= 1.1` (or `platforms` present) before sending `platform` or `mappingCache`; a v1 loader ignores unknown request fields and would bake the vanilla base.
- Point `mappingCache` at one directory shared by all instances (for example `<amberData>/cache/mappings`). It is local only (Mojang names); never upload or sync it. It may be deleted at any time; graphs are rebuilt (about 1-2 s per game version).
- State files written by v1 have no `platform`: the launcher treats them as vanilla.

## Split of responsibilities

**Amber owns:**
- deciding when each stage runs;
- all downloads, including mappings, the loader's own libraries and the platform install (reusing Amber's mirrors and failover);
- choosing Java;
- UI and progress display;
- instance bake state and invalidation;
- ASCII junctions for Chinese paths;
- deleting caches.

**The loader owns:**
- reading mod metadata;
- dependency resolution;
- mapping/remapping, including the mapping graphs and their cache;
- nested jars;
- Mixin;
- access rules;
- the platform bridges (FML for NeoForge and Forge, the Fabric API bridge on NeoForge);
- file formats and cache keys inside its cache dirs;
- telling Amber which caches are still in use.

## How Amber calls the loader

- The loader ships as the packaged layout (v1.3, below): `loader.jar`, `libs/`, `bridges/`, `manifest.json`. Amber runs `java -cp <manifest.classpath, resolved against the package folder> amber.ul.Bake <stage> --request <request.json>` with a Java of at least `manifest.javaMin`. Each stage is its own process, and Amber can kill it at any time to cancel.
- **All arguments go in a UTF-8 JSON request file, never on the command line.** This avoids code page 1252 corrupting Chinese arguments. Amber converts every path it gives the JVM (including the request file itself) through its ASCII junction first. When Amber is the caller, the loader does not create junctions itself.
- Progress and results go to stdout as JSON lines, one object per line:
  - `{"type":"progress","stage":"bake-mod","step":"remap","done":12,"total":40,"message":"lithium-0.15.4.jar"}`
  - `{"type":"problem","severity":"error|warning","code":"missing_dep","modId":"forgedemo","file":"forgedemo-0.3.0.jar","dependency":{"id":"fabricdemo","range":"[1.0,)","found":"0.9.0"},"message":"…"}`
  - `{"type":"result",…}`: the stage's output; formats are below.
  - `{"type":"error","code":"…","message":"…"}`
- Exit codes: 0 success; 2 dependency problems (the problem lines give the reasons); 1 any other failure.
- Every write goes to a temp file first and is then renamed atomically, so a killed process never leaves a corrupt cache.
- `amber.ul.Bake version` prints (the 0.4.0 package, verbatim):
  ```
  {"type":"result","schema":"amber-ul/version-result@1","loaderVersion":"0.4.0","pipelineVersion":"ul-proto-9","protocol":1,"interface":"1.3","interfaceRevision":"1.3.2",
   "supportedMc":["1.12.2","1.20.1","1.21.1"],
   "platforms":{"vanilla":["1.21.1"],"neoforge":["1.21.1"],"forge":["1.20.1"],"forge-1.12.2":["1.12.2"]},
   "mappingCache":{"composer":"amber-map-1","keyFormat":"amber-tablekey/1"},
   "libs":{"antlr4-runtime":"4.11.1","tomlj":"1.1.1","access-widener":"2.1.0","asm":"9.10.1","asm-analysis":"9.10.1","asm-commons":"9.10.1","asm-tree":"9.10.1","asm-util":"9.10.1","intermediary":"1.21.1-v2","mapping-io":"0.6.1","mixinextras-fabric":"0.5.5","sponge-mixin":"0.17.5+mixin.0.8.7","tiny-remapper":"0.10.4","xz":"1.9","gson":"2.10.1"},
   "javaMin":{"vanilla":21,"neoforge":21,"forge":21,"forge-1.12.2":21},"javaRuntime":21,
   "accepts":[{"platform":"vanilla","mc":"1.21.1","available":true,"accepts":["fabric","quilt"],"missingBridges":[]},
              {"platform":"neoforge","mc":"1.21.1","available":true,"accepts":["neoforge","fabric","quilt","forge"],"missingBridges":[]},
              {"platform":"forge","mc":"1.20.1","available":true,"accepts":["forge","neoforge","fabric","quilt"],"missingBridges":[]},
              {"platform":"forge-1.12.2","mc":"1.12.2","available":true,"accepts":["forge"],"missingBridges":[]}],
   "javaLaunch":{"forge-1.12.2":{"min":8,"max":8}}}
  ```
  `accepts` (v1.3.1) is what this build loads per platform, from the bridges present: use it to decide which mods to offer for an instance.
  When `protocol` changes, Amber should treat old caches as invalid. `interface` changes on additive revisions only.
- Every request and response carries `"schema":"amber-ul/<name>@1"` (for example `amber-ul/bake-mod-request@1`). An unknown schema name is an error. v1.1 keeps the `@1` schemas: its fields are optional additions.
- Errors with their own code: `bad_request`, `unknown_stage`, `stale_state`, `java_too_old` (v1.3), `internal`.
- Problem lines carry a stable `code` field. Amber uses it to hook into its existing mismatch UI and "find a matching version". Codes: `missing_dep`, `version_mismatch`, `incompatible_loader` (also: platform not supported on that game version), `incompatible_mc`, `conflict` (breaks/conflicts), `duplicate_mod`, `nested_unresolved`, `unsupported_feature`, and (loader matrix, added after v1.3, additive) `forge_api_missing` (a Forge 1.21.1 mod on NeoForge uses Forge APIs Amber's compat layer lacks) and `quilt_api_missing` (a Quilt mod uses Quilt APIs Amber lacks); both are errors, the mod is not loaded, and `message` names the first missing references. Also additive: `mixin_lambda_unresolved` (error: on NeoForge, a Fabric/Quilt mixin names a game lambda that the NeoForge-patched class no longer has, and no unique same-shape lambda exists; Mixin would fail at launch) and `mixin_target_changed` (warning: an injector's method or @At point is gone in the patched game and the bake-time fixer could not move it; the launch may fail). See "Bake-time mixin pass" below. Amber should treat an unknown code like `unsupported_feature`. Every problem carries `modId` and `file`; dependency problems also carry `dependency:{id, range, found?}`.

## Preparation stage: requirements (once per game version and platform)

- **Input:** `{mcVersion, side, platform?, versionJson?}`. `platform` is `vanilla` (default), `neoforge` or `forge`.
- **Output:** `{files, javaMin}`. `files` are the files to download, as `[{kind, url, sha1, size, path}]`: Mojang's official mappings (`client_mappings`, url/sha1 from `versionJson`), Fabric intermediary, Forge MCPConfig (Forge 1.20.1), and the loader libraries (`kind: "loader-lib"`; v1.3: generated from the build, with `group`, `artifact`, `version`, `sha256` too; the same jars as the package's `libs/`, so a launcher that ships the package can skip them). `path` is relative to a shared directory Amber chooses; the mapping files go to `mappings/<mc>/`, which is then the `mappingsDir` of the bake stages.
- The platform install itself (NeoForge or Forge installer output: `inst/`, `libraries/`, `platform-cache/`) is not listed; Amber installs it with the platform's official installer and passes its directory as `platform.installDir`.
- Amber downloads them; the loader never goes online.

## Stage 1: download time, `bake-mod` (once per mod file)

- **Input:** `{mcVersion, modJar, sha256?, gameJar, libraries, cacheRoot, mappingsDir, mappingCache?}`. If Amber already has the hash from Modrinth, it passes it in.
- **Does:**
  - reads metadata (Fabric, Quilt, Forge and NeoForge are all supported);
  - extracts nested jars;
  - remaps the mod's names to runtime names, including refmaps and the access widener;
  - pre-scans entrypoints and Mixin configs.
- Platform mods (NeoForge, Forge) are baked together with their platform in `bake-instance` (one class universe with the platform's own jars); `bake-mod` is for the vanilla/Fabric base.
- **Output:** `{key, mods:[{id, version, kind, deps, provides, nested:[…], environment}], bakedJars:[…], warnings}`
- **Cache location:** `<cacheRoot>/baked/…`, content-addressed and shared by all instances.
- **Cache key:** sha256 of the mod jar + loaderVersion + protocol + pipelineVersion + mapping fingerprint + bake context (game jar + libraries). Amber needs no invalidation logic for this stage: a changed key simply means a new file.

## Stage 2: install time, `bake-instance` (when the mod list, game version, platform or Java changes)

- **Input:** `{mcVersion, side, gameJar, libraries:[…], libRoot?, mods:[{path, sha256}], javaMajor, instanceDir, gameDir, cacheRoot, mappingsDir, mappingCache?, platform?}`
  - `platform` (v1.1): `{kind, installDir, mappingsDir?, manifest?, production?}`
    - `kind`: `vanilla` (default, no other fields), `neoforge`, `forge`.
    - `installDir`: the platform install (`inst/version.json`, `libraries/`, `platform-cache/`).
    - Forge only: `mappingsDir` (default: the request's `mappingsDir`) holds the three tables from `requirements`; `manifest` is an optional JSON manifest to check them against (`{"files":{"<mc>/<file>":{sha1,size}}}`), else the bundled requirements table is used; `production` (default `true`).
- **Does:**
  - resolves dependencies across loaders;
  - remaps the game once per game version (and, on Forge, Forge itself and the Forge mods), into the shared cache;
  - patches for this mod set (access rules, loader hooks, platform patches, Forge JS coremods at bake time), stored in the cache.
- **Output:** `{stateKey, resolvedMods:[…], launch:{mainClass:"amber.ul.Launcher", classpath:[…], jvmArgs:[…], gameArgsPrefix:[…], stateFile, javaMin, problemsFile}, stats, cache}`.
  - `cache` (v1.3): `{state: "new" | "rebuilt" | "reused", stateKeyChanged, bakeMisses, newCacheFiles, warm}`. `reused` means the same stateKey as the instance's previous state, no bake misses, and no cache file the previous state did not list. `warm: true` whenever anything was (re)built or the previous state had no `cacheFiles`: run `warm` next. `warm` results carry the same object.
  - A request with `javaMajor` below the platform's `javaMin` is refused: error `java_too_old`, exit 1. Problems come as problem lines and can feed straight into Amber's existing mismatch UI.
  - `launch.classpath` includes the platform jars that must be on the system class path (securejarhandler, JarJarFileSystems); `launch.jvmArgs` includes `-Damber.ul.mappingCache=…`, the platform's `--add-opens`/`--add-exports`, and `-Damber.forge.production=false` only when asked.
- **State file:** `<instanceDir>/.amber-ul/state.json`, recording fingerprints of every input, plus (v1.1) `platform` and `mappingCache`.
- **stateKey:** platform (kind + install dir), mcVersion, game jar hash, sorted mod keys, loaderVersion, javaMajor, side, runtime library versions, bake context.

## Mapping cache (v1.1)

- **Location:** the request's `mappingCache`, else `<cacheRoot>/mappings`. Layout: `<mappingCache>/<mc>/graph/<tableKey>.amap` (the compiled graph, memory-mapped) and `<tableKey>.report.json` (every input with sha1, url and licence; counts; timings; the amap's sha256).
- **tableKey:** sha256 over a canonical text: key format (`amber-tablekey/1`), composer version (`amber-map-1`), each reader step in order (kind, namespace arguments, input sha1), the official game jar's sha1, the library set used for completion (file name + size), and options (for example the Forge runtime column). Anything that changes the graph changes the key.
- **Reuse:** a graph is used only when its report's key and the amap's sha256 both match; anything else (missing, truncated, edited) is rebuilt. Writes are temp file + atomic rename, so concurrent instances are safe.
- **Inputs:** official tables only: Mojang client mappings, Fabric intermediary, NeoForm or Forge MCPConfig. Community name sets (Yarn, Quilt, Parchment, hashed) are not inputs.
- **Licence:** every graph has a Mojang column, so the cache is local only (`"localOnly": true` in the report).
- **Cleanup:** Amber may delete the whole directory at any time.

## Stage 3: pre-launch warm-up, `warm` (v1.2; can run in the background, no game window)

- **Input:** `{stateFile}` (schema `amber-ul/warm-request@1`). The state must be from a v1.2 loader (has `libraries`); an older one is refused with `stale_state`: run `bake-instance` again.
- **Does:** rebuilds the instance request from the state file (game jar, libraries, mods, platform, mapping cache) and runs the same preparation as a launch: discovery, resolve, game remap, mod bakes, platform bakes (Forge/NeoForge jars, Forge JS coremods) and the per-mod-set game bake. Then it builds the mapping graphs those bakes use (or maps them in from the mapping cache). Everything already cached is only verified (hits). It rewrites the state's `cacheFiles`.
- **Output:** `{stateFile, stats:{bakeHits, bakeMisses, cacheFiles, ms}}`. Problems come as problem lines (exit 2 when blocking), as in `bake-instance`.
- **No window, no game:** it never calls the game's main, never initialises LWJGL/GLFW and never loads game classes for running.
- **Can be killed at any time:** every product is written to a temp file and renamed atomically, so a kill leaves only complete entries plus temp files (which `gc` cleans up later). If warm is still running when the user presses Play, Amber may kill it; the launch fills in whatever is missing.
- **When to run:** after `bake-instance`, and again after a launch reported a rebake (a launch-time rebake is not recorded in `cacheFiles`).
- **Not yet:** pre-transforming Mixin targets into the class cache. The class cache is still written during the first launch.

## Stage 4: launch time

- **Launch command:** `java <launch.jvmArgs> -cp <launch.classpath> amber.ul.Launcher --state <stateFile> -- <vanilla game arguments>`
- **Self-check:** at launch the loader checks the state file quickly by file size and modification time only, without re-hashing. If the state is stale, it logs a warning and rebakes inline, so the launch does not fail. Normally Amber's invalidation has already triggered stage 2. The platform and mapping cache come from the state file.
- **Forge platform at runtime (v1.1):** the loader runs Forge's FML through its own bridge (no modlauncher/bootstraplauncher boot). It reports `FMLLoader.isProduction()=true` and hosts launch plugins that mods add at runtime. Forge's update check follows the instance's `config/fml.toml` (`versionCheck`); Amber should set it to `false` for offline launches.
- **Runtime problems (v1.3):** the launch truncates `launch.problemsFile` (`<instanceDir>/.amber-ul/runtime-problems.jsonl`; `-Damber.ul.problemsFile=…` overrides) and writes one JSON object per line, flushed per line, so it can be read while the game runs and after a crash:
  - first line `{"type":"launch","schema":"amber-ul/runtime-problem@1","loaderVersion","interface","time"}`;
  - then `{"type":"problem","schema":"amber-ul/runtime-problem@1","severity":"error|warning|info","code","modId"?,"message",…}` with code-specific string fields:

    | code | severity | fields | meaning |
    | --- | --- | --- | --- |
    | `mixin_incompatible` | warning | `mixin`, `target`, `category`, `providesApi` | a mixin did not apply and was switched off (the mod still runs) |
    | `mixin_semantic_risk` | warning | `mixin` + `handler`, or `option`; `action` (`injector_removed`, `option_disabled`) | a known-risky injector/option was removed by a compat rule |
    | `fabric_api_bridged` | info | `mixins`, `platform` | a Fabric API module's mixins replaced by the platform bridge |
    | `fabric_api_bridge_version` | warning | `detail` | the bridge manifest does not match this module version |
    | `fabric_init_not_run` | error | | Fabric entrypoints never ran (platform registration window missed) |
    | `launch_rebaked` | info | `bakeMisses` | the launch had to bake something: run `warm` again afterwards |
    | dependency codes (`missing_dep`, …) | as in bake | `file`, `dependency`, `range`, `found` | same as the bake's problem lines |

  - The same objects are printed on stdout behind the prefix `@amber-ul/problem ` (the game's logger may put a timestamp in front: search for the prefix within the line). The Chinese log lines (`[amber-ul] 问题 …`) stay for people; don't parse them.
- **Java 25 AOT cache:** Amber decides whether to enable it. It mainly speeds up JDK and library classes. Game and mod classes are loaded by our own class loader, so AOT is not expected to help with them; the loader's class cache covers that part.

## Packaged layout (v1.3)

`python -I tools/package.py` (after `bash build.sh`) writes `build/dist/amber-ul-<loaderVersion>/` and `build/dist/amber-ul-<loaderVersion>.zip` (the same folder, one top-level directory, fixed timestamps):

```
amber-ul-0.4.0/
  loader.jar          the loader
  libs/*.jar          its libraries (unmodified official jars; manifest.libs gives Maven coordinates + url)
  bridges/            fmlbridge.jar, fabricbridge.jar (NeoForge 1.21.1), fmlbridge-forge.jar, fabricbridge-forge.jar
                      (Forge 1.20.1), forgecompat.jar (Forge 1.21.1 mods on NeoForge),
                      fmlbridge-1.12.2.jar (Forge 1.12.2: the core-legacy runtime and transformer cache, Java 8 bytecode)
  manifest.json
```

`manifest.json` (`schema: amber-ul/package@1`): `loaderVersion`, `pipelineVersion`, `protocol`, `interface`, `interfaceRevision` (v1.3.1), `platforms`, `accepts` (v1.3.1, as in `version`), `javaMin` (per platform), `entry: {bake: "amber.ul.Bake", launch: "amber.ul.Launcher"}`, `classpath` (relative paths, in order: `loader.jar` then `libs/…`), `bridges` (name → path), `libs` (`group`, `artifact`, `version`, `url`, `sha1`, `sha256`, `size`, `path`), `files` (every file except the manifest → `sha256`, `sha1`, `size`). The version fields are read from the packaged loader itself (`Bake version` run from the package).

For Amber: verify every `files` entry, run the stages with `-cp` = `classpath` resolved against the folder, and pass the launch block on unchanged (its class path already points into the folder). The package contains no Mojang, NeoForge or Forge files; it may be shipped or downloaded as is. The folder must not be moved while an instance's launch block points into it (re-run `bake-instance` after moving).

## Invalidation and cleanup

- **Amber's instance bake state:** stores the stateKey and every input. If the mods, game version, platform, Java major version or loaderVersion change, Amber reruns stage 2 and then stage 3.
- **Cleanup:** the `gc` stage (v1.2), below.

## Cleanup: `gc` (v1.2)

- **Input:** `{cacheRoot, liveStateFiles:[…], mappingCache?, apply?, allowNoLiveStates?, tmpMaxAgeHours?}` (schema `amber-ul/gc-request@1`).
  - `liveStateFiles`: the state file of **every** instance that uses this cache root. Anything a missing instance uses would be listed as unreferenced, so pass all of them.
  - `apply` (default `false`): dry run unless `true`.
  - `allowNoLiveStates` (default `false`): an empty `liveStateFiles` is refused unless this is `true` (it means "everything in the managed parts goes").
  - `mappingCache`: also check the graphs in this mapping cache (only `<mc>/graph/*`). Pass it only when every instance that shares it is in `liveStateFiles`.
  - `tmpMaxAgeHours` (default 1): temp files left by a killed bake are removable once older than this.
- **Scope, and what is never touched:**
  - Only regular files under `<cacheRoot>/baked`, `<cacheRoot>/nested`, `<cacheRoot>/mappings` and the given mapping cache's graphs are candidates. The class cache, `changed-classes*.txt` and the Mixin isolation data are not.
  - Links and junctions are not followed; anything whose real path leaves the root is skipped. A top-level directory, or a directory with none of `baked/`, `nested/`, `mappings/`, is refused as a cache root.
  - A file is kept when a live state lists it in `cacheFiles`, lies under a listed directory (Forge mod bakes are one directory per mod set), or is a sidecar of a listed file (`x.jar.srg.txt`). Fresh temp files are kept.
- **Refusals (exit 1, nothing done):** `cacheRoot` missing or not a cache root; a live state file that does not exist; a live state without `cacheFiles` (`stale_state`: run `warm` on it first); an empty live list without `allowNoLiveStates`.
- **Output:** `{apply, cacheRoot, liveStates, liveFiles, kept, skipped, removable, removableBytes, remove:[{path, bytes, reason}]}`; `reason` is `unreferenced` or `stale temp`. With `apply`: also `removed` and `failed` (exit 1 if any delete failed), and empty directories are removed.
- **Notes for Amber:** a `bake-mod` result (stage 1) is unreferenced until an instance uses it, so run `gc` after the instances' `bake-instance`/`warm`, not between download and install. Show the dry-run list (or its totals) before applying. Do not run `gc` while an instance using the cache is baking or launching.


## Current status (2026-11-10)

- Base: real 1.21.1 vanilla and Fabric mod sets (Fabric API, Mod Menu, Lithium, FerriteCore …) in world.
- NeoForge 1.21.1 platform with Amber's Fabric API bridge: all no-download Fabric API modules work on NeoForge; census on the bridge test set has 0 isolated mixins.
- Forge 1.20.1 platform: a 36-mod corpus (Create, Quark, ModernFix …) in world; census 0 failures over 674 classes.
- Mapping engine: Amber's own remapper is byte-identical to NeoForge's ART and Forge's FART on their reference jars; mapping graphs cached by tableKey.
- CLI stages implemented: `version`, `requirements`, `bake-mod`, `bake-instance`, `warm`, `gc`; packaged layout (`tools/package.py`).
- Check: `python -I tools/bake_cli_test.py [--package]` (v1.3: version/requirements against the real class path, rebuilt/reused, `java_too_old`, runtime problems file; `--package` runs everything from the packaged folder; v1 request on the vanilla base + a Forge 1.20.1 platform bake launched from the returned launch block; warm on both; gc refusals, dry runs, and an apply on the test cache only, followed by a launch).


## v1.3.2: forge-1.12.2 (additive, loader 0.4.0)

- New platform `forge-1.12.2` for Minecraft 1.12.2 with Forge 14.23.5.x. `accepts` lists it as available when the bridge `fmlbridge-1.12.2.jar` is present (`accepts: ["forge"]`).
- `version` adds `javaLaunch: {"forge-1.12.2": {"min": 8, "max": 8}}`: the bake stages still run on the loader's Java 21 (`javaMin` 21), but the game must be launched on Java 8. `requirements` repeats `javaLaunch` for that platform, and the launch block carries `javaMin: 8, javaMax: 8`.
- Older consumers: a launcher written for v1.3.1 or earlier looks Forge up under the platform key `forge` (1.20.1 only) and never under `forge-1.12.2`, so it keeps reporting a 1.12.2 Forge instance as unsupported. `javaMin` (21) is about the bake stages only; a consumer that launches forge-1.12.2 must read `javaLaunch` (or the launch block's `javaMin`/`javaMax`).
- `requirements` (mcVersion 1.12.2): no mapping files; only the loader libraries (for the bake stages).
- `bake-instance` with `platform: {kind: "forge-1.12.2", installDir}`. installDir holds Forge's `version.json` (from the installer) and a Maven-layout `libraries/` with every library it lists, the universal jar included. The profile is checked: `mainClass` must be `net.minecraft.launchwrapper.Launch`, it must be for 1.12.2 (`inheritsFrom` or `id`) and list `net.minecraftforge:forge:1.12.2-14.23.5.*`; every library path must stay inside `libraries/` (no absolute paths, drive letters or `..`), and a library `name` must be `group:artifact:version[:classifier]`; otherwise `bad_platform`. `gameJar` must be the 1.12.2 client jar (sha1 checked; otherwise `bad_request`). Pass the vanilla 1.12.2 libraries in `libraries` (and `libRoot`, so Forge's newer copies replace vanilla's). Nothing is baked ahead: mods are checked (legacy `@Mod` / `mcmod.info` / coremod metadata; missing required mods and duplicate ids are errors, version-range mismatches are warnings because FML enforces them at launch; mods a jar ships through its manifest's `ContainedDeps` (nested jars at the root or under `META-INF/libraries/`, which FML extracts at launch) count as present; `@Mod` classes in a coremod jar count only when its manifest has `FMLCorePluginContainsFMLMod`, as in FML), and the launch block runs `amber.ul.legacy.LegacyLaunch` with the transformer cache under `<instanceDir>/.amber-ul/legacy-1.12.2/<key>` (named as `launch.cache.transformerCache` and `cache.transformerCache`; per instance because the game JVM writes it while running, so `cacheRoot` stays read-only at launch). `version.json` may omit libraries marked `"clientreq": false` (server-only, old-style profiles); entries carrying that flag are skipped, every other listed library must be present. The launcher must put the instance's mod jars in `<gameDir>/mods` (FML 1.12 scans only there; `launch.modsDir`) and extract natives as for vanilla 1.12.2.
- Transformer cache switch: `transformerCache: false` in the bake-instance request leaves the cache out of the launch block (`launch.cache.enabled: false`, no `-Damber.ul.legacy.cache`), and the game starts exactly like the official Forge profile. Default is on (`launch.cache.enabled: true`). If the cache cannot be set up at launch (directory not writable, a bridge without the cache part, a corrupt file), the game starts on the stock LaunchWrapper class loader and a `legacy_cache_disabled` warning is reported. A launcher that sees a crash it suspects is cache related should offer to relaunch with the cache off, or delete `launch.cache.transformerCache` (it is rebuilt on the next start).
- Cache key: the cache keys itself on the class path and the contents of `mods/` and `config/`. Files the game itself writes while loading (default configs, registry dumps, jars extracted to `mods/memory_repo`) are recorded as aliases of the run's key; recording stops when FML reaches its AVAILABLE state (main menu), so a config changed later, in game or by hand, gives a new key on the next start. `-Damber.ul.legacy.cacheDebug=true` (diagnostics only) also writes `stats.txt` and `env-<key>.txt` next to the cache.
- FML's own jar checks stay on: the launch block sets no `fml.ignoreInvalidMinecraftCertificates` / `fml.ignorePatchDiscrepancies`.
- `warm` answers `not_supported` for a forge-1.12.2 state: the transformer cache fills while the game runs (first launch cold, later launches warm).
- `gc`: a live forge-1.12.2 state has no `cacheFiles` and is accepted as is (nothing of it lives under `cacheRoot`); `legacy-1.12.2/` under `cacheRoot` (an earlier prototype layout) is managed and never live.
- Problem codes: dependency errors use `missing_dep` on every platform (Amber should still accept the older `missing_dependency`); `bad_platform` (error) means `platform.installDir` is not a usable Forge 1.12.2 install (no `version.json`, not a LaunchWrapper profile, listed libraries missing).
- Runtime problems: the launch block passes `-Damber.ul.problemsFile` (also `launch.problemsFile`) and `-Damber.ul.loaderVersion`; `LegacyLaunch` writes the usual launch line and `@amber-ul/problem` lines for what fails before the game takes over: `legacy_java_unsupported` (error; `javaVersion`, `required`), `legacy_launchwrapper_missing` (error), `legacy_launch_failed` (error; `exception`, `rootCause`?, `log`; when LaunchWrapper itself caught the failure the cause is only in `logs/latest.log`), `legacy_cache_disabled` (warning; `cacheDir`; the launch goes on without the cache). Later failures are FML's own crash reports. Java 8 writes stdout in the platform code page, so `@amber-ul/problem` lines (and the problems file) are pure ASCII: anything outside 0x20-0x7E is a JSON `\uXXXX` escape.
- Package: `manifest.json` carries `javaLaunch`; `bridges/fmlbridge-1.12.2.jar` is the core-legacy runtime, Java 8 bytecode throughout (checked when packaging).

## v1.3.3: quick play (additive)

`version` reports `"interfaceRevision":"1.3.3"`. A launcher that does not send the new field gets exactly the v1.3.2 behaviour.

- `bake-instance` takes an optional `quickPlay`, exactly one of:
  ```
  "quickPlay": {"singleplayer": "<world folder>"}
  "quickPlay": {"multiplayer": "<host>[:<port>]"}
  ```
  - `singleplayer`: the folder name under `<gameDir>/saves/` (`gameDir` from the request, else `instanceDir`). One path segment: no `/`, `\`, `:` or control characters, and not `.` or `..`. The world must already exist (`level.dat`); the bake only reads it and never creates a world.
  - `multiplayer`: a host name or IPv4 address with an optional `:port` (1-65535, default 25565), or `[IPv6]:port`.
  - Anything else (both keys, neither, an extra key, a missing world, a bad port, a bare IPv6 address) is `{"type":"error","code":"bad_request"}`, exit 1, before anything is baked.
  - `quickPlay` is not part of `stateKey`: a bake that only changes it reuses every cached file.
- The launch block then carries `quickPlay` (echo, for the UI) and the arguments that do it:
  ```
  "launch": {..., "quickPlay": {"singleplayer": "<world folder>", "via": "game" | "amber-ul"}, ...}
  "launch": {..., "quickPlay": {"multiplayer": "<host>:<port>", "via": "game"}, ...}
  ```
  `via: "game"` = the game's own quick play arguments; `via: "amber-ul"` = the loader does it (Forge 1.12.2 singleplayer). Launch exactly as before (`jvmArgs`, `classpath`, `mainClass`, `gameArgsPrefix` then the launcher's own game arguments); there is nothing extra to append.

  | platform | singleplayer | multiplayer |
  |---|---|---|
  | vanilla / NeoForge 1.21.1, Forge 1.20.1 | `gameArgsPrefix` ends with `--quickPlaySingleplayer <world>` | `gameArgsPrefix` ends with `--quickPlayMultiplayer <host>:<port>` |
  | forge-1.12.2 | `jvmArgs` has `-Damber.ul.legacy.quickPlay=<world>`; `gameArgsPrefix` stays `[]` | `gameArgsPrefix` is `["--server", host, "--port", port]` |

- On 1.20.1 / 1.21.1 the title screen is never shown. The loader's "title screen reached" moment (class cache save, `fabric_init_not_run` check) fires when the game's quick play starts the join (`QuickPlay.joinSingleplayerWorld` / `joinMultiplayerWorld`, after the first resource reload). If the world or server cannot be reached the game shows its own error screen and returns to the menu.
- On Forge 1.12.2 singleplayer, core-legacy waits until FML has finished loading and the main menu is up. That is vanilla's `GuiMainMenu`, a subclass, or a replacement such as CustomMainMenu's (RLCraft). It then opens the world on the client thread, with the same call the world list uses. FML's own questions (missing mods, an old save) still appear. If the menu never comes up (default wait 30 minutes, `-Damber.ul.legacy.quickPlayWaitMs`) or the world fails to open, the game stays on the menu and core-legacy reports `legacy_quickplay_failed` as a runtime problem (warning or error; `world`).
- Everything runs inside the normal game client (its integrated server for singleplayer). Nothing is written by the bake; the game writes only its own files in `gameDir`.

## v1.3.3: maintenance mode (additive)

A launch of the normal game client that prepares one singleplayer world and then quits by itself. It never starts a dedicated server and never reads or writes `eula.txt`.

- `bake-instance` takes an optional `maintenance` (side `client` only, not together with `quickPlay`):
  ```
  "maintenance": {"world": "<world folder>", "upgrade": true, "pregenRadius": 32}
  ```
  - `world`: a folder under `<gameDir>/saves/`, with the same rules as `quickPlay.singleplayer`. It must exist. The bake reads its `level.dat` (`Data.DataVersion`) and writes nothing.
  - `upgrade` (default `true`): on 1.20.1 / 1.21.1, a world last saved by an older game version first goes through the game's own world upgrader, the "Optimize World" button of the world list (`OptimizeWorldScreen` / `WorldUpgrader`, cache not erased). `false`: the game opens it as it would any old world.
  - `pregenRadius` (default 0, at most 512): overworld chunks around the world spawn to generate on top of the spawn area. The spawn area is always generated: 8 chunks around the world spawn (17 × 17).
  - `bad_request` (exit 1, before anything is baked): an unknown key, a missing world, a world folder that is not one path segment, a world saved by a newer game, `upgrade` that is not a boolean, a radius that is not a whole number 0-512, `quickPlay` in the same request, side `server`.
  - Not part of `stateKey`: a bake that only adds or changes `maintenance` reuses every cached file.
- The launch block then carries `launch.maintenance` and the `-Damber.ul.maintenance*` JVM properties that make it happen (nothing extra to append):
  ```
  "maintenance": {"world": "...", "upgrade": "needed" | "not needed" | "off" | "game", "worldDataVersion": 3465, "gameDataVersion": 3955,
                  "spawnRadius": 8, "pregenRadius": 32, "progressFile": "<world>/amber-ul-pregen.json", "cancelFile": "<instanceDir>/.amber-ul/maintenance.cancel"}
  ```
- What the launch does, all inside the client and its integrated server, through the game's own code:
  1. at the first title screen: the upgrade, if needed (the game's own progress screen is shown);
  2. the world is opened, as a click in the world list would;
  3. the overworld chunks in a square spiral around the world spawn are loaded through the server's own chunk cache (generated if missing): first the spawn area, then out to `pregenRadius`;
  4. the world is saved and closed ("Save and Quit to Title"), and the game exits with code 0.
- Progress: one JSON object per line on stdout behind `@amber-ul/progress ` (about once a second while a step runs):
  ```
  {"type":"progress","schema":"amber-ul/maintenance-progress@1","stage":"maintenance","step":"<step>","done":n,"total":m,"world":"...","message"?:"..."}
  ```
  steps in order: `start`, `upgrade` (`done`/`total` = chunks, `message` = the game's status text; the last one has `message` `"upgraded"`), `open` (0/1, then 1/1), `resume` (when an earlier run's place is picked up), `spawn` (chunks of the spawn area), `pregen` (chunks of the whole square, spawn area included), then `done`, `cancelled` or `failed`, and last `quit`.
- Resumable: after each forced save (every 30 s or 4096 chunks, and at the end or on cancel), the place in the spiral is written to `progressFile` (`{"schema":"amber-ul/pregen@1","dimension":"minecraft:overworld","centerX","centerZ","radius","done","total","complete"}`; chunks are flushed to disk before the index). A later maintenance run of the same world with the same spawn continues from there. A bigger radius continues the same spiral. Closing the game or killing it loses at most the work since the last save. The upgrade itself is the game's and is not resumable: a cancelled upgrade starts again.
- Cancel: create `cancelFile` (any content). The run stops at the next batch, saves its place, reports `cancelled` and quits normally. A cancelled upgrade leaves the world unopened. A stale cancel file is deleted when maintenance starts. Closing the game window also stops it.
- Failures: `maintenance_failed` (error; `world`) in the runtime problems, a `failed` progress line, then the game quits.
- Forge 1.12.2: core-legacy does the same steps once FML has finished and the main menu is up (vanilla's or a replacement, as for quick play). There is no separate upgrade step (`"upgrade": "game"`): the 1.12.2 game converts older saves itself while it loads them, as it always does. Chunks are loaded through `ChunkProviderServer.provideChunk` and queued for unloading right after (the spawn area stays loaded, as in the game), and saved with `saveAllChunks` + `flushToDisk`. The progress lines, the progress file and the cancel file are the same. The progress file is shared between versions: the same spiral order is used on every version.
- `upgrade: false` on an older world: the game asks its own "back up first?" question when the world is opened, and a person has to answer it. The run waits for that (up to 30 minutes, `-Damber.ul.maintenance.openWaitMs`).
