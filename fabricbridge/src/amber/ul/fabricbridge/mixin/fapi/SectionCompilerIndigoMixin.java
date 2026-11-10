package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.impl.client.indigo.Indigo;
import net.fabricmc.fabric.impl.client.indigo.renderer.accessor.AccessChunkRendererRegion;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-renderer-indigo re-base: terrain tessellation of Fabric models. Indigo's SectionBuilderMixin redirects vanilla's one
 * renderBlock call per block (stripped by the bridge manifest; its prepare/release injectors are moved to NeoForge's compile
 * overload by the fixer). NeoForge calls renderBatched once per render type of the model instead, and Indigo picks each
 * quad's layer itself, so a non-vanilla-adapter model is tessellated on its block's first renderBatched call and the others
 * are skipped. Vanilla-adapter models keep NeoForge's path.
 */
@Mixin(value = SectionCompiler.class, remap = false)
public abstract class SectionCompilerIndigoMixin {
    private static final String COMPILE = "compile(Lnet/minecraft/core/SectionPos;Lnet/minecraft/client/renderer/chunk/RenderChunkRegion;Lcom/mojang/blaze3d/vertex/VertexSorting;Lnet/minecraft/client/renderer/SectionBufferBuilderPack;Ljava/util/List;)Lnet/minecraft/client/renderer/chunk/SectionCompiler$Results;";
    /** per compile thread: {0 = none yet / 1, BlockPos.asLong of the last block Indigo tessellated} */
    private static final ThreadLocal<long[]> AMBER_LAST = ThreadLocal.withInitial(() -> new long[2]);

    @Inject(method = COMPILE, remap = false, at = @At("HEAD"))
    private void amber$indigoReset(CallbackInfoReturnable<SectionCompiler.Results> cir) { AMBER_LAST.get()[0] = 0; }

    @WrapOperation(method = COMPILE, remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/neoforged/neoforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V"))
    private void amber$indigoTessellate(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter view, PoseStack pose, VertexConsumer consumer,
                                        boolean checkSides, RandomSource random, ModelData data, RenderType type, Operation<Void> original) {
        BakedModel model = dispatcher.getBlockModel(state);
        TerrainRenderContext ctx = view instanceof AccessChunkRendererRegion r ? r.fabric_getRenderer() : null;
        if (ctx != null && (Indigo.ALWAYS_TESSELATE_INDIGO || !((FabricBakedModel) model).isVanillaAdapter())) {
            long[] last = AMBER_LAST.get(); long p = pos.asLong();
            if (last[0] == 0 || last[1] != p) { last[0] = 1; last[1] = p; ctx.tessellateBlock(state, pos, model, pose); }
            return;
        }
        original.call(dispatcher, state, pos, view, pose, consumer, checkSides, random, data, type);
    }
}
