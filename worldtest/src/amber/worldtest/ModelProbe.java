package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.client.model.loading.v1.DelegatingUnbakedModel;
import net.fabricmc.fabric.api.client.model.loading.v1.FabricBakedModelManager;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * fabric-model-loading-api-v1 probe (-Damber.modelprobe=true): one ModelLoadingPlugin using every hook, checked on the
 * baked result the game uses. addModels (an extra model from an existing JSON), resolveModel (an id with no JSON),
 * registerBlockStateResolver (a block with no blockstate file), modifyModelOnLoad (stone -> dirt), modifyModelAfterBake
 * (granite wrapped), each through FabricBakedModelManager.getModel / BlockModelShaper. Models are compared by particle
 * sprite name.
 */
final class ModelProbe {
    private ModelProbe() {}
    static final ResourceLocation EXTRA = ResourceLocation.withDefaultNamespace("block/diamond_block");
    static final ResourceLocation RESOLVED = ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe/resolved");
    static final ResourceLocation BLOCK_ID = ResourceLocation.fromNamespaceAndPath("amber-worldtest", "model_probe_block");
    static Block BLOCK;
    static int resolveCalls, stateResolverCalls, onLoadHits, afterBakeHits, ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] model probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    /** Main entrypoint: a block that ships no blockstate file (its model comes only from the block state resolver). */
    static void register() {
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID, new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)));
    }

    /** Wraps a baked model so the probe can see modifyModelAfterBake's result. */
    static class Wrapped implements BakedModel {
        final BakedModel m;
        Wrapped(BakedModel m) { this.m = m; }
        @Override public java.util.List<net.minecraft.client.renderer.block.model.BakedQuad> getQuads(BlockState s, net.minecraft.core.Direction d, net.minecraft.util.RandomSource r) { return m.getQuads(s, d, r); }
        @Override public boolean useAmbientOcclusion() { return m.useAmbientOcclusion(); }
        @Override public boolean isGui3d() { return m.isGui3d(); }
        @Override public boolean usesBlockLight() { return m.usesBlockLight(); }
        @Override public boolean isCustomRenderer() { return m.isCustomRenderer(); }
        @Override public net.minecraft.client.renderer.texture.TextureAtlasSprite getParticleIcon() { return m.getParticleIcon(); }
        @Override public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() { return m.getTransforms(); }
        @Override public net.minecraft.client.renderer.block.model.ItemOverrides getOverrides() { return m.getOverrides(); }
    }

    static void registerClient() {
        ModelLoadingPlugin.register(ctx -> {
            ctx.addModels(EXTRA, RESOLVED);
            ctx.resolveModel().register(c -> {
                if (!c.id().equals(RESOLVED)) return null;
                resolveCalls++; return new DelegatingUnbakedModel(ResourceLocation.withDefaultNamespace("block/gold_block"));
            });
            ctx.registerBlockStateResolver(BLOCK, c -> {
                stateResolverCalls++;
                for (BlockState s : c.block().getStateDefinition().getPossibleStates()) c.setModel(s, new DelegatingUnbakedModel(ResourceLocation.withDefaultNamespace("block/emerald_block")));
            });
            ModelResourceLocation stone = BlockModelShaper.stateToModelLocation(Blocks.STONE.defaultBlockState()), granite = BlockModelShaper.stateToModelLocation(Blocks.GRANITE.defaultBlockState());
            ctx.modifyModelOnLoad().register((model, c) -> {
                if (!stone.equals(c.topLevelId())) return model;
                onLoadHits++; return new DelegatingUnbakedModel(ResourceLocation.withDefaultNamespace("block/dirt"));
            });
            ctx.modifyModelAfterBake().register((model, c) -> {
                if (!granite.equals(c.topLevelId()) || model == null) return model;
                afterBakeHits++; return new Wrapped(model);
            });
        });
        System.out.println("[amber-worldtest] model probe: plugin registered");
    }

    static String particle(BakedModel m) { return m == null ? "null" : m.getParticleIcon().contents().name().toString(); }

    static void run(Minecraft client) {
        try {
            ModelManager mm = client.getModelManager(); BakedModel missing = mm.getMissingModel();
            BakedModel extra = ((FabricBakedModelManager) (Object) mm).getModel(EXTRA), resolved = ((FabricBakedModelManager) (Object) mm).getModel(RESOLVED);
            check("addModels extra model present", true, extra != null && extra != missing);
            check("addModels extra model sprite", "minecraft:block/diamond_block", particle(extra));
            check("resolveModel called", true, resolveCalls > 0);
            check("resolveModel model present", true, resolved != null && resolved != missing);
            check("resolveModel model sprite", "minecraft:block/gold_block", particle(resolved));
            BlockModelShaper shaper = mm.getBlockModelShaper();
            BakedModel blockModel = shaper.getBlockModel(BLOCK.defaultBlockState());
            check("block state resolver called", true, stateResolverCalls > 0);
            check("block state resolver model (no blockstate file)", "minecraft:block/emerald_block", blockModel == missing ? "missing" : particle(blockModel));
            check("modifyModelOnLoad hit stone", true, onLoadHits > 0);
            check("modifyModelOnLoad stone now dirt", "minecraft:block/dirt", particle(shaper.getBlockModel(Blocks.STONE.defaultBlockState())));
            BakedModel granite = shaper.getBlockModel(Blocks.GRANITE.defaultBlockState());
            check("modifyModelAfterBake hit granite", true, afterBakeHits > 0);
            check("modifyModelAfterBake granite wrapped", "Wrapped", granite.getClass().getSimpleName());
            check("granite still granite", "minecraft:block/granite", particle(granite));
            check("untouched model (andesite)", "minecraft:block/andesite", particle(shaper.getBlockModel(Blocks.ANDESITE.defaultBlockState())));
        } catch (Throwable e) { System.out.println("[amber-worldtest] model probe: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-worldtest] model probe total: " + ok + "/" + total + " OK");
    }
}
