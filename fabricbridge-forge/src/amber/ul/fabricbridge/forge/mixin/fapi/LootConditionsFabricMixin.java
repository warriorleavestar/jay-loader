package amber.ul.fabricbridge.forge.mixin.fapi;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import net.fabricmc.fabric.api.resource.conditions.v1.ResourceConditions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-resource-conditions-api-v1 on Forge 47: "fabric:load_conditions" on loot tables, predicates and item modifiers.
 * Fabric's LootManagerMixin#applyResourceConditions (stripped) sits at HEAD of vanilla's per-file lambda
 * (LootDataType, Map, ResourceLocation, JsonElement); Forge's lambda also takes the ResourceManager (for its own
 * "forge:conditions"), so the handler no longer matches. Same check at HEAD of Forge's lambda: a file whose conditions
 * don't hold is skipped. Fabric's runAsync/runAsyncEnd (registry access for the conditions) are retargeted to Forge's
 * outer lambda by the manifest.
 */
@Mixin(value = LootDataManager.class, remap = false)
public abstract class LootConditionsFabricMixin {
    @Inject(method = "lambda$scheduleElementParse$4(Lnet/minecraft/world/level/storage/loot/LootDataType;Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/Map;Lnet/minecraft/resources/ResourceLocation;Lcom/google/gson/JsonElement;)V",
            remap = false, cancellable = true, at = @At("HEAD"))
    private static void amber$fabricConditions(LootDataType<?> type, ResourceManager resources, Map<?, ?> out, ResourceLocation id, JsonElement json, CallbackInfo ci) {
        if (json.isJsonObject()) {
            JsonObject obj = json.getAsJsonObject();
            if (obj.has(ResourceConditions.CONDITIONS_KEY) && !ResourceConditions.objectMatchesConditions(obj)) ci.cancel();
        }
    }
}
