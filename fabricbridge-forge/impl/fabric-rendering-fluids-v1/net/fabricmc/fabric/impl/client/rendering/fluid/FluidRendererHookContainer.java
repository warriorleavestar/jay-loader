/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.client.rendering.fluid;

import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandler;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Amber's re-based fabric-rendering-fluids-v1 hook container (Forge 47 platform). Same binary surface as the official class.
 * FluidRendererMixin.modSpriteArray hands LiquidBlockRenderer.tesselate this sprites array in place of the one it got;
 * vanilla reads [0] and [1], but Forge's tesselate (sprites from ForgeHooksClient.getFluidSprites) also reads [2], the
 * overlay, when it is non-null. The official array has 2 slots (AIOOBE on Forge, e.g. lava in view); here it has 3, and
 * [2] carries the handler's overlay sprite or null, which is what Forge expects.
 */
public class FluidRendererHookContainer {
    public BlockAndTintGetter view;
    public BlockPos pos;
    public BlockState blockState;
    public FluidState fluidState;
    public FluidRenderHandler handler;
    public final TextureAtlasSprite[] sprites = new TextureAtlasSprite[3];
    public TextureAtlasSprite overlay;
    public boolean hasOverlay;

    public void getSprites(BlockAndTintGetter world, BlockPos pos, FluidState fluidState) {
        if (handler != null) {
            TextureAtlasSprite[] s = handler.getFluidSprites(world, pos, fluidState);
            sprites[0] = s[0];
            sprites[1] = s[1];
            if (s.length > 2) { hasOverlay = true; overlay = s[2]; }
            sprites[2] = s.length > 2 ? s[2] : null;
        } else {
            hasOverlay = false;
        }
    }

    public void clear() {
        view = null; pos = null; blockState = null; fluidState = null; handler = null;
        sprites[0] = null; sprites[1] = null; sprites[2] = null;
        overlay = null; hasOverlay = false;
    }
}
