package amber.ul.personality;

import amber.ul.bake.Mappings;
import amber.ul.model.ModInfo;
import amber.ul.runtime.ModRegistry;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.*;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

/** Backing objects for the Fabric Loader API shim. */
public final class FabricFacade {
    private FabricFacade() {}
    public static volatile Mappings.Set3 mappings;
    /** fabric.mod.json-like metadata for synthetic builtin mods (e.g. amber_ul_compat carrying lithium:options vetoes). */
    public static final Map<String, JsonObject> SYNTHETIC = new HashMap<>();
    private static final Map<String, ModContainer> CONTAINERS = new HashMap<>();

    public static synchronized ModContainer container(String id) {
        ModContainer c = CONTAINERS.get(id);
        if (c != null) return c;
        ModInfo m = ModRegistry.get(id);
        if (m != null) c = new FabricContainer(id, m.version, m.name, m.description, m.raw instanceof JsonObject o ? o : null, m.jar, m);
        else if (ModRegistry.builtins().containsKey(id)) c = new FabricContainer(id, ModRegistry.builtins().get(id), id, "", SYNTHETIC.get(id), id.equals("minecraft") ? ModRegistry.gameJar : null, null);
        if (c != null) CONTAINERS.put(id, c);
        return c;
    }
    public static Collection<ModContainer> all() {
        List<ModContainer> l = new ArrayList<>();
        for (String id : ModRegistry.builtins().keySet()) l.add(container(id));
        for (ModInfo m : ModRegistry.all()) if (m.kind == amber.ul.model.LoaderKind.FABRIC || m.kind == amber.ul.model.LoaderKind.QUILT) l.add(container(m.id));
        return l;
    }
    public static <T> List<T> entrypoints(String key, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EntrypointContainer<T> c : entrypointContainers(key, type)) out.add(c.getEntrypoint());
        return out;
    }

    /** One lazily created instance per (mod, entrypoint key, value), as Fabric does (main and client get separate instances). */
    private static final Map<String, Object> INSTANCES = new HashMap<>();
    /** Bridge manifest (phase 5): entrypoint values that must not run on this platform. */
    public static final Set<String> SKIP_ENTRYPOINTS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<String> SKIPPED = new HashSet<>();
    private static final Map<String, LanguageAdapter> ADAPTERS = new HashMap<>();

    public static synchronized <T> List<EntrypointContainer<T>> entrypointContainers(String key, Class<T> type) {
        List<EntrypointContainer<T>> out = new ArrayList<>();
        for (ModInfo m : ModRegistry.all()) {
            if (!(m.raw instanceof JsonObject o)) continue;
            // Fabric: root "entrypoints"; Quilt: quilt_loader.entrypoints, where a value may be a single string or object
            JsonObject eps = m.kind == amber.ul.model.LoaderKind.QUILT ? (o.has("quilt_loader") && o.getAsJsonObject("quilt_loader").has("entrypoints") ? o.getAsJsonObject("quilt_loader").getAsJsonObject("entrypoints") : null)
                    : o.has("entrypoints") ? o.getAsJsonObject("entrypoints") : null;
            if (eps == null || !eps.has(key)) continue;
            JsonElement ev = eps.get(key);
            JsonArray list = ev.isJsonArray() ? ev.getAsJsonArray() : new JsonArray();
            if (!ev.isJsonArray()) list.add(ev);
            for (JsonElement el : list) {
                String adapter = "default", value;
                if (el.isJsonObject()) { value = el.getAsJsonObject().get("value").getAsString(); if (el.getAsJsonObject().has("adapter")) adapter = el.getAsJsonObject().get("adapter").getAsString(); }
                else value = el.getAsString();
                if (SKIP_ENTRYPOINTS.contains(value)) { if (SKIPPED.add(m.id + "|" + key + "|" + value)) System.out.printf("[amber-ul] 桥接: 跳过入口点 %s/%s %s (由 NeoForge 系统或 Amber 桥接代替)%n", m.id, key, value); continue; }
                String ik = m.id + "|" + key + "|" + adapter + "|" + value;   // Fabric: one instance per (mod, key, value)
                Object inst = INSTANCES.get(ik);
                if (inst == null) {
                    try { inst = adapter(adapter).create(container(m.id), value, type); }
                    catch (Exception e) { throw new RuntimeException("Could not create entrypoint '" + key + "' of mod " + m.id + ": " + value, e); }
                    INSTANCES.put(ik, inst);
                }
                T t = type.cast(inst); ModContainer prov = container(m.id); String def = value;
                out.add(new EntrypointContainer<T>() {
                    public T getEntrypoint() { return t; }
                    public ModContainer getProvider() { return prov; }
                    public String getDefinition() { return def; }
                });
            }
        }
        return out;
    }

    private static LanguageAdapter adapter(String name) throws Exception {
        if (name.equals("default")) return DEFAULT_ADAPTER;
        LanguageAdapter a = ADAPTERS.get(name);
        if (a != null) return a;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (ModInfo m : ModRegistry.all())
            if (m.raw instanceof JsonObject o && o.has("languageAdapters") && o.getAsJsonObject("languageAdapters").has(name)) {
                a = (LanguageAdapter) Class.forName(o.getAsJsonObject("languageAdapters").get(name).getAsString(), true, cl).getDeclaredConstructor().newInstance();
                ADAPTERS.put(name, a); return a;
            }
        throw new IllegalStateException("no language adapter '" + name + "'");
    }

    /** Fabric's default adapter: "a.b.C" (no-arg instance), "a.b.C::field" (static field), "a.b.C::method" (functional-interface proxy). */
    public static final LanguageAdapter DEFAULT_ADAPTER = new LanguageAdapter() {
        public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try {
                int i = value.indexOf("::");
                Class<?> c = Class.forName(i < 0 ? value : value.substring(0, i), true, cl);
                if (i < 0) return type.cast(c.getDeclaredConstructor().newInstance());
                String member = value.substring(i + 2);
                try { java.lang.reflect.Field f = c.getDeclaredField(member); f.setAccessible(true); return type.cast(f.get(null)); }
                catch (NoSuchFieldException ignored) {}
                java.lang.reflect.Method target = null;
                for (java.lang.reflect.Method mm : c.getDeclaredMethods()) if (mm.getName().equals(member)) { if (target != null) throw new LanguageAdapterException("ambiguous method " + value); target = mm; }
                if (target == null || !type.isInterface()) throw new LanguageAdapterException("can't resolve " + value);
                target.setAccessible(true);
                java.lang.reflect.Method tm = target;
                Object recv = java.lang.reflect.Modifier.isStatic(tm.getModifiers()) ? null : c.getDeclaredConstructor().newInstance();
                return type.cast(java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return method.invoke(recv == null ? tm : recv, args);
                    return tm.invoke(recv, args);
                }));
            } catch (LanguageAdapterException e) { throw e; }
            catch (Exception e) { throw new LanguageAdapterException(e); }
        }
    };

    public static SemanticVersion semver(String s, boolean strict) throws VersionParsingException { return FabricVersions.semver(s); }
    public static Version stringVersion(String s) { return FabricVersions.string(s); }
    public static final ObjectShare OBJECT_SHARE = FabricVersions.OBJECT_SHARE;

    static CustomValue cv(JsonElement e) { return e != null && e.isJsonObject() ? new CvObj(e) : e != null && e.isJsonArray() ? new CvArr(e) : new Cv(e); }
    static class Cv implements CustomValue {
        final JsonElement e; Cv(JsonElement e) { this.e = e; }
        public CvType getType() {
            if (e == null || e.isJsonNull()) return CvType.NULL;
            if (e.isJsonObject()) return CvType.OBJECT;
            if (e.isJsonArray()) return CvType.ARRAY;
            JsonPrimitive p = e.getAsJsonPrimitive();
            return p.isBoolean() ? CvType.BOOLEAN : p.isNumber() ? CvType.NUMBER : CvType.STRING;
        }
        public CvObject getAsObject() { check(CvType.OBJECT); return (CvObject) this; }
        public CvArray getAsArray() { check(CvType.ARRAY); return (CvArray) this; }
        public String getAsString() { check(CvType.STRING); return e.getAsString(); }
        public Number getAsNumber() { check(CvType.NUMBER); return e.getAsNumber(); }
        public boolean getAsBoolean() { check(CvType.BOOLEAN); return e.getAsBoolean(); }
        void check(CvType t) { if (getType() != t) throw new ClassCastException("can't convert " + getType() + " to " + t); }
    }
    static final class CvObj extends Cv implements CustomValue.CvObject {
        CvObj(JsonElement e) { super(e); }
        public int size() { return e.getAsJsonObject().size(); }
        public boolean containsKey(String k) { return e.getAsJsonObject().has(k); }
        public CustomValue get(String k) { JsonElement x = e.getAsJsonObject().get(k); return x == null ? null : cv(x); }
        public Iterator<Map.Entry<String, CustomValue>> iterator() { return e.getAsJsonObject().entrySet().stream().map(x -> Map.entry(x.getKey(), cv(x.getValue()))).iterator(); }
    }
    static final class CvArr extends Cv implements CustomValue.CvArray {
        CvArr(JsonElement e) { super(e); }
        public int size() { return e.getAsJsonArray().size(); }
        public CustomValue get(int i) { return cv(e.getAsJsonArray().get(i)); }
        public Iterator<CustomValue> iterator() { return e.getAsJsonArray().asList().stream().map(FabricFacade::cv).iterator(); }
    }

    public static final MappingResolver RESOLVER = new MappingResolver() {
        public Collection<String> getNamespaces() { return List.of("official", "intermediary", "named"); }
        public String getCurrentRuntimeNamespace() { return "named"; }
        private Mappings m(String ns) { return mappings.get(ns, "named"); }
        public String mapClassName(String ns, String cls) { return ns.equals("named") ? cls : m(ns).mapClass(cls.replace('.', '/')).replace('/', '.'); }
        public String unmapClassName(String targetNs, String cls) { return targetNs.equals("named") ? cls : mappings.get("named", targetNs).mapClass(cls.replace('.', '/')).replace('/', '.'); }
        public String mapFieldName(String ns, String owner, String name, String desc) { return ns.equals("named") ? name : m(ns).mapField(owner.replace('.', '/'), name, desc); }
        public String mapMethodName(String ns, String owner, String name, String desc) { return ns.equals("named") ? name : m(ns).mapMethod(owner.replace('.', '/'), name, desc); }
    };
}
