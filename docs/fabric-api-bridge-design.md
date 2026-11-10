# Fabric API bridge on NeoForge: design (v1)

Date: 2026-11-05. Platform: NeoForge 21.1.265 / MC 1.21.1, official Fabric API 0.116.17 (49 modules).
Decision by the maintainer: the bridge is Amber's own code. Forgified Fabric API is not downloaded, vendored or copied. Its ideas may be learned from (Apache-2.0), but nothing of its code is used.

## 1. Goals and rules

- **The official jars run.** The Fabric API jars run as published. Every Fabric mod sees the official API classes, `Event` objects and interfaces, binary-identical to the published ones. There is no forked API.
- **Each module gets one of three verdicts** (section 4):
  - **(a) runs as is**: its mixins apply and nothing in NeoForge does the same job.
  - **(b) fixer**: its failing mixins are repaired by the bake-time MixinFixer and verified by the census like any other mod.
  - **(c) re-based**: the module's job is already done by a NeoForge system. Its own mixins are disabled at bake time, and our code makes the module's public API work on top of that NeoForge system.
- **Granularity.** A module can be split: some of its mixins are fixed or kept, and the job of one specific mixin is re-based. The design table gives the verdict per module and lists the re-based parts.
- **Reporting.** Nothing is silent. A bridged mixin is reported as `fabric_api_bridged` (info). Something the bridge does not cover yet is reported as `fabric_api_unsupported` (warning, with the API surface that will not work). Mixins that are still broken stay isolated as `mixin_incompatible`, as before.

## 2. Mechanisms

| Id | Mechanism | What it is | Used for |
|---|---|---|---|
| M0 | Bake-time disable | Bridge manifest `core/res/amber-ul/bridge/fabric-api-<ver>-neoforge-<mc>.json` lists, per module, the mixin classes to drop. Same exclusion filter as isolation, but reported as bridged and keyed into the isolation key (manifest fingerprint). | every (c) part |
| M1 | Driver | Our NeoForge event listeners (registered on `NeoForge.EVENT_BUS` / the mod bus of a synthetic `amber_fabric_bridge` NeoForge mod container) call the **official** Fabric `Event` invokers (`ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server)` …). Result/cancellation is mapped back onto the NeoForge event. | event-only modules: lifecycle, interaction, entity events, screen events, item-group events |
| M2 | Impl substitution | Where API classes delegate into `net.fabricmc.fabric.impl.<module>.*`, we provide our own classes with those binary names (served through the class loader's `overrides`, like fixer output), written against NeoForge. The member set to provide is measured: every `impl.*` member referenced from the official API classes of that module, from other Fabric API modules, and from the mod corpus (today: no corpus mod uses `impl.*` of a re-based module; only c2me touches `impl.base`). | networking, registry-sync, resource-loader, particles registry, screen-handler, custom ingredients, colour providers |
| M3 | Extension adapter | Our own mixins into vanilla classes that add NeoForge extension-interface methods (`IItemExtension`, `SoundInstance#getStream`, …) delegating to the Fabric interface-injected methods (`FabricItem`, `FabricSoundInstance`, …), so NeoForge's own call sites honour what Fabric mods override. | item API, sound API |

All bridge code lives in a new source tree `fabricbridge/src` (package `amber.ul.fabricbridge`). It is compiled against the official Fabric API jars and NeoForge, and is loaded only when the NeoForge platform is active and Fabric API is in the instance. That is the same pattern as `fmlbridge`.

## 3. Lifecycle: when Fabric init runs on NeoForge (design decision D1)

Today, Fabric `main` and `client` entrypoints run at Fabric's own hook in `Minecraft.<init>`. On the NeoForge platform that hook comes **before** NeoForge's `ClientModLoader.begin`. At that point the vanilla registries are already frozen by `Bootstrap`, while NeoForge expects all registry writes to happen inside its registration window: `GameData.unfreezeData()` … `RegisterEvent` per registry … `GameData.freezeData()`.

**D1 (revised after the phase-5 brief, as implemented).** On NeoForge, Fabric `main` and `client` entrypoints are deferred out of `Minecraft.<init>` and run at the **HEAD of `GameData.postRegisterEvents`**: after `NewRegistryEvent` and `unfreezeData()`, before the first `RegisterEvent` is posted. The hook is a mixin in Amber's bridge mod (`fabricbridge`, `GameDataMixin` → `FabricWindow.run()`). During the window the root registry `BuiltInRegistries.REGISTRY` is also unfrozen (so `FabricRegistryBuilder` can add a registry) and refrozen in a `finally`. `trackModdedRegistry` is never called. preLaunch stays where it is.

Why not the first `RegisterEvent` (the original D1): the window is then mid-dispatch for one registry, and a Fabric mod creating a new registry would miss NeoForge's `NewRegistryEvent` handling. HEAD of `postRegisterEvents` is the one point where every registry, the root included, can take writes and every `RegisterEvent` is still to come.

Consequences:
- A Fabric `Registry.register(...)` in `onInitialize` is an ordinary registration inside NeoForge's window. NeoForge snapshots it, assigns ids, syncs it (`SyncRegistries` configuration task) and remaps it on load. The fabric-registry-sync-v0 machinery is therefore not needed: verdict (c).
- Fabric's relative order is kept: all `main`, then all `client`, both before any NeoForge `FMLCommonSetupEvent`. `client` init now runs after `Minecraft.<init>`'s `GameOptions` exist, which is why key bindings go through `RegisterKeyMappingsEvent` (fabric-key-binding-api-v1 re-based, M2).
- If the window never comes (no NeoForge registration), the launcher reports `fabric_init_not_run` (error) at the title screen.
- Verified in 5a: `[amber-fabric-bridge] Fabric 入口点在 NeoForge 注册窗口运行 (GameData.postRegisterEvents 开头)`; registry-sync proof with a test mod is step 3 of §7.

Implementation refinements found during 5a (all data in `core/res/amber-ul/bridge/fabric-api-neoforge-1.21.1.json`):
- recipe-api: all 4 mixins vetoed as one unit (IngredientMixin adds `requiresTesting()`, which ShapelessRecipeMixin calls).
- networking: the per-connection Fabric network addon mixins are vetoed on both sides (they send their own `minecraft:register`, which NeoForge owns; ClassCastException in NeoForge's codec), with the init entrypoints skipped.
- A Mixin bug surfaced on NeoForge's `Pack` (its constructor builds child `Pack`s before field initializers run): fixed generically at game-bake time by `CtorSelfNew` (self-`new` in a constructor is hoisted into a static synthetic factory).

## 4. Module table

Census evidence (corpus-nf8, fixer v3): 57 failing pairs on NeoForge, of which 39 are isolated and all 39 are in Fabric API; 6 Fabric API mixins are fixed. In the "census" column, "fails k (fixed j)" counts failing mixin classes.

| Module | Census on NeoForge | Verdict | NeoForge system it maps to | Re-based part / notes |
|---|---|---|---|---|
| fabric-api-base | ok | a | — | — |
| fabric-api-lookup-api-v1 | ok | a | (capabilities are separate; no interop in v1) | Interop with `Capabilities` is later work |
| fabric-biome-api-v1 | ok | **c** | `BiomeModifier` (`NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS`), `ModifiableBiomeInfo` | NeoForge serves `Biome#getGenerationSettings`/`getMobSettings` from `ModifiableBiomeInfo`, so Fabric's direct edits of the biome's fields would be invisible. We register one code-defined `BiomeModifier` that runs Fabric's registered selectors and modifiers in NeoForge's ADD/REMOVE/MODIFY phases. Disable `BiomeModifications` application mixins (MinecraftServerMixin and similar). Not needed for 5a. |
| fabric-block-api-v1 | fails 2 (fixed 2) | b | — | — |
| fabric-block-view-api-v2 | fails 1 | **c** (partial) | `ModelData` / `ModelDataManager`, `IBlockAndTintGetterExtension` | Keep the interface mixins. Replace `ChunkRendererRegionBuilderMixin` (`RenderRegionCache.createRegion` has NeoForge's extra parameter): `getBlockEntityRenderData` reads `RenderDataBlockEntity#getRenderData` from the live level when the region is built. |
| fabric-blockrenderlayer-v1 | ok | a | (`ItemBlockRenderTypes.setRenderLayer` still honoured) | — |
| fabric-client-tags-api-v1 | ok | a | — | — |
| fabric-command-api-v1/v2, fabric-commands-v0 | ok | a | (`RegisterCommandsEvent` fires in the same constructor) | Both run; they are independent. |
| fabric-content-registries-v0 | fails 1 | **c** (partial) | `FurnaceFuelBurnTimeEvent`; data maps `NeoForgeDataMaps.FURNACE_FUELS`, `COMPOSTABLES` | `FuelRegistry` is answered from `FurnaceFuelBurnTimeEvent`, and the furnace mixin is disabled. Composting, stripping, tilling and flattening registries must be checked at runtime: NeoForge reads data maps and `ItemAbility` first. Anything ignored there is re-based to a `DataMapsUpdatedEvent`/`BlockEvent.BlockToolModificationEvent` driver. |
| fabric-convention-tags-v1/v2 | ok | a | (same `c:` namespace) | — |
| fabric-crash-report-info-v1 | ok | a | — | — |
| fabric-data-attachment-api-v1 | ok | a | — | Its sync (1.4) uses networking, so it depends on the networking bridge. |
| fabric-data-generation-api-v1 | fails 1 | isolate | — | Development-only. Reported `fabric_api_unsupported` (datagen) and left isolated. |
| fabric-dimensions-v1 | ok | a | — | — |
| fabric-entity-events-v1 | fails 2 | **c** (partial) | `LivingIncomingDamageEvent`, `LivingDeathEvent`, `PlayerEvent.Clone`, `PlayerEvent.PlayerRespawnEvent`, `CanPlayerSleepEvent`, `PlayerWakeUpEvent` | M1 driver for the hooks of the 2 failing mixins (`LivingEntityMixin`: ALLOW_DAMAGE / ALLOW_DEATH / AFTER_DEATH; `ServerPlayerEntityMixin`: COPY_FROM / AFTER_RESPAWN). The remaining mixins of the module stay. |
| fabric-events-interaction-v0 | fails 3 | **c** | `PlayerInteractEvent.LeftClickBlock/RightClickBlock/RightClickItem/EntityInteract(Specific)`, `AttackEntityEvent`, `BlockEvent.BreakEvent` | NeoForge already hooks exactly these sites. Running both would double-fire or misorder them. M1 drivers for all 5 callbacks plus PlayerBlockBreakEvents BEFORE/AFTER/CANCELED. The whole module's mixins are disabled. Client pick-block (`ClientPickBlockApplyCallback` and similar) is mapped to the client `PlayerInteractEvent` variants, or reported unsupported. |
| fabric-game-rule-api-v1 | ok | a | — | — |
| fabric-item-api-v1 | fails 9 | **c** | `IItemExtension`, `ItemAttributeModifierEvent`, `ModifyDefaultComponentsEvent`, `ItemTooltipEvent`, `IItemExtension#supportsEnchantment` | M3: our mixin into `Item` implements the `IItemExtension` methods by delegating to `FabricItem` overrides: `getCraftingRemainingItem(stack)` → `getRecipeRemainder`; `shouldCauseBlockBreakReset` → `allowContinuingBlockBreaking`; `shouldCauseReequipAnimation` → `allowComponentsUpdateAnimation`; `supportsEnchantment`/`isPrimaryItemFor` → `canBeEnchantedWith`; `damageItem` → `CustomDamageHandler`; `getEquipmentSlot` → `EquipmentSlotProvider`. Each delegation is only active when the Fabric method is actually overridden, to avoid recursion. M1: `DefaultItemComponentEvents.MODIFY` → `ModifyDefaultComponentsEvent`; `ModifyItemAttributeModifiersCallback` → `ItemAttributeModifierEvent`; `EnchantmentEvents.ALLOW_ENCHANTING` → `supportsEnchantment` path. All 20 mixins are disabled except the interface-injection ones (`ItemMixin` / `ItemSettingsMixin` / `ItemStackMixin` for `FabricItem`/`FabricItem.Settings`/`FabricItemStack`, checked mixin by mixin). |
| fabric-item-group-api-v1 | ok (applies) | **c** | `BuildCreativeModeTabContentsEvent`, `CreativeModeTabRegistry`, NeoForge creative tab paging | The mixins apply, but they bring a second creative-tab paging system on top of NeoForge's (`CreativeInventoryScreenMixin`), and `ItemGroupEvents.modifyEntriesEvent` would fire next to NeoForge's own contents event. M1: `ItemGroupEvents.MODIFY_ENTRIES_ALL` / `modifyEntriesEvent(key)` → `BuildCreativeModeTabContentsEvent`. `FabricItemGroup.builder()` → vanilla `CreativeModeTab.builder()` (NeoForge sorts and pages). All 4 mixins are disabled. |
| fabric-key-binding-api-v1, fabric-keybindings-v0 | ok | a | (`RegisterKeyMappingsEvent` not needed) | Check at runtime that keys appear once in Controls. |
| fabric-lifecycle-events-v1 | fails 1 | **c** (partial) | `ChunkEvent.Unload` (client) | M1 for the client chunk unload hook (`WorldChunkMixin`). The other 17 mixins stay, since they don't collide with NeoForge. Server and client start, stop and tick events keep Fabric's own hook points, so the vanilla timing is exact. |
| fabric-loot-api-v2/v3 | ok | a | (`LootTableLoadEvent` coexists) | — |
| fabric-message-api-v1 | ok | a | — | — |
| fabric-model-loading-api-v1 | fails 1 (fixed 1) | b | (`ModelEvent.RegisterAdditional`, `ModifyBakingResult` as fallback) | Runtime check: models from Fabric `ModelLoadingPlugin` must appear. If they don't, re-base to `ModelEvent`. |
| fabric-networking-api-v1 | fails 2 (fixed 2) | **c** | `RegisterPayloadHandlersEvent` / `PayloadRegistrar`, `RegisterConfigurationTasksEvent`, `ICustomConfigurationTask`, `PacketDistributor`, NeoForge channel negotiation (`minecraft:register`) | See section 5. All 28 mixins are disabled. M2 replaces `impl.networking.*`. |
| fabric-object-builder-api-v1 | fails 2 | b + isolate | (`VillagerTradesEvent`) | Builders register through vanilla calls, so they run as is. `TradeOffersTypeAwareBuyForOneEmeraldFactoryMixin` and the `HangingSignEditScreen` texture mixin stay isolated, with a warning. |
| fabric-particles-v1 | fails 2 | **c** (partial) | `RegisterParticleProvidersEvent` | `ParticleFactoryRegistry` (M2 `impl.client.particle`): registrations are buffered and replayed in `RegisterParticleProvidersEvent` (`registerSpecial` / `registerSpriteSet`). NeoForge changed `ParticleEngine.providers` to a map keyed by `ResourceLocation`, which breaks the accessor. `BlockDustParticleMixin` stays isolated (cosmetic). |
| fabric-recipe-api-v1 | fails 1 | **c** | `ICustomIngredient`, `IngredientType` (`NeoForgeRegistries.Keys.INGREDIENT_TYPES`) | A Fabric `CustomIngredientSerializer` is registered as a NeoForge `IngredientType` with an adapter `ICustomIngredient`. `CustomIngredient#toVanilla()` → `new Ingredient(adapter)`. The ingredient-support handshake is removed, because NeoForge syncs ingredient types as a registry. All mixins are disabled. |
| fabric-registry-sync-v0 | fails 4 | **c** | `RegisterEvent`, `NewRegistryEvent`, `RegistryBuilder#sync`, `GameData` freeze/snapshot, `SyncRegistries` task, `RegisterColorHandlersEvent` (client colours) | See D1. The API is kept: `FabricRegistryBuilder` → a `MappedRegistry` registered into `BuiltInRegistries.REGISTRY` inside the window and flagged for NeoForge sync. `RegistryAttribute.SYNCED` → sync, `PERSISTED` → persisted by NeoForge. `RegistryEntryAddedCallback` → driver on `MappedRegistry` add (an M3 mixin of ours into NeoForge's patched register path). `DynamicRegistries.register/registerSynced` → `DataPackRegistryEvent.NewRegistry`. All 21 mixins are disabled. |
| fabric-renderer-api-v1 | ok | a | — | — |
| fabric-renderer-indigo | fails 1 | **c** (partial) | `SectionCompiler.compile` (NeoForge signature, `AddSectionGeometryEvent`), `ModelData` | Our own hand-written port of `SectionBuilderMixin` for the NeoForge `compile`. It routes non-vanilla `FabricBakedModel` blocks through Indigo's terrain context and leaves vanilla models on NeoForge's path. The other Indigo mixins stay. |
| fabric-renderer-registries-v1, fabric-rendering-v0, fabric-rendering-data-attachment-v1 | ok | a | — | Depend on block-view. |
| fabric-rendering-fluids-v1 | fails 1 | **c**, later | `IClientFluidTypeExtensions`, `RegisterClientExtensionsEvent` | NeoForge renders fluids through `FluidType` client extensions. A Fabric fluid needs a `FluidType`, which is open research. Reported `fabric_api_unsupported` until then. Not needed for 5a. |
| fabric-rendering-v1 | fails 4 | **c** (partial) | `RegisterColorHandlersEvent.Block/Item`; core shaders: NeoForge already accepts namespaced core shader ids | `ColorProviderRegistry` (M2): buffered and replayed in `RegisterColorHandlersEvent`, and later registrations go straight to `BlockColors.register`. `ShaderProgramMixin` / `ShaderProgramImportProcessorMixin` are disabled: NeoForge does that natively (`native_substitute`). All other rendering events keep their own mixins. |
| fabric-resource-conditions-api-v1 | ok | a | (coexists with `neoforge:conditions`) | — |
| fabric-resource-loader-v0 | fails 1 | **c** | `AddReloadListenerEvent` (server data), `RegisterClientReloadListenersEvent`, `AddPackFindersEvent`, NeoForge `mod_resources` pack | One pack source: Fabric mods' resources must reach the game exactly once, through NeoForge's mod resources (checked in the 5a run). `ResourceManagerHelper.registerReloadListener` → events, with Fabric's `IdentifiableResourceReloadListener` dependency ordering applied when they are handed over. `registerBuiltinResourcePack` → `AddPackFindersEvent`. Pack-repository, config-phase and known-pack mixins are disabled. Language and font mixins are checked one by one. |
| fabric-screen-api-v1 | fails 2 (fixed 1) | b, plus the fixer's new `lambda_renamed` support | — | `MinecraftClientMixin` targets `lambda$tick$32`, but NeoForge renumbers lambdas. The fixer gets a LAMBDA_RENAMED type: match the clean lambda to the patched one by body, after the same calls. This also covers `entity-events LivingEntityMixin` and `item EnchantmentHelperMixin`. If that fails, M1 driver on `ScreenEvent`. |
| fabric-screen-handler-api-v1 | fails 1 | **c** | `IMenuTypeExtension.create(IContainerFactory)`, `ServerPlayer#openMenu(MenuProvider, Consumer<RegistryFriendlyByteBuf>)` | `ExtendedScreenHandlerType` is created as a NeoForge `IContainerFactory`-backed menu type. Opening an `ExtendedScreenHandlerFactory` writes its data with the type's packet codec into NeoForge's open-menu buffer, and the client decodes with the same codec. Mixins are disabled. |
| fabric-sound-api-v1 | fails 1 | **c** | `SoundInstance#getStream` (NeoForge default method) | M3: our mixin on `SoundInstance` implements `getStream` by delegating to `FabricSoundInstance#getAudioStream` when it is overridden. `SoundSystemMixin` is disabled. |
| fabric-tag-api-v1 | fails 1 | b / isolate | — | `TagFileMixin` fails on NeoForge's extended `TagFile` codec (`remove` entries). Fixer first; otherwise isolated with a warning. Investigated after 5a. |
| fabric-transfer-api-v1 | ok | a | (no `IItemHandler`/`IFluidHandler` interop in v1) | Interop is later work. |
| fabric-transitive-access-wideners-v1 | ok | a | — | — |

Summary over the 49 modules:
- 30 are (a) or (b).
- 17 are (c), whole or partial: biome, block-view, content-registries, entity-events, events-interaction, item, item-group, lifecycle (one hook), networking, particles, recipe, registry-sync, indigo, rendering-v1, resource-loader, screen-handler, sound.
- 2 are reported unsupported for now: rendering-fluids (c, later) and data-generation (isolated). This covers all 32 failures that overlap NeoForge systems, plus the modules whose mixins apply but misbehave (item-group, biome).

## 5. Networking (the largest re-base)

Fabric API model: `PayloadTypeRegistry.playS2C()/playC2S()/configurationS2C()/configurationC2S().register(id, codec)`, then global or per-connection receivers (`ServerPlayNetworking.registerGlobalReceiver`, `ClientPlayNetworking.registerReceiver`…). Channels are negotiated with `minecraft:register` and queried with `canSend`. There are configuration tasks (`ServerConfigurationConnectionEvents.CONFIGURE` + `addTask`/`completeTask`) and login queries.

NeoForge model: every payload type is registered once, in `RegisterPayloadHandlersEvent`, with a codec and a handler. Optional payloads (`registrar.optional()`) don't break the connection when the other side lacks them. NeoForge negotiates channels itself. Configuration tasks are `ICustomConfigurationTask`s registered in `RegisterConfigurationTasksEvent`.

Bridge (`impl.networking.*` replaced, M2):
- `PayloadTypeRegistry.register` records the type. Fabric requires types to be registered during init, and D1 puts init before NeoForge's `RegisterPayloadHandlersEvent` (mod bus, after registries), so we replay every recorded type there as **optional**. The handler looks up Fabric receivers at the moment the payload arrives. Global receivers and per-connection receivers are tables we own. A type registered after the event gets a `fabric_api_late_payload` warning.
- `canSend(player, id)` → NeoForge's negotiated channel set for that connection (`NetworkRegistry.hasChannel`).
- `ServerPlayNetworking.send` → `PacketDistributor.sendToPlayer`, or `connection.send(new ClientboundCustomPayloadPacket(payload))`. The client direction is symmetric.
- Connection events (`ServerPlayConnectionEvents.INIT/JOIN/DISCONNECT`, the client ones, `S2CPlayChannelEvents.REGISTER/UNREGISTER`) are M1 drivers on `PlayerEvent.PlayerLoggedInEvent`/`LoggedOutEvent` and the client `ClientPlayerNetworkEvent` events. INIT/JOIN order follows Fabric's documentation.
- Configuration: we register one `ICustomConfigurationTask` per connection. It fires `ServerConfigurationConnectionEvents.CONFIGURE` and runs the Fabric tasks the mods add, finishing when `completeTask` is called.
- Login queries (`ServerLoginNetworking`) are reported unsupported in v1. Few mods use them, and NeoForge has no public login-phase hook.

Milestone order: 5a needs only that nothing breaks the handshake (Fabric API's own `fabric:*` registry-sync and resource-loader configuration packets are gone, because those modules are re-based). Real payload traffic is the "networking" step after 5a, tested with a small Fabric test mod that sends a C2S payload and gets an S2C answer in the integrated server.

## 6. Bake-time switches

- The manifest `core/res/amber-ul/bridge/fabric-api-0.116.17-neoforge-1.21.1.json` holds, per module: `verdict`, `disableMixins` (class names or package prefixes), `replaceImpl` (the impl class names served by the bridge), `notes`. The manifest fingerprint goes into the isolation key.
- The census runs **after** the manifest is applied: disabled mixins are not counted as failures, and re-based modules are reported as `fabric_api_bridged` (info).
- The bridge loads only when (NeoForge platform) and (Fabric API ≥ 0.116 present, matched by module id + version). Other Fabric API versions get one warning, `fabric_api_bridge_version`, and the official jars run unbridged. Fixer and isolation still apply.

## 7. Implementation order

1. D1, entrypoints at the registration window (HEAD of `GameData.postRegisterEvents`), with the 5a pack: Mod Menu + Fabric API + Lithium (Fabric) + Torchmaster + Iron Furnaces + nfworldtest. Run, see what breaks, and fix in this order: lifecycle timing, registry-sync re-base (disable its mixins and keep the API), resource-loader (single pack source), networking "do no harm" (disable the handshake mixins, keep the API working for local registration).
2. **5a**: enter a new world together, strict mode, with the regression trio green.
3. Registry sync proof: a Fabric test mod registering a block, an item and a custom synced registry. It enters a world, the ids match on both sides, and the world reloads with the same content.
4. Networking: the full section 5, with the payload round-trip test.
5. The other (c) parts, in the order test mods need them: item, item-group, interaction, colour/particles, screen-handler, recipe, sound, biome, indigo.
6. Dedicated server test (no EULA acceptance by us: the test is blocked until the maintainer provides one, or it runs the server class in-process with the EULA check unchanged; decided then).

## 8. Out of scope for v1

Fabric↔NeoForge capability/transfer interop, Fabric fluids, datagen, login-phase networking, Fabric API versions other than 0.116.17.
