package amber.worldtest;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry;
import net.fabricmc.fabric.api.client.render.fluid.v1.SimpleFluidRenderHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * fabric-rendering-fluids-v1 + Fabric fluids on NeoForge (-Damber.fluidprobe=true). A Fabric fluid in the vanilla style
 * (FlowingFluid + LiquidBlock, in #minecraft:water via this mod's data pack) with a Fabric SimpleFluidRenderHandler
 * (lava sprites, tint 0xFF3366), checked through the game's own paths: server: getFluidType does not throw, a pig in it
 * is in water and its fire goes out, the source spreads; client: LiquidBlockRenderer through BlockRenderDispatcher
 * (handler called, vertices with the handler's sprite and tint), vanilla water untouched, and
 * FluidRenderHandlerRegistry.setBlockTransparency(oak fence) giving the water overlay next to it (birch fence: none).
 */
final class FluidProbe {
    private FluidProbe() {}
    static final ResourceLocation STILL_ID = ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe_fluid");
    static final ResourceLocation FLOW_ID = ResourceLocation.fromNamespaceAndPath("amber-worldtest", "flowing_probe_fluid");
    static ProbeFluid STILL, FLOWING; static LiquidBlock BLOCK;
    static int handlerCalls, ok, total;
    static BlockPos base;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] fluid probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    /** Main entrypoint: the fluid pair and its block. */
    static void register() {
        STILL = Registry.register(BuiltInRegistries.FLUID, STILL_ID, new ProbeFluid.Source());
        FLOWING = Registry.register(BuiltInRegistries.FLUID, FLOW_ID, new ProbeFluid.Flowing());
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, STILL_ID, new LiquidBlock(STILL, BlockBehaviour.Properties.ofFullCopy(Blocks.WATER)) {});
        System.out.println("[amber-worldtest] fluid probe: fluid registered");
    }

    /** Client entrypoint: the Fabric render handler and a transparency override. */
    static void registerClient() {
        SimpleFluidRenderHandler h = new SimpleFluidRenderHandler(ResourceLocation.withDefaultNamespace("block/lava_still"), ResourceLocation.withDefaultNamespace("block/lava_flow"), 0xFF3366) {
            @Override public void renderFluid(BlockPos pos, BlockAndTintGetter world, VertexConsumer vc, BlockState bs, FluidState fs) { handlerCalls++; super.renderFluid(pos, world, vc, bs, fs); }
        };
        FluidRenderHandlerRegistry.INSTANCE.register(STILL, FLOWING, h);
        FluidRenderHandlerRegistry.INSTANCE.setBlockTransparency(Blocks.OAK_FENCE, true);
        System.out.println("[amber-worldtest] fluid probe: render handler registered");
    }

    static Object fluidType(Fluid f) {
        try { return String.valueOf(f.getClass().getMethod("getFluidType").invoke(f)); }
        catch (NoSuchMethodException e) { return "n/a (no NeoForge)"; }
        catch (java.lang.reflect.InvocationTargetException e) { return "threw " + e.getCause(); }
        catch (Throwable e) { return "err " + e; }
    }

    /** Server, t=3 s: build the test spots near the player. */
    static void place(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || client.player == null) return;
        BlockPos p = client.player.blockPosition().offset(4, 6, 0);
        server.execute(() -> {
            try {
                ServerLevel l = server.overworld(); base = p;
                // A: a closed well of probe fluid with a burning pig in it
                BlockPos a = p; stoneBox(l, a);
                l.setBlockAndUpdate(a, BLOCK.defaultBlockState());
                Pig pig = EntityType.PIG.create(l); pig.moveTo(a.getX() + 0.5, a.getY() + 0.05, a.getZ() + 0.5, 0, 0); pig.setNoAi(true); pig.setRemainingFireTicks(200); pig.addTag("amber_fluid_probe"); l.addFreshEntity(pig);
                // C: a source on a floor, open to the east
                BlockPos c = p.offset(0, 0, 4); l.setBlockAndUpdate(c.below(), Blocks.STONE.defaultBlockState()); l.setBlockAndUpdate(c.east().below(), Blocks.STONE.defaultBlockState());
                l.setBlockAndUpdate(c.east(), Blocks.AIR.defaultBlockState()); l.setBlockAndUpdate(c, BLOCK.defaultBlockState());
                // D / E: vanilla water with an oak fence (transparency set) / birch fence (not) to the east
                for (int i = 0; i < 2; i++) {
                    BlockPos d = p.offset(0, 0, 8 + 4 * i); stoneBox(l, d);
                    l.setBlockAndUpdate(d.east(), (i == 0 ? Blocks.OAK_FENCE : Blocks.BIRCH_FENCE).defaultBlockState());
                    l.setBlockAndUpdate(d, Blocks.WATER.defaultBlockState());
                }
                for (Fluid f : new Fluid[] {STILL, FLOWING}) {
                    String t = String.valueOf(fluidType(f));
                    System.out.println("[amber-worldtest] fluid probe: getFluidType " + BuiltInRegistries.FLUID.getKey(f) + " = " + t);
                    check("getFluidType " + BuiltInRegistries.FLUID.getKey(f).getPath() + " does not throw", false, t.startsWith("threw") || t.startsWith("err"));
                }
            } catch (Throwable e) { System.out.println("[amber-worldtest] fluid probe: place crashed " + e); e.printStackTrace(System.out); }
        });
    }
    static void stoneBox(ServerLevel l, BlockPos at) {
        l.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        for (Direction d : Direction.Plane.HORIZONTAL) l.setBlockAndUpdate(at.relative(d), Blocks.STONE.defaultBlockState());
        l.setBlockAndUpdate(at.above(), Blocks.AIR.defaultBlockState());
    }

    /** t=7 s: server physics, then client rendering. */
    static void report(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        if (server == null || base == null) return;
        server.execute(() -> {
            try {
                ServerLevel l = server.overworld();
                List<Pig> pigs = l.getEntitiesOfClass(Pig.class, new net.minecraft.world.phys.AABB(base).inflate(2), e -> e.getTags().contains("amber_fluid_probe"));
                Pig pig = pigs.isEmpty() ? null : pigs.get(0);
                check("pig found", true, pig != null);
                if (pig != null) {
                    check("pig isInWater (water-tagged Fabric fluid)", true, pig.isInWater());
                    check("pig fire put out", true, pig.getRemainingFireTicks() <= 0);
                    pig.discard();
                }
                BlockPos c = base.offset(0, 0, 4);
                // vanilla spreading picks the sides nearest a drop, so ask for any side
                String spread = "none";
                for (Direction d : Direction.Plane.HORIZONTAL) { FluidState f = l.getFluidState(c.relative(d)); if (!f.isEmpty()) spread = BuiltInRegistries.FLUID.getKey(f.getType()).toString(); }
                check("source spread to a side", "amber-worldtest:flowing_probe_fluid", spread);
                check("block at source", "amber-worldtest:probe_fluid", BuiltInRegistries.BLOCK.getKey(l.getBlockState(base).getBlock()).toString());
            } catch (Throwable e) { System.out.println("[amber-worldtest] fluid probe: server crashed " + e); e.printStackTrace(System.out); }
            client.execute(() -> {
                try { renderChecks(client); }
                catch (Throwable e) { System.out.println("[amber-worldtest] fluid probe: render crashed " + e); e.printStackTrace(System.out); }
                System.out.println("[amber-worldtest] fluid probe total: " + ok + "/" + total + " OK");
            });
        });
    }

    static TextureAtlasSprite sprite(String path) {
        return Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(ResourceLocation.withDefaultNamespace(path));
    }
    static boolean in(TextureAtlasSprite s, float u, float v) {
        float e = 1e-5f; return u >= s.getU0() - e && u <= s.getU1() + e && v >= s.getV0() - e && v <= s.getV1() + e;
    }

    static void renderChecks(Minecraft client) {
        var level = client.level; var br = client.getBlockRenderer();
        // probe fluid (Fabric handler)
        Capture cap = new Capture(); int before = handlerCalls;
        BlockState s = level.getBlockState(base);
        br.renderLiquid(base, level, cap, s, s.getFluidState());
        check("handler renderFluid called", true, handlerCalls > before);
        check("probe fluid vertices > 0", true, cap.n > 0);
        TextureAtlasSprite lavaStill = sprite("block/lava_still"), lavaFlow = sprite("block/lava_flow"), waterStill = sprite("block/water_still"), waterFlow = sprite("block/water_flow"), overlay = sprite("block/water_overlay");
        check("probe fluid uses handler sprites (lava)", true, cap.allIn(lavaStill, lavaFlow));
        check("probe fluid no water sprite", false, cap.anyIn(waterStill) || cap.anyIn(waterFlow));
        check("probe fluid top tint r/g/b", "255/51/102", cap.maxR + "/" + cap.maxG + "/" + cap.maxB);
        check("probe fluid alpha opaque", 255, cap.minA);
        // vanilla water next to an oak fence (setBlockTransparency true) and a birch fence (default)
        for (int i = 0; i < 2; i++) {
            BlockPos d = base.offset(0, 0, 8 + 4 * i); BlockState w = level.getBlockState(d);
            Capture wc = new Capture(); br.renderLiquid(d, level, wc, w, w.getFluidState());
            if (i == 0) {
                check("water vertices > 0", true, wc.n > 0);
                check("water uses water sprites", true, wc.anyIn(waterStill));
                check("water next to oak fence (transparent) has overlay", true, wc.anyIn(overlay));
            } else check("water next to birch fence has no overlay", false, wc.anyIn(overlay));
        }
    }

    /** Records what LiquidBlockRenderer writes. */
    static final class Capture implements VertexConsumer {
        int n, maxR, maxG, maxB, minA = 255; final List<float[]> uvs = new ArrayList<>();
        @Override public VertexConsumer addVertex(float x, float y, float z) { n++; return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { maxR = Math.max(maxR, r); maxG = Math.max(maxG, g); maxB = Math.max(maxB, b); minA = Math.min(minA, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { uvs.add(new float[] {u, v}); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
        boolean anyIn(TextureAtlasSprite s) { for (float[] uv : uvs) if (in(s, uv[0], uv[1])) return true; return false; }
        boolean allIn(TextureAtlasSprite a, TextureAtlasSprite b) { if (uvs.isEmpty()) return false; for (float[] uv : uvs) if (!in(a, uv[0], uv[1]) && !in(b, uv[0], uv[1])) return false; return true; }
    }

    /** A vanilla-style flowing fluid (what Fabric mods write: FlowingFluid with Source/Flowing subclasses). */
    abstract static class ProbeFluid extends FlowingFluid {
        @Override public Fluid getFlowing() { return FLOWING; }
        @Override public Fluid getSource() { return STILL; }
        @Override public Item getBucket() { return Items.AIR; }
        @Override protected boolean canConvertToSource(Level level) { return false; }
        @Override protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(pos) : null; Block.dropResources(state, level, pos, be);
        }
        @Override protected int getSlopeFindDistance(LevelReader level) { return 4; }
        @Override protected int getDropOff(LevelReader level) { return 1; }
        @Override public int getTickDelay(LevelReader level) { return 5; }
        @Override protected float getExplosionResistance() { return 100f; }
        @Override protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos, Fluid fluid, Direction dir) { return false; }
        @Override protected BlockState createLegacyBlock(FluidState state) { return BLOCK.defaultBlockState().setValue(LiquidBlock.LEVEL, getLegacyLevel(state)); }
        @Override public boolean isSame(Fluid f) { return f == STILL || f == FLOWING; }

        static final class Source extends ProbeFluid {
            @Override public boolean isSource(FluidState s) { return true; }
            @Override public int getAmount(FluidState s) { return 8; }
        }
        static final class Flowing extends ProbeFluid {
            @Override protected void createFluidStateDefinition(StateDefinition.Builder<Fluid, FluidState> b) { super.createFluidStateDefinition(b); b.add(LEVEL); }
            @Override public boolean isSource(FluidState s) { return false; }
            @Override public int getAmount(FluidState s) { return s.getValue(LEVEL); }
        }
    }
}
