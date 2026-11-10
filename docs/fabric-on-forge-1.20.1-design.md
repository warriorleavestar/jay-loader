# Fabric mods on the Forge 1.20.1 platform: bridge design notes (draft, no code yet)

Date: 2026-11-09. Platform: Forge 1.20.1-47.4.28 (our FML bridge, `fmlbridge-forge`, Mojmap everywhere, option A). Reference: the NeoForge bridge, `docs/fabric-api-bridge-design.md` (v1, Fabric API 0.116.17 on NeoForge 1.21.1).
Same rule as the NeoForge bridge: the bridge is Amber's own code; the official Fabric API jars run as published; nothing from Sinytra Connector / Forgified Fabric API is used (ideas only).

Status: notes for a decision. Every Forge class named here exists in the 47.4.28 universal jar (checked with javap); every claim marked **(verify)** is from memory of Fabric API 1.20.1 or Forge behaviour, and needs a census or a run before it is relied on.

## 1. What is reused from the NeoForge bridge as is

| Part | Where | Why it carries over |
|---|---|---|
| Fabric loading itself | core (`FabricPersonality`, discovery, resolver, entrypoints) | Loader-neutral. Fabric mods are already discovered next to Forge mods in one instance. |
| Remapping | `Baker` + mapping cache | Our Forge platform runs Mojmap (the game, Forge and Forge mods are remapped forge→Mojmap at bake time). Fabric mods are remapped intermediary→Mojmap exactly as on NeoForge and vanilla, with the same graph (tableKey: Mojang + intermediary for 1.20.1). No SRG step for Fabric mods. |
| Mixin pipeline | MixinFixer, census, isolation | Loader-neutral. The census runs on the Forge-patched game, so Forge patches show up as failing Fabric API mixins, which is exactly the input the module table needs. |
| Bridge manifest mechanism (M0) | `BridgeManifest`, `core/res/amber-ul/bridge/` | Only the file name is keyed by platform today (`fabric-api-neoforge-<mc>.json`). Add a platform part: `fabric-api-forge-1.20.1.json`. Verdicts, `disableMixins`, `replaceImpl`, the isolation key and the `fabric_api_bridged`/`fabric_api_unsupported` reporting are unchanged. |
| Mechanisms M1–M3 | design §2 | Driver (Forge event → official Fabric invoker), impl substitution (our `net.fabricmc.fabric.impl.*` classes served through the class loader's overrides), extension adapter (mixin adds `IForge*` methods delegating to Fabric's interface-injected ones). Same three tools; the Forge targets differ (section 3). |
| D1, the registration window | `GameDataMixin` → `FabricWindow.run()` | Forge 47 has the same window: `GameData.postNewRegistryEvent()`, `unfreezeData()`, `postRegisterEvents()`, `freezeData()`, `vanillaSnapshot()` (all public static in `net.minecraftforge.registries.GameData`). The hook "HEAD of `postRegisterEvents`, root registry unfrozen in a try/finally" ports one to one with the package renamed. |
| Bake-time switches, census-after-manifest, version gate | design §6 | Same logic; the version gate matches the Fabric API version for 1.20.1 instead of 0.116.17. |
| Tests | regress/census tooling, `bake_cli_test.py` | A Forge + Fabric mixed set slots into the existing Forge census and world runs. |

What is **not** reusable as code: everything under `fabricbridge/src/amber/ul/fabricbridge` that imports `net.neoforged.*` (about 40 classes). It is reusable as a template: the module verdicts and the structure of each adapter mostly carry over, the Forge API calls do not.

## 2. Packaging

- A second bridge jar, `fabricbridge-forge.jar` (source tree `fabricbridge-forge/src`, package `amber.ul.fabricbridge.forge`), compiled against Forge 47 (Mojmap, from our baked universal jar) and the official Fabric API for 1.20.1. Loaded only when the Forge platform is active and a Fabric mod is in the instance (the same check as `Instance.java:101`, extended to `p.forge != null`).
- Platform-neutral helpers (the Fabric window, the journal for transfer, logging/problem lines) move into a small shared source set, so the two bridges don't fork them. This is the only refactor in the NeoForge bridge, and it can be done last.
- `NeoBus` becomes `ForgeBus`: game events on `MinecraftForge.EVENT_BUS`; mod-bus events on the `forge` mod container's bus (our FML bridge creates that container; verify that `ModList.get().getModContainerById("forge")` has a usable bus in our boot, else give the bridge its own synthetic `FMLModContainer`).

## 3. Main differences in Forge 47 hooks

### 3.1 Game version under the API (largest difference)

Fabric mods for 1.20.1 are built against **Fabric API 0.92.x** (verify the exact last version), not 0.116.x. So the bridge is written against a different API, not just a different platform:
- **Networking** in 1.20.1 has no configuration phase (added to vanilla in 1.20.2) and no `CustomPacketPayload` records. Fabric API 1.20.1 networking is `PacketByteBuf` based (`ServerPlayNetworking.registerGlobalReceiver(Identifier, handler)`, `PacketSender`, plus `FabricPacket`/`PacketType`) and login-phase queries (`ServerLoginNetworking`) **(verify)**.
- **Registry sync** in Fabric API 1.20.1 runs in the login/play phase, not the configuration phase **(verify)**.
- Some modules don't exist yet or have older versions (no `fabric-data-attachment-api-v1` before 1.20.4 **(verify)**; `fabric-item-api-v1` without data components, since components arrived in 1.20.5).
- Consequence: the module table (section 4) must come from a **new census** with the 1.20.1 Fabric API jar on our Forge platform. The NeoForge table is the starting guess, not the answer.

### 3.2 Registries

- Window: same as D1 (section 1). Forge 47's `RegisterEvent` and `NewRegistryEvent` exist.
- Forge 1.20.1 still has its own `ForgeRegistry` wrappers for vanilla registries (`ForgeRegistries.*`), unlike NeoForge 1.21.1 which writes straight into `MappedRegistry`. A Fabric `Registry.register(BuiltInRegistries.ITEM, …)` inside the window has to end up in Forge's `ForgeRegistry` snapshot to be synced and remapped. Forge 1.20.1 routes vanilla `Registry.register` into the Forge registry through its `MappedRegistry` patches **(verify by a test mod; this is the first thing to prove)**.
- `FabricRegistryBuilder` (a new registry) → Forge `NewRegistryEvent.create(RegistryBuilder)` with `hasTags`/sync flags; `DynamicRegistries.register` → `DataPackRegistryEvent.NewRegistry` (exists in 47).
- Registry sync itself: Forge syncs registry ids during the login handshake (FML handshake), so registry-sync-v0's own packets are dropped (verdict c), as on NeoForge.

### 3.3 Networking

| Fabric (1.20.1) | NeoForge 1.21.1 target (today) | Forge 47 target |
|---|---|---|
| Channel registration | `RegisterPayloadHandlersEvent` / `PayloadRegistrar`, optional payloads | `NetworkRegistry.newEventChannel` / `ChannelBuilder` per Fabric channel id, with an accept-missing version predicate (the Forge equivalent of "optional") |
| Send / receive | `PacketDistributor`, `CustomPacketPayload` | vanilla `ClientboundCustomPayloadPacket(ResourceLocation, FriendlyByteBuf)` (Forge 47 still has the buf-based packet) and the event channel's `NetworkEvent` handlers |
| `canSend` | NeoForge negotiated channel set | Forge's channel list from the login handshake plus `minecraft:register` (Forge sends it for its channels) |
| Configuration tasks | `ICustomConfigurationTask` | none: there is no configuration phase in 1.20.1; the Fabric 1.20.1 API has none either |
| Login queries | unsupported in v1 | Forge 47 has a login phase it owns (`fml:loginwrapper`). Fabric login queries could ride on it, still out of scope for v1 |

Because the 1.20.1 packets carry a raw buffer and a channel id, the Forge bridge is closer to a direct pass-through than the NeoForge one: one event channel per Fabric channel id, whose handler calls the Fabric receiver with the buffer. The open question is only the Forge handshake: a channel that the other side does not list must not fail the connection (accept-missing predicate on both sides).

### 3.4 Other hook targets (per NeoForge (c) part)

| NeoForge 1.21.1 target in the current bridge | Forge 47 counterpart | Note |
|---|---|---|
| `IItemExtension` (M3) | `IForgeItem` | Method names differ in places (`getCraftingRemainingItem`, `shouldCauseReequipAnimation`, `shouldCauseBlockBreakReset`, `canApplyAtEnchantingTable`, `damageItem`); no data components in 1.20.1, so the component events are gone. |
| `ModifyDefaultComponentsEvent` | none (1.20.1 has NBT, not components) | That Fabric API surface doesn't exist on 1.20.1 either. |
| `ItemAttributeModifierEvent`, `ItemTooltipEvent` | same names, `net.minecraftforge.event.*` | — |
| `BuildCreativeModeTabContentsEvent` | same event in Forge 47 | Item-group re-base ports as is. Forge 1.20.1 has its own creative tab paging too. |
| `LivingIncomingDamageEvent` | `LivingAttackEvent` (can cancel) + `LivingHurtEvent` | ALLOW_DAMAGE maps to `LivingAttackEvent` cancel. |
| `LivingDeathEvent`, `PlayerEvent.Clone`, `PlayerRespawnEvent` | same | — |
| `CanPlayerSleepEvent`, `PlayerWakeUpEvent` | `PlayerSleepInBedEvent`, `SleepingLocationCheckEvent`, `PlayerWakeUpEvent` | (verify the split) |
| `PlayerInteractEvent.*`, `AttackEntityEvent`, `BlockEvent.BreakEvent` | same names | Interaction re-base ports with a package rename. |
| `RegisterParticleProvidersEvent`, `RegisterColorHandlersEvent`, `RegisterKeyMappingsEvent`, `ModelEvent.*`, `RegisterClientReloadListenersEvent`, `AddPackFindersEvent`, `AddReloadListenerEvent`, `RegisterCommandsEvent`, `ChunkEvent.Unload`, `FurnaceFuelBurnTimeEvent`, `ScreenEvent` | same names in Forge 47 | Package rename, mostly. These are the parts where the NeoForge adapters are the best template. |
| Data maps (`NeoForgeDataMaps.FURNACE_FUELS`, `COMPOSTABLES`, …), `DataMapsUpdatedEvent` | none in 1.20.1 | Composting goes back to vanilla `ComposterBlock.COMPOSTABLES` (Fabric's own mixins likely just work, verdict a/b). Tool modification uses `ToolAction` + `IForgeBlock#getToolModifiedState` instead of `ItemAbility`. |
| `ICustomIngredient` / `IngredientType` | `AbstractIngredient` + `IIngredientSerializer` (`CraftingHelper.register`) | A different ingredient model: the recipe-api adapter is rewritten, not ported. |
| `IMenuTypeExtension.create`, `openMenu(provider, buf)` | `IForgeMenuType.create(IContainerFactory)`, `NetworkHooks.openScreen(player, provider, buf)` | Screen-handler re-base ports with new names; the extra data is a `FriendlyByteBuf` in both. |
| `SoundInstance#getStream` (NeoForge default method) | Forge 47's own patch for streams on `SoundInstance` **(verify the method name)** | M3 adapter stays, target method to be checked. |
| `BiomeModifier`, `ModifiableBiomeInfo` | same concepts in Forge 47 (`ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS`, `ModifiableBiomeInfo` exists) | Ports with a package rename. |
| `FluidType`, `IClientFluidTypeExtensions` | same in Forge 47 | Still "c, later", as on NeoForge. |
| `IItemHandler` / `IFluidHandler` (transfer interop) | Forge capabilities (`Capability`, `LazyOptional`, `ForgeCapabilities.ITEM_HANDLER`) | A different model (capabilities on block entities via `getCapability`, not NeoForge's registered block capabilities). Interop stays out of scope for v1 as on NeoForge. |
| Event bus | NeoForge bus-api (`IEventBus`, `EventPriority`) | Forge eventbus 6 (`net.minecraftforge.eventbus.api.*`, `MinecraftForge.EVENT_BUS`). Same usage shape for lambdas with `addListener`. |

### 3.5 Lifecycle and boot

- Our Forge boot does not use modlauncher/bootstraplauncher (our FML bridge runs FML's `ModLoader` directly). The NeoForge D1 hook depends only on `GameData.postRegisterEvents` being called by FML, which is the same on Forge 47. preLaunch stays where it is.
- Forge 47 bakes JS coremods at bake time (`ForgeCoreMods`) and hosts launch plugins at runtime (`ForgeLaunchPlugins`). Fabric mods' mixins are applied on top of the coremod output, so the census must be run with coremods applied (it already is for Forge mods).
- Forge's `ModList`/`ModLoadingContext` must see the Fabric mods for mods that check `ModList.get().isLoaded("fabric-api")` or similar. On NeoForge this was solved by the synthetic container of the bridge; on Forge the same approach (one synthetic container per Fabric mod, or one for the bridge) needs a decision. Recommendation: per Fabric mod, info only, so `isLoaded(id)` answers true and the Mods screen lists them.

## 3.6 Finding from the first window run (2026-11-09, our own test mod only)

- The D1 port works as planned: deferred Fabric `main` + `client` run at HEAD of `GameData.postRegisterEvents` (bridge jar `fabricbridge-forge.jar`, mixin `ForgeGameDataMixin`, `ForgeFabricWindow`).
- Forge 47 has **two** locks that NeoForge 1.21.1 does not: each `ForgeRegistry` is frozen except during its own `RegisterEvent` (`isLocked()`/`unfreeze()`/`freeze()`), and each vanilla-facing `NamespacedWrapper` (package-private) has a `locked` flag that Forge sets once and never clears ("Can not register to a locked registry. Modder should use Forge Register methods."). With the wrapper unlocked, `Registry.register` goes to `ForgeRegistry.add`, so the entry is a normal Forge entry (snapshot, id sync, remap). The window clears both for its duration and sets exactly those again in a `finally` (29 wrappers on 1.20.1).
- Result (`fabricforgetest`, plain Fabric mod in intermediary, block + item via vanilla `Registry.register`): registered inside the window, present in `ForgeRegistries.BLOCKS/ITEMS`, network id assigned (1003), placed and read back by the integrated server, 30 s in world. Before the fix (runs fft1/fft2) the mod's init failed with the lock error, which is also the negative control.
- Answers 3.2's open question: vanilla `Registry.register` does reach Forge's registry, but only with the wrapper lock cleared.
- Run it: `bash fabricforgetest/build.sh`, copy `fabricforgetest/amber-fabricforgetest-0.1.0.jar` and `forgeworldtest/amber-forgeworldtest-0.1.0.jar` into `mc/mods-forge-fft/`, then `python -I tools/run_mc.py fft amber --forge --mods mc/mods-forge-fft --cache mc/ulcache-fft --gamedir <proto>/mc/game-forge --marker "in world 30 s" --jvm=-Damber.forgeworldtest=fft`; look for `[amber-fwt] FABRIC block … placed=true readback=true`.

## 3.7 Static read of Fabric API 0.92.12+1.20.1 (no code run)

`python -I tools/fapi_static.py <fabric-api.jar> <merged.tiny> <forge client.jar> <out.json>` reads every module's mixin configs and each mixin's `@Mixin` targets from the class files, maps them intermediary → Mojmap and marks targets that Forge 47 patches (its binpatched `client.jar`: 1442 of 7436 game classes). Result: `docs/data/fabric-api-0.92.12-forge-47-static.json`. 53 modules, 275 mixins, 215 of them on a Forge-patched class. This is a risk list, not a failure list: the census (pending, needs the run permission) decides. Modules with no mixins at all (api-base, client-tags, command-api-v1, commands-v0, convention-tags, events-lifecycle-v0, keybindings-v0, models-v0, networking-v0, renderer-registries-v1, rendering-v0, transitive-access-wideners) are verdict (a) candidates already. The bridge manifest for this platform goes to `core/res/amber-ul/bridge/fabric-api-forge-1.20.1.json` (the lookup is platform-keyed now).

## 3.8 First census: Fabric API 0.92.12 + Mod Menu 7.2.2 + Lithium 0.11.4 + FerriteCore 6.0.1 on Forge 47 (2026-11-09)

Run `ffc` (one round, no MixinFixer round, window bridge active, no Forge bridge manifest yet): **13 failing mixins, 347 changed classes** (`mc/census/ffc.json`, `mc/runs/ffc.log`). Every target loaded; Fabric init ran in the window. Compared with the static read (215 mixins on Forge-patched classes), only 13 actually fail.

| Module | Mixin | Target | Failure | Likely verdict (NeoForge analogue) |
|---|---|---|---|---|
| fabric-events-interaction-v0 | client `MinecraftClientMixin` | `Minecraft.startUseItem` | LVT changed (Forge patch) | c: driver on `PlayerInteractEvent` (same as NeoForge) |
| fabric-events-interaction-v0 | `ServerPlayNetworkHandlerMixin` | `ServerGamePacketListenerImpl$1` | shadow missing | c: `PlayerInteractEvent.EntityInteract(Specific)` |
| fabric-entity-events-v1 | `ServerPlayerEntityMixin` | `ServerPlayer.startSleepInBed` | LVT changed | c (partial): `PlayerSleepInBedEvent` driver |
| fabric-item-api-v1 | `AbstractFurnaceBlockEntityMixin` | `AbstractFurnaceBlockEntity` | arg modifier check | c (partial): `IForgeItem#getCraftingRemainingItem` adapter |
| fabric-registry-sync-v0 | client `BlockColorsMixin`, `ItemColorsMixin`, `ParticleManagerMixin` | colour / particle maps | shadow missing (Forge replaced the id maps) | c: registry-sync re-base; colours/particles via `RegisterColorHandlersEvent` / `RegisterParticleProvidersEvent` |
| fabric-rendering-v1 | `ShaderProgramMixin` | `ShaderInstance` | other | native substitute (Forge accepts namespaced core shaders), as on NeoForge |
| fabric-renderer-indigo | `ChunkBuilderBuiltChunkRebuildTaskMixin` | `RenderChunk$RebuildTask` | redirect target gone (Forge's model-data render path) | c (partial): hand port, as on NeoForge |
| fabric-tag-api-v1 | `TagFileMixin` | `TagFile` | other (Forge's `remove` entries in the codec) | b / isolate, as on NeoForge |
| fabric-loot-tables-v1 | `LootTableMixin` | `LootTable` | shadow missing | b or c: `LootTableLoadEvent` |
| lithium | `inventory_change_listening.ChestBlockEntityMixin` | `ChestBlockEntity` | other | not Fabric API: fixer / isolate (Lithium has a Forge-aware build, not used) |
| lithium | `block.hopper.HopperBlockEntityMixin` | `HopperBlockEntity.suckInItems` | LVT changed | fixer / isolate |

Full census (`python -I tools/census.py ffr <proto>/mc/mods-forge-fabric forge`, rounds until nothing new): round 2 adds 5 failures hidden behind round 1, round 3 none. **18 failing pairs** (`mc/census/ffr.json`); by category: shadow_missing 7, local_capture 4, other 3, accessor_missing 2, target_method_missing 1, injection_point_not_found 1. The 5 from round 2:

| Module | Mixin | Target | Failure | Likely verdict |
|---|---|---|---|---|
| fabric-rendering-v1 | client `BlockColorsMixin`, `ItemColorsMixin` | `BlockColors` / `ItemColors` | shadow missing (Forge's map fields) | c: `ColorProviderRegistry` buffered and replayed in `RegisterColorHandlersEvent`, as on NeoForge |
| fabric-particles-v1 | client `ParticleManagerAccessor` | `ParticleEngine.providers` | accessor missing (Forge: map keyed by `ResourceLocation`) | c: `RegisterParticleProvidersEvent`, as on NeoForge |
| fabric-transfer-api-v1 | `HopperBlockEntityMixin` | `HopperBlockEntity` | LVT changed | fixer / isolate (Forge's `VanillaInventoryCodeHooks` takes over hopper transfer) |
| fabric-loot-api-v2 | `LootTableAccessor` | `LootTable.pools` | accessor missing (Forge made `pools` a mutable `List`) | c: `LootTableLoadEvent` driver |

Compared with NeoForge 1.21.1 (57 failing pairs with 0.116.17), Forge 47 with 0.92.12 is a smaller job: 18 pairs, all in modules the NeoForge bridge already re-bases (interaction, entity events, item, registry sync, rendering colours, particles, indigo, tag, loot) except Lithium's two and loot-api's accessor.

Next:
1. Wire the MixinFixer for the Forge platform (census.py runs it only on NeoForge today; Forge needs the clean SRG game vs the patched one as the diff base, which `ForgePlatform` already has: `srg` + `patched`).
2. Forge bridge manifest `fabric-api-forge-1.20.1.json` with the verdicts above; each veto lands together with its driver, so nothing is silently lost.
3. Drivers in this order (each with a small Fabric test mod): interaction + entity events, colours + particles, registry-sync client side, item remainder, loot, indigo.

## 3.9 Forge bridge manifest, group 1 (2026-11-10)

Manifest `core/res/amber-ul/bridge/fabric-api-forge-1.20.1.json` (Fabric API 0.92.12), each veto/strip landing with its replacement in `fabricbridge-forge` (adapters in `amber.ul.fabricbridge.forge.mixin.fapi`, re-based impl classes under `fabricbridge-forge/impl/<module>`, packed as `amber-impl/<module>/` and served only when that module is installed). Test switches `-Damber.fabricbridge.no<Part>=true` (Interact, Sleep, Keys, Loot, DynReg) turn one adapter off for a bridge-off control (separate `--cache`).

| Module | Forge 47 difference | Manifest | Replacement |
|---|---|---|---|
| fabric-events-interaction-v0 | `ServerGamePacketListenerImpl$1` has `val$entity`; `startUseItem` has Forge's event local | veto `ServerPlayNetworkHandlerMixin`, strip `client.MinecraftClientMixin#injectUseEntityCallback` | `ServerInteractEntityFabricMixin`, `MinecraftUseEntityFabricMixin` |
| fabric-entity-events-v1 | `startSleepInBed`: Forge locals; `Level.isDay()` replaced by `ForgeEventFactory.fireSleepingTimeCheck` | strip `ServerPlayerEntityMixin#onTrySleepDirectionCheck`, `#redirectDaySleepCheck` | `ServerPlayerSleepDirectionFabricMixin`, `ServerPlayerSleepTimeFabricMixin` (Forge's answer is the vanilla result, a non-PASS Fabric result overrides it) |
| fabric-key-binding-api-v1 | Fabric client init runs after `Options` exist (window), official impl throws | veto `GameOptionsMixin`, `replaceImpl` | re-based `KeyBindingRegistryImpl` collects keys; `ForgeKeyMappingsFabricMixin` hands them over at the tail of `ForgeHooksClient.onRegisterKeyMappings` |
| fabric-loot-tables-v1 | `LootTable.pools` is a `List` | veto `LootTableMixin` | `LootTableSupplierFabricMixin` (FabricLootSupplier over the List) |
| fabric-loot-api-v2 | same | strip `LootTableAccessor#fabric_getPools` with `invoker`: the `@Accessor` becomes `@Invoker("amber$fabricPools")` | `LootTablePoolsFabricMixin` (priority 900) adds `amber$fabricPools()` |
| fabric-registry-sync-v0 | `WorldLoader.load` takes `DataPackRegistriesHooks.getDataPackRegistries()`; Fabric's copy of `WORLDGEN_REGISTRIES` lacks `forge:biome_modifier` etc. ("Missing registry", server can't start) | veto `SaveLoadingMixin` | `WorldLoaderDynamicRegistriesFabricMixin`: Forge's list plus Fabric-registered dynamic registries |
| fabric-rendering-fluids-v1 | Forge's `LiquidBlockRenderer.tesselate` reads `sprites[2]` (overlay) | `replaceImpl` | re-based `FluidRendererHookContainer`: 3 slots, `[2]` = overlay or null (was AIOOBE with lava in view) |
| fabric-recipe-api-v1 | Forge made `Ingredient.toNetwork` final; `CustomIngredientImpl` overrides it (only such case in Fabric API, static scan) | none | access widener in the bridge (`extendable method`) |

New manifest field: `strip[].invoker` (MixinFixer.accessorToInvoker). A plain abstract method in an accessor interface would turn it into an interface mixin (target must be an interface), so the accessor is turned into an invoker of a method the bridge adds.

Results (mod set `mc/mods-forge-ffp`: the 4 downloaded jars + `fabricforgeprobe` + `forgeworldtest`):
- census `ffp`: 15 failing pairs before group 1, now 13 (12 excluded, 4 fixed, 1 refuted): registry-sync colours/particles x3, rendering colours x2 + shader, particles accessor, item furnace, tag, transfer hopper, lithium x2.
- world run `ffp1` (`-Damber.ffprobe=interact,sleep,loot,keys`): joins the world; interact 3/3, sleep 9/9, loot 6/6, keys 4/4.
- bridge-off control `ffp-off` (noInteract, noSleep, noKeys): interact 0/3, sleep 4/9, keys 3/4 (Fabric key missing). Loot, dynamic registries and fluids have no "off and still in world" state: before their fix the same mod set failed with ClassCastException (LootTable to FabricLootSupplier), "Missing registry forge:biome_modifier" and AIOOBE in `LiquidBlockRenderer.tesselate`.

Found on the way: under lenient mode (`mixinErrors=warn`) Amber sets every config's `defaultRequire` to 0, and on Forge `countInjections` is off (on purpose, for Forge mods). So a Fabric injector whose target Forge's patch replaced passes silently; this hid the sleep-time redirect. `census.py --strict-require` (`-Damber.ul.strictFabricRequire=true`) keeps Fabric mods' own require on Forge: 60 failing pairs instead of 13 (`mc/census/ffp-strict.json`), mostly injectors Forge replaced (furnace fuel, recipe remainders, `ItemStack` attribute modifiers, brewing remainder, block-break callbacks, screen render hooks, Lithium fluid pushing / hopper hooks, sound stream, chunk block-entity removal, dimension codec). A real (non-lenient) launch would stop on the first of these, so they are the next list to work through. Strict mode writes the same isolation file: rerun the normal census before a lenient launch. The MixinFixer's `instance_to_static` fix of screen-api `GameRendererMixin` (moved to `ForgeHooksClient.drawScreen`) passed census verification but fails at runtime (LVT at that point differs); to check when the strict list is worked on.

"Was empty after loading" (`forge:entity_data_serializers`, `forge:global_loot_modifier_serializers`, only with Fabric API): log only. Forge 47 registers nothing in either; Fabric's registry-sync client `MinecraftClientMixin` calls vanilla `BuiltInRegistries.bootStrap()` (freeze + validate) again at the start of `Minecraft.run`, when Forge's custom registries are in the root registry, while plain Forge validates before they exist. The small test behind it found a real problem though: `forgeworldtest` now registers a global loot modifier (emerald on `blocks/dirt`, `forge:loot_table_id` condition) and an entity data serializer. Forge only (`glm0`): both work. With Fabric API (`glm1`): serializers registered, but the modifier never applied. Fabric's loot-v2 `LootManagerMixin` rebuilds every loot table after reload (`copyOf(table)...build()`), and the copies lose Forge's table id and freeze, so every Forge mod's global loot modifier keyed on the table id stopped. `LootDataManagerTableIdFabricMixin` restores both in `LootDataManager.getElement`: `glm-on` dirt drops dirt + gold (Fabric MODIFY) + emerald (Forge GLM); control `glm-off` (`noLootId`) dirt + gold only.

Open: `DynamicRegistries.addSyncedRegistry` reassigns `RegistrySynchronization.NETWORKABLE_REGISTRIES` (no mod in the set calls it).

## 3.10 Strict census as the source of truth; group 2: fabric-screen-api-v1 (2026-11-10)

From here on the strict census (`--strict-require`) is the list. Grouped by injector (`tools/strict_list.py mc/census/ffp-strict.json`): 39 injectors/mixins in 21 modules. A real launch without lenient mode (`ffs1`, strict isolation) reached the title screen and crashed on the first frame: the fixer's retarget of screen-api `GameRendererMixin#onBeforeRenderScreen` to `ForgeHooksClient.drawScreen` fails at runtime (LVT mismatch in `GameRenderer.render`). So screen-api went first.

| Fabric injector | Forge 47 | Manifest | Replacement |
|---|---|---|---|
| `GameRendererMixin#onBeforeRenderScreen`, `#onAfterRenderScreen` (render locals at `Screen.renderWithTooltip`) | the call moved to `ForgeHooksClient.drawScreenInternal` (Render.Pre/Post, also used for Forge GUI layers) | strip both | `ScreenRenderFabricMixin`: same two points in `drawScreenInternal`, current screen only, no local capture |
| `KeyboardMixin` 4 key press/release hooks on vanilla's lambda `(int, Screen, boolean[], int, int, int)` | `lambda$keyPress$5(int, boolean[], Screen, int, int, int)`, Pre/Post around the same `keyPressed`/`keyReleased` | strip all 4 | `ScreenKeyboardFabricMixin`: same points on Forge's lambda; a veto skips the screen's handler and Forge's Post |
| `MinecraftClientMixin#beforeScreenTick`, `#afterScreenTick` (lambda `$38`) | same body in `lambda$tick$42` | strip with `method` (retarget) | none needed |

Results: strict launch `ffs2` joins the world; screen probe 10/10 (render before/after counts equal, tick, F20 press/release order, F21 vetoed by ALLOW_KEY_PRESS never reaches the screen, remove), keys 4/4, interact 3/3, loot 6/6. Control `ffs2-off` (`noScreen`): 5/10 (no render or key events, F21 reaches the screen; tick/init/remove still fine). Strict census 60 -> 57 failing pairs. Regressions: NeoForge `m5s` census unchanged (11 / 0 excluded / 15 fixed), vanilla Fabric `rgw` and NeoForge mixed `rgnf` reach the world.

The strict launch also showed sleep at 3/9 (9/9 under lenient): strict exclusion drops whole mixin classes, and Fabric's `ServerPlayerEntityMixin extends LivingEntityMixin`, so one dead lambda injector in `LivingEntityMixin` took the server-side sleep hooks with it. Group 3.

## 3.11 Group 3: fabric-entity-events-v1 under strict require (2026-11-10)

Strict exclusion works on whole mixin classes, and Mixin reports only the first dead injector of a class per run, so this took three census rounds. `ServerPlayerEntityMixin extends LivingEntityMixin`: while `LivingEntityMixin` was excluded, the server-side sleep hooks (monsters, spawn point, direction) were gone too.

| Fabric injector | Forge 47 | Manifest | Replacement |
|---|---|---|---|
| `PlayerEntityMixin#redirectDaySleepCheck` (`isDay()` in `Player.tick`) | `ForgeEventFactory.fireSleepingTimeCheck` | strip | `PlayerSleepTickFabricMixin` (same rule as `ServerPlayerSleepTimeFabricMixin`) |
| `LivingEntityMixin#onIsSleepingInBed` (vanilla lambda `method_18405`) | `lambda$checkBedExists$9`, same `(BlockPos)Boolean` (calls `fireSleepingLocationCheck`) | retarget | none |
| `#modifyWakeUpPosition` (stop-sleeping lambda `method_18404`) | `lambda$stopSleeping$11`, `findStandUpPosition` unchanged | retarget | none |
| `#modifyBedForOccupiedState` (`getBlockState` in `sleep` + that lambda; ALLOW_BED swaps in a RED_BED stand-in) | unchanged in `startSleeping` and `$11` | retarget to both (`method` now takes a comma-separated list) | none |
| `#setOccupiedState` (redirects `Level.setBlock(OCCUPIED)`) | `BlockState.setBedOccupied`, whose default would put the stand-in RED_BED into the world | strip | `LivingEntityBedFabricMixin`: SET_BED_OCCUPATION_STATE on the block actually there, else the Forge bed's own `setBedOccupied`, else OCCUPIED if present, else nothing |
| `#onGetSleepingDirection` (wraps `BedBlock.getDirection` in `getBedOrientation`) | `BlockState.getBedDirection` | strip | `LivingEntityBedFabricMixin` wraps that |

Results: strict launch `ffs3` sleep 17/17 (new checks: still asleep 10 ticks later in daytime with the bed removed, ALLOW_BED, SET_BED_OCCUPATION_STATE with the real bed occupied, MODIFY_WAKE_UP_POSITION used, no bed placed where it was removed), keys 4/4, interact 3/3, loot 6/6, screen 10/10. No entity-events mixin excluded any more. Control `ffs3-off` (`noSleep`, strict): 5/17 (daytime sleep refused NOT_POSSIBLE_NOW, so the rest falls with it; retargets still on). Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world). Strict list by injector 39 -> 34 (pair counts move with Lithium's per-subclass entity targets).

## 3.12 Group 4: fabric-events-interaction-v0 server mixin (2026-11-10)

`ServerPlayerInteractionManagerMixin` holds five hooks: AttackBlockCallback (`handleBlockBreakAction` HEAD), UseBlockCallback (`useItemOn` HEAD), UseItemCallback (`useItem` HEAD), and PlayerBlockBreakEvents `#breakBlock` / `#onBlockBroken` (`Block.playerWillDestroy` / `Block.destroy` in `destroyBlock`, local capture). Forge moved both of those calls into its private `removeBlock(pos, canHarvest)` (`onDestroyedByPlayer`, then `destroy`), called once for creative and once for survival. Under strict require the two dead break hooks excluded the whole mixin, so the three working HEAD hooks went too (server-side attack / use block / use item callbacks: protection and tool mods).

Manifest: strip `#breakBlock`, `#onBlockBroken`. `ServerBlockBreakFabricMixin`: BEFORE before both `removeBlock` calls (false: CANCELED, `destroyBlock` returns false and vanilla resyncs the client), AFTER on `removeBlock`'s result when removed. Locals by type (MixinExtras `@Local`), no position capture.

Results: strict `ffs4` break probe 14/14 (survival stone: BEFORE + AFTER, gone; gold refused: CANCELED, false, kept; attack FAIL keeps dirt; use block / use item results from Fabric), all other probes unchanged (keys 4, loot 6, sleep 17, interact 3, screen 10). Control `ffs4-off` (`noBreak`): 8/14 (break events gone, gold broken; the three HEAD hooks still work, so the strip keeps them alive). Strict census 56 -> 55 pairs, 30 -> 29 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.13 Group 5: fabric-item-api-v1 (2026-11-10)

Port of the NeoForge 1.21.1 entry. Forge 47's crafting (`Recipe.getRemainingItems`), furnace and brewing stand ask the stack-aware `ItemStack.has/getCraftingRemainingItem()` -> `IForgeItem(stack)`; `ItemStack.getAttributeModifiers` asks `IForgeItem.getAttributeModifiers(slot, stack)`; `ItemStack.isCorrectToolForDrops` asks `IForgeItem.isCorrectToolForDrops(stack, state)`. Fabric redirects the stack-less vanilla calls, which are gone.

Manifest: veto `RecipeMixin`, `AbstractFurnaceBlockEntityMixin`, `BrewingStandBlockEntityMixin`; strip `ItemStackMixin#hookGetAttributeModifiers`, `#hookIsSuitableFor` (its damage hooks for CustomDamageHandler stay: Forge's `hurtAndBreak` still calls `hurt(int, RandomSource, ServerPlayer)`; under strict require the whole mixin had been excluded). `ItemFabricExtensionMixin` (Forge): item classes that override `FabricItem.getRecipeRemainder` / `getAttributeModifiers` / `isSuitableFor` answer Forge's `IForgeItem` methods; everything else keeps Forge's default, Forge items overriding the Forge methods keep theirs.

Results: strict `ffs5` item probe 11/11 (Fabric remainder, +5 attack, isSuitableFor obsidian / not stone, CustomDamageHandler amount 0; vanilla water bucket, diamond sword, diamond pickaxe unchanged), all other probes unchanged. Control `ffs5-off` (`noItem`): 7/11 (the four Fabric overrides ignored; vanilla items and the damage handler fine). Strict census 55 -> 50 pairs, 29 -> 25 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world). The probe items declare `implements FabricItem` because the probe compiles against the baked game without Fabric's interface injection; at runtime `Item` implements it through Fabric's `ItemMixin` either way.

## 3.14 Group 6: fabric-rendering-v1 colours, fabric-particles-v1 (2026-11-10)

Port of the NeoForge entries. Forge 47 keeps `BlockColors` / `ItemColors` in Maps keyed by `ForgeRegistries.BLOCKS/ITEMS.getDelegateOrThrow(..)` (vanilla: `IdMapper`), keys particle providers by `ResourceLocation` (vanilla: `Int2ObjectMap` by raw id), and replaced vanilla's `!state.is(GRASS_BLOCK)` dust-tint check with `IClientBlockExtensions.areBreakingParticlesTinted`.

Manifest: fabric-rendering-v1 veto `BlockColorsMixin`, `ItemColorsMixin`; fabric-particles-v1 veto `BlockDustParticleMixin`, strip `ParticleManagerAccessor#getFactories`, `replaceImpl` (re-based `ParticleFactoryRegistryImpl` registers through `ParticleEngine.register`); registry-sync veto `client.BlockColorsMixin`, `client.ItemColorsMixin`, `client.ParticleManagerMixin` (raw-id remapping of tables Forge does not key by raw id). Forge bridge mixins: `BlockColorsFabricMixin` / `ItemColorsFabricMixin` (ColorMapperHolder.get from Forge's maps with Forge's own delegate key - the first try used `builtInRegistryHolder()`, which is a different object, and `ColorProviderRegistry.get` came back null; initialize at `createDefault` RETURN), `TerrainParticleFabricMixin` (AND `ALLOW_BLOCK_DUST_TINT` into Forge's tint check).

Results: strict `ffs6b` colours probe 10/10 (Fabric block/item colours through Forge's maps, `ColorProviderRegistry.get` for the Fabric block, grass and the Fabric item, pending and direct particle providers, sprite set from the particle json, dust-tint event asked), all other probes unchanged. Control `ffs6-off` (`noColors`, `noParticles`): 4/10 (colours and the dust-tint event lost; the particle checks still pass because the particle registry is a replaced implementation, not a switchable mixin). Strict census 50 -> 43 pairs, 25 -> 18 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.15 Group 7: fabric-sound-api-v1, fabric-tag-api-v1, rendering-v1 shaders (2026-11-10)

Ports of the NeoForge entries; all three checked against Forge 47's classes first.

- Sound: Forge's `SoundEngine.play` streams through `SoundInstance.getStream(library, sound, looping)` (vanilla calls `SoundBufferLibrary.getStream`, which Fabric's `SoundSystemMixin` redirects). Veto `SoundSystemMixin`; `SoundEngineStreamFabricMixin` (Forge) redirects Forge's call and streams instances whose class overrides `FabricSoundInstance.getAudioStream` through the override.
- Tags: Forge's `TagFile` record already has `remove` (JSON `"remove"`) and `TagLoader` applies removals after all packs' additions. Veto `TagFileMixin` and `TagGroupLoaderMixin` (one unit); `TagFileFabricMixin` (Forge) makes `TagFile` a `FabricTagFile` through Forge's accessor and reads `"fabric:remove"` into `"remove"`.
- Shaders: Forge's `ShaderInstance` builds `new ResourceLocation(name)` and loads `<ns>:shaders/core/<path>.json` and each stage from its namespace; `#moj_import` goes through `ForgeHooksClient.getShaderImportLocation`. That is all `shader.ShaderProgramMixin` / `ShaderProgramImportProcessorMixin` add, so both are vetoed with nothing in their place.

Results: strict `ffs7` probe `sts` 13/13 (namespaced core shader with namespaced stages and `#moj_import` registered through `CoreShaderRegistrationCallback`, its uniform, vanilla shader; `"fabric:remove"` in the probe's own tag and in `minecraft:decorated_pot_sherds`, untouched entries kept, `TagFile` is a `FabricTagFile` with one removal parsed; a `FabricSoundInstance` overriding `getAudioStream` streams through it), all other probes unchanged. Control `ffs7-off` (`noTag`, `noSound`): 7/13 (both removals ignored, `TagFile` not a `FabricTagFile`, the override never streamed; the shader checks and untouched tag entries still pass). The shader vetoes have no switch (nothing replaces them), the probe is the evidence that Forge does the job alone. Strict census 43 -> 39 pairs, 18 -> 14 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.16 Group 8: fabric-lifecycle-events-v1 block-entity events (2026-11-10)

Port of the NeoForge entry. Forge 47's `LevelChunk.getBlockEntity(pos, type)` drops a removed block entity (`blockEntities.remove`) before the pending/create path, so Fabric's redirect, sliced from `createBlockEntity`, finds nothing. Under strict require the whole `client.WorldChunkMixin` was excluded, which also took the client `BLOCK_ENTITY_LOAD` / `UNLOAD` events in `setBlockEntity` / `removeBlockEntity` with it. `server.WorldChunkMixin` has the same sliced redirect; Mixin stops at the first dead injector on `LevelChunk`, so only the client one was reported.

Manifest: strip `client.WorldChunkMixin` and `server.WorldChunkMixin` handler `onRemoveBlockEntity(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;` (descriptor, because the other handlers share the name). `LevelChunkUnloadFabricMixin` (Forge) wraps Forge's first `Map.remove` in `getBlockEntity` and fires the server or client `BLOCK_ENTITY_UNLOAD`.

Results: strict `ffs8` probe `chunk` 5/5 (server LOAD on placing a chest; a block entity marked removed is dropped by `getBlockEntity` with one server UNLOAD; client LOAD and UNLOAD for the chest), all other probes unchanged. Control `ffs8-off` (`noChunkBE`): 4/5 (the removed-lookup UNLOAD lost; the client LOAD/UNLOAD come from Fabric's kept handlers, which strict require used to exclude). Strict census 39 -> 38 pairs, 14 -> 13 excluded; nothing new surfaced on `LevelChunk`. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.17 Group 9: fabric-transfer-api-v1 hoppers (2026-11-10)

Fabric's `HopperBlockEntityMixin` hooks vanilla `insert(Level, BlockPos, BlockState, Container)`, which Forge 47 turned into `ejectItems(.., HopperBlockEntity)` behind `VanillaInventoryCodeHooks.insertHook`, and captures `suckInItems`' locals, where Forge stores the `extractHook` result first. Under strict require the whole mixin was excluded: hoppers ignored blocks that are only a Fabric `ItemStorage`.

Manifest: veto `HopperBlockEntityMixin`. `HopperTransferFabricMixin` (Forge): same logic at the same point, after Forge's container lookups (`getAttachedContainer` / `getSourceContainer`) come back null: move one item to or from `ItemStorage.SIDED`. Forge's own `IItemHandler` hooks still run first. Fabric `Storage` <-> Forge `IItemHandler` interop (NeoForge has `TransferBridge`) is not done for Forge yet.

Results: strict `ffs9` probe `hopper` 4/4 (a block with no block entity, only a Fabric `ItemStorage`, above a hopper and another beside it: the hopper pulls both items from the top one and pushes both into the side one), all other probes unchanged. Control `ffs9-off` (`noHopper`): 2/4 (nothing moves). Strict census with group 10: 38 -> 36 pairs, 13 -> 11 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.18 Group 10: fabric-content-registries-v0 furnace fuel (2026-11-10)

Forge 47's `isFuel` / `getBurnDuration` ask `ForgeHooks.getBurnTime`, which falls back to `VANILLA_BURNS`; `ForgeHooks.updateBurns` refills that from `AbstractFurnaceBlockEntity.getFuel()` on every tag reload. Fabric's `AbstractFurnaceBlockEntityMixin` adds `FuelRegistry` entries at `getFuel` RETURN (`fuelTimeMapHook`) and redirects vanilla's `getFuel()` calls in `isFuel` / `getBurnDuration` to its cache; those calls are gone on Forge, so under strict require the whole mixin, hook included, was excluded and Fabric fuels were lost.

Manifest: strip `canUseAsFuelRedirect` and `getFuelTimeRedirect`; `fuelTimeMapHook` stays and feeds Forge's map. Nothing on the Forge side. A `FuelRegistry` change after the last tag reload shows up at the next one (Fabric's own cache also resets on tag reload).

Results: strict `ffs9` probe `fuel` 5/5 (Fabric-added rotten flesh is a fuel with Forge burn time 1234 and `FuelRegistry.get` 1234; Fabric-removed stick no longer burns; coal still 1600). No bridge switch (strips only); before this group the strict census excluded the mixin.

## 3.19 Group 11: fabric-resource-conditions-api-v1 on loot data (2026-11-10)

Fabric's `LootManagerMixin#applyResourceConditions` sits at HEAD of vanilla's per-file lambda `(LootDataType, Map, ResourceLocation, JsonElement)`; Forge 47's `lambda$scheduleElementParse$4` also takes the `ResourceManager` (for `"forge:conditions"`), so the handler no longer matches (`lambda_renamed`), and under strict require the whole mixin was excluded: loot tables, predicates and item modifiers with `"fabric:load_conditions"` loaded whatever their conditions said.

Manifest: strip `applyResourceConditions`; retarget `runAsync` / `runAsyncEnd` (registry access for the conditions while the files load) to `lambda$scheduleElementParse$5(ResourceManager, LootDataType, Map)`, vanilla's shape under a Forge name. `LootConditionsFabricMixin` (Forge): the same check at HEAD of Forge's per-file lambda, skipping a file whose conditions don't hold.

Results: strict `ffs11` probe `conditions` 3/3 (`amber_ffprobe:cond_off`, needing a mod that isn't there, not loaded; `cond_on`, needing `minecraft`, loaded; a vanilla chest table loaded), all other probes unchanged. Control `ffs11-off` (`noLootConditions`): 2/3 (`cond_off` loaded). Strict census 36 -> 35 pairs, 11 -> 10 excluded. Regressions: one set with group 12, see 3.20.

## 3.20 Group 12: fabric-dimensions-v1 (2026-11-10)

Fabric's `EntityMixin` targets `ServerPlayer` and `Entity`: `getTeleportTarget` (HEAD of `findDimensionEntryPoint`, answers `FabricDimensions.teleport`'s custom target) and `stopEndSpecificBehavior` (redirects `Level.END` in vanilla `changeDimension(ServerLevel)` so a custom teleport out of the End doesn't roll the credits). Forge 47 moved the body to `changeDimension(ServerLevel, ITeleporter)`; under strict require the whole mixin was excluded, so `FabricDimensions.teleport` failed outright (`ClassCastException`: not a `Teleportable`). A retarget to the Forge overload is not enough: only `ServerPlayer`'s reads `Level.END`, and Mixin checks `require` per target class, so the `Entity` application still fails (first try, `ffs12`). `RegistryCodecsMixin` wraps `fullCodec`'s map in `FailSoftMapCodec` (a world keeps loading when a dimension's mod is gone); Forge's `fullCodec` already uses its own `LenientUnboundedMapCodec`.

Manifest: veto `RegistryCodecsMixin` (nothing in its place); strip `EntityMixin#stopEndSpecificBehavior`. `ServerPlayerEndFabricMixin` (Forge): the same redirect on `ServerPlayer.changeDimension(ServerLevel, ITeleporter)` only. It reads the custom target from the field Fabric's `EntityMixin` merges into the class; because the mixin targets both classes, `ServerPlayer` has its own copy and a player's `Teleportable` setter writes that one (the first try read `Entity`'s and still rolled the credits).

Results: strict `ffs12d` probe `dim` 4/4 (a pig teleported to the Nether at the exact custom position and yaw), all other probes unchanged; separate run `ffs12p3` probe `dimplayer` 4/4 (player to the End at a custom target, back to the Overworld at a custom target: no credits, right dimension and position). Control `ffs12p-off` (`noDimEnd`): 1/4 (credits rolled, player left in the End). Strict census 35 -> 33 pairs, 10 -> 8 excluded. Regressions for groups 11 and 12: unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

Probe fix in the same step: on a slow run (`ffs12c`, world reached after ~100 s instead of ~47 s) the hopper probe read 2/4, one item pulled and none pushed yet; it counted client ticks while the hopper runs on server ticks. It now reports once both items have arrived, or at client tick 245 at the latest; `ffs12d` 4/4, control `ffs12h-off` (`noHopper`) still 2/4.

## 3.21 Group 13: fabric-model-loading-api-v1 before/after bake (2026-11-10)

Fabric's `ModelLoaderBakerImplMixin` targets `bake`, which its refmap resolves to vanilla `ModelBakerImpl.bake(ResourceLocation, ModelState)`; Forge 47 turned that into a one-line delegate to `bake(ResourceLocation, ModelState, Function<Material, TextureAtlasSprite>)`, which now holds the `getModel` call and the two `bake` calls Fabric hooks. The census fixer's own retarget was refuted by its verifier, so under strict require the whole mixin was excluded and `ModelModifier.BeforeBake` / `AfterBake` never ran.

Manifest: retarget `invokeModifyBeforeBake` and both `invokeModifyAfterBake` redirects to the three-argument `bake`. The handlers only capture the leading `ResourceLocation, ModelState` arguments (same order) and the call arguments, so nothing else changes; the redirects get the sprite function Forge passes to the call.

Results: strict `ffs13` probe `model` 4/4 (before- and after-bake modifiers called, before-bake saw `minecraft:stone#`, the after-bake result is the model the `ModelManager` returns for stone), all other probes unchanged except one screen check that saw a stray real key code 343 (left Windows key) from the desktop; rerun `ffs13s` (`screen,model`): screen 10/10, model 4/4. No bridge switch (manifest retarget only). Strict census 33 -> 32 pairs, 8 -> 7 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.22 Group 14: fabric-rendering-fluids-v1 handlers (2026-11-10)

Fabric 0.92's `FluidRendererMixin` hooks vanilla `LiquidBlockRenderer.tesselate`: HEAD dispatch to a registered `FluidRenderHandler`, then inside the handler's call the sprite array, tint, water-overlay sprite and lava check, plus `getOverlayBlock` / `modSideSpriteForOverlay` on vanilla's `neighbour.getBlock() instanceof HalfTransparentBlock || LeavesBlock` side-overlay check. Forge 47 keeps the locals the first group needs (sprite array after `ForgeHooksClient.getFluidSprites`, the int tint after `getTintColor`, the `waterOverlay` read) but replaced the overlay check with `BlockState.shouldDisplayFluidOverlay`. Under strict require the first dead injector, `modSideSpriteForOverlay`, excluded the whole mixin: no Fabric fluid handler ran at all.

Manifest: strip `getOverlayBlock` and `modSideSpriteForOverlay`; the rest applies to Forge's `tesselate` (with the 3-slot sprite container from 3.9). `LiquidOverlayFabricMixin` (Forge): an explicit `setBlockTransparency` override wins over Forge's `shouldDisplayFluidOverlay`, otherwise Forge's answer (port of the NeoForge overlay redirect).

Results: strict `ffs14` all other probes unchanged; probe `fluid` first 3/5. Not a bridge fault: Fabric's `onFluidRendererReload` re-registers its default water/lava handlers through `register()` on every resource reload, which replaces a water override registered at init (same on Fabric; mods register their own fluids). The probe now registers its green water handler after the reload: `ffs14b` 5/5 (Fabric handler rendered the water, green tint with no red on the vertices, lava still renders through Forge's path, no `ArrayIndexOutOfBounds`). The side-overlay redirect is not probed (it only changes which sprite a side uses). No bridge switch for the restored Fabric handlers. Strict census 32 -> 31 pairs, 7 -> 6 excluded. Regressions unchanged (`m5s` 11/0/15, `rgw`, `rgnf` in world).

## 3.23 Networking and registry sync over the integrated server (2026-11-10)

The census only covers mixins; this checks behaviour. Probes `net` (NetProbe) and `regsync` (RegSyncProbe), run strict with `screen`.

Networking (fabric-networking-api-v1 1.3.15): no bridge part. Fabric's own play addons and `handleCustomPayload` hooks work beside Forge 47's channels (they apply under strict require; the census has no networking pairs).
- Probe `net` 13/13:
  - a raw-buffer channel both ways (ping C2S 41, pong S2C 42);
  - the FabricPacket/PacketType variant both ways (21 -> 42);
  - client and server `canSend` (so the `minecraft:register` exchange works);
  - play JOIN on both sides;
  - the channel REGISTER events on both sides (S2CPlayChannelEvents on the server, C2SPlayChannelEvents on the client);
  - the server handler runs on the server thread after `server.execute`, and the client handler on the client thread.
- The first run was 11/12 because the probe listened to the wrong side's REGISTER event. That was a probe error, not the bridge.
- There is no bridge-off control, because no bridge part exists here. Only the integrated server (memory connection) is covered. A remote client over a real socket (Forge's FML handshake plus Fabric's `minecraft:register`) needs a dedicated server, which is a real test.

Registry sync (fabric-registry-sync-v0 2.4.1):
- A block and item registered the Fabric way are in `ForgeRegistries.BLOCKS/ITEMS`. Forge's `ForgeRegistry.getID` equals the vanilla raw id (1003 / 1255 in this pack).
- A `FabricRegistryBuilder` custom registry (SYNCED) is in the root registry, keeps its entries and raw ids 0/1, has SYNCED+MODDED, and refuses late registration after the freeze.
- Fabric dynamic (data-pack) registries: `DynamicRegistries.register` and `registerSynced`.
  - First run: no entries loaded. Forge 47 patches `RegistryDataLoader.registryDirPath` to `ForgeHooks.prefixNamespace` (namespace/path for every non-minecraft registry). Fabric's `RegistryLoaderMixin#prependDirectoryWithNamespace` (RETURN) then prefixes the namespace again, so the JSON was looked up one directory too deep.
  - Manifest fix: strip that handler. Both loaders use the same layout, `data/<ns>/<ns>/<path>/`, so Forge's prefix alone is right.
  - After the fix: the server loads both registries' entries, the synced one reaches the client, and the unsynced one does not.
- Probe `regsync` 14/14. Control `-Damber.fabricbridge.noDynReg=true` (WorldLoaderDynamicRegistriesFabricMixin off): 10/14. The Fabric dynamic registries are missing on the server and the client.
- In singleplayer the client shares the server's static registries. Fabric skips its own registry sync for the host, and Forge's handshake only syncs Forge registries. So these need a dedicated server:
  - ids of a Fabric custom registry on a remote client, which depends on Fabric's `fabric:registry/sync` packet beside Forge's handshake;
  - the synced dynamic registry over the network codec. `DynamicRegistries.addSyncedRegistry` reassigns `RegistrySynchronization.NETWORKABLE_REGISTRIES`, which is still open.
- Not downloaded or set up (needs the maintainer's OK): a Forge 1.20.1 dedicated server jar.

Strict census unchanged at 31 pairs, 6 excluded (the stripped handler had applied, wrongly). Regressions unchanged.

## 4. First steps when this becomes code

1. Get Fabric API for 1.20.1 (official jar, through Amber's normal download path, after the maintainer's OK) and a small 1.20.1 Fabric corpus (Mod Menu, Lithium/Sodium-free set first).
2. Census of Fabric API 1.20.1 on our Forge platform with no bridge: the failing-mixin list replaces section 4 of the NeoForge design for this platform.
3. Port D1 (window hook) and prove registry sync with a Fabric test mod (block, item, a synced custom registry): ids equal on both sides, world reload keeps content. This answers the `ForgeRegistry` question in 3.2.
4. Networking pass-through (3.3) with a payload round-trip test mod.
5. Then the (c) parts in the order test mods need them, starting with those that only need a package rename (interaction, particles, colours, item-group, screen-handler).

## 5. Open questions for the maintainer

- Is Fabric-on-Forge 1.20.1 wanted for v1, or after the NeoForge bridge is complete? The work is about the size of the NeoForge bridge minus the shared parts (section 1).
- Which Fabric API 1.20.1 version to target (the last 1.20.1 release is the natural choice).
- OK to download the official Fabric API 1.20.1 jar and a few Fabric 1.20.1 mods into the prototype's test dirs for the census.
