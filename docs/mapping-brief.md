# Amber Mapping Layer: Implementation Brief (fact-checked 2026-11-05)

> Design brief (2026-11-05), researched and fact-checked.
>
> How to read it against the project's direction (our own mapping engine, faster and more capable, integrating the others):
> - §4.1 recommends mapping-io + tiny-remapper as the applier. Amber writes its own engine instead. tiny-remapper and ART are the **behaviour oracles**:
>   - Output must equal ART byte-for-byte on the NeoForge base jar.
>   - Output must equal tiny-remapper on the golden mod set, with every diff explained.
> - mapping-io is Apache-2.0, so it may be linked for parsers; our own readers/writers are fine too.
> - These stay ours: AmberMappingTree (obf pivot, holes, hierarchy completion), the one-pass parallel remapper, the .amap mmap format, and the reflection/refmap/AW/AT/MixinExtras passes.
> - Licence: R1/R2 in §8 agree with mc/mappings-cache/manifest.json.
>   - NeoForm/MCPConfig are local-only because of their POMs.
>   - Anything with Mojang names is local-only.
>   - CC0 sets may ship.
> - §0 corrects earlier assumptions:
>   - NeoForge has used Mojmap since 1.20.2, Forge since 1.20.6.
>   - 26.1+ is unobfuscated, so the layer is the identity there and must be gated per version.

Unreachable from the cloud (robots): maven.quiltmc.org, maven.parchmentmc.org, ldtteam.jfrog.io. Claims about them are marked (unverified). Those files were fetched locally (see the manifest), so trust the local checksums. Everything else not marked was checked against the named source.

## 0. Corrections to current assumptions
1. NeoForge has used Mojmap at runtime since 1.20.2, not 1.20.5.
   - Release post neoforged/websites content/news/20.2release.md: "We are now using MojMaps everywhere."
   - NeoForm obfuscated-branch versions/release/1.20.2/joined.tsrg reads `tsrg2 obf srg id` / `a a 252363` / `\ta a 252495`, so srg = obf.
   - The NeoForge 1.20.1 AT has `public net.minecraft.client.Minecraft f_90987_ # textureManager`. The 1.20.2 AT has `... textureManager # textureManager`.
   - NeoForge CoreMods ASMAPI.mapMethod/mapField are `return name;`.
   - NeoForge 1.20.1 (47.1.x, a Forge fork) is the only NeoForge version with SRG at runtime.
2. MinecraftForge has used Mojmap since 1.20.6.
   - Branch 1.19.x references MC_SRG.
   - Branches 1.20.1 and 1.20.4 build MC_SRG via `MERGE_MAPPING --classes --reverse-right`.
   - Branches 1.20.x (MC 1.20.6, MCP 20240429.135109) and 1.21.1 (MCP 20240808.132146) build MC_OFF:
     - `DOWNLOAD_MOJMAPS --sanitize`
     - then ForgeAutoRenamingTool 1.0.6 `--names {MOJMAPS} --ann-fix --ids-fix --src-fix --record-fix --strip-sigs --reverse`.
   - The 1.20.6 installer still runs MCP_DATA --key mappings but does not feed it to the renamer. 1.21.1 has no MCP_DATA step.
3. Minecraft is unobfuscated from 26.1.
   - Mojang post 29 Oct 2025 (minecraft.net/en-us/article/removing-obfuscation-in-java-edition): "No more obfuscation maps in version .jsons".
     - The post does not name 1.21.11. "1.21.11 is the last obfuscated release" is an inference.
   - The 26.1 version json has only client/server under downloads; releaseTime 2026-03-24.
   - MCPConfig versions/release/26.1/config.json has "obfuscated": false and no joined.tsrg. The mcp_config maven still has 26.x zips (e.g. 26.1-20260324.123823).
   - NeoForm branches: main, 26.1.x, release/26.2, unobfuscated, obfuscated, 1.20.2-dev … 1.21.11-dev.
   - Fabric side:
     - The intermediary repo stops at 1.21.11.tiny.
     - meta.fabricmc.net lists 26.x entries (e.g. intermediary:26.4-snapshot-7). Treat them as placeholders with no artifact (unverified).
     - Yarn latest is 1.21.11+build.6. The newest QM branch is 1.21.11.
   - Loom: RELEASE_TIME_1_21_11_UNOBFUSCATED_SNAPSHOTS = 2025-11-01T00:00:00+00:00.
     - A version counts as unobfuscated if it is at or after that time and its downloads has no client_mappings.
     - Manifest key Fabric-Mapping-Namespace exists (its value official on 26.x is unverified).
   - Fabric Loader reads no per-mod namespace. getDefaultModDistributionNamespace() is official if the runtime namespace is official, else intermediary (overridable by system property).
   - So for 26.1+ the layer is the identity. Gate it per version.

## 1. Goal and design
- One mapping graph per MC version. Namespaces are nodes; tables are edges.
- The pivot:
  - Every table is re-keyed onto one pivot: the obf names of that version's vanilla jar (identified by client jar SHA-1).
  - Composed edges are generated locally by joining on obf name + obf descriptor. Never join on Mojang names.
  - Pre-1.3: clientOfficial, serverOfficial and Babric glue are separate pivots.
- Runtime target:
  - Mojmap for 1.14.4–1.21.11; on 26.1+ official already is Mojmap.
  - Exception: Forge 1.20.1 runs a hybrid (Mojmap classes, SRG members), see §5.3.
- Data model: one row per obf entity, one column per namespace.
  - A cell is a name or a HOLE (a hole is not "same as obf").
  - Keys:
    - class = obfInternalName
    - field = (owner, name, obfDesc?) (TSRG2 fields have no desc)
    - method = (owner, name, obfDesc)
    - param = (methodKey, lvIndex)
  - Descriptors are stored in obf form only.
- Cache `<amberData>/cache/mappings/<mc>/`:
  - `sources/<id>/`: raw bytes + meta.json.
  - `local-only/`: anything with Mojang names.
  - `graph/<tableKey>.amap` + `.report.json`.
  - tableKey = SHA-256(game jar SHA-1, each input SHA-1, composer version, options).
  - Remapped-mod key = SHA-256(mod sha256, tableKey, remapper version, sorted hashes of the jars holding the mod's external supertypes). Stored in a sidecar `<out>.input`; rebuild on mismatch.

## 2. Namespace catalogue (Bundle = Amber may ship it; Local = fetch/generate on the user's machine only)
- **obf/official**
  - Source: version json downloads.client (1.21.1 client.jar sha1 30c73b1c…).
  - Format: bytecode. Coverage: all. Licence: EULA. Bundle: no.
- **mojmap**
  - Source: downloads.client_mappings/server_mappings {sha1,size,url}. 1.21.1 client.txt is 9,598,610 B, sha1 2244b6f0….
  - Format: ProGuard named->obf, e.g.
    - `com.mojang.blaze3d.Blaze3D -> eza:`
    - `    10:10:void <init>() -> <init>`
    - `    12:13:void process(com.mojang.blaze3d.pipeline.RenderPipeline,float) -> a`
  - Coverage: from 19w36a (Sep 2019); 1.14.4 back-fill (unverified); last 1.21.11.
  - Licence:
    - 2020 header (a single # line in 1.21.1): "(c) 2020 Microsoft Corporation. These mappings are provided "as-is"… You may copy and use the mappings for development purposes, but you may not redistribute the mappings complete and unmodified… governed by the Minecraft End User License Agreement…".
    - Older 2019 text (per cpw): "for your internal, reference purposes".
    - Which files carry which header is unverified.
  - Bundle: NEVER, local only.
- **intermediary**
  - Sources:
    - raw.githubusercontent.com/FabricMC/intermediary/master/mappings/<mc>.tiny
    - maven.fabricmc.net/net/fabricmc/intermediary/<v>/intermediary-<v>-v2.jar (also a v1 jar; .md5/.sha1/.sha256/.sha512 sidecars).
  - Format: tiny v1, e.g.
    - `v1\tofficial\tintermediary`
    - `CLASS\ta\tnet/minecraft/class_7833`
    - `FIELD\ta\tLa;\ta\tfield_40713`
    - `METHOD\ta\t(F)Lorg/joml/Quaternionf;\ta\tmethod_46349`
  - Coverage: 18w43b…1.21.11 (461 files). Licence: CC0. Bundle: yes.
- **srg** (MCPConfig TSRG2)
  - Sources:
    - repo MinecraftForge/MCPConfig versions/release/<mc>/joined.tsrg
    - maven.minecraftforge.net/de/oceanlabs/mcp/mcp_config/<mc>-<ts>/mcp_config-<mc>-<ts>.zip (.sha1 exists).
  - Format (1.20.1), e.g.
    - `tsrg2 obf srg id`
    - `a net/minecraft/src/C_252363_ 252363`
    - `\ta f_252495_ 252495`
    - `\ta (F)Lorg/joml/Quaternionf; m_252774_ 252774`
    - `\t\tstatic`
    - `\t\t0 o p_253997_ 253997`
  - Coverage: 1.16.5 (TSRG2, C_1_/f_8134_) to 1.21.11; 26.x zips have no tsrg.
  - Licence: the repo LICENSE is modified zlib, "Copyright (c) 2018 Forge Development LLC"; the maven POM says "All Rights Reserved, No redistribution."
  - Bundle: fetch locally.
- **srg-legacy** (TSRG1)
  - Format: no header, e.g. `a net/minecraft/util/text/TextFormatting` / `\ta BLACK` / `\ta (I)La; func_175744_a` (1.12.2).
  - Coverage: 1.12.2–1.16.4.
- **neoform**
  - Sources:
    - repo neoforged/NeoForm (obfuscated branch and <mc>-dev branches; main/26.1.x are unobfuscated)
    - maven.neoforged.net/releases/net/neoforged/neoform/<ver>/neoform-<ver>.zip (confirmed for 1.21.1-20240808.144430, with md5/sha1/sha256/sha512).
  - Format: TSRG2. From 1.20.2 srg = obf, but ids and p_ params are kept (1.21.1 has 97,186 p_ lines).
  - Coverage: maven from 23w31a to 26.x snapshots (26.x versions like 26.1-1).
    - There is NO 1.20.1 artifact. The repo's 1.20.1 joined.tsrg is byte-identical to MCPConfig's.
    - NeoForge 1.20.1 uses mcp_config:1.20.1-20230612.114412 (mirrored at maven.neoforged.net/releases/de/oceanlabs/mcp/mcp_config/).
  - Licence: modified zlib; Mojang.md warns the use "may not be considered development use". Local.
- **mcp** (CSV)
  - Sources: maven.minecraftforge.net/de/oceanlabs/mcp/mcp_snapshot/<v>/…zip, mcp_stable; archive github.com/ModCoderPack/MCPMappingsArchive.
  - Format: fields.csv/methods.csv `searge,name,side,desc`; params.csv (unverified).
  - Coverage: ~1.7–1.16.5. Licence: historically restrictive. Local, not needed at runtime.
- **mcp-srg** (pre-1.13)
  - Sources: de.oceanlabs.mcp:mcp:<mc>:srg@zip; Forge <=1.12.2 deobfuscation_data-<mc>.lzma (unverified).
  - Format: CL:/FD:/MD:. Local.
- **yarn**
  - Source: net.fabricmc:yarn:<mc>+build.N[:v2|mergedv2]. Format: tiny v2 intermediary->named.
  - Coverage: 18w49a–1.21.11. Licence: CC0. Bundle: debug only.
- **quilt** (QM)
  - Sources:
    - org.quiltmc:quilt-mappings
    - meta.quiltmc.org/v3/versions/quilt-mappings (fields maven, version, gameVersion, build, separator, hashed, file_size, hashes{sha1,sha256,sha512}).
  - Branches 1.17.1–1.21.11. Licence: CC0. Debug only; keep out of "clean" tables.
- **hashed**
  - Source: org.quiltmc:hashed:<mc>, generator QuiltMC/mappings-hasher (Apache-2.0). Names like `net/minecraft/unmapped/C_<h>`.
  - Derived from Mojang, so local only.
- **parchment**
  - Branches versions/1.16.x…1.21.x. Licence: CC0. Keyed by Mojmap. Local; debug/decompile only.
  - parchment.json shape (from Loom ParchmentTreeV1): {version, classes:[{name, fields:[{name,descriptor,javadoc}], methods:[{name,descriptor,parameters:[{index,name,javadoc}],javadoc}], javadoc}], packages:[{name,javadoc}]}.
- **Legacy Fabric**
  - lf-int-v1:
    - Sources: repo Legacy-Fabric/Legacy-Intermediaries legacy/mappings; meta.legacyfabric.net/v2/versions/intermediary -> net.legacyfabric:intermediary:<mc>.
    - maven.legacyfabric.net redirects to repo.legacyfabric.net/legacyfabric/.
    - 1.3–1.13.2, tiny v1, CC0.
  - lf-int-v2: net.legacyfabric.v2:intermediary; 1.8.2-pre5–1.13.2. Its numbering matches Fabric's (class_124). CC0.
- **Ornithe**
  - calamus:
    - Source: net.ornithemc:calamus-intermediary-gen2 (meta.ornithemc.net/v3/versions/intermediary).
    - Format: `CLASS\ta\tnet/minecraft/unmapped/C_49122968`; pre-1.3 header `v1\tintermediary\tclientOfficial\tserverOfficial`.
    - Coverage <=1.14.4 (816 files). CC0.
  - feather: net.ornithemc:feather-gen2 on maven.ornithemc.net/releases (b1.0.tiny + 831 .tinydiff). CC0.
- **babric-int**
  - Sources: github.com/babric/intermediary b1.7.3.tiny; maven.glass-launcher.net/babric/babric/intermediary/.
  - Header `v1\tintermediary\tglue\tserver\tclient`. Its numbering collides with Fabric's. CC0.
- **spigot** (BuildData): never.

Runtime namespace by loader (what shipped mods are compiled against):
- Fabric/Quilt 1.14–1.21.11: intermediary. 26.1+: official.
- Legacy Fabric/Ornithe/Babric: lf-int / calamus / babric.
- Forge <=1.12.2: MCP classes + func_/field_. Forge 1.13–1.16.5: full SRG.
- Forge 1.17–1.20.4: Mojmap classes + SRG members. NeoForge 1.20.1: same as Forge 1.20.1.
- Forge 1.20.6+ and NeoForge 1.20.2+: Mojmap. Everything 26.1+: unobfuscated.

## 3. Fetching
- Mojang:
  - Manifest piston-meta.mojang.com/mc/game/version_manifest_v2.json (entries: id, type, url, time, releaseTime, sha1, complianceLevel).
  - The version json gives downloads.{client,client_mappings,server,server_mappings}.{sha1,size,url}. Verify sha1 + size.
  - Store client.txt byte-for-byte. Forge's --sanitize re-serialises through srgutils, which strips comments and sorts.
  - No client_mappings and releaseTime >= 2025-11-01 means unobfuscated.
- Maven: discovery via maven-metadata.xml. Verify against .sha1/.sha512 sidecars (Fabric, NeoForged) or the inline hashes in Quilt meta.
- Choosing the MCPConfig version:
  - Forge 1.20.1: install_profile data.MCP_VERSION = 20230612.114412.
  - 1.20.4+: read it from the MCP_DATA processor input [de.oceanlabs.mcp:mcp_config:<mc>-<mcp>@zip] or from the net.minecraft:client:<mc>-<mcp> coordinates.
  - Known: 1.20.1 20230612.114412, 1.20.4 20231207.112700, 1.20.6 20240429.135109, 1.21.1 20240808.132146.
- MCPConfig zip contents: config.json (now has obfuscated and official flags), config/joined.tsrg, config/inject/, patches/.
  - Do not fetch the srg2off classifier; regenerate it locally from joined.tsrg + Mojang mappings.
- GitHub raw fallback: pin the commit SHA and record your own SHA-256.
- meta.json:
  - licence ids: CC0-1.0, LicenseRef-Forge-MCPConfig-zlib, LicenseRef-Mojang-Mappings-2020/-2019;
  - mojang_header;
  - derived_from, with a transitive redistributable flag.

## 4. Composition pipeline
### 4.1 Tooling (as found; see the note at the top on our own engine)
- mapping-io (Apache-2.0):
  - Central latest 0.9.2; Fabric Loader bundles 0.9.1.
  - Formats: TINY_FILE, TINY_2_FILE, ENIGMA_FILE/DIR, PROGUARD_FILE, SRG_FILE, XSRG_FILE, JAM_FILE, CSRG_FILE, TSRG_FILE, TSRG_2_FILE, INTELLIJ_MIGRATION_MAP_FILE, RECAF_SIMPLE_FILE, JOBF_FILE.
  - Adapters: MappingSourceNsSwitch, MappingNsCompleter, MappingNsRenamer, MappingDstNsReorder, MissingDescFilter, OuterClassNamePropagator.
  - It does not read Parchment json, MCP CSV, config.json or .tinydiff; write those readers yourself.
- srgutils, InstallerTools (LGPL-2.1): reference only.
- tiny-remapper (LGPL-3.0):
  - Builder: propagateRecordComponents, propagateBridges, propagatePrivate, skipLocalVariableMapping, rebuildSourceFilenames, withKnownIndyBsm, extraPostApplyVisitor, fixPackageAccess, checkPackageAccess.
  - NonClassCopyMode: UNCHANGED, FIX_META_INF, SKIP_META_INF.
- ART (neoforged/AutoRenamingTool, LGPL-2.1): Forge invokes it with --ann-fix --ids-fix --src-fix --record-fix --strip-sigs [--reverse].

### 4.2 Algorithm per MC version
1. Resolve and hash the pivot: the class index of the obf jar (per side pre-1.3).
2. Parse every source into obf->X.
   - ProGuard is named->obf, so switch its source namespace.
   - ProGuard method lines carry line ranges and Java-syntax Mojmap types. Convert them to obf descriptors with the file's own class map.
   - Re-key Yarn/QM through intermediary. Keep the TSRG2 id, static flag and params.
3. Complete each table against the jar hierarchy BEFORE joining.
   - Intermediary names only root declarations: 1.21.1 has 41,243 METHOD lines with 41,201 distinct names.
   - MCPConfig lists every override: in 1.20.1, 3,059 m_ ids occur more than once, m_5797_ 349 times.
   - Copy each override group's name onto every member. Groups rooted outside the game jar get the identity.
   - MappingNsCompleter is a fallback only.
4. Join: row[ns] = lookup ?: HOLE.
   - TSRG2 field fallback on (owner,name) only if unique, and assert it.
5. Missing entries:
   - Identity for <init>, <clinit>, enum values/valueOf, library overrides and unobfuscated code.
   - A hole stays a hole and counts against coverage.
   - Stale entries are dropped and counted.
6. Inner classes: name = map(outer)+'$'+(map(innerSimple) ?: obfSimple), e.g. intermediary h$1 -> class_4990$1.
   - Remap InnerClasses, EnclosingMethod, NestHost/NestMembers, PermittedSubclasses.
7. Records: component, field and accessor share one name per namespace (1.21.1 has 4,546 comp_ lines).
8. Params: debug only; account for `this` and wide types.
9. Emit .amap:
   - Little-endian header, string pool, class table, member tables, permutations, token maps.
   - Load with FileChannel.map (FFM is preview in 21, final in 22).
10. Cross-version: ids are stable in practice (1.20.1 C_252363_ is still 252363 in NeoForm 1.21.11), but this is not a stated policy.

### 4.3 Propagation
- Rules by method kind:
  - Virtual: up and down the whole hierarchy incl. interfaces.
  - Private: the method only.
  - Static: down until the next matching signature.
  - Static/private interface methods: never.
- Enforce the package-private non-override rule across packages yourself.
- Follow bridges.
- Default methods: the JVM's maximally-specific rule.
- Conflicts are fatal.
- One class universe: game + libraries + all mods in one pass.

## 5. Applying mappings to mods
### 5.1 Common
- Bytecode: classes, members and every descriptor (fields, methods, Handle, ConstantDynamic, LDC Type, annotation Type, Signature).
- Lambdas/method refs: the indy name maps with owner = return type of the indy desc and desc = bsmArgs[0] (metafactory/altMetafactory).
- Gaps needing our own passes:
  - ObjectMethods bootstrap "a;b;c" component-names string: rebuild it from the remapped component names.
  - SwitchBootstraps.enumSwitch/typeSwitch enum labels (String or EnumDesc condy) that name a remapped enum constant (verify the javac 21 form).
  - StringConcatFactory recipes: rewrite complete tokens only.
- Equivalent settings:
  - record propagation on;
  - skip LVT mapping, keep MethodParameters;
  - rebuild source filenames;
  - known indy BSMs from the Fabric-Loom-Known-Indy-BSMS manifest key.
- Multi-release jars: META-INF/versions/N is its own hierarchy context.
- Non-class files:
  - Strip signatures.
  - Remap META-INF/services names and contents, Main-Class and Launcher-Agent-Class.
  - AWs, refmaps and mixin json need dedicated passes.
- Package-access widening is REQUIRED.
  - 1.21.1 intermediary has 8,220 class entries under net/minecraft (5,344 top-level).
  - Intermediary code can therefore legally reach package-private members that land in different Mojmap packages, causing IllegalAccessError.
  - Make non-private members of net/minecraft/** (and com/mojang/blaze3d) public at load.
  - Fabric's PackageAccessFixer does this (WIDEN_ALL_PACKAGE_ACCESS when the runtime is named or fabric.fixPackageAccess is set).
- Mixin target hierarchy: inject each mixin's @Mixin targets as fake parents during propagation (the Connector IntermediaryClassProvider/MixinClassInfo idea).

### 5.2 Fabric/Quilt (intermediary -> Mojmap)
- Manifest keys (Loom): Fabric-Loom-Remap, Fabric-Loom-Mixin-Remap-Type, Fabric-Mapping-Namespace, Fabric-Loom-Split-Environment(-Name), Fabric-Loom-Client-Only-Entries, Fabric-Jar-Type, Fabric-Minecraft-Version, Fabric-Loom-Known-Indy-BSMS.
- Mixin remap type:
  - A missing key defaults to fabric.defaultMixinRemapType = "mixin".
  - Only "static" triggers tiny-remapper's MixinExtension.
  - We should apply the mixin-annotation remap on static jars (or on all of them).
- Refmaps: Connector remaps to env "mojang" (SOURCE_NAMESPACE intermediary, OBF_NAMESPACE mojang).
- AWs: Fabric Loader uses class-tweaker 0.3.0 (Apache-2.0), ClassTweakerRemapperVisitor.
- Override collisions: Connector appends "$connector_disabled$"+i.
- Intermediary has duplicate names for different methods. Connector excludes them; we should keep a qualified map instead.

### 5.3 Forge/NeoForge 1.17–1.20.4 (SRG members -> Mojmap), first target Forge 1.20.1
- Table: classes from client.txt; members = inverse(obf->srg) ∘ (obf->moj).
  - This reproduces MERGE_MAPPING --classes --reverse-right, with members mapped too.
  - Measure whether each SRG id maps to exactly one Mojmap name.
- What to remap in Forge mods:
  - Bytecode: member renames only.
  - META-INF/accesstransformer.cfg (the Forge 1.20.1 AT has 468 lines), e.g. `public net.minecraft.advancements.CriteriaTriggers m_10595_(Lnet/minecraft/advancements/CriterionTrigger;)Lnet/minecraft/advancements/CriterionTrigger; # register`.
  - Refmaps: env key searge (unverified).
  - JS coremods:
    - Forge 1.20.1 uses coremods 5.2.4. Its ASMAPI.mapMethod/mapField call Launcher.INSTANCE.environment().findNameMapping("srg") with an identity fallback.
    - Our shim must return SRG->Mojmap.
    - Raw `node.name === "m_…"` comparisons still break.
  - ObfuscationReflectionHelper.remapName = FMLLoader.getNameFunction("srg")…orElse(name). Provide the "srg" name function backed by the flat map.
  - Bare strings: the §6 LDC pass, regex ^[mfp]_\d+_$.
  - @Mixin(targets=…): already Mojmap.
- Game side for M4: (A) Mojmap everywhere (recommended) or (B) a hybrid SRG runtime. Forge 1.20.1's installer builds MC_SRG via ART --names {MERGED_MAPPINGS}.

### 5.4 Older targets (later)
- Forge 1.13–1.16.5: full SRG from TSRG1 -> Mojmap (from 1.14.4). For 1.16.5 the func_<->m_ bridge goes via obf.
- Forge <=1.12.2: deobfuscation_data lzma; no Mojmap target (out of scope).
- Fabric <1.14: detect the intermediary by MC version.
  - C_\d{8}/f_\d{8}/m_\d{8} means Calamus.
  - class_\d+ is ambiguous (Fabric/LF v2, LF v1, Babric): resolve every referenced token against each candidate and pick the one that resolves all.

## 6. Reflection string remapping
- Layer 1, token map. Intermediary and SRG ids never collide with real names, so a name-only lookup is safe.
  - Regexes:
    - intermediary class: `net[/.]minecraft[/.]class_\d+(\$class_\d+)*` (keep the . or / style)
    - intermediary member: `\b(method|field|comp)_\d+\b`
    - SRG >=1.17: `\b[mfp]_\d+_\b`, `net/minecraft/src/C_\d+_`
    - MCP <=1.16: `\b(func|field)_\d+_[a-zA-Z]+_?\b`
  - Intermediary tokens are not globally unique (duplicates): use a flat map plus a qualified map.
- Layer 2, a static bake-time pass.
  - Covers every LdcInsnNode.cst, indy bsm/bsmArgs, FieldNode.value, annotation values, condy string args and StringConcatFactory recipes (\u0001 holes; whole tokens only).
  - Classify each string:
    - descriptor `^L[a-zA-Z0-9/$_]+;$`
    - dotted FQN
    - slashed internal name
    - `Lowner;name(desc)`
    - field qualifier `^(?<owner>L...;)?(?<name>\w+)(?::(?<desc>...))?$`: remap only with an owner, or if the name matches a token pattern
    - else a bare token.
  - Scan ALL LDC strings, because javac inlines static final Strings and folds "a"+"b".
- Layer 3, data-flow (ASM Analyzer + SourceInterpreter). Trace the string args of:
  - Class.forName, ClassLoader.loadClass;
  - Class.get(Declared)Field/Method: owner from an LDC Type or a known getClass(); track Class[] for overloads;
  - MethodHandles.Lookup.find{Virtual,Static,Special,Getter,Setter,StaticGetter,StaticSetter,VarHandle,StaticVarHandle,Class};
  - MethodType.fromMethodDescriptorString, ObfuscationReflectionHelper.*, Fabric MappingResolver.map*/unmap*;
  - generic: any call whose first three args are single-source LDC strings forming a resolvable (owner,name,desc).
- Layer 4, a small runtime net in remapped mods only.
  - Redirect those call sites to amber.Reflect.* helpers.
  - The helpers forward-map a runtime string ONLY if it matches `^(net\.minecraft\.class_\d+|method_\d+|field_\d+|comp_\d+|[mfp]_\d+_)$`.
  - Do not patch the JDK.
- Pitfalls:
  - Do NOT reverse-map getName()/Class.getName() by default (it breaks serializers, logging and equality). Per-mod opt-in only.
  - Kilt mapped getField->findField (losing public inherited fields) and missed Lookup.find*/VarHandle.
  - Arclight:
    - Hooking all reflection is costly, and matching by name+params is fragile.
    - Without defineClass interception, ASM-generated classes escape.
    - Arclight HEAD has since removed name remapping.
  - Runtime-built names ("method_"+id) and names from configs/JSON cannot be fixed statically; report them.
  - Data/asset files that name classes: log token hits, don't rewrite by default.
  - Arclight (GPL-3.0), Kilt (LGPL-2.1): ideas only.

## 7. Validation
1. Coverage per namespace over obf entities (excluding identity cases).
   - Report % classes/fields/methods and holes by package in report.json; warn below e.g. 99.5% of methods.
   - Reference 1.21.1 intermediary: 8,245 CLASS, 41,243 METHOD, 37,679 FIELD, 4,546 comp_.
   - Reference 1.20.1 TSRG2: 212,139 lines, 7,436 classes (7,389 C_).
2. Injectivity:
   - the class map is 1:1;
   - no two members of a class share name+desc;
   - one target name per override group;
   - no accidental overrides;
   - records are consistent;
   - every intermediary/SRG id maps to exactly one Mojmap name (list exceptions).
3. Round-trip: A->B->A == id, and compose(obf->A, A->B) == obf->B for all pairs.
4. Ground truth: every Mojmap class/member in the table exists in the installer-built NeoForge game jar (extra patch members expected).
5. Link-check remapped mods:
   - Resolve every GET/PUTFIELD, INVOKE*, Handle and LDC Type against runtime jar + libraries + mods per JVMS §5.4.3.
   - Report anything unresolved before launch.
   - Optionally run ASM CheckClassAdapter on a sample.
6. Reflection report: strings rewritten, and token-matching strings left alone.
7. Golden tests:
   - (a) The new 1.21.1 intermediary->mojmap output equals the current prototype remapper's output on a fixed mod set, or every diff is explained. Our engine also equals ART on the NeoForge base jar.
   - (b) Fixtures: record, bridge/generic override, lambda, enum + pattern switch, package-private cross-package access, static-mixin jar, refmap jar, MixinExtras @Expression, one reflection literal of each kind.
   - (c) Forge 1.20.1: AT, refmap, coremod and ORH examples.
   - (d) 26.1: identity table, remap skipped.
8. Taint check: fail if any bundled resource contains net/minecraft/world/ or other Mojmap-only paths.

## 8. Licence rules
- **R1, may bundle:** CC0 sets (intermediary, Legacy-Intermediaries, Calamus, Feather, Babric, Yarn; QM for debug).
  - MCPConfig/NeoForm data is modified zlib ("no misrepresentation"; "Altered versions must be plainly marked as such"; "This notice may not be removed or altered").
  - But the POM says "All Rights Reserved, No redistribution." So prefer fetching upstream on the user's machine (the manifest already does this).
- **R2, local only:**
  - Mojang client/server.txt and anything containing Mojang names;
  - Forge 1.17–1.20.4 runtime tables, hashed, Parchment exports, Spigot, srg2off;
  - remapped game and mod jars.
  - Exports, crash reports and sync must refuse local-only/.
- **R3:** keep Mojang files byte-for-byte and record the header. Show it the way Loom does: "Using of the official minecraft mappings is at your own risk!" + "Please make sure to read and understand the following license:" + the leading # lines.
- **R4:** taint is transitive.
- **R5:** never use Mojmap as a join key for anything shipped.
- **R6:** 26.1+ names fall under the EULA.
- Grey zone: cpw's 2020 note calls runtime deobfuscation "potentially problematic"; NeoForm Mojang.md says the same. Not legal advice.
- Tool licences:
  - mapping-io: Apache-2.0 (link freely).
  - tiny-remapper (LGPL-3.0); srgutils, InstallerTools, ART/FART, CoreMods (LGPL-2.1): reference, or use as an unmodified library.
  - SpecialSource: BSD-style.
  - Ideas or code with attribution:
    - Loom: MIT.
    - Fabric Loader, Quilt Loader, mappings-hasher, stitch, class-tweaker, access-widener: Apache-2.0.
    - Connector, Sinytra Adapter: MIT.
    - ForgifiedFabricLoader: Apache-2.0.
  - Ideas only:
    - Matcher: GPL-3.0.
    - Kilt: LGPL-2.1.
    - Patchwork: LGPL-3.0.
    - Arclight, Mohist, Youer: GPL-3.0.
  - BluSpring/srg2intermediary has no licence: do not use.
  - Do not copy prior art that ships Mojang-derived merged tables inside jars.

## 9. Implementation order
- **M0 infrastructure**
  - Build: fetcher/manifest (mostly done), SHA verification, meta.json, local-only/ layout, licence banner, taint propagation, CI taint grep, THIRD_PARTY_NOTICES.
  - Tests: hash mismatch; missing client_mappings => unobfuscated; export refuses tainted paths.
- **M1 1.21.1 graph** (obf, mojmap, intermediary, + NeoForm ids)
  - Build: readers, AmberMappingTree, hierarchy completion, join, inner classes/records, .amap + report.
  - Check: coverage, injectivity, round-trip, ground truth; golden (a), incl. byte-identical vs ART on the NeoForge base jar.
- **M2 Fabric/Quilt application on 1.21.1**
  - Build:
    - manifest handling and mixin remap type;
    - package-access widening;
    - ObjectMethods/SwitchBootstraps passes;
    - flat+qualified token map, MappingResolver;
    - §6 layers 2–3, then 4;
    - remapped-mod cache keys.
  - Check: golden (b); link-check clean on the test pack; reflection report reviewed. Measure speed vs the current remapper.
- **M3 1.20.1 graph**
  - Build: srg from mcp_config 1.20.1-20230612.114412 (not NeoForm). Compose srg->mojmap members + intermediary->mojmap + an srgId token map.
  - Check: one Mojmap name per SRG id.
- **M4 Forge 1.20.1 integration**
  - Decide A vs B with a spike.
  - Remap Forge mods: bytecode, AT, refmap env, coremod ASMAPI shim, srg name function for ORH, LDC pass.
  - Check: golden (c); a small real Forge 1.20.1 pack + Fabric mods boots.
  - Note: no Forge 1.20.1 mods may be downloaded without asking the user.
- **M5 gating + other versions**
  - Build: 26.1+ identity; the other 1.17–1.21.11 versions; Yarn/QM/Parchment as debug namespaces for readable crash reports; cross-version id diffs.
  - Check: golden (d); a coverage report per version.
- **M6 legacy (optional):** Forge 1.13–1.16.5 TSRG1, LF v1/v2, Calamus, Babric per-side pivots, intermediary detection by token resolution.

## 10. Still unverified
- Quilt/Parchment/hashed maven artifacts (they are fetched locally; record what was seen)
- 1.14.4 Mojmap
- Mojang header era boundaries
- MCP CSV details
- Jammer
- Quilt Loader mojmapPath/targetNamespace
- SRG id -> Mojmap uniqueness
- javac 21 SwitchBootstraps label form
- .amap load time
- the meaning of Fabric's 26.x meta entries

Report progress at M1 and M2, with speed numbers vs ART and the current remapper. Add a 映射引擎 section to 阶段5报告.md (or start 阶段6报告.md).
