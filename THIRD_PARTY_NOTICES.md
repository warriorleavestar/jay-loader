# Third-party notices

Jay Loader's own code is under the PolyForm Noncommercial License 1.0.0 (`LICENSE`), with the Jay Loader Commercial Use Exception (`COMMERCIAL-EXCEPTION.md`), except for the files listed in section 1, which are under the Apache License 2.0. This now covers every version of Jay Loader, including versions first released under the MIT License; anyone who already received a copy of an earlier version under MIT keeps the MIT rights to that copy.

## 1. Code in this repository derived from, or compatible with, other projects

### Fabric API (Apache License 2.0)
Fabric API is Copyright (c) FabricMC and contributors, https://github.com/FabricMC/fabric.

The files under `fabricbridge/impl/` and `fabricbridge-forge/impl/` are re-based versions of Fabric API `impl` classes, and one `api` interface (`CustomIngredient`). They keep the official classes' names and binary surface, and part of their structure and code. They were modified to run on NeoForge 1.21.1 and Forge 1.20.1. Each file says so in its header, and each is licensed under the Apache License 2.0 (`licenses/Apache-2.0.txt`). Fabric API is not bundled.

Everything else that refers to Fabric API is Jay Loader's own code:
- the bridge mixins and adapters under `fabricbridge/src` and `fabricbridge-forge/src`;
- the bridge manifests under `core/res/amber-ul/bridge/`, which list Fabric API mixin and class names.

### Fabric Loader and Quilt Loader / QSL (Apache License 2.0)
`shims/src/net/fabricmc/` and `shims/src/org/quiltmc/` declare a small subset of the Fabric Loader and Quilt/QSL APIs, with the same names and signatures, so that mods compiled against them link. Their implementations are Jay Loader's own. They are licensed under the Apache License 2.0, like the APIs they follow.

Some behaviour of the Mixin bootstrap follows Fabric Loader (Apache License 2.0), but none of its code was copied:
- the Mixin phase switch;
- the `fabric-compat` decoration table of Fabric Loader versions to Mixin compatibility levels.

### Forge and NeoForge (LGPL 2.1)
Nothing is copied from Forge or NeoForge, and nothing of them is bundled.
- `forgecompat/` declares a few Forge 1.21.1 API types under `amber.forgecompat` with Forge-compatible names and signatures. Its implementation is Jay Loader's own code, written against NeoForge's public API.
- The FML bridges (`fmlbridge/`, `fmlbridge-forge/`) call FML's public classes, which come from the user's own Forge/NeoForge installation at run time.
- Forge's JS coremods engine and Nashorn are not bundled. On the Forge 1.20.1 platform the coremods engine is loaded from the user's Forge installation.

### Minecraft Forge 1.12.2 and LaunchWrapper (forge-1.12.2 platform)
Nothing of Forge 1.12.2 or of Mojang's LaunchWrapper is copied or bundled; both come from the user's own Forge 1.12.2 installation at run time.
- `core-legacy/` (packaged as `bridges/fmlbridge-1.12.2.jar`) is Jay Loader's own code. `CachingLaunchClassLoader` subclasses LaunchWrapper's `LaunchClassLoader`, is compiled against launchwrapper 1.12, and reads its private state by reflection; its class-loading behaviour follows `LaunchClassLoader.findClass`, but no code was copied.
- `core/src/amber/ul/meta/LegacyForgeMeta.java` reads Forge 1.12 mod metadata (`@Mod`, `mcmod.info`, coremod manifest attributes, FML dependency strings) with its own parser.
- `legacytest/` is a test-only mod that refers to Minecraft classes by their SRG names, as 1.12 mods do. It is never packaged.

### Minecraft
Nothing from Minecraft is in this repository or in the package: no game jars, mapping files or patched/binpatched jars. The source refers to Minecraft classes by their official (Mojang) names, as mods do. Mojang's mappings are downloaded by the user's launcher at bake time, under Mojang's own terms.

## 2. Libraries bundled in the package (`libs/` in the release zip, unmodified)

| Library | Version | Licence | Text |
|---|---|---|---|
| ASM (`asm`, `asm-analysis`, `asm-commons`, `asm-tree`, `asm-util`), OW2 | 9.10.1 | BSD-3-Clause | `licenses/ASM-BSD-3-Clause.txt` |
| ANTLR 4 runtime | 4.11.1 | BSD-3-Clause | `licenses/ANTLR-BSD-3-Clause.txt` |
| TomlJ | 1.1.1 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| Gson, Google | 2.10.1 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| access-widener, FabricMC | 2.1.0 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| mapping-io, FabricMC | 0.6.1 | Apache-2.0 | `licenses/Apache-2.0.txt` |
| tiny-remapper, FabricMC | 0.10.4 | LGPL-3.0 | `licenses/LGPL-3.0.txt` (with `licenses/GPL-3.0.txt`) |
| Mixin (Fabric's fork, `sponge-mixin`), SpongePowered and FabricMC | 0.17.5+mixin.0.8.7 | MIT | `licenses/Mixin-MIT.txt` (from the jar) |
| MixinExtras, LlamaLad7 | 0.5.5 | MIT | `licenses/MixinExtras-MIT.txt` (from the jar) |
| Intermediary mappings 1.21.1, FabricMC | 1.21.1 (v2) | CC0-1.0 | `licenses/CC0-1.0.txt` |
| XZ for Java, Tukaani | 1.9 | Public domain | `licenses/XZ-for-Java-COPYING.txt` |

Exact Maven coordinates, URLs and sha256 are in `libs.lock.json` and in the package's `manifest.json`.

tiny-remapper (LGPL-3.0) ships as its own unmodified jar. Its source is at https://github.com/FabricMC/tiny-remapper, and it can be replaced with a compatible build by swapping `libs/tiny-remapper-*.jar`.

The licence texts in `licenses/` were fetched from the licensors' and projects' official sources. `licenses/sources.json` lists the URL and sha256 of each (`tools/fetch_license_texts.py`).
