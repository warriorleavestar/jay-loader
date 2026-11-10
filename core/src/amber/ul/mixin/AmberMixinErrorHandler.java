package amber.ul.mixin;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.spongepowered.asm.mixin.extensibility.*;

/**
 * Records every mixin that fails to prepare/apply (mod id, config, mixin, target, reason, category).
 * -Damber.ul.mixinErrors=warn downgrades failures to warnings (census / lenient packs); default keeps Mixin's own decision.
 * -Damber.ul.mixinExclude=<json file> lists mixins (FQN, optionally "mixin@target") to drop from their configs before Mixin
 * reads them: a mixin that can't apply then no longer takes the other mixins on the same target class down with it.
 */
public final class AmberMixinErrorHandler implements IMixinErrorHandler {
    public record Rec(String phase, String mod, String config, String mixin, String target, String handler, String category, String message) {}
    public static final List<Rec> RECORDS = Collections.synchronizedList(new ArrayList<>());
    public static final List<String> FAILURES = Collections.synchronizedList(new ArrayList<>());
    static boolean lenient() { return "warn".equals(System.getProperty("amber.ul.mixinErrors")); }

    @Override public ErrorAction onPrepareError(IMixinConfig config, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return record("prepare", mixin == null ? "?" : mixin.getClassName(), "-", config == null ? "?" : config.getName(), config == null ? "?" : modId(config), th, action);
    }
    @Override public ErrorAction onApplyError(String target, Throwable th, IMixinInfo mixin, ErrorAction action) {
        return record("apply", mixin.getClassName(), target, mixin.getConfig().getName(), modId(mixin.getConfig()), th, action);
    }
    private static String modId(IMixinConfig c) { return org.spongepowered.asm.mixin.FabricUtil.getModId(c, "?"); }

    private static ErrorAction record(String phase, String mixin, String target, String config, String mod, Throwable th, ErrorAction action) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = th; t != null; t = t.getCause() == t ? null : t.getCause()) all.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        String m = all.toString().replaceAll("\\s+", " ");
        String handler = null;
        var hm = java.util.regex.Pattern.compile("(?:Callback method|Redirector|Argument modifier method|method) ([A-Za-z0-9_$]+)\\(").matcher(m);
        if (hm.find()) handler = hm.group(1);
        Rec r = new Rec(phase, mod, config, mixin, target, handler, classify(m), m.length() > 1500 ? m.substring(0, 1500) : m);
        RECORDS.add(r);
        String line = String.format("[amber-ul] Mixin 失败 (%s, %s) 模组=%s mixin=%s 目标=%s%s", phase, r.category(), mod, mixin, target, handler == null ? "" : " 方法=" + handler);
        FAILURES.add(line); System.out.println(line);
        return lenient() && action == ErrorAction.ERROR ? ErrorAction.WARN : action;
    }

    /** Census categories (phase 4). Order matters: most specific first. */
    public static String classify(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        if (s.contains("lvt") || s.contains("local capture") || s.contains("locals") && s.contains("expected") || s.contains("no matching local") || s.contains("local variable")) return "local_capture";
        if (s.contains("@shadow") && (s.contains("not located") || s.contains("not found"))) return "shadow_missing";
        if ((s.contains("@accessor") || s.contains("@invoker")) && (s.contains("not") || s.contains("no "))) return "accessor_missing";
        if (s.contains("lambda$")) return "lambda_renamed";
        if (s.contains("scanned 0 target")) return "target_method_missing";
        if (s.contains("injection validation failed") || s.contains("failed injection check") || s.contains("(0/")) return "injection_point_not_found";
        if (s.contains("is not visible") || s.contains("illegalaccess") || s.contains("access")) return "access_changed";
        if (s.contains("could not find") || s.contains("cannot find") || s.contains("not found")) return "target_missing";
        return "other";
    }

    /** Baked mod jars of the current run, for the census JSON (the fixer must read exactly these, not stale cache entries). */
    public static final List<String> BAKED_JARS = new ArrayList<>();
    public static void writeCensus(Path out, Collection<String> changed, String platform, long ms) throws java.io.IOException {
        JsonObject o = new JsonObject();
        o.addProperty("schema", "amber-ul/mixin-census@1"); o.addProperty("platform", platform); o.addProperty("ms", ms);
        o.addProperty("changedClasses", changed.size());
        o.addProperty("isolationFile", String.valueOf(System.getProperty("amber.ul.isoFile")));
        JsonArray bj = new JsonArray(); for (String j : BAKED_JARS) bj.add(j); o.add("bakedJars", bj);   // the exact mod jars of this run (fixer input)
        JsonArray fs = new JsonArray();
        for (Rec r : new ArrayList<>(RECORDS)) fs.add(new Gson().toJsonTree(r));
        o.add("failures", fs);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(o), StandardCharsets.UTF_8);
    }

    // ---- exclusion (isolation) -----------------------------------------------------------------------------------------
    private static Set<String> excluded;
    public static synchronized Set<String> excluded() {
        if (excluded == null) {
            excluded = new HashSet<>();
            String f = System.getProperty("amber.ul.mixinExclude");
            if (f != null && Files.exists(Path.of(f))) try {
                for (JsonElement e : JsonParser.parseString(Files.readString(Path.of(f))).getAsJsonArray())
                    excluded.add(e.isJsonObject() ? e.getAsJsonObject().get("mixin").getAsString() : e.getAsString());
            } catch (Exception ex) { System.err.println("[amber-ul] bad mixinExclude file: " + ex); }
        }
        return excluded;
    }
    public static final List<String> REMOVED = Collections.synchronizedList(new ArrayList<>());
    /** Bridge vetoes (phase 5): mixin FQN -> module whose job a NeoForge system / Amber's bridge does instead. Not failures. */
    public static final Map<String, String> VETO = new java.util.concurrent.ConcurrentHashMap<>();

    /** Census: cumulative isolation result = mixins already excluded this round + the ones that failed now. */
    public static void writeIsolation(Path file, ClassLoader cl) throws java.io.IOException {
        Map<String, JsonObject> byMixin = new LinkedHashMap<>();
        // previous details only for mixins that are still excluded (a fixed mixin leaves the exclusion list)
        if (Files.exists(file)) for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
            String m = e.getAsJsonObject().get("mixin").getAsString();
            if (excluded().contains(m)) byMixin.put(m, e.getAsJsonObject());
        }
        for (Rec r : new ArrayList<>(RECORDS)) {
            if (byMixin.containsKey(r.mixin())) continue;
            JsonObject o = new Gson().toJsonTree(r).getAsJsonObject();
            o.addProperty("providesApi", providesApi(r.mixin(), cl));
            byMixin.put(r.mixin(), o);
        }
        for (String ex : excluded()) if (!byMixin.containsKey(ex)) { JsonObject o = new JsonObject(); o.addProperty("mixin", ex); byMixin.put(ex, o); }
        JsonArray arr = new JsonArray(); byMixin.values().forEach(arr::add);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(arr));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Accessor/invoker mixins are interfaces the mod's own code casts to; mixins that add interfaces likewise. Removing them can break the mod. */
    static boolean providesApi(String mixin, ClassLoader cl) {
        try (var in = cl.getResourceAsStream(mixin.replace('.', '/') + ".class")) {
            if (in == null) return false;
            var cr = new org.objectweb.asm.ClassReader(in.readAllBytes());
            return (cr.getAccess() & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0 || cr.getInterfaces().length > 0;
        } catch (Exception e) { return false; }
    }

    /** Launch: one problem line per isolated mixin (stable code mixin_incompatible), as Amber's problem stream expects. */
    public static void reportIsolated(Path file) {
        try {
            for (JsonElement e : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                String s = String.format("[amber-ul] 问题 mixin_incompatible (warning) 模组=%s mixin=%s 目标=%s 原因=%s%s",
                        str(o, "mod"), str(o, "mixin"), str(o, "target"), str(o, "category"),
                        o.has("providesApi") && o.get("providesApi").getAsBoolean() ? " ——注意: 该 mixin 提供接口/访问器, 模组自身代码可能依赖它" : "");
                System.out.println(s);
                amber.ul.RuntimeProblems.report("warning", "mixin_incompatible", str(o, "mod"), "mixin disabled: it does not apply to its target (" + str(o, "category") + ")",
                        "mixin", str(o, "mixin"), "target", str(o, "target"), "category", str(o, "category"),
                        "providesApi", String.valueOf(o.has("providesApi") && o.get("providesApi").getAsBoolean()));
            }
        } catch (Exception ex) { System.err.println("[amber-ul] bad isolation file " + file + ": " + ex); }
    }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "?"; }
}
