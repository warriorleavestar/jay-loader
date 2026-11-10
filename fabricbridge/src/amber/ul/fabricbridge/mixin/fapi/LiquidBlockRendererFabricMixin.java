package amber.ul.fabricbridge.mixin.fapi;

import amber.ul.fabricbridge.fapi.fluid.FabricFluidTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderHandlerInfo;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderHandlerRegistryImpl;
import net.fabricmc.fabric.impl.client.rendering.fluid.FluidRenderingImpl;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.client.textures.FluidSpriteCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-rendering-fluids-v1 re-base. Fabric's FluidRendererMixin (vetoed) hooks vanilla's sprite/lava/tint/overlay
 * locals in tesselate; NeoForge replaced those with FluidSpriteCache.getFluidSprites (still, flowing, overlay-or-null),
 * IClientFluidTypeExtensions.getTintColor (ARGB) and BlockState.shouldDisplayFluidOverlay. Same behaviour on NeoForge's
 * calls: a Fabric-registered handler (FluidRenderHandlerRegistry.register, i.e. getOverride) renders the fluid; inside
 * Fabric's default renderer (FluidRenderHandlerInfo set) sprites and tint come from that handler; setBlockTransparency
 * overrides decide the water overlay. Vanilla water/lava and NeoForge fluids without a Fabric handler keep NeoForge's path.
 */
@Mixin(value = LiquidBlockRenderer.class, remap = false)
public abstract class LiquidBlockRendererFabricMixin {
    @Shadow(remap = false) @Final private TextureAtlasSprite[] lavaIcons;
    @Shadow(remap = false) @Final private TextureAtlasSprite[] waterIcons;
    @Shadow(remap = false) private TextureAtlasSprite waterOverlay;

    @Inject(method = "setupSprites()V", remap = false, at = @At("RETURN"))
    private void amber$fabricReload(CallbackInfo ci) {
        ((FluidRenderHandlerRegistryImpl) FluidRenderHandlerRegistry.INSTANCE).onFluidRendererReload((LiquidBlockRenderer) (Object) this, waterIcons, lavaIcons, waterOverlay);
    }

    @Inject(method = "tesselate", remap = false, at = @At("HEAD"), cancellable = true)
    private void amber$fabricHandler(BlockAndTintGetter level, BlockPos pos, VertexConsumer vc, BlockState block, FluidState fluid, CallbackInfo ci) {
        if (FluidRenderingImpl.getCurrentInfo().handler != null) return;   // inside Fabric's default renderer: render here
        FluidRenderHandler h = ((FluidRenderHandlerRegistryImpl) FluidRenderHandlerRegistry.INSTANCE).getOverride(fluid.getType());
        if (h == null) return;
        h.renderFluid(pos, level, vc, block, fluid);
        ci.cancel();
    }

    @Redirect(method = "tesselate", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/client/textures/FluidSpriteCache;getFluidSprites(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)[Lnet/minecraft/client/renderer/texture/TextureAtlasSprite;"))
    private TextureAtlasSprite[] amber$fabricSprites(BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
        FluidRenderHandlerInfo info = FluidRenderingImpl.getCurrentInfo();
        if (info.handler != null) return new TextureAtlasSprite[] {info.sprites[0], info.sprites[1], info.hasOverlay ? info.overlaySprite : null};
        // a Fabric fluid with no Fabric handler and no tag: vanilla draws it with water's sprites (it is not lava)
        if (FabricFluidTypes.isInert(fluid.getFluidType())) return new TextureAtlasSprite[] {waterIcons[0], waterIcons[1], waterOverlay};
        return FluidSpriteCache.getFluidSprites(level, pos, fluid);
    }

    @Redirect(method = "tesselate", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/neoforged/neoforge/client/extensions/common/IClientFluidTypeExtensions;getTintColor(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;)I"))
    private int amber$fabricTint(IClientFluidTypeExtensions ext, FluidState fluid, BlockAndTintGetter level, BlockPos pos) {
        FluidRenderHandlerInfo info = FluidRenderingImpl.getCurrentInfo();
        if (info.handler != null) return 0xFF000000 | info.handler.getFluidColor(level, pos, fluid);   // Fabric colours are RGB, NeoForge's ARGB
        if (FabricFluidTypes.isInert(fluid.getFluidType())) return 0xFFFFFFFF;
        return ext.getTintColor(fluid, level, pos);
    }

    @Redirect(method = "tesselate", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/world/level/block/state/BlockState;shouldDisplayFluidOverlay(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/material/FluidState;)Z"))
    private boolean amber$fabricOverlay(BlockState neighbour, BlockAndTintGetter level, BlockPos pos, FluidState fluid) {
        boolean neo = neighbour.shouldDisplayFluidOverlay(level, pos, fluid);
        Block b = neighbour.getBlock();
        boolean fabric = FluidRenderHandlerRegistry.INSTANCE.isBlockTransparent(b), fabricDefault = b instanceof HalfTransparentBlock || b instanceof LeavesBlock;
        return fabric != fabricDefault ? fabric : neo;   // an explicit setBlockTransparency wins; otherwise NeoForge's answer (which includes NeoForge mods' overrides)
    }
}
