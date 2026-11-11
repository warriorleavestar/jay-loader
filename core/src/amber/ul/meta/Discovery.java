package amber.ul.meta;

import amber.ul.bake.Hash;
import amber.ul.model.*;
import amber.ul.resolve.Versions;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.tomlj.*;

/**
 * Finds every mod, including nested ones, through one pipeline for all loaders:
 *  - Fabric fabric.mod.json "jars" (recursive), NeoForge/Forge META-INF/jarjar/metadata.json, Quilt quilt_loader.jars.
 *  - Nested jars are extracted once into a content-addressed cache (<cache>/nested/<sha256>.jar).
 *  - Nested jars with mod metadata become ordinary candidates; ones without become library-only entries.
 *  - Duplicates by id: highest version wins (top-level wins a tie); ids the runtime provides itself are dropped.
 */
public final class Discovery {
    public final List<String> log = new ArrayList<>();
    private final Path nestedDir; private final Map<String, String> builtins;
    private final List<ModInfo> candidates = new ArrayList<>();
    private final Map<String, ModInfo> libs = new LinkedHashMap<>();   // sha -> library entry
    public int nestedJars;
    /** Metadata/limit problems found while walking jars (invalid_mod_id, bad_metadata, resource_limit, nested_unresolved). */
    public final List<amber.ul.resolve.Resolver.Problem> problems = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();   // sha256 of every jar walked (nested children deduped before recursing)
    /** Per top-level jar: nested jars and inflated bytes so far. */
    private static final class Budget { int jars; long bytes; }
    private void problem(String code, String severity, String modId, Path file, String msg) {
        problems.add(new amber.ul.resolve.Resolver.Problem(code, severity, modId, file == null ? null : file.getFileName().toString(), null, null, null, msg));
        log.add(code + ": " + msg);
    }

    public Discovery(Path cacheDir, Map<String, String> builtins) throws IOException {
        this.nestedDir = amber.ul.bake.CacheUse.ensureDir(cacheDir.resolve("nested")); this.builtins = builtins;
    }

    public List<ModInfo> discover(Path modsDir) throws IOException {
        try (var s = Files.list(modsDir)) { return discover(s.filter(p -> p.toString().endsWith(".jar")).sorted().toList()); }
    }

    public List<ModInfo> discover(List<Path> jars) throws IOException {
        for (Path j : jars) visit(j, null, 0, new Budget());
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo m : candidates) {
            if (builtins.containsKey(m.id)) { log.add("跳过 " + m + "（运行时自带 " + m.id + " " + builtins.get(m.id) + "）"); continue; }
            ModInfo prev = byId.get(m.id);
            if (prev == null) { byId.put(m.id, m); continue; }
            int c = Versions.compare(m.version, prev.version);
            ModInfo win = c > 0 || (c == 0 && prev.parent != null && m.parent == null) ? m : prev;
            log.add("重复 " + m.id + ": " + prev.version + (prev.parent == null ? "" : " (嵌套于 " + prev.parent + ")") + " vs " + m.version + (m.parent == null ? "" : " (嵌套于 " + m.parent + ")") + " -> 保留 " + win.version);
            byId.put(m.id, win);
        }
        List<ModInfo> out = new ArrayList<>(byId.values());
        out.addAll(libs.values());
        return out;
    }

    private void visit(Path jar, String parent, int depth, Budget budget) throws IOException {
        String sha = Hash.file(jar);
        if (!visited.add(sha)) { log.add("同一 jar 已处理过, 跳过: " + jar.getFileName() + (parent == null ? "" : " (嵌套于 " + parent + ")")); return; }
        List<ModInfo> mods;
        try { mods = MetadataReaders.read(jar); }
        catch (amber.ul.bake.SafeFiles.LimitException e) { problem("resource_limit", "error", parent, jar, "metadata too large in " + jar.getFileName() + ": " + e.getMessage()); return; }
        catch (RuntimeException | IOException e) { problem("bad_metadata", "error", parent, jar, "unreadable mod metadata in " + jar.getFileName() + ": " + e.getMessage()); return; }
        List<ModInfo> ok = new ArrayList<>();
        for (ModInfo m : mods) {
            if (amber.ul.bake.SafeFiles.validModId(m.kind, m.id)) ok.add(m);
            else problem("invalid_mod_id", "error", null, jar, "invalid " + m.kind + " mod id " + (m.id == null ? "null" : "\"" + m.id + "\"") + " in " + jar.getFileName() + " (mod not loaded)");
        }
        if (!mods.isEmpty() && ok.isEmpty()) return;
        mods = ok;
        if (mods.isEmpty()) {
            if (parent != null && providedByRuntime(jar)) { log.add("跳过嵌套库 " + jar.getFileName() + "（嵌套于 " + parent + "，MixinExtras 由运行时提供，只保留一份）"); return; }
            if (parent != null && !libs.containsKey(sha)) {
                ModInfo lib = new ModInfo("lib:" + jar.getFileName(), "0", LoaderKind.LIBRARY, jar);
                lib.sha256 = sha; lib.parent = parent; libs.put(sha, lib);
            }
            return;
        }
        for (ModInfo m : mods) { m.sha256 = sha; m.parent = parent; candidates.add(m); }
        ModInfo owner = mods.get(0);
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (String path : nestedPaths(jf, owner)) {
                JarEntry e = jf.getJarEntry(path);
                if (e == null) { log.add(owner.id + " 声明的嵌套 jar 不存在: " + path); continue; }
                if (depth + 1 > amber.ul.bake.SafeFiles.NEST_DEPTH_MAX) { problem("nested_unresolved", "error", owner.id, jar, "nesting deeper than " + amber.ul.bake.SafeFiles.NEST_DEPTH_MAX + ": " + path); continue; }
                if (++budget.jars > amber.ul.bake.SafeFiles.ROOT_NESTED_MAX) { problem("resource_limit", "error", owner.id, jar, "more than " + amber.ul.bake.SafeFiles.ROOT_NESTED_MAX + " nested jars under one mod file; rest skipped"); break; }
                byte[] b;
                long left = Math.min(amber.ul.bake.SafeFiles.NESTED_JAR_MAX, amber.ul.bake.SafeFiles.ROOT_INFLATED_MAX - budget.bytes);
                try (InputStream in = jf.getInputStream(e)) { b = amber.ul.bake.SafeFiles.boundedRead(in, left, path); }
                catch (amber.ul.bake.SafeFiles.LimitException x) { problem("resource_limit", "error", owner.id, jar, "nested jar over the size limit (" + x.getMessage() + "); skipped"); continue; }
                budget.bytes += b.length;
                String nsha = Hash.sha256(b);
                if (visited.contains(nsha)) { nestedJars++; continue; }   // dedupe by content before recursing
                Path out = amber.ul.bake.CacheUse.mark(amber.ul.bake.SafeFiles.under(nestedDir, nestedDir.resolve(nsha.substring(0, 32) + ".jar")));
                if (!Files.exists(out)) { Files.createDirectories(out.getParent()); Path tmp = Files.createTempFile(out.getParent(), "nest", ".tmp"); Files.write(tmp, b); amber.ul.bake.SafeFiles.publish(tmp, out); }
                nestedJars++;
                visit(out, owner.id, depth + 1, budget);
            }
        }
    }

    /** Nested copies of things the Amber runtime provides itself (one copy only on a single class loader). */
    static boolean providedByRuntime(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) { return jf.getJarEntry("com/llamalad7/mixinextras/MixinExtrasBootstrap.class") != null; }
    }

    static List<String> nestedPaths(JarFile jf, ModInfo m) throws IOException {
        List<String> l = new ArrayList<>();
        switch (m.kind) {
            case FABRIC -> { if (m.raw instanceof JsonObject o && o.has("jars")) for (JsonElement e : o.getAsJsonArray("jars")) l.add(e.getAsJsonObject().get("file").getAsString()); }
            case QUILT -> { if (m.raw instanceof JsonObject o && o.has("quilt_loader") && o.getAsJsonObject("quilt_loader").has("jars"))
                    for (JsonElement e : o.getAsJsonObject("quilt_loader").getAsJsonArray("jars")) l.add(e.getAsString()); }
            case NEOFORGE, FORGE -> {
                JarEntry e = jf.getJarEntry("META-INF/jarjar/metadata.json");
                if (e != null) try (InputStream in = jf.getInputStream(e)) {
                    JsonObject o = JsonParser.parseString(new String(amber.ul.bake.SafeFiles.boundedRead(in, amber.ul.bake.SafeFiles.METADATA_MAX, "META-INF/jarjar/metadata.json"), StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement x : o.getAsJsonArray("jars")) l.add(x.getAsJsonObject().get("path").getAsString());
                }
            }
            default -> {}
        }
        return l;
    }
}
