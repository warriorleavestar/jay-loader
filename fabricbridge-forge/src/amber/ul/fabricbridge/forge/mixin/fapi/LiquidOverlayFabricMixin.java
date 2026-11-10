package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fabric-rendering-fluids-v1 on Forge 47: FluidRenderHandlerRegistry.setBlockTransparency decides the side overlay sprite.
 * Fabric's FluidRendererMixin#getOverlayBlock / #modSideSpriteForOverlay (stripped) hook vanilla's
 * {@code neighbour.getBlock() instanceof HalfTransparentBlock || LeavesBlock}; Forge replaced it with
 * BlockState.shouldDisplayFluidOverlay. An explicit Fabric override (differs from the vanilla default for that block) wins;
 * otherwise Forge's answer, which includes Forge mods' overrides. (Port of the NeoForge bridge's overlay redirect.)
 */
@Mixin(value = LiquidBlockRenderer.class, remap = false)
public abstract class LiquidOverlayFabricMixin {
    @Redirect(method = "tesselate", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/world/level/block/state/BlockState;shouldDisplayFluidOverlay(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)Z"))
    private boolean amber$fabricOverlay(BlockState neighbour, BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
        boolean forge = neighbour.shouldDisplayFluidOverlay(level, pos, fluid);
        Block b = neighbour.getBlock();
        boolean fabric = FluidRenderHandlerRegistry.INSTANCE.isBlockTransparent(b), fabricDefault = b instanceof HalfTransparentBlock || b instanceof LeavesBlock;
        return fabric != fabricDefault ? fabric : forge;
    }
}
