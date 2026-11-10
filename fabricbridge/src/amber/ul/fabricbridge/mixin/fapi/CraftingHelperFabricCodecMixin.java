package amber.ul.fabricbridge.mixin.fapi;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.fabricmc.fabric.impl.recipe.ingredient.CustomIngredientImpl;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.CraftingHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-recipe-api-v1 re-base: Fabric recipes (data packs, datagen output) write custom ingredients as
 * {"fabric:type": "<serializer id>", ...}; NeoForge dispatches custom ingredients on "type". The ingredient map codec
 * (Ingredient.CODEC, CODEC_NONEMPTY, MAP_CODEC_NONEMPTY and the list forms are all built from it) first looks for
 * "fabric:type" and decodes through the Fabric serializer; everything else, and all encoding, stays NeoForge's.
 */
@Mixin(value = CraftingHelper.class, remap = false)
public abstract class CraftingHelperFabricCodecMixin {
    @Inject(method = "makeIngredientMapCodec()Lcom/mojang/serialization/MapCodec;", remap = false, at = @At("RETURN"), cancellable = true)
    private static void amber$fabricType(CallbackInfoReturnable<MapCodec<Ingredient>> cir) {
        MapCodec<Ingredient> neo = cir.getReturnValue();
        cir.setReturnValue(new MapCodec<>() {
            @Override public <T> Stream<T> keys(DynamicOps<T> ops) { return Stream.concat(neo.keys(ops), Stream.of(ops.createString(CustomIngredientImpl.TYPE_KEY))); }
            @Override public <T> DataResult<Ingredient> decode(DynamicOps<T> ops, MapLike<T> input) {
                T type = input.get(CustomIngredientImpl.TYPE_KEY);
                if (type == null) return neo.decode(ops, input);
                return ResourceLocation.CODEC.parse(ops, type).flatMap(id -> {
                    CustomIngredientSerializer<?> s = CustomIngredientImpl.getSerializer(id);
                    if (s == null) return DataResult.error(() -> "Unknown Fabric custom ingredient serializer: " + id);
                    return s.getCodec(true).decode(ops, input).map(c -> c.toVanilla());
                });
            }
            @Override public <T> RecordBuilder<T> encode(Ingredient input, DynamicOps<T> ops, RecordBuilder<T> prefix) { return neo.encode(input, ops, prefix); }
            @Override public String toString() { return "AmberFabricType[" + neo + "]"; }
        });
    }
}
