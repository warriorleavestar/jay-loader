package amber.ul.fabricbridge.mixin.fapi;

import java.util.Map;
import net.fabricmc.fabric.impl.client.rendering.ColorProviderRegistryImpl;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-rendering-v1 re-base: ColorProviderRegistry.BLOCK. Fabric's BlockColorsMixin (vetoed) shadows vanilla's
 * IdMapper field; NeoForge keeps block colours in a Map (blockColors). Same two jobs: BlockColors answers
 * ColorMapperHolder.get from that map, and the providers registered before the game made its BlockColors are handed to
 * it at the end of createDefault (after vanilla's and NeoForge's RegisterColorHandlersEvent.Block).
 */
@Mixin(value = BlockColors.class, remap = false)
public abstract class BlockColorsFabricMixin implements ColorProviderRegistryImpl.ColorMapperHolder<Block, BlockColor> {
    @Shadow(remap = false) @Final private Map<Block, BlockColor> blockColors;

    @Inject(method = "createDefault()Lnet/minecraft/client/color/block/BlockColors;", remap = false, at = @At("RETURN"))
    private static void amber$fabricInit(CallbackInfoReturnable<BlockColors> cir) { ColorProviderRegistryImpl.BLOCK.initialize(cir.getReturnValue()); }

    @Override public BlockColor get(Block block) { return blockColors.get(block); }
}
