package amber.ul.runtime;

import amber.ul.model.ModInfo;
import java.util.*;

/** The single source of truth behind every loader's "is mod X loaded" API. */
public final class ModRegistry {
    /** dedicated server run (Fabric getEnvironmentType = SERVER). */
    public static volatile boolean server;
    /** the baked game jar: root of the "minecraft" mod container (Fabric resource loader reads vanilla lang from it). */
    public static volatile java.nio.file.Path gameJar;
    public static java.nio.file.Path gameDir = java.nio.file.Path.of(".");
    private static final Map<String, ModInfo> MODS = new LinkedHashMap<>();
    private static final Map<String, String> BUILTINS = new LinkedHashMap<>();
    public static void init(List<ModInfo> mods, Map<String, String> builtins) { mods.forEach(m -> MODS.put(m.id, m)); BUILTINS.putAll(builtins); }
    public static boolean isLoaded(String id) { return MODS.containsKey(id) || BUILTINS.containsKey(id); }
    public static Optional<String> version(String id) { ModInfo m = MODS.get(id); return m != null ? Optional.of(m.version) : Optional.ofNullable(BUILTINS.get(id)); }
    public static ModInfo get(String id) { return MODS.get(id); }
    public static Map<String, String> builtins() { return BUILTINS; }
    public static Collection<ModInfo> all() { return MODS.values(); }
}
