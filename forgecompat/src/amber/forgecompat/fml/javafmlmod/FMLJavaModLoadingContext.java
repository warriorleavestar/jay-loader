package amber.forgecompat.fml.javafmlmod;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;

/**
 * Forge's FMLJavaModLoadingContext (gone on NeoForge 21.1). get() works while a mod is being constructed (NeoForge's
 * active container); a Forge 52 @Mod constructor taking this type is called from Amber's bridge constructor
 * (ForgeCompat adds one taking NeoForge's ModContainer).
 */
public class FMLJavaModLoadingContext {
    private final ModContainer container;
    FMLJavaModLoadingContext(ModContainer c) { container = c; }
    public static FMLJavaModLoadingContext get() { return new FMLJavaModLoadingContext(net.neoforged.fml.ModLoadingContext.get().getActiveContainer()); }
    public static FMLJavaModLoadingContext of(ModContainer c) { return new FMLJavaModLoadingContext(c); }
    public IEventBus getModEventBus() { return container.getEventBus(); }
    public ModContainer getContainer() { return container; }
    public void registerConfig(ModConfig.Type type, IConfigSpec spec) { container.registerConfig(type, spec); }
    public void registerConfig(ModConfig.Type type, IConfigSpec spec, String fileName) { container.registerConfig(type, spec, fileName); }
}
