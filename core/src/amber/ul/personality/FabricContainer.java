package amber.ul.personality;

import amber.ul.model.*;
import amber.ul.runtime.ModRegistry;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.*;

/** ModContainer + ModMetadata for one mod (or a runtime builtin such as "minecraft"), backed by its fabric.mod.json. */
final class FabricContainer implements ModContainer, ModMetadata {
    final String id, version, name, description; final JsonObject json; final Path jar; final ModInfo mod;
    FabricContainer(String id, String version, String name, String description, JsonObject json, Path jar, ModInfo mod) {
        this.id = id; this.version = version; this.name = name; this.description = description; this.json = json; this.jar = jar; this.mod = mod;
    }

    public ModMetadata getMetadata() { return this; }
    public List<Path> getRootPaths() {
        if (jar == null) return List.of();
        try {
            FileSystem fs;
            try { fs = FileSystems.getFileSystem(java.net.URI.create("jar:" + jar.toUri())); } catch (FileSystemNotFoundException e) { fs = FileSystems.newFileSystem(jar); }
            return List.of(fs.getPath("/"));
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public ModOrigin getOrigin() {
        return new ModOrigin() {
            public Kind getKind() { return mod == null ? Kind.UNKNOWN : mod.parent != null ? Kind.NESTED : Kind.PATH; }
            public List<Path> getPaths() { if (getKind() != Kind.PATH) throw new UnsupportedOperationException("kind " + getKind()); return List.of(mod.originalJar != null ? mod.originalJar : jar); }
            public String getParentModId() { if (getKind() != Kind.NESTED) throw new UnsupportedOperationException(); return mod.parent; }
            public String getParentSubLocation() { if (getKind() != Kind.NESTED) throw new UnsupportedOperationException(); return String.valueOf(mod.originalJar); }
            public String toString() { return getKind() + " " + (mod == null ? "" : mod.originalJar); }
        };
    }
    public Optional<ModContainer> getContainingMod() { return mod == null || mod.parent == null ? Optional.empty() : Optional.ofNullable(FabricFacade.container(mod.parent)); }
    public Collection<ModContainer> getContainedMods() {
        List<ModContainer> l = new ArrayList<>();
        for (ModInfo m : ModRegistry.all()) if (id.equals(m.parent) && m.kind != LoaderKind.LIBRARY) l.add(FabricFacade.container(m.id));
        return l;
    }

    public String getType() { return mod == null ? "builtin" : "fabric"; }
    public String getId() { return id; }
    public Collection<String> getProvides() { return mod == null ? List.of() : mod.provides; }
    public Version getVersion() { try { return Version.parse(version); } catch (VersionParsingException e) { return FabricFacade.stringVersion(version); } }
    public ModEnvironment getEnvironment() {
        String e = json != null && json.has("environment") ? json.get("environment").getAsString() : "*";
        return e.equals("client") ? ModEnvironment.CLIENT : e.equals("server") ? ModEnvironment.SERVER : ModEnvironment.UNIVERSAL;
    }
    public Collection<ModDependency> getDependencies() {
        List<ModDependency> l = new ArrayList<>();
        if (json != null) for (ModDependency.Kind kind : ModDependency.Kind.values()) {
            String key = kind.name().toLowerCase(Locale.ROOT);
            if (json.has(key)) for (String dep : json.getAsJsonObject(key).keySet())
                l.add(new ModDependency() { public Kind getKind() { return kind; } public String getModId() { return dep; } });
        }
        return l;
    }
    public String getName() { return name; }
    public String getDescription() { return description == null ? "" : description; }
    public Collection<Person> getAuthors() { return people("authors"); }
    public Collection<Person> getContributors() { return people("contributors"); }
    public ContactInformation getContact() { return json != null && json.has("contact") ? contact(json.getAsJsonObject("contact")) : ContactInformation.EMPTY; }
    public Collection<String> getLicense() {
        if (json == null || !json.has("license")) return List.of();
        JsonElement e = json.get("license");
        return e.isJsonArray() ? e.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList() : List.of(e.getAsString());
    }
    public Optional<String> getIconPath(int size) {
        if (json == null || !json.has("icon")) return Optional.empty();
        JsonElement e = json.get("icon");
        if (e.isJsonPrimitive()) return Optional.of(e.getAsString());
        TreeMap<Integer, String> m = new TreeMap<>();   // size -> path: smallest >= size, else largest
        for (var x : e.getAsJsonObject().entrySet()) m.put(Integer.parseInt(x.getKey()), x.getValue().getAsString());
        var c = m.ceilingEntry(size); if (c == null) c = m.lastEntry();
        return c == null ? Optional.empty() : Optional.of(c.getValue());
    }
    public Map<String, CustomValue> getCustomValues() {
        Map<String, CustomValue> r = new LinkedHashMap<>();
        if (json != null && json.has("custom")) for (var e : json.getAsJsonObject("custom").entrySet()) r.put(e.getKey(), FabricFacade.cv(e.getValue()));
        return r;
    }
    private Collection<Person> people(String key) {
        List<Person> l = new ArrayList<>();
        if (json != null && json.has(key)) for (JsonElement e : json.getAsJsonArray(key)) {
            String n = e.isJsonObject() ? e.getAsJsonObject().get("name").getAsString() : e.getAsString();
            ContactInformation c = e.isJsonObject() && e.getAsJsonObject().has("contact") ? contact(e.getAsJsonObject().getAsJsonObject("contact")) : ContactInformation.EMPTY;
            l.add(new Person() { public String getName() { return n; } public ContactInformation getContact() { return c; } public String toString() { return n; } });
        }
        return l;
    }
    static ContactInformation contact(JsonObject o) {
        Map<String, String> m = new LinkedHashMap<>();
        for (var e : o.entrySet()) if (e.getValue().isJsonPrimitive()) m.put(e.getKey(), e.getValue().getAsString());
        return new ContactInformation() {
            public Optional<String> get(String k) { return Optional.ofNullable(m.get(k)); }
            public Map<String, String> asMap() { return Collections.unmodifiableMap(m); }
        };
    }
    public String toString() { return id + " " + version; }
}
