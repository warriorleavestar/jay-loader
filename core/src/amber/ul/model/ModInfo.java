package amber.ul.model;

import java.nio.file.Path;
import java.util.*;

/** Loader-neutral description of one mod. Every metadata format is translated into this. */
public final class ModInfo {
    public final String id; public final String version; public final LoaderKind kind; public final Path jar;
    public final List<Dependency> deps = new ArrayList<>();
    /** entrypoint key -> class names (Fabric "main"/"client", Quilt "init" ...). */
    public final Map<String, List<String>> entrypoints = new LinkedHashMap<>();
    public final List<String> mixinConfigs = new ArrayList<>();
    /** Access widener (Fabric) / access transformer (Forge) resource paths inside the jar. */
    public final List<String> accessFiles = new ArrayList<>();
    public final Set<String> provides = new LinkedHashSet<>();
    public String sha256 = "";
    public String name, description = ""; public Path originalJar;
    /** The loader-specific metadata as parsed (fabric.mod.json JsonObject, ...), for API facades. */
    public Object raw;
    /** Id of the mod that shipped this one as a nested jar (JiJ / JarJar), null for top-level jars. */
    public String parent;
    public ModInfo(String id, String version, LoaderKind kind, Path jar) { this.id = id; this.version = version; this.kind = kind; this.jar = jar; }
    @Override public String toString() { return id + "@" + version + " [" + kind + "]"; }
}
