# Phase 3 recon: NeoForge 21.1.265 (MC 1.21.1)

Date: 2026-11-04

## What was downloaded (mc/neoforge/, never into .minecraft)

- `neoforge-21.1.265-installer.jar`, extracted to `inst/`: `install_profile.json`, `version.json`, `data/client.lzma`, `data/server.lzma`.
- All 117 libraries listed in version.json and install_profile.json, sha1-verified, in `mc/neoforge/libraries/` (26 MB). This includes:
  - the universal jar
  - FML 4.0.45 loader and earlydisplay
  - bus 8.0.5, coremods 7.0.3, modlauncher 11.0.5, securejarhandler 3.0.8, bootstraplauncher 2.0.2
  - accesstransformers 10.0.1
  - **sponge-mixin 0.16.4+mixin.0.8.7 (Fabric's fork)**
  - night-config 3.8.3, JarJar 0.4.1, maven-artifact 3.8.5, ASM 9.10.1
  - the processors: installertools, jarsplitter, AutoRenamingTool, binarypatcher

Launch shape from version.json:
- main class `cpw.mods.bootstraplauncher.BootstrapLauncher`;
- a module path holding bootstraplauncher, securejarhandler, ASM and JarJarFileSystems;
- `--launchTarget forgeclient --fml.neoForgeVersion 21.1.265 --fml.fmlVersion 4.0.45 --fml.mcVersion 1.21.1 --fml.neoFormVersion 20240808.144430`.

## Installer client processor chain (install_profile.json)

1. `installertools MCP_DATA`: extracts the mappings from the neoform zip.
2. `DOWNLOAD_MOJMAPS`: fetches Mojang's client mappings. These are the same mappings we already have.
3. `MERGE_MAPPING`: neoform + mojmap, `--reverse-right`.
4. `jarsplitter`: splits client.jar into a slim jar (classes) and an extra jar (resources), using the merged mappings.
5. `AutoRenamingTool 2.0.3`: renames the slim jar to Mojmap with `--ann-fix --ids-fix --src-fix --record-fix`.
6. `binarypatcher 2.1.2`: applies `client.lzma` and writes the PATCHED jar (`net.neoforged:neoforge:21.1.265:client`).

## Binpatch format (verified by reading the file)

- `client.lzma` is the LZMA "alone" format. It decompresses to a jar of 1615 `*.binpatch` entries.
- Each entry contains, in order:
  - a version byte (1);
  - the obf name and the srg name (Java `writeUTF` strings);
  - an `exists` byte;
  - if `exists` is set, the **Adler32 checksum of the base class bytes**;
  - the patch length and a **GDIFF v4** patch (magic `D1 FF D1 FF 04`).
- There are 8 patches with `exists=0`: new classes created by the patch.
- **Checksum test against our tiny-remapper Mojmap jar: 0 of 1607 match.** The patches only apply on top of AutoRenamingTool's exact output. A GDIFF applier is easy to write in our own code (COPY/DATA commands), but its input must be ART's byte-exact output.

Options:
- (a) Run ART + binarypatcher (the official processors) once per MC/NeoForge version at bake time, and cache the PATCHED jar in our cache. This is what every launcher (PCL, HMCL, the vanilla launcher's installer) does.
- (b) Reimplement ART byte-for-byte: same ASM version, same visitor chain and fixes. This is high risk.

## Bytecode scan of neoforge-21.1.265-universal.jar (tools/ScanApi, raw lists in mc/runs/nf-scan/)

| Bucket | Distinct refs | Main owners |
|---|---|---|
| FML public API (`net/neoforged/fml/**` without `loading`) | 137 | ModList, ModConfig/ConfigTracker/ModConfigs, ModLoadingIssue, ModLoader, VersionChecker, ModContainer, enumextension, earlydisplay, FMLTranslations/I18nManager, ModLoadingContext, ModWorkManager, LogicalSide, IExtensionPoint, CrashReportCallables, RuntimeDistCleaner, ObfuscationReflectionHelper |
| FML loading internals (`net/neoforged/fml/loading/**`) | 26 | FMLLoader(5), FMLPaths(4), StartupNotificationManager(3), FMLEnvironment(2), LoadingModList(2), VersionInfo(2), ProgressMeter(2), FMLConfig, ImmediateWindowHandler, JarVersionLookupHandler, StringUtils, TopologicalSort |
| neoforgespi | 29 | IModInfo / IModFileInfo / IModFile / IConfigurable / ModFileScanData |
| modlauncher | 9 | `Launcher.INSTANCE.environment().findLaunchPlugin`, ITransformer/TargetType (for coremods), TransformingThrowablePatternConverter |
| securejarhandler | 1 | `SecureJar.getRootPath()` (via `IModFile.getSecureJar`) |
| bus (`net/neoforged/bus/**`) | 15 | IEventBus, EventPriority, SubscribeEvent, Event / ICancellableEvent |
| JPMS (`java/lang/Module`, `ModuleLayer`) | 0 | none |

Implication: the FML **public** API dominates and is plain Java. The loading internals NeoForge touches are a short list of static facades (FMLLoader, FMLPaths, FMLEnvironment, LoadingModList, VersionInfo) plus the early-window/progress hooks. Only those need our own implementation or state injection. NeoForge itself never calls JPMS.

## Reuse vs reimplement (first estimate; to be confirmed when it runs)

| Piece | Plan | Why |
|---|---|---|
| `net.neoforged:bus` 8.0.5 | reuse unchanged (to confirm) | standalone event bus; NeoForge mods only see IEventBus |
| night-config, maven-artifact, typetools, JarJar metadata | reuse | plain libraries |
| FML loader jar: `net.neoforged.fml.*` public API (ModList, ModContainer, ModLoader, config, events, i18n) | reuse unchanged on the single class loader, fed by our discovery | NeoForge depends on the concrete classes |
| FML loading internals (FMLLoader, FMLPaths, FMLEnvironment, LoadingModList, VersionInfo) | reuse the classes but populate their static state ourselves, without running FML's discovery/launch handlers | these are facades read by NeoForge |
| modlauncher / bootstraplauncher / securejarhandler / JarJarFileSystems | do not run; small stub `Launcher.INSTANCE` + `SecureJar` adapter | our single class loader replaces the module layers |
| coremods (JS via nashorn) | stub at first (NeoForge 21.1 ships few or none; to check) | needs the modlauncher transformer API |
| AccessTransformers (NeoForge's own AT file) | apply at game bake time with our AccessRules (AT parser exists) | the same mechanism as for Fabric AWs |
| earlydisplay | skip (no early window) | cosmetic |
| sponge-mixin 0.16.4 (NeoForge's) vs our 0.15.4 | pick one copy; probably upgrade the loader to 0.16.4 for both personalities | one class loader, one Mixin |

## Blocker

Running the installer's processor jars (installertools, AutoRenamingTool, binarypatcher) from the prototype was **blocked by the session's permission policy** ("code from external"). Milestone 3a needs either explicit user permission to run them (in the prototype dir only), or a byte-exact reimplementation of ART.
