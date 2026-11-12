package amber.ul;

import amber.ul.bake.Hash;
import amber.ul.meta.LegacyForgeMeta;
import amber.ul.model.*;
import amber.ul.resolve.Versions;
import com.google.gson.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * forge-1.12.2 in the bake interface (docs/forge-1.12.2-plan.md step 6, docs/amber-bake-interface.md).
 *
 * Unlike the other platforms nothing is baked ahead: 1.12 coremods are opaque IClassTransformers, so the game runs
 * through LaunchWrapper + FML as the official profile does, and the bridge (core-legacy, fmlbridge-1.12.2.jar) caches the
 * whole transformer chain's output per class at run time (CachingLaunchClassLoader). bake-instance therefore checks the
 * mods (legacy metadata, required dependencies) and returns the launch block: Java 8, LegacyLaunch, the Forge and
 * vanilla libraries, and the transformer cache directory.
 *
 * Launcher contract (forge-1.12.2): platform.installDir holds Forge 14.23.5.x's version.json (from the installer jar) and
 * a Maven-layout libraries/ with every library it lists, the universal jar included (the installer's maven/ entry).
 * The instance's mod jars must be in <gameDir>/mods (FML 1.12 only scans there); natives are extracted by the launcher
 * as for vanilla 1.12.2.
 */
final class LegacyPlatform {
    private LegacyPlatform() {}
    static final String KIND = "forge-1.12.2", BRIDGE = "fmlbridge-1.12.2.jar";
    /** Mod ids FML 1.12 itself provides. */
    static final Set<String> BUILTIN = Set.of("minecraft", "mcp", "fml", "forge");

    static int bakeInstance(JsonObject q, JsonObject pf) throws Exception {
        String mc = Bake.str(q, "mcVersion");
        Path game = Bake.path(q, "gameJar"), cacheRoot = Bake.path(q, "cacheRoot"), installDir = Bake.path(pf, "installDir");
        Path bridge = LoaderHome.bridge(BRIDGE);
        if (!Files.isRegularFile(bridge)) { Bake.problem("error", "platform_unavailable", null, null, null, "the " + KIND + " bridge (" + BRIDGE + ") is missing from this loader build"); return 1; }
        Path versionJson = installDir.resolve("version.json");
        if (!Files.isRegularFile(versionJson)) { Bake.error("bad_platform", "platform.installDir has no version.json (Forge 1.12.2's, from the installer)"); return 1; }
        JsonObject fv = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!fv.has("mainClass") || !fv.get("mainClass").getAsString().equals("net.minecraft.launchwrapper.Launch")) { Bake.error("bad_platform", "version.json is not a LaunchWrapper (Forge 1.12.2) profile"); return 1; }

        // the Forge libraries, in the profile's order, all present
        List<Path> forgeLibs = new ArrayList<>(); List<String> missing = new ArrayList<>();
        for (JsonElement e : fv.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (lib.has("clientreq") && !lib.get("clientreq").getAsBoolean()) continue;   // server-only (old-style profiles); may also be omitted
            String rel = lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact") ? lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString() : mavenPath(lib.get("name").getAsString());
            Path p = installDir.resolve("libraries").resolve(rel.replace('/', File.separatorChar));
            if (Files.isRegularFile(p)) forgeLibs.add(p); else missing.add(rel);
        }
        if (!missing.isEmpty()) { Bake.error("bad_platform", "Forge libraries missing under " + installDir.resolve("libraries") + ": " + missing); return 1; }

        // mods: legacy metadata + required dependencies (FML checks version ranges itself at launch: mismatches are warnings)
        List<ModInfo> mods = new ArrayList<>(); int bad = 0;
        List<Path> modJars = new ArrayList<>();
        for (JsonElement e : q.getAsJsonArray("mods")) modJars.add(Path.of(e.isJsonObject() ? e.getAsJsonObject().get("path").getAsString() : e.getAsString()));
        Bake.progress("bake-instance", "prepare", 0, 1, modJars.size() + " mod file(s)");
        for (Path j : modJars) {
            try { mods.addAll(LegacyForgeMeta.read(j)); }
            catch (Exception ex) { Bake.problem("error", "bad_metadata", null, j.toString(), null, j.getFileName() + ": " + ex.getMessage()); bad++; }
        }
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo m : mods) {
            ModInfo prev = byId.putIfAbsent(m.id, m);
            if (prev != null) { Bake.problem("error", "duplicate_mod", m.id, m.jar.toString(), null, "mod " + m.id + " is in both " + prev.jar.getFileName() + " and " + m.jar.getFileName()); bad++; }
        }
        for (ModInfo m : mods) for (Dependency d : m.deps) {
            if (!d.required() || BUILTIN.contains(d.modId().toLowerCase(Locale.ROOT))) continue;
            ModInfo t = byId.get(d.modId());
            JsonObject dep = new JsonObject(); dep.addProperty("id", d.modId()); dep.addProperty("range", d.range());
            if (t == null) { Bake.problem("error", "missing_dep", m.id, m.jar.toString(), dep, m.id + " requires " + d.modId() + " " + d.range()); bad++; continue; }
            dep.addProperty("found", t.version);
            boolean ok; try { ok = Versions.matches(t.version, d.range()); } catch (RuntimeException ex) { ok = true; }
            if (!ok) Bake.problem("warning", "version_mismatch", m.id, m.jar.toString(), dep, m.id + " wants " + d.modId() + " " + d.range() + ", found " + t.version + " (FML checks this at launch)");
        }
        if (bad > 0) return 2;

        // state + launch block
        Path stateDir = Files.createDirectories(Bake.path(q, "instanceDir").resolve(".amber-ul"));
        Path stateFile = stateDir.resolve("state.json");
        StringBuilder key = new StringBuilder("platform=" + KIND + ":" + installDir + "|" + mc + "|" + Hash.file(game) + "|" + Bake.LOADER_VERSION + "|" + Hash.file(bridge));
        for (Path j : modJars) key.append('|').append(Hash.file(j));
        String stateKey = Hash.str(key.toString());
        // per instance, not under cacheRoot: the game JVM writes it while running, and the shared cache stays read-only at
        // launch (bake interface #6); the cache inside keys itself further on the class path, mods/ and config/
        Path transformerCache = stateDir.resolve("legacy-1.12.2").resolve(stateKey.substring(0, 16));
        JsonObject st = new JsonObject();
        st.addProperty("schema", "amber-ul/state@1"); st.addProperty("stateKey", stateKey);
        st.addProperty("mcVersion", mc); st.addProperty("side", q.has("side") ? q.get("side").getAsString() : "client");
        st.addProperty("gameJar", game.toString()); st.addProperty("cacheRoot", cacheRoot.toString());
        JsonObject sp = new JsonObject(); sp.addProperty("kind", KIND); sp.addProperty("installDir", installDir.toString()); st.add("platform", sp);
        JsonArray ms = new JsonArray(); modJars.forEach(m -> ms.add(m.toString())); st.add("mods", ms);
        JsonArray libs = new JsonArray(); Bake.paths(q, "libraries").forEach(l -> libs.add(l.toString())); st.add("libraries", libs);
        st.addProperty("transformerCache", transformerCache.toString());
        Bake.writeAtomic(stateFile, Bake.G.toJson(st));

        JsonObject r = Bake.result("bake-instance");
        r.addProperty("stateKey", stateKey);
        JsonArray rm = new JsonArray(); for (ModInfo m : mods) rm.add(Bake.modJson(m)); r.add("resolvedMods", rm);
        JsonObject launch = new JsonObject();
        launch.addProperty("mainClass", "amber.ul.legacy.LegacyLaunch");
        JsonArray cp = new JsonArray();
        cp.add(bridge.toAbsolutePath().toString());
        Set<String> seen = new HashSet<>();   // Forge's entry wins over vanilla's for the same group:artifact (as the profiles merge)
        for (Path l : forgeLibs) { seen.add(ga(installDir.resolve("libraries"), l)); cp.add(l.toString()); }
        for (Path l : Bake.paths(q, "libraries")) { String k = q.has("libRoot") ? ga(Bake.path(q, "libRoot"), l) : null; if (k == null || seen.add(k)) cp.add(l.toString()); }
        cp.add(game.toString());
        launch.add("classpath", cp);
        JsonArray jvm = new JsonArray();
        jvm.add("-Damber.ul.legacy.cache=" + transformerCache);
        Path problems = stateDir.resolve(RuntimeProblems.FILE);
        jvm.add("-Damber.ul.problemsFile=" + problems);
        jvm.add("-Damber.ul.loaderVersion=" + Bake.LOADER_VERSION);
        jvm.add("-Dlog4j2.formatMsgNoLookups=true");   // Forge 2860 ships log4j 2.15.0
        jvm.add("-Dfml.ignoreInvalidMinecraftCertificates=true"); jvm.add("-Dfml.ignorePatchDiscrepancies=true");
        launch.add("jvmArgs", jvm);
        launch.add("gameArgsPrefix", new JsonArray());   // LegacyLaunch adds FML's tweaker
        launch.addProperty("javaMin", 8); launch.addProperty("javaMax", 8);
        launch.addProperty("modsDir", "mods");          // FML 1.12 loads <gameDir>/mods only
        launch.addProperty("stateFile", stateFile.toString());
        launch.addProperty("problemsFile", problems.toString());
        JsonObject lc = new JsonObject(); lc.addProperty("transformerCache", transformerCache.toString()); launch.add("cache", lc);
        r.add("launch", launch);
        JsonObject c = new JsonObject(); c.addProperty("state", "new"); c.addProperty("warm", false); c.addProperty("transformerCache", transformerCache.toString());
        r.add("cache", c);
        Bake.emit(r); return 0;
    }

    /** group/artifact key of a jar under a Maven-layout root, or null when it is not under it. */
    static String ga(Path root, Path jar) {
        Path rel;
        try { rel = root.toAbsolutePath().normalize().relativize(jar.toAbsolutePath().normalize()); } catch (IllegalArgumentException e) { return null; }
        if (rel.startsWith("..") || rel.getNameCount() < 4) return null;
        return rel.subpath(0, rel.getNameCount() - 2).toString().replace('\\', '/');
    }

    static String mavenPath(String coord) {
        String[] c = coord.split(":");
        String cls = c.length > 3 ? "-" + c[3] : "";
        return c[0].replace('.', '/') + "/" + c[1] + "/" + c[2] + "/" + c[1] + "-" + c[2] + cls + ".jar";
    }
}
