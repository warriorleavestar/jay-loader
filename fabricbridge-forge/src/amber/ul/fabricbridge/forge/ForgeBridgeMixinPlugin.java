package amber.ul.fabricbridge.forge;

import amber.ul.runtime.UniversalClassLoader;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Forge bridge adapters (amber.ul.fabricbridge.forge.mixin.fapi.*) apply only when the Fabric API module whose handler
 * the Forge bridge manifest strips is present. Test switches (-Damber.fabricbridge.no<Part>=true) turn one adapter off
 * for a bridge-off control run (use a separate --cache: transformed classes are cached).
 */
public final class ForgeBridgeMixinPlugin implements IMixinConfigPlugin {
    static final String P = "amber.ul.fabricbridge.forge.mixin.fapi.";
    static final String[][] NEEDS = {
        // fabric-events-interaction-v0: ServerPlayNetworkHandlerMixin vetoed, client.MinecraftClientMixin#injectUseEntityCallback stripped
        {P + "ServerInteractEntityFabricMixin", "net/fabricmc/fabric/api/event/player/UseEntityCallback", "noInteract"},
        {P + "MinecraftUseEntityFabricMixin", "net/fabricmc/fabric/api/event/player/UseEntityCallback", "noInteract"},
        // ... and ServerPlayerInteractionManagerMixin#breakBlock / #onBlockBroken stripped (Forge's removeBlock)
        {P + "ServerBlockBreakFabricMixin", "net/fabricmc/fabric/api/event/player/PlayerBlockBreakEvents", "noBreak"},
        // fabric-item-api-v1: remainder mixins vetoed, ItemStackMixin attribute / suitable-tool redirects stripped
        {P + "ItemFabricExtensionMixin", "net/fabricmc/fabric/api/item/v1/FabricItem", "noItem"},
        // fabric-rendering-v1: Block/ItemColorsMixin vetoed (IdMapper shadows); fabric-particles-v1: BlockDustParticleMixin vetoed
        {P + "BlockColorsFabricMixin", "net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry", "noColors"},
        {P + "ItemColorsFabricMixin", "net/fabricmc/fabric/api/client/rendering/v1/ColorProviderRegistry", "noColors"},
        {P + "TerrainParticleFabricMixin", "net/fabricmc/fabric/api/client/particle/v1/ParticleRenderEvents", "noParticles"},
        {P + "TagFileFabricMixin", "net/fabricmc/fabric/api/tag/v1/FabricTagFile", "noTag"},
        {P + "SoundEngineStreamFabricMixin", "net/fabricmc/fabric/api/client/sound/v1/FabricSoundInstance", "noSound"},
        {P + "LevelChunkUnloadFabricMixin", "net/fabricmc/fabric/api/event/lifecycle/v1/ServerBlockEntityEvents", "noChunkBE"},
        {P + "HopperTransferFabricMixin", "net/fabricmc/fabric/api/transfer/v1/item/ItemStorage", "noHopper"},
        {P + "LootConditionsFabricMixin", "net/fabricmc/fabric/api/resource/conditions/v1/ResourceConditions", "noLootConditions"},
        {P + "ServerPlayerEndFabricMixin", "net/fabricmc/fabric/api/dimension/v1/FabricDimensions", "noDimEnd"},
        {P + "LiquidOverlayFabricMixin", "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry", "noFluidOverlay"},
        // fabric-entity-events-v1: ServerPlayerEntityMixin#onTrySleepDirectionCheck and #redirectDaySleepCheck stripped
        {P + "ServerPlayerSleepDirectionFabricMixin", "net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents", "noSleep"},
        {P + "ServerPlayerSleepTimeFabricMixin", "net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents", "noSleep"},
        // ... and PlayerEntityMixin#redirectDaySleepCheck (Player.tick) stripped
        {P + "PlayerSleepTickFabricMixin", "net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents", "noSleep"},
        // ... and LivingEntityMixin#setOccupiedState (Forge's setBedOccupied), #onGetSleepingDirection (Forge's getBedDirection)
        {P + "LivingEntityBedFabricMixin", "net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents", "noSleep"},
        // fabric-key-binding-api-v1: GameOptionsMixin vetoed, KeyBindingRegistryImpl re-based (amber-impl)
        {P + "ForgeKeyMappingsFabricMixin", "net/fabricmc/fabric/api/client/keybinding/v1/KeyBindingHelper", "noKeys"},
        // fabric-loot-tables-v1: LootTableMixin vetoed; fabric-loot-api-v2: LootTableAccessor#fabric_getPools @Accessor stripped (method kept)
        {P + "LootTableSupplierFabricMixin", "net/fabricmc/fabric/api/loot/v1/FabricLootSupplier", "noLoot"},
        {P + "LootTablePoolsFabricMixin", "net/fabricmc/fabric/api/loot/v2/FabricLootTableBuilder", "noLoot"},
        {P + "LootDataManagerTableIdFabricMixin", "net/fabricmc/fabric/api/loot/v2/FabricLootTableBuilder", "noLootId"},
        // fabric-registry-sync-v0: SaveLoadingMixin vetoed (it dropped Forge's data-pack registries)
        {P + "WorldLoaderDynamicRegistriesFabricMixin", "net/fabricmc/fabric/api/event/registry/DynamicRegistries", "noDynReg"},
        // fabric-screen-api-v1: GameRendererMixin#onBefore/AfterRenderScreen and KeyboardMixin's four key hooks stripped
        {P + "ScreenRenderFabricMixin", "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents", "noScreen"},
        {P + "ScreenKeyboardFabricMixin", "net/fabricmc/fabric/api/client/screen/v1/ScreenKeyboardEvents", "noScreen"},
    };

    static boolean has(String internalName) {
        ClassLoader cl = ForgeBridgeMixinPlugin.class.getClassLoader();
        if (cl instanceof UniversalClassLoader u) return u.classBytes(internalName) != null;
        return cl.getResource(internalName + ".class") != null;
    }

    @Override public boolean shouldApplyMixin(String target, String mixin) {
        for (String[] n : NEEDS) if (mixin.equals(n[0])) return !Boolean.getBoolean("amber.fabricbridge." + n[2]) && has(n[1]);
        return true;
    }
    @Override public void onLoad(String pkg) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
