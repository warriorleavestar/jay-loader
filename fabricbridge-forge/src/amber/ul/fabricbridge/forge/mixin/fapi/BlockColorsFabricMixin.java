package amber.ul.fabricbridge.forge.mixin.fapi;

import java.util.Map;
import net.fabricmc.fabric.impl.client.rendering.ColorProviderRegistryImpl;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-rendering-v1 on Forge 47: ColorProviderRegistry.BLOCK (port of the NeoForge bridge's mixin). Fabric's
 * BlockColorsMixin (vetoed) shadows vanilla's IdMapper; Forge keeps block colours in a Map keyed by the block's
 * registry holder (ForgeRegistries.BLOCKS.getDelegateOrThrow; not the same object as builtInRegistryHolder, so the same lookup is used). Same two
 * jobs: answer ColorMapperHolder.get from that map, and hand the providers registered before BlockColors existed to it
 * at the end of createDefault (after vanilla's and Forge's RegisterColorHandlersEvent.Block).
 */
@SuppressWarnings("deprecation")
@Mixin(value = BlockColors.class, remap = false)
public abstract class BlockColorsFabricMixin implements ColorProviderRegistryImpl.ColorMapperHolder<Block, BlockColor> {
    @Shadow(remap = false) @Final private Map<Holder.Reference<Block>, BlockColor> blockColors;

    @Inject(method = "createDefault()Lnet/minecraft/client/color/block/BlockColors;", remap = false, at = @At("RETURN"))
    private static void amber$fabricInit(CallbackInfoReturnable<BlockColors> cir) { ColorProviderRegistryImpl.BLOCK.initialize(cir.getReturnValue()); }

    @Override public BlockColor get(Block block) { return blockColors.get(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getDelegateOrThrow(block)); }
}
