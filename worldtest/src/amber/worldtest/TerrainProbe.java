package amber.worldtest;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.blockview.v2.FabricBlockView;
import net.fabricmc.fabric.api.blockview.v2.RenderDataBlockEntity;
import net.fabricmc.fabric.api.client.model.loading.v1.DelegatingUnbakedModel;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Terrain rendering probe (-Damber.terrainprobe=true): fabric-renderer-indigo + fabric-block-view-api-v2 in chunk meshing.
 * A block whose baked model is a non-vanilla-adapter FabricBakedModel and whose block entity has render data is placed in
 * the camera's section. Indigo must tessellate it (emitBlockQuads called while the section compiles) and the render
 * region must carry the block entity's render data (FabricBlockView.getBlockEntityRenderData). On NeoForge both depend
 * on injectors moved from compile/createRegion to NeoForge's overloads and on SectionCompilerIndigoMixin.
 */
final class TerrainProbe {
    private TerrainProbe() {}
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("amber-worldtest", "terrain_probe_block");
    static final String DATA = "amber-render-data";
    static Block BLOCK; static BlockEntityType<ProbeBE> TYPE; static volatile BlockPos at;
    static final AtomicInteger emits = new AtomicInteger(), wraps = new AtomicInteger();
    static volatile Object seenData = "none";
    /** Which Indigo context tessellated the block: terrain (per-quad layers) vs the generic block context NeoForge's
     *  per-render-type renderBatched -> tesselateBlock path falls back to without the bridge. */
    static volatile String seenCtx = "none";
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] terrain probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static final class ProbeBE extends BlockEntity implements RenderDataBlockEntity {
        ProbeBE(BlockPos pos, BlockState state) { super(TYPE, pos, state); }
        /** Tags the value with the thread that asked: block-view's ChunkRendererRegionBuilderMixin captures render data while the
         *  region is built on the render thread; without it FabricBlockView's default reads it lazily on the meshing thread. */
        @Override public Object getRenderData() { return DATA + "@" + Thread.currentThread().getName(); }
    }

    static final class ProbeBlock extends Block implements EntityBlock {
        ProbeBlock(BlockBehaviour.Properties p) { super(p); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProbeBE(pos, state); }
    }

    /** Main entrypoint. */
    static void register() {
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID, new ProbeBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)));
        TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, ID, FabricBlockEntityTypeBuilder.create(ProbeBE::new, BLOCK).build());
    }

    /** A Fabric model that is not a vanilla adapter: Indigo has to tessellate it through emitBlockQuads. */
    static final class FabricModel extends ModelProbe.Wrapped implements FabricBakedModel {
        FabricModel(BakedModel m) { super(m); }
        @Override public boolean isVanillaAdapter() { return false; }
        @Override public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos, Supplier<RandomSource> random, RenderContext ctx) {
            if (pos.equals(at)) { emits.incrementAndGet(); seenCtx = ctx.getClass().getSimpleName(); seenData = view instanceof FabricBlockView fv ? fv.getBlockEntityRenderData(pos) : "not a FabricBlockView: " + view.getClass().getName(); }
            ((FabricBakedModel) m).emitBlockQuads(view, state, pos, random, ctx);
        }
    }

    static void registerClient() {
        ModelLoadingPlugin.register(ctx -> {
            ctx.registerBlockStateResolver(BLOCK, c -> {
                for (BlockState s : c.block().getStateDefinition().getPossibleStates()) c.setModel(s, new DelegatingUnbakedModel(ResourceLocation.withDefaultNamespace("block/stone")));
            });
            ModelResourceLocation loc = BlockModelShaper.stateToModelLocation(BLOCK.defaultBlockState());
            ctx.modifyModelAfterBake().register((model, c) -> {
                if (!loc.equals(c.topLevelId()) || model == null) return model;
                wraps.incrementAndGet(); return new FabricModel(model);
            });
        });
    }

    /** An air block in the camera's section (always meshed) away from the player. */
    static void place(Minecraft client) {
        BlockPos p = client.player.blockPosition(), o = SectionPos.of(client.gameRenderer.getMainCamera().getBlockPosition()).origin();
        // prefer 3+ above the feet: the other probes place blocks at the player's level (pass 1 only takes those)
        for (int pass = 0; pass < 2 && at == null; pass++)
        for (int y = 0; y < 16 && at == null; y++) for (int x = 0; x < 16 && at == null; x++) for (int z = 0; z < 16 && at == null; z++) {
            BlockPos q = o.offset(x, y, z);
            if ((pass == 1 || q.getY() >= p.getY() + 3) && q.distManhattan(p) >= 3 && q.distManhattan(p) <= 6 && client.level.getBlockState(q).isAir()) at = q.immutable();
        }
        if (at == null) { System.out.println("[amber-worldtest] terrain probe: no air block in the camera section"); return; }
        MinecraftServer server = client.getSingleplayerServer();
        BlockPos q = at;
        server.execute(() -> { boolean set = server.overworld().setBlockAndUpdate(q, BLOCK.defaultBlockState());
            System.out.println("[amber-worldtest] terrain probe: placed at " + q + " set=" + set + " now=" + server.overworld().getBlockState(q)); });
    }

    /** The client got the block and meshed it at least once (a fresh join of an existing world can take longer than 6 s). */
    static boolean meshed() { return emits.get() > 0; }

    static void report(Minecraft client) {
        check("model wrapped after bake", true, wraps.get() > 0);
        BakedModel m = client.getModelManager().getBlockModelShaper().getBlockModel(BLOCK.defaultBlockState());
        check("block model is the Fabric model", "FabricModel", m.getClass().getSimpleName());
        check("block placed", true, at != null && client.level.getBlockState(at).is(BLOCK));
        check("block entity on the client", true, at != null && client.level.getBlockEntity(at) instanceof ProbeBE);
        check("Indigo tessellated the Fabric model in chunk meshing", true, emits.get() > 0);
        check("tessellated by Indigo's terrain context", "TerrainRenderContext", seenCtx);
        check("render data captured with the render region (render thread)", DATA + "@Render thread", seenData);
        if (at != null) { MinecraftServer server = client.getSingleplayerServer(); BlockPos q = at; server.execute(() -> server.overworld().removeBlock(q, false)); }
        System.out.println("[amber-worldtest] terrain probe total: " + ok + "/" + total + " OK (emits=" + emits.get() + ")");
    }
}
