package amber.forgecompat.fml;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

/** Forge's ModLoadingContext: config registration moved to ModContainer on NeoForge. */
public class ModLoadingContext {
    private static final ModLoadingContext INSTANCE = new ModLoadingContext();
    public static ModLoadingContext get() { return INSTANCE; }
    public ModContainer getActiveContainer() { return net.neoforged.fml.ModLoadingContext.get().getActiveContainer(); }
    public String getActiveNamespace() { return net.neoforged.fml.ModLoadingContext.get().getActiveNamespace(); }
    public void registerConfig(ModConfig.Type type, IConfigSpec spec) { getActiveContainer().registerConfig(type, spec); }
    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) { getActiveContainer().registerConfig(type, spec, fileName); }
}
