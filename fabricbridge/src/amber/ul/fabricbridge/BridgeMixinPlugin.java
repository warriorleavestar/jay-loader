package amber.ul.fabricbridge;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Bridge adapters that implement Fabric API interfaces (amber.ul.fabricbridge.mixin.fapi.*) apply only when the Fabric API
 * module that owns the interface is present: name prefix -> marker class resource.
 */
public final class BridgeMixinPlugin implements IMixinConfigPlugin {
    static final String[][] NEEDS = {
        {"amber.ul.fabricbridge.mixin.fapi.MappedRegistryListenable", "net/fabricmc/fabric/impl/registry/sync/ListenableRegistry.class"},
        // networking re-base: only with Amber's impl replacement served (fabric-networking-api-v1 matched by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.ServerConfigurationListenerMixin", "net/fabricmc/fabric/api/networking/v1/PacketSender.class"},
        {"amber.ul.fabricbridge.mixin.fapi.NetworkRegistryMixin", "net/fabricmc/fabric/api/networking/v1/PacketSender.class"},
        // sound re-base: only with fabric-sound-api-v1 (its SoundSystemMixin is vetoed by the bridge manifest)
        // screen handler re-base: only with fabric-screen-handler-api-v1 (its ServerPlayerEntityMixin is vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.ServerPlayerOpenMenuMixin", "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerFactory.class"},
        {"amber.ul.fabricbridge.mixin.fapi.ExtendedScreenHandlerTypeMixin", "net/fabricmc/fabric/api/screenhandler/v1/ExtendedScreenHandlerType.class"},
        // recipe re-base: only with Amber's CustomIngredient replacement served (fabric-recipe-api-v1 matched by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.IngredientFabricMixin", "net/fabricmc/fabric/impl/recipe/ingredient/AmberNeoIngredient.class"},
        {"amber.ul.fabricbridge.mixin.fapi.CraftingHelperFabricCodecMixin", "net/fabricmc/fabric/impl/recipe/ingredient/AmberNeoIngredient.class"},
        // fluid rendering re-base: only with fabric-rendering-fluids-v1 (its FluidRendererMixin is vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.LiquidBlockRendererFabricMixin", "net/fabricmc/fabric/api/client/render/fluid/v1/FluidRenderHandlerRegistry.class"},
        {"amber.ul.fabricbridge.mixin.fapi.SoundEngineStreamMixin", "net/fabricmc/fabric/api/client/sound/v1/FabricSoundInstance.class"},
        // item re-base: only with fabric-item-api-v1 (its ItemStack/RegistryLoader/remainder/enchanting mixins are vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.ItemStackFabricMixin", "net/fabricmc/fabric/api/item/v1/FabricItemStack.class"},
        {"amber.ul.fabricbridge.mixin.fapi.ItemFabricExtensionMixin", "net/fabricmc/fabric/api/item/v1/FabricItem.class"},
        {"amber.ul.fabricbridge.mixin.fapi.RegistryDataLoaderFabricMixin", "net/fabricmc/fabric/impl/item/EnchantmentUtil.class"},
        {"amber.ul.fabricbridge.mixin.fapi.MultiPlayerGameModeFabricMixin", "net/fabricmc/fabric/api/item/v1/FabricItem.class"},
        // colour providers re-base: only with fabric-rendering-v1 (its BlockColorsMixin / ItemColorsMixin are vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.BlockColorsFabricMixin", "net/fabricmc/fabric/impl/client/rendering/ColorProviderRegistryImpl.class"},
        {"amber.ul.fabricbridge.mixin.fapi.ItemColorsFabricMixin", "net/fabricmc/fabric/impl/client/rendering/ColorProviderRegistryImpl.class"},
        // interaction events re-base: only with fabric-events-interaction-v0 (ServerPlayNetworkHandlerMixin vetoed, three handlers stripped by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.ServerInteractEntityFabricMixin", "net/fabricmc/fabric/api/event/player/UseEntityCallback.class"},
        {"amber.ul.fabricbridge.mixin.fapi.ServerPlayerGameModeFabricMixin", "net/fabricmc/fabric/api/event/player/PlayerBlockBreakEvents.class"},
        {"amber.ul.fabricbridge.mixin.fapi.MinecraftUseEntityFabricMixin", "net/fabricmc/fabric/api/event/player/UseEntityCallback.class"},
        // particles re-base: only with fabric-particles-v1 (BlockDustParticleMixin vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.TerrainParticleFabricMixin", "net/fabricmc/fabric/api/client/particle/v1/ParticleRenderEvents.class"},
        // sleep events re-base: only with fabric-entity-events-v1 (three LivingEntityMixin handlers stripped by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.LivingEntitySleepFabricMixin", "net/fabricmc/fabric/api/entity/event/v1/EntitySleepEvents.class"},
        // tag removal re-base: only with fabric-tag-api-v1 (TagFileMixin / TagGroupLoaderMixin vetoed by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.TagFileFabricMixin", "net/fabricmc/fabric/api/tag/v1/FabricTagFile.class"},
        // block-entity unload re-base: only with fabric-lifecycle-events-v1 (one client.WorldChunkMixin redirect stripped by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.LevelChunkUnloadFabricMixin", "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientBlockEntityEvents.class"},
        // terrain tessellation re-base: only with fabric-renderer-indigo active (SectionBuilderMixin redirect stripped by the bridge manifest)
        {"amber.ul.fabricbridge.mixin.fapi.SectionCompilerIndigoMixin", "net/fabricmc/fabric/impl/client/indigo/renderer/render/TerrainRenderContext.class"},
    };
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        // test switch (negative control; use a separate --cache, transformed classes are cached)
        if (Boolean.getBoolean("amber.fabricbridge.noScreen") && (mixin.endsWith(".ServerPlayerOpenMenuMixin") || mixin.endsWith(".ExtendedScreenHandlerTypeMixin"))) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noFluid") && (mixin.endsWith(".FluidTypeFallbackMixin") || mixin.endsWith(".LiquidBlockRendererFabricMixin"))) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noItem") && (mixin.endsWith(".ItemStackFabricMixin") || mixin.endsWith(".ItemFabricExtensionMixin") || mixin.endsWith(".RegistryDataLoaderFabricMixin") || mixin.endsWith(".MultiPlayerGameModeFabricMixin"))) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noColors") && (mixin.endsWith(".BlockColorsFabricMixin") || mixin.endsWith(".ItemColorsFabricMixin"))) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noInteract") && (mixin.endsWith(".ServerInteractEntityFabricMixin") || mixin.endsWith(".ServerPlayerGameModeFabricMixin") || mixin.endsWith(".MinecraftUseEntityFabricMixin"))) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noParticles") && mixin.endsWith(".TerrainParticleFabricMixin")) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noSleep") && mixin.endsWith(".LivingEntitySleepFabricMixin")) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noTags") && mixin.endsWith(".TagFileFabricMixin")) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noLifecycle") && mixin.endsWith(".LevelChunkUnloadFabricMixin")) return false;
        if (Boolean.getBoolean("amber.fabricbridge.noIndigo") && mixin.endsWith(".SectionCompilerIndigoMixin")) return false;
        if (mixin.endsWith(".SectionCompilerIndigoMixin") && BridgeEnv.has("net/fabricmc/fabric/impl/client/indigo/renderer/render/TerrainRenderContext") && !indigoActive()) return false;
        for (String[] n : NEEDS) if (mixin.equals(n[0])) return BridgeEnv.has(n[1].substring(0, n[1].length() - 6));
        return true;
    }
    /** Indigo's own switch (another renderer present, or disabled in config): its mixins do not apply, so neither does ours. */
    static boolean indigoActive() {
        try {
            var m = Class.forName("net.fabricmc.fabric.impl.client.indigo.IndigoMixinConfigPlugin").getDeclaredMethod("shouldApplyIndigo");
            m.setAccessible(true); return (Boolean) m.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return true; }
    }
    @Override public void onLoad(String pkg) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String t, ClassNode c, String m, IMixinInfo i) {}
    @Override public void postApply(String t, ClassNode c, String m, IMixinInfo i) {}
}
