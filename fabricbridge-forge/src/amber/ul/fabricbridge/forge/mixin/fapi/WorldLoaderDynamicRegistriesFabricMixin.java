package amber.ul.fabricbridge.forge.mixin.fapi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.WorldLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * fabric-registry-sync-v0 on Forge: the worldgen registries loaded from data packs. The official SaveLoadingMixin
 * (vetoed) replaces the list with DynamicRegistries.getDynamicRegistries(), a copy of RegistryDataLoader.WORLDGEN_REGISTRIES
 * taken when Fabric initialises, so Forge's own data-pack registries (forge:biome_modifier, forge:structure_modifier and
 * mods' DataPackRegistryEvent.NewRegistry ones, added later to DataPackRegistriesHooks) went missing and the server
 * failed to start. Forge's list stays as is; registries a Fabric mod registered through DynamicRegistries are appended.
 */
@Mixin(value = WorldLoader.class, remap = false)
public abstract class WorldLoaderDynamicRegistriesFabricMixin {
    @ModifyArg(method = "load", remap = false, allow = 1, index = 3, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/server/WorldLoader;loadAndReplaceLayer(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/LayeredRegistryAccess;Lnet/minecraft/server/RegistryLayer;Ljava/util/List;)Lnet/minecraft/core/LayeredRegistryAccess;"))
    private static List<RegistryDataLoader.RegistryData<?>> amber$withFabricDynamicRegistries(List<RegistryDataLoader.RegistryData<?>> forge) {
        List<RegistryDataLoader.RegistryData<?>> out = new ArrayList<>(forge);
        Set<ResourceKey<?>> keys = new HashSet<>(); for (var d : forge) keys.add(d.key());
        for (var d : DynamicRegistries.getDynamicRegistries()) if (keys.add(d.key())) out.add(d);
        return out;
    }
}
