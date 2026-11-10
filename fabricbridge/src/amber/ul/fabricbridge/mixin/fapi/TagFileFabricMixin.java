package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.tag.v1.FabricTagFile;
import net.minecraft.tags.TagFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-tag-api-v1 re-base: tag removal. Fabric's TagFileMixin (vetoed) adds a {@code remove} list and the
 * {@code "fabric:remove"} JSON key, and its TagGroupLoaderMixin (vetoed) applies the removals; NeoForge's TagFile record
 * already has {@code remove} (JSON {@code "remove"}) and its TagLoader applies it after every pack's additions, as Fabric
 * does. So TagFile is a FabricTagFile through NeoForge's own accessor, and the codec reads {@code "fabric:remove"} as more
 * {@code "remove"} entries.
 */
@Mixin(value = TagFile.class, remap = false)
public abstract class TagFileFabricMixin implements FabricTagFile {
    @ModifyExpressionValue(method = "<clinit>", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lcom/mojang/serialization/codecs/RecordCodecBuilder;create(Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"))
    private static Codec<TagFile> amber$fabricRemoveKey(Codec<TagFile> codec) {
        return new Codec<>() {
            @Override public <T> DataResult<Pair<TagFile, T>> decode(DynamicOps<T> ops, T input) {
                T in = input;
                var map = ops.getMap(input).result().orElse(null);
                T fabric = map == null ? null : map.get("fabric:remove");
                if (fabric != null) {
                    T own = map.get("remove");
                    Stream<T> a = own == null ? Stream.empty() : ops.getStream(own).result().orElse(Stream.empty());
                    Stream<T> b = ops.getStream(fabric).result().orElse(Stream.empty());
                    in = ops.remove(ops.set(input, "remove", ops.createList(Stream.concat(a, b))), "fabric:remove");
                }
                return codec.decode(ops, in);
            }
            @Override public <T> DataResult<T> encode(TagFile file, DynamicOps<T> ops, T prefix) { return codec.encode(file, ops, prefix); }
            @Override public String toString() { return codec + "+fabric:remove"; }
        };
    }
}
