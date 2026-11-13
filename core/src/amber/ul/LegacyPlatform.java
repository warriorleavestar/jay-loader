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

    static int bakeInstance(JsonObject q, JsonObject pf, QuickPlay quick, Maintenance maint) throws Exception {
        String mc = Bake.str(q, "mcVersion");
        Path game = Bake.path(q, "gameJar"), cacheRoot = Bake.path(q, "cacheRoot"), installDir = Bake.path(pf, "installDir");
        Path bridge = LoaderHome.bridge(BRIDGE);
        if (!Files.isRegularFile(bridge)) { Bake.problem("error", "platform_unavailable", null, null, null, "the " + KIND + " bridge (" + BRIDGE + ") is missing from this loader build"); return 1; }
        Path versionJson = installDir.resolve("version.json");
        if (!Files.isRegularFile(versionJson)) { Bake.error("bad_platform", "platform.installDir has no version.json (Forge 1.12.2's, from the installer)"); return 1; }
        JsonObject fv = JsonParser.parseString(Files.readString(versionJson, StandardCharsets.UTF_8)).getAsJsonObject();
        String why = profileProblem(fv);
        if (why != null) { Bake.error("bad_platform", why); return 1; }
        // FML's own check of the Minecraft jar's signature stays on (the cache keeps the jar's signers), and the game jar
        // must be Mojang's 1.12.2 client: bake-instance refuses anything else
        String gameSha1 = sha1(game);
        if (!CLIENT_1122_SHA1.equals(gameSha1)) { Bake.error("bad_request", "gameJar is not Minecraft 1.12.2's client jar (sha1 " + gameSha1 + ", expected " + CLIENT_1122_SHA1 + ")"); return 1; }

        // the Forge libraries, in the profile's order, all present
        List<Path> forgeLibs = new ArrayList<>(); List<String> missing = new ArrayList<>();
        Path libDir = installDir.resolve("libraries").toAbsolutePath().normalize();
        for (JsonElement e : fv.getAsJsonArray("libraries")) {
            JsonObject lib = e.getAsJsonObject();
            if (lib.has("clientreq") && !lib.get("clientreq").getAsBoolean()) continue;   // server-only (old-style profiles); may also be omitted
            Path p;
            try {
                String rel = lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact") ? lib.getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString() : mavenPath(lib.get("name").getAsString());
                p = libraryPath(libDir, rel);
            } catch (RuntimeException ex) { Bake.error("bad_platform", "version.json library entry rejected: " + ex.getMessage()); return 1; }
            if (Files.isRegularFile(p)) forgeLibs.add(p); else missing.add(libDir.relativize(p).toString().replace(File.separatorChar, '/'));
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
        // ContainedDeps: FML extracts these at launch and loads them unless the same file is already in mods/, so they
        // satisfy dependencies (and are checked themselves) but never count as duplicates of a top-level mod
        List<ModInfo> contained = new ArrayList<>();
        for (Path j : modJars) {
            try { contained.addAll(LegacyForgeMeta.readContained(j)); } catch (Exception ex) { /* reported above as bad_metadata */ }
        }
        Map<String, ModInfo> byId = new LinkedHashMap<>();
        for (ModInfo m : mods) {
            ModInfo prev = byId.putIfAbsent(m.id, m);
            if (prev != null) { Bake.problem("error", "duplicate_mod", m.id, m.jar.toString(), null, "mod " + m.id + " is in both " + prev.jar.getFileName() + " and " + m.jar.getFileName()); bad++; }
        }
        List<ModInfo> checked = new ArrayList<>(mods);
        for (ModInfo m : contained) if (byId.putIfAbsent(m.id, m) == null) checked.add(m);
        for (ModInfo m : checked) for (Dependency d : m.deps) {
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
        st.addProperty("mcVersion", mc); st.addProperty("side", q.has("side") && !q.get("side").isJsonNull() ? Bake.str(q, "side") : "client");   // null counts as no side: client
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
        // the cache switch (bake-instance request "transformerCache": false): off = no -D, so LegacyLaunch starts the game
        // exactly as the official profile does; also what a launcher should use after a crash it suspects the cache of
        boolean cacheOn = !q.has("transformerCache") || q.get("transformerCache").getAsBoolean();
        if (cacheOn) jvm.add("-Damber.ul.legacy.cache=" + transformerCache);
        Path problems = stateDir.resolve(RuntimeProblems.FILE);
        jvm.add("-Damber.ul.problemsFile=" + problems);
        jvm.add("-Damber.ul.loaderVersion=" + Bake.LOADER_VERSION);
        jvm.add("-Dlog4j2.formatMsgNoLookups=true");   // Forge 2860 ships log4j 2.15.0
        JsonArray pre = new JsonArray();   // LegacyLaunch adds FML's tweaker
        if (quick != null) { quick.legacyArgs(jvm, pre); launch.add("quickPlay", quick.json(quick.singleplayer != null ? "amber-ul" : "game")); }   // v1.3.3
        if (maint != null) { maint.jvmArgs(jvm); launch.add("maintenance", maint.json()); }   // v1.3.3: core-legacy LegacyMaintenance
        launch.add("jvmArgs", jvm);
        launch.add("gameArgsPrefix", pre);
        launch.addProperty("javaMin", 8); launch.addProperty("javaMax", 8);
        launch.addProperty("modsDir", "mods");          // FML 1.12 loads <gameDir>/mods only
        launch.addProperty("stateFile", stateFile.toString());
        launch.addProperty("problemsFile", problems.toString());
        JsonObject lc = new JsonObject(); lc.addProperty("enabled", cacheOn); lc.addProperty("transformerCache", transformerCache.toString()); launch.add("cache", lc);
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

    /** Mojang's 1.12.2 client jar (piston-meta 1.12.2.json downloads.client.sha1). */
    static final String CLIENT_1122_SHA1 = "0f275bc1547d01fa5f56ba34bdc87d981ee12daf";

    /** Null when version.json is Forge 14.23.5.x for 1.12.2 on LaunchWrapper; otherwise why not. */
    static String profileProblem(JsonObject fv) {
        String main = fv.has("mainClass") ? fv.get("mainClass").getAsString() : null;
        if (!"net.minecraft.launchwrapper.Launch".equals(main)) return "version.json is not a LaunchWrapper profile (mainClass " + main + ")";
        String inherits = fv.has("inheritsFrom") ? fv.get("inheritsFrom").getAsString() : null;
        String id = fv.has("id") ? fv.get("id").getAsString() : "";
        if (!"1.12.2".equals(inherits) && !id.startsWith("1.12.2-")) return "version.json is not for Minecraft 1.12.2 (inheritsFrom " + inherits + ", id " + id + ")";
        if (!fv.has("libraries") || !fv.get("libraries").isJsonArray()) return "version.json lists no libraries";
        for (JsonElement e : fv.getAsJsonArray("libraries")) {
            if (!e.isJsonObject() || !e.getAsJsonObject().has("name")) continue;
            String n = e.getAsJsonObject().get("name").getAsString();
            if (n.startsWith("net.minecraftforge:forge:")) {
                String v = n.substring("net.minecraftforge:forge:".length());
                return v.startsWith("1.12.2-14.23.5.") ? null : "version.json is Forge " + v + ", not 1.12.2-14.23.5.x";
            }
        }
        return "version.json has no net.minecraftforge:forge library";
    }

    /** A library's path under libraries/: relative, no "..", and still inside libDir once resolved. */
    static Path libraryPath(Path libDir, String rel) {
        if (rel == null || rel.isEmpty() || rel.startsWith("/") || rel.startsWith("\\") || rel.contains(":") || Arrays.asList(rel.split("[/\\\\]")).contains(".."))
            throw new IllegalArgumentException("unsafe library path '" + rel + "'");
        Path p = libDir.resolve(rel.replace('/', File.separatorChar)).normalize();
        if (!p.startsWith(libDir)) throw new IllegalArgumentException("library path '" + rel + "' leaves libraries/");
        return p;
    }

    static String sha1(Path f) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
        try (var in = Files.newInputStream(f)) { byte[] b = new byte[1 << 16]; for (int n; (n = in.read(b)) > 0; ) md.update(b, 0, n); }
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    static String mavenPath(String coord) {
        String[] c = coord.split(":");
        if (c.length < 3 || Arrays.stream(c).anyMatch(String::isEmpty)) throw new IllegalArgumentException("library name '" + coord + "' is not group:artifact:version[:classifier]");
        String cls = c.length > 3 ? "-" + c[3] : "";
        return c[0].replace('.', '/') + "/" + c[1] + "/" + c[2] + "/" + c[1] + "-" + c[2] + cls + ".jar";
    }
}
