package amber.fabricforgeprobe;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Objects;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * fabric-rendering-fluids-v1 on Forge: a Fabric FluidRenderHandler registered for water (green tint) renders water, its
 * tint reaches the vertices, and lava still renders through Forge's path. Water and lava are placed next to the player
 * and rendered straight through BlockRenderDispatcher.renderLiquid into a counting VertexConsumer. Under strict require
 * the whole FluidRendererMixin had been excluded (no handler dispatch at all).
 */
final class FluidProbe {
    private FluidProbe() {}
    static volatile int handlerCalls;
    static BlockPos water, lava;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] fluid: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static final class Green extends SimpleFluidRenderHandler {
        Green() { super(new ResourceLocation("minecraft", "block/water_still"), new ResourceLocation("minecraft", "block/water_flow"), new ResourceLocation("minecraft", "block/water_overlay"), 0x00FF00); }
        @Override public void renderFluid(BlockPos pos, BlockAndTintGetter world, VertexConsumer vc, BlockState blockState, FluidState fluidState) {
            handlerCalls++;
            super.renderFluid(pos, world, vc, blockState, fluidState);
        }
    }

    /**
     * tick 100 (client thread): override water's handler and place a water and a lava source a few blocks up beside the
     * player. Registered here, not at init: Fabric's onFluidRendererReload re-registers its default water/lava handlers
     * through register() on every resource reload, which replaces a water override made earlier (same on Fabric).
     */
    static void place(MinecraftServer server, BlockPos near) {
        Green g = new Green();
        g.reloadTextures(Minecraft.getInstance().getModelManager().getAtlas(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS));
        FluidRenderHandlerRegistry.INSTANCE.register(Fluids.WATER, Fluids.FLOWING_WATER, g);
        water = near.offset(4, 4, 0); lava = near.offset(-4, 4, 0);
        server.execute(() -> {
            server.overworld().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
            server.overworld().setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
        });
    }

    static final class Counter implements VertexConsumer {
        int vertices, red, green;
        @Override public VertexConsumer vertex(double x, double y, double z) { vertices++; return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { red = Math.max(red, r); green = Math.max(green, g); return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }

    /** tick 140: the client has the blocks; render them directly. */
    static void run(Minecraft mc) {
        try {
            BlockState ws = mc.level.getBlockState(water), ls = mc.level.getBlockState(lava);
            check("client sees the water", true, ws.getFluidState().is(Fluids.WATER));
            int before = handlerCalls;
            Counter w = new Counter();
            mc.getBlockRenderer().renderLiquid(water, mc.level, w, ws, ws.getFluidState());
            check("Fabric handler rendered the water", true, handlerCalls > before);
            check("water vertices written", true, w.vertices > 0);
            check("handler tint reached the vertices (no red, green)", "0,true", w.red + "," + (w.green > 0));
            Counter l = new Counter();
            mc.getBlockRenderer().renderLiquid(lava, mc.level, l, ls, ls.getFluidState());
            check("lava still renders (Forge path)", true, l.vertices > 0);
        } catch (Throwable e) { System.out.println("[amber-ffprobe] fluid: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] fluid total: " + ok + "/" + total + " OK");
    }

    static void clear(MinecraftServer server) {
        server.execute(() -> { if (water != null) { server.overworld().setBlockAndUpdate(water, Blocks.AIR.defaultBlockState()); server.overworld().setBlockAndUpdate(lava, Blocks.AIR.defaultBlockState()); } });
    }
}
