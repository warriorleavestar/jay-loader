package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

/**
 * fabric-model-loading-api-v1 before/after-bake modifiers on Forge. Fabric's ModelLoaderBakerImplMixin targets vanilla
 * ModelBakerImpl.bake(ResourceLocation, ModelState); on Forge that delegates to bake(.., Function sprites), which has the
 * calls Fabric hooks, so the manifest retargets all three handlers there. Under strict require the mixin had been
 * excluded. The probe sees the stone block model go through both modifiers and the after-bake result reach the
 * ModelManager.
 */
final class ModelProbe {
    private ModelProbe() {}
    static final ModelResourceLocation STONE = new ModelResourceLocation(new ResourceLocation("minecraft", "stone"), "");
    static volatile int before, after; static volatile boolean stoneBefore; static volatile BakedModel stoneAfter;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] model: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void registerClient() {
        ModelLoadingPlugin.register(ctx -> {
            ctx.modifyModelBeforeBake().register((model, c) -> { before++; if (STONE.equals(c.id())) stoneBefore = true; return model; });
            ctx.modifyModelAfterBake().register((model, c) -> { after++; if (STONE.equals(c.id())) stoneAfter = model; return model; });
        });
    }

    static void run(Minecraft mc) {
        try {
            check("before-bake modifier called", true, before > 0);
            check("after-bake modifier called", true, after > 0);
            check("before-bake saw minecraft:stone#", true, stoneBefore);
            check("after-bake result is the stone model in use", true, stoneAfter != null && mc.getModelManager().getModel(STONE) == stoneAfter);
        } catch (Throwable e) { System.out.println("[amber-ffprobe] model: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] model total: " + ok + "/" + total + " OK");
    }
}
