package amber.testmod;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class TestMod implements ModInitializer, ClientModInitializer {
    /** referenced as "amber.testmod.TestMod::preLaunch" (default adapter, method reference) */
    public static void preLaunch() { System.out.println("[amber-testmod] preLaunch (before game main), gameInstance=" + FabricLoader.getInstance().getGameInstance()); }

    @Override public void onInitialize() {
        System.out.println("[amber-testmod] main/onInitialize, gameInstance set=" + (FabricLoader.getInstance().getGameInstance() != null));
    }
    @Override public void onInitializeClient() {
        FabricLoader fl = FabricLoader.getInstance();
        String fps = fl.getMappingResolver().mapFieldName("intermediary", "net.minecraft.class_310", "field_1738", "I");
        System.out.println("[amber-testmod] onInitializeClient: runtime ns=" + fl.getMappingResolver().getCurrentRuntimeNamespace()
                + ", class_310.field_1738 -> " + fps + ", class_310 -> " + fl.getMappingResolver().mapClassName("intermediary", "net.minecraft.class_310")
                + ", ferritecore loaded=" + fl.isModLoaded("ferritecore") + ", env=" + fl.getEnvironmentType()
                + ", same instance as main=" + (fl.getEntrypoints("main", Object.class).get(0) == this));
    }
}
