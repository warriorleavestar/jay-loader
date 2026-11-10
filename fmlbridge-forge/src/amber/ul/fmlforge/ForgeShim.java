package amber.ul.fmlforge;

import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.INameMappingService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiFunction;

/**
 * What modlauncher's Launcher answers on a Forge launch, for a launch without modlauncher. cpw.mods.modlauncher.Launcher
 * .findLayerManager / findNameMapping are redirected here at class load (amber.ul.fml.Jpms.forgeLauncher):
 *   layers: every layer is the boot layer (FML's ServiceLoader/findModule calls are redirected to the universal loader
 *           anyway, Jpms.redirect), so FMLLoader.getGameLayer() and LanguageLoadingProvider work;
 *   "srg" name mapping: SRG id -> Mojmap name, the same table the bake used. This is what ObfuscationReflectionHelper
 *           .remapName and the coremods' ASMAPI.mapMethod/mapField ask (FMLLoader.getNameFunction("srg")): Forge mods
 *           pass SRG names there, the runtime is Mojmap.
 */
public final class ForgeShim {
    private ForgeShim() {}
    public static volatile Map<String, String> srg = Map.of();
    static final IModuleLayerManager LAYERS = layer -> Optional.of(ModuleLayer.boot());

    public static Optional<IModuleLayerManager> findLayerManager() { return Optional.of(LAYERS); }

    public static Optional<BiFunction<INameMappingService.Domain, String, String>> findNameMapping(String naming) {
        if (!"srg".equals(naming)) return Optional.empty();
        return Optional.of((domain, name) -> srg.getOrDefault(name, name));
    }

    /** "m_1234_ name" per line (methods and fields; SRG ids are unique). */
    public static int load(Path table) throws IOException {
        Map<String, String> m = new HashMap<>();
        for (String l : Files.readAllLines(table, StandardCharsets.UTF_8)) { int sp = l.indexOf(' '); if (sp > 0) m.put(l.substring(0, sp), l.substring(sp + 1)); }
        srg = m;
        return m.size();
    }
}
