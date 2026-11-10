package amber.ul.personality;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A mod as Quilt mods see it (Amber's Quilt Loader API subset, loader matrix item 3), over the mod's Fabric container. */
public final class QuiltContainer implements org.quiltmc.loader.api.ModContainer, org.quiltmc.loader.api.ModMetadata {
    private static final Map<String, QuiltContainer> BY_ID = new ConcurrentHashMap<>();
    private final net.fabricmc.loader.api.ModContainer fabric;
    private QuiltContainer(net.fabricmc.loader.api.ModContainer f) { fabric = f; }
    public static QuiltContainer of(String id) { return BY_ID.computeIfAbsent(id, k -> new QuiltContainer(FabricFacade.container(k))); }

    public org.quiltmc.loader.api.ModMetadata metadata() { return this; }
    public Path rootPath() { List<Path> r = fabric.getRootPaths(); return r.get(0); }
    public String id() { return fabric.getMetadata().getId(); }
    public String group() {
        amber.ul.model.ModInfo m = amber.ul.runtime.ModRegistry.get(id());
        if (m != null && m.raw instanceof com.google.gson.JsonObject o && o.has("quilt_loader") && o.getAsJsonObject("quilt_loader").has("group"))
            return o.getAsJsonObject("quilt_loader").get("group").getAsString();
        return "loader.fabric";
    }
    public String name() { return fabric.getMetadata().getName(); }
    public String description() { return fabric.getMetadata().getDescription(); }
    @Override public String toString() { return id(); }
}
