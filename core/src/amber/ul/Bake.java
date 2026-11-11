package amber.ul;

import amber.ul.bake.*;
import amber.ul.meta.Discovery;
import amber.ul.model.*;
import amber.ul.resolve.Resolver;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Amber's entry into the loader's bake pipeline (docs/amber-bake-interface.md, interface v1.3, protocol 1):
 *   java -cp <loader cp> amber.ul.Bake <stage> --request <utf8 request.json>
 * Stages: version | requirements | bake-mod | bake-instance | warm | gc. stdout = JSON lines (progress / problem / result / error).
 * Exit: 0 ok, 2 dependency problems, 1 anything else. Every file is written to a temp name and renamed atomically.
 */
public final class Bake {
    public static final String LOADER_VERSION = "0.3.0", PROTOCOL = "1";
    /** Interface revision (docs/amber-bake-interface.md). 1.1 added optional fields, 1.2 the warm and gc stages, 1.3 javaMin,
     *  generated loader libraries, the packaged layout, the rebuilt/reused report and runtime problem files; protocol (cache layout) unchanged. */
    public static final String INTERFACE = "1.3";
    /** Additive revision within INTERFACE (v1.3.1: `accepts`); consumers that check `interface` keep working. */
    public static final String INTERFACE_REVISION = "1.3.1";
    /** Java major version the loader needs, per platform (v1.3). The loader is Java 21 bytecode on every platform; it is
     *  also what the baked launch needs, whatever the game version's own json asks for (Forge 1.20.1 asks for 17). */
    static final Map<String, Integer> JAVA_MIN = new LinkedHashMap<>();
    static { JAVA_MIN.put("vanilla", 21); JAVA_MIN.put("neoforge", 21); JAVA_MIN.put("forge", 21); }
    /** Game versions per platform this loader can bake (interface v1.1). */
    static final Map<String, List<String>> PLATFORMS = new LinkedHashMap<>();
    static { PLATFORMS.put("vanilla", List.of("1.21.1")); PLATFORMS.put("neoforge", List.of("1.21.1")); PLATFORMS.put("forge", List.of("1.20.1")); }
    static final Gson G = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    static PrintStream out;

    public static void main(String[] argv) {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        // the bake must not write anything outside its caches: keep log4j (Forge's coremod engine) off Forge's file config
        if (System.getProperty("log4j.configurationFile") == null && System.getProperty("log4j2.configurationFile") == null) {
            var cfg = Bake.class.getResource("/amber-ul/bake-log4j2.xml");
            if (cfg != null) System.setProperty("log4j2.configurationFile", cfg.toString());
        }
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8)); // stray prints must not corrupt the JSON stream
        int code;
        try {
            String stage = argv.length > 0 ? argv[0] : "";
            JsonObject req = argv.length > 2 && argv[1].equals("--request")
                    ? JsonParser.parseString(Files.readString(Path.of(argv[2]), StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
            code = switch (stage) {
                case "version" -> version();
                case "requirements" -> requirements(check(req, "requirements-request"));
                case "bake-mod" -> bakeMod(check(req, "bake-mod-request"));
                case "bake-instance" -> bakeInstance(check(req, "bake-instance-request"));
                case "warm" -> warm(check(req, "warm-request"));
                case "gc" -> gc(check(req, "gc-request"));
                default -> { error("unknown_stage", "unknown stage '" + stage + "' (version | requirements | bake-mod | bake-instance | warm | gc)"); yield 1; }
            };
        } catch (BadRequest e) { error("bad_request", e.getMessage()); code = 1; }
        catch (Throwable t) { error("internal", t.toString()); t.printStackTrace(); code = 1; }
        out.flush(); System.exit(code);
    }

    // ---- stages -------------------------------------------------------------------------------------------------------

    static int version() throws IOException {
        JsonObject r = result("version");
        r.addProperty("loaderVersion", LOADER_VERSION); r.addProperty("pipelineVersion", Baker.PIPELINE); r.addProperty("protocol", Integer.parseInt(PROTOCOL));
        r.addProperty("interface", INTERFACE); r.addProperty("interfaceRevision", INTERFACE_REVISION);
        JsonArray mcs = new JsonArray(); PLATFORMS.values().stream().flatMap(List::stream).distinct().sorted().forEach(mcs::add); r.add("supportedMc", mcs);
        JsonObject pf = new JsonObject(); PLATFORMS.forEach((k, v) -> { JsonArray x = new JsonArray(); v.forEach(x::add); pf.add(k, x); }); r.add("platforms", pf);
        JsonObject mcache = new JsonObject(); mcache.addProperty("composer", amber.ul.map.MappingCache.COMPOSER); mcache.addProperty("keyFormat", "amber-tablekey/1"); r.add("mappingCache", mcache);
        // v1.3: from the build's class path (amber-ul/loader-libs.json), keyed by Maven artifact
        JsonObject libs = new JsonObject(); for (JsonElement e : loaderLibs()) { JsonObject f = e.getAsJsonObject(); libs.addProperty(f.get("artifact").getAsString(), f.get("version").getAsString()); }
        r.add("libs", libs);
        JsonObject jm = new JsonObject(); JAVA_MIN.forEach(jm::addProperty); r.add("javaMin", jm);
        r.addProperty("javaRuntime", Runtime.version().feature());
        r.add("accepts", accepts());
        emit(r); return 0;
    }

    /**
     * v1.3.1: per platform and game version, which mod ecosystems (Modrinth loader names, the platform's own first) this build loads, derived from the bridge jars that are
     * actually present (a core-only package has none). A Forge/NeoForge platform needs its FML bridge at all
     * (available=false and accepts=[] without it); Fabric/Quilt mods there need the Fabric API bridge; Forge 1.21.1 mods on
     * NeoForge need forgecompat. NeoForge 1.20.1 mods on Forge 1.20.1 and Quilt on the Fabric base are built into the loader.
     */
    static JsonArray accepts() {
        JsonArray out = new JsonArray();
        for (var e : PLATFORMS.entrySet()) for (String mc : e.getValue()) {
            String kind = e.getKey();
            List<String> acc = new ArrayList<>(), missing = new ArrayList<>();
            boolean available = true;
            switch (kind) {
                case "vanilla" -> { acc.add("fabric"); acc.add("quilt"); }
                case "neoforge" -> {
                    if (has("fmlbridge.jar", missing)) {
                        acc.add("neoforge");
                        if (has("fabricbridge.jar", missing)) { acc.add("fabric"); acc.add("quilt"); }
                        if (has("forgecompat.jar", missing)) acc.add("forge");
                    } else { available = false; has("fabricbridge.jar", missing); has("forgecompat.jar", missing); }
                }
                case "forge" -> {
                    if (has("fmlbridge-forge.jar", missing)) {
                        acc.add("forge"); acc.add("neoforge");
                        if (has("fabricbridge-forge.jar", missing)) { acc.add("fabric"); acc.add("quilt"); }
                    } else { available = false; has("fabricbridge-forge.jar", missing); }
                }
                default -> { }
            }
            JsonObject p = new JsonObject();
            p.addProperty("platform", kind); p.addProperty("mc", mc); p.addProperty("available", available);
            JsonArray a = new JsonArray(); acc.forEach(a::add); p.add("accepts", a);
            JsonArray m = new JsonArray(); missing.forEach(m::add); p.add("missingBridges", m);
            out.add(p);
        }
        return out;
    }
    static boolean has(String bridge, List<String> missing) {
        if (Files.isRegularFile(LoaderHome.bridge(bridge))) return true;
        missing.add(bridge); return false;
    }

    /** The loader's own libraries, generated at build time from the real class path (tools/loader_libs.py). */
    static JsonArray loaderLibs() throws IOException {
        try (var in = Bake.class.getResourceAsStream("/amber-ul/loader-libs.json")) {
            if (in == null) throw new IOException("amber-ul/loader-libs.json missing from the loader build");
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("files");
        }
    }

    static int requirements(JsonObject q) throws Exception {
        String mc = str(q, "mcVersion");
        var in = Bake.class.getResourceAsStream("/amber-ul/requirements-" + mc + ".json");
        if (in == null) { problem("error", "incompatible_mc", null, null, null, "Minecraft " + mc + " is not supported by this loader"); return 1; }
        JsonObject table = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        String platform = q.has("platform") ? q.get("platform").getAsString() : "vanilla";
        if (!PLATFORMS.getOrDefault(platform, List.of()).contains(mc)) { problem("error", "incompatible_loader", null, null, null, "platform " + platform + " is not supported on Minecraft " + mc + " (supported: " + PLATFORMS + ")"); return 1; }
        JsonArray files = new JsonArray(); Map<String, String> byPath = new HashMap<>();
        for (JsonElement e : table.getAsJsonArray("files")) { JsonObject f = e.getAsJsonObject().deepCopy(); if (f.has("kind") && f.get("kind").getAsString().equals("loader-lib")) continue; files.add(f); byPath.put(f.get("path").getAsString(), f.get("sha1").getAsString()); }
        for (JsonElement e : loaderLibs()) files.add(e.getAsJsonObject().deepCopy());   // v1.3: exactly the jars the loader runs with
        if (q.has("versionJson")) {   // Mojang's client mappings: url/sha1 come from the version json Amber already has
            JsonObject vj = JsonParser.parseString(Files.readString(Path.of(q.get("versionJson").getAsString()))).getAsJsonObject();
            JsonObject cm = vj.getAsJsonObject("downloads").getAsJsonObject("client_mappings");
            JsonObject f = new JsonObject(); f.addProperty("kind", "mappings"); f.addProperty("url", cm.get("url").getAsString());
            f.addProperty("sha1", cm.get("sha1").getAsString()); f.addProperty("size", cm.get("size").getAsLong()); f.addProperty("path", "mappings/" + mc + "/client_mappings.txt");
            String have = byPath.get(f.get("path").getAsString());
            if (have == null) files.add(f);   // the bundled table may already list it (Forge 1.20.1); then the sha1s must agree
            else if (!have.equalsIgnoreCase(f.get("sha1").getAsString())) { problem("error", "incompatible_mc", null, null, null, "versionJson client_mappings sha1 " + f.get("sha1").getAsString() + " differs from the bundled table " + have); return 1; }
        } else if (!byPath.containsKey("mappings/" + mc + "/client_mappings.txt")) problem("warning", "unsupported_feature", null, null, null, "no versionJson in request: client_mappings entry omitted");
        JsonObject r = result("requirements"); r.add("files", files); r.addProperty("javaMin", JAVA_MIN.get(platform)); emit(r); return 0;
    }

    /** Stage 1: one mod file (and its nested jars) -> metadata + baked jars in the shared, content-addressed cache. */
    static int bakeMod(JsonObject q) throws Exception {
        String mc = str(q, "mcVersion");
        Path jar = path(q, "modJar"), cacheRoot = path(q, "cacheRoot");
        mappingCache(q, cacheRoot);
        Path mappings = ensureMappings(q, cacheRoot, mc);
        progress("bake-mod", "discover", 0, 1, jar.getFileName().toString());
        Map<String, String> builtins = Map.of("minecraft", mc, "fabricloader", RuntimeVersions.FABRIC_LOADER, "mixinextras", RuntimeVersions.MIXINEXTRAS, "java", String.valueOf(Runtime.version().feature()));
        Discovery disc = new Discovery(cacheRoot, builtins);
        List<ModInfo> mods = disc.discover(List.of(jar));
        if (mods.isEmpty()) { problem("error", "unsupported_feature", null, jar.getFileName().toString(), null, "no mod metadata (fabric.mod.json / quilt.mod.json / mods.toml / neoforge.mods.toml)"); return 1; }
        for (String l : disc.log) problem("warning", "duplicate_mod", null, jar.getFileName().toString(), null, l);
        List<Path> libs = paths(q, "libraries");
        Path gameJar = q.has("gameJar") ? path(q, "gameJar") : null;
        if (gameJar == null || !Files.isRegularFile(gameJar)) {   // H16: never publish a bake made without the game classpath
            problem("error", "bad_request", mods.get(0).id, jar.getFileName().toString(), null, "gameJar missing or not a regular file: " + gameJar + " (nothing baked)");
            return 1;
        }
        for (var pr : disc.problems) problemOf(pr);
        Baker baker = new Baker(cacheRoot.resolve("baked"));
        baker.context = Baker.context(gameJar, libs);
        baker.obfGame = gameJar; baker.libs = libs;
        Mappings.Set3 mset = new Mappings.Set3(mappings);
        Map<String, List<ModInfo>> byNs = new LinkedHashMap<>();
        for (ModInfo m : mods) byNs.computeIfAbsent(m.kind == LoaderKind.LIBRARY ? Instance.RUNTIME_NS : m.kind.sourceNamespace(), k -> new ArrayList<>()).add(m);
        Map<ModInfo, Path> baked = new LinkedHashMap<>();
        int done = 0;
        for (var g : byNs.entrySet()) {
            String ns = g.getKey();
            progress("bake-mod", "remap", done, mods.size(), ns + " -> named, " + g.getValue().size() + " mod(s)");
            Mappings mp = ns.equals(Instance.RUNTIME_NS) ? Mappings.identity(ns) : mset.get(ns, Instance.RUNTIME_NS);
            baked.putAll(baker.bakeMods(g.getValue(), mp, () -> {
                List<Path> cp = new ArrayList<>();
                try { cp.add(baker.remapGame(gameJar, libs, mset.get("official", ns))); } catch (Exception e) { throw new RuntimeException(e); }
                cp.addAll(libs); return cp;
            }));
            done += g.getValue().size();
        }
        progress("bake-mod", "done", mods.size(), mods.size(), jar.getFileName().toString());
        JsonObject r = result("bake-mod");
        r.addProperty("key", Hash.str(mods.get(0).sha256 + "|" + LOADER_VERSION + "|" + PROTOCOL + "|" + Baker.PIPELINE + "|" + mset.get("intermediary", "named").fingerprint + "|" + baker.context));
        JsonArray ms = new JsonArray(), bj = new JsonArray();
        for (ModInfo m : mods) { ms.add(modJson(m)); bj.add(baked.get(m).toString()); }
        r.add("mods", ms); r.add("bakedJars", bj);
        emit(r); return 0;
    }

    /** Stage 2: resolve + per-mod-set game bake; writes <instanceDir>/.amber-ul/state.json for the Launcher. */
    static int bakeInstance(JsonObject q) throws Exception {
        Instance.Request rq = new Instance.Request();
        rq.mc = str(q, "mcVersion"); rq.client = path(q, "gameJar"); rq.cacheRoot = path(q, "cacheRoot");
        Path mapCache = mappingCache(q, rq.cacheRoot);
        // v1.1 platform: {kind: vanilla | neoforge | forge, installDir, mappingsDir?, manifest?, production?}
        JsonObject pf = q.has("platform") ? q.getAsJsonObject("platform") : new JsonObject();
        String kind = pf.has("kind") ? pf.get("kind").getAsString() : "vanilla";
        if (!PLATFORMS.getOrDefault(kind, List.of()).contains(rq.mc)) { problem("error", "incompatible_loader", null, null, null, "platform " + kind + " is not supported on Minecraft " + rq.mc + " (supported: " + PLATFORMS + ")"); return 1; }
        int javaMin = JAVA_MIN.get(kind);
        if (q.has("javaMajor") && q.get("javaMajor").getAsInt() < javaMin) { error("java_too_old", "the loader needs Java " + javaMin + " or newer for " + kind + " (request javaMajor " + q.get("javaMajor").getAsInt() + ")"); return 1; }
        Path installDir = kind.equals("vanilla") ? null : path(pf, "installDir");
        if (kind.equals("neoforge")) { rq.neoforge = installDir; rq.fmlBridge = LoaderHome.bridge("fmlbridge.jar"); rq.fabricBridge = LoaderHome.bridge("fabricbridge.jar"); }
        if (kind.equals("forge")) {
            rq.forge = installDir; rq.forgeBridge = LoaderHome.bridge("fmlbridge-forge.jar"); rq.fabricBridge = LoaderHome.bridge("fabricbridge-forge.jar");
            rq.forgeMappings = pf.has("mappingsDir") ? path(pf, "mappingsDir") : path(q, "mappingsDir");
            if (pf.has("manifest")) rq.forgeManifest = path(pf, "manifest");
        }
        boolean production = !pf.has("production") || pf.get("production").getAsBoolean();
        rq.libs = paths(q, "libraries"); rq.mappings = ensureMappings(q, rq.cacheRoot, rq.mc);
        if (q.has("libRoot")) rq.libRoot = path(q, "libRoot");
        rq.modJars = new ArrayList<>();
        for (JsonElement e : q.getAsJsonArray("mods")) rq.modJars.add(Path.of(e.isJsonObject() ? e.getAsJsonObject().get("path").getAsString() : e.getAsString()));
        rq.log = s -> progress("bake-instance", "log", 0, 0, s);
        progress("bake-instance", "prepare", 0, 1, rq.modJars.size() + " mod file(s)");
        Instance.Prepared p = Instance.prepare(rq);
        for (Resolver.Problem pr : p.problems) problemOf(pr);
        if (Resolver.blocking(p.problems)) return 2;

        Path stateDir = Files.createDirectories(path(q, "instanceDir").resolve(".amber-ul"));
        Path stateFile = stateDir.resolve("state.json");
        JsonObject prev = null;   // v1.3: what the previous bake of this instance left, to report rebuilt / reused
        try { if (Files.exists(stateFile)) prev = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject(); } catch (Exception e) { prev = null; }
        StringBuilder key = new StringBuilder("platform=" + kind + ":" + installDir + "|" + rq.mc + "|" + Hash.file(rq.client) + "|" + LOADER_VERSION + "|" + (q.has("javaMajor") ? q.get("javaMajor").getAsString() : "?") + "|" + str(q, "side") + "|" + RuntimeVersions.fingerprint() + "|" + Baker.context(rq.client, rq.libs) + "|game=" + p.bakedGame.getFileName());
        p.baked.stream().map(m -> m.sha256).sorted().forEach(h -> key.append('|').append(h));
        JsonObject st = new JsonObject();
        st.addProperty("schema", "amber-ul/state@1");
        st.addProperty("stateKey", Hash.str(key.toString()));
        st.addProperty("mcVersion", rq.mc); st.addProperty("side", str(q, "side"));
        st.addProperty("gameJar", rq.client.toString()); st.addProperty("mappings", rq.mappings.toString()); st.addProperty("cacheRoot", rq.cacheRoot.toString());
        if (rq.libRoot != null) st.addProperty("libRoot", rq.libRoot.toString());
        JsonObject sp = new JsonObject(); sp.addProperty("kind", kind);
        if (installDir != null) sp.addProperty("installDir", installDir.toString());
        if (rq.forgeMappings != null) sp.addProperty("mappingsDir", rq.forgeMappings.toString());
        if (rq.forgeManifest != null) sp.addProperty("manifest", rq.forgeManifest.toString());
        st.add("platform", sp);
        st.addProperty("mappingCache", mapCache.toString());
        JsonArray mods = new JsonArray(); rq.modJars.forEach(m -> mods.add(m.toString())); st.add("mods", mods);
        st.addProperty("bakedGame", p.bakedGame.toString());
        JsonArray sl = new JsonArray(); rq.libs.forEach(l -> sl.add(l.toString())); st.add("libraries", sl);   // v1.2: warm rebuilds from the state alone
        Instance.warmGraphs(p, rq);          // the graph is part of what the instance uses (gc keeps it), built now or mapped in
        JsonArray files = cacheFiles();
        st.add("cacheFiles", files);
        JsonObject fp = new JsonObject();   // cheap launch-time self-check: size + mtime
        for (Path f : rq.modJars) fp.addProperty(f.toString(), Files.size(f) + ":" + Files.getLastModifiedTime(f).toMillis());
        st.add("inputs", fp);
        writeAtomic(stateFile, G.toJson(st));

        JsonObject r = result("bake-instance");
        r.addProperty("stateKey", st.get("stateKey").getAsString());
        JsonArray rm = new JsonArray(); for (ModInfo m : p.baked) rm.add(modJson(m)); r.add("resolvedMods", rm);
        JsonObject launch = new JsonObject();
        launch.addProperty("mainClass", "amber.ul.Launcher");
        JsonArray cp = new JsonArray();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) if (!e.contains("gson-2.11")) cp.add(Path.of(e).toAbsolutePath().toString());   // the loader itself (absolute: the game runs in gameDir)
        for (Path l : rq.libs) cp.add(l.toString());                                                                            // game libraries
        // NeoForge/Forge: securejarhandler + JarJarFileSystems must be on the system class path (their FileSystemProviders)
        if (installDir != null) for (Path l : parentJars(installDir, kind.equals("forge") ? ForgePlatform.PARENT : NeoForgePlatform.PARENT)) cp.add(l.toString());
        launch.add("classpath", cp);
        JsonArray jvm = new JsonArray(); jvm.add("-Dlog4j2.formatMsgNoLookups=true");
        jvm.add("-Damber.ul.mappingCache=" + mapCache);
        if (installDir != null) for (String x : List.of("--add-opens", "java.base/java.util.jar=ALL-UNNAMED", "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
                "--add-exports", "java.base/sun.security.util=ALL-UNNAMED", "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming")) jvm.add(x);   // FML / securejarhandler
        if (kind.equals("forge") && !production) jvm.add("-Damber.forge.production=false");   // FMLLoader.isProduction(): true unless asked (dev only)
        launch.add("jvmArgs", jvm);
        JsonArray pre = new JsonArray(); pre.add("--state"); pre.add(stateFile.toString()); pre.add("--"); launch.add("gameArgsPrefix", pre);
        launch.addProperty("stateFile", stateFile.toString());
        r.add("launch", launch);
        JsonObject stats = new JsonObject(); stats.addProperty("bakeHits", p.baker.hits); stats.addProperty("bakeMisses", p.baker.misses);
        stats.addProperty("accessRules", p.rules.rules.size()); stats.addProperty("ms", (p.tDiscover + p.tResolve + p.tBake) / 1_000_000);
        r.add("stats", stats);
        r.add("cache", cacheReport(prev, st, files, p.baker.misses));
        launch.addProperty("javaMin", javaMin);
        launch.addProperty("problemsFile", stateDir.resolve(RuntimeProblems.FILE).toString());
        emit(r); return 0;
    }

    /**
     * v1.3: did this bake-instance rebuild anything? {state: "reused" | "rebuilt" | "new", stateKeyChanged, bakeMisses,
     * newCacheFiles, warm}. "reused" = same stateKey as the previous state, no bake misses and no cache file that the
     * previous state did not list; warm = true when Amber should run warm again (anything rebuilt or no earlier cacheFiles).
     */
    static JsonObject cacheReport(JsonObject prev, JsonObject st, JsonArray files, int misses) {
        JsonObject c = new JsonObject();
        Set<String> before = new HashSet<>();
        if (prev != null && prev.has("cacheFiles")) for (JsonElement e : prev.getAsJsonArray("cacheFiles")) before.add(e.getAsString());
        int fresh = 0; for (JsonElement e : files) if (!before.contains(e.getAsString())) fresh++;
        boolean keyChanged = prev == null || !prev.has("stateKey") || !prev.get("stateKey").getAsString().equals(st.get("stateKey").getAsString());
        String state = prev == null ? "new" : keyChanged || misses > 0 || fresh > 0 ? "rebuilt" : "reused";
        c.addProperty("state", state); c.addProperty("stateKeyChanged", keyChanged); c.addProperty("bakeMisses", misses); c.addProperty("newCacheFiles", fresh);
        c.addProperty("warm", !state.equals("reused") || prev == null || !prev.has("cacheFiles"));
        return c;
    }

    /**
     * warm (v1.2): everything the instance's launch needs from the caches (mapping graphs, remapped game, baked mods,
     * platform jars, coremod outputs), built now or verified as hits, without starting the game. Rewrites the state's
     * cacheFiles. Can be killed at any time (every product is published atomically).
     */
    static int warm(JsonObject q) throws Exception {
        Path stateFile = path(q, "stateFile");
        JsonObject st = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8)).getAsJsonObject();
        if (!st.has("libraries")) { error("stale_state", "state file has no libraries (written by an interface v1 loader): run bake-instance again"); return 1; }
        long t0 = System.nanoTime();
        JsonObject prevWarm = st.deepCopy();
        Instance.Request rq = requestFromState(st);
        rq.log = s -> progress("warm", "log", 0, 0, s);
        progress("warm", "prepare", 0, 1, rq.modJars.size() + " mod file(s)");
        Instance.Prepared p = Instance.prepare(rq);
        for (Resolver.Problem pr : p.problems) problemOf(pr);
        if (Resolver.blocking(p.problems)) return 2;
        progress("warm", "graphs", 0, 1, "mapping graphs");
        Instance.warmGraphs(p, rq);
        JsonArray files = cacheFiles();
        st.add("cacheFiles", files);
        writeAtomic(stateFile, G.toJson(st));
        JsonObject r = result("warm");
        JsonObject stats = new JsonObject(); stats.addProperty("bakeHits", p.baker.hits); stats.addProperty("bakeMisses", p.baker.misses);
        stats.addProperty("cacheFiles", files.size()); stats.addProperty("ms", (System.nanoTime() - t0) / 1_000_000);
        r.add("stats", stats); r.addProperty("stateFile", stateFile.toString());
        r.add("cache", cacheReport(prevWarm, st, files, p.baker.misses));
        emit(r); return 0;
    }

    /** The instance request a state file describes (same fields the Launcher reads). */
    static Instance.Request requestFromState(JsonObject st) throws Exception {
        Instance.Request q = new Instance.Request();
        q.mc = st.get("mcVersion").getAsString(); q.client = Path.of(st.get("gameJar").getAsString());
        q.mappings = CacheUse.mark(Path.of(st.get("mappings").getAsString())); q.cacheRoot = Path.of(st.get("cacheRoot").getAsString());
        if (st.has("libRoot")) q.libRoot = Path.of(st.get("libRoot").getAsString());
        q.modJars = new ArrayList<>(); for (JsonElement m : st.getAsJsonArray("mods")) q.modJars.add(Path.of(m.getAsString()));
        q.libs = new ArrayList<>(); for (JsonElement l : st.getAsJsonArray("libraries")) q.libs.add(Path.of(l.getAsString()));
        if (st.has("mappingCache")) System.setProperty("amber.ul.mappingCache", st.get("mappingCache").getAsString());
        if (st.has("platform")) {
            JsonObject pf = st.getAsJsonObject("platform"); String kind = pf.get("kind").getAsString();
            if (kind.equals("neoforge")) { q.neoforge = Path.of(pf.get("installDir").getAsString()); q.fmlBridge = LoaderHome.bridge("fmlbridge.jar"); q.fabricBridge = LoaderHome.bridge("fabricbridge.jar"); }
            if (kind.equals("forge")) {
                q.forge = Path.of(pf.get("installDir").getAsString()); q.forgeBridge = LoaderHome.bridge("fmlbridge-forge.jar"); q.fabricBridge = LoaderHome.bridge("fabricbridge-forge.jar");
                if (pf.has("mappingsDir")) q.forgeMappings = Path.of(pf.get("mappingsDir").getAsString());
                if (pf.has("manifest")) q.forgeManifest = Path.of(pf.get("manifest").getAsString());
            }
        }
        return q;
    }

    /** Products that exist (a path computed only for a key, e.g. a Fabric-style bake path on Forge, is not one). */
    static JsonArray cacheFiles() { JsonArray a = new JsonArray(); for (Path p : CacheUse.snapshot()) if (Files.exists(p)) a.add(p.toString()); return a; }

    /** Loader-managed parts of a cache root; gc never looks anywhere else (classcache, changed-classes, isolation data stay). */
    static final List<String> MANAGED = List.of("baked", "nested", "mappings");
    /** Temp names left by a killed bake (pid + nanoTime, ".tmp"): removable once older than tmpMaxAgeHours. */
    static boolean isTemp(String n) { return n.contains(".tmp") || n.matches("bake-\\d+-\\d+\\.(pre|cls)\\.jar") || n.matches(".+\\.t"); }

    /**
     * gc (v1.2): cache entries no live state uses. Dry run unless "apply": true. Only regular files under
     * cacheRoot/{baked,nested,mappings} (and the request's mappingCache, if given) are ever candidates; links are not
     * followed and anything whose real path leaves the root is skipped. Every live state must list its cacheFiles
     * (written by bake-instance/warm v1.2), else nothing is done.
     */
    static int gc(JsonObject q) throws Exception {
        Path root0 = path(q, "cacheRoot");
        if (!Files.isDirectory(root0)) { error("bad_request", "cacheRoot is not a directory: " + root0); return 1; }
        final Path root = root0.toRealPath();
        if (root.getNameCount() < 2) { error("bad_request", "refusing to gc a top-level directory: " + root); return 1; }
        if (MANAGED.stream().noneMatch(d -> Files.isDirectory(root.resolve(d)))) { error("bad_request", "not a loader cache root (no baked/, nested/ or mappings/): " + root); return 1; }
        boolean apply = q.has("apply") && q.get("apply").getAsBoolean();
        double maxAgeH = q.has("tmpMaxAgeHours") ? q.get("tmpMaxAgeHours").getAsDouble() : 1.0;
        JsonArray states = q.has("liveStateFiles") ? q.getAsJsonArray("liveStateFiles") : new JsonArray();
        if (states.isEmpty() && !(q.has("allowNoLiveStates") && q.get("allowNoLiveStates").getAsBoolean())) {
            error("bad_request", "no liveStateFiles: everything would be removed; pass \"allowNoLiveStates\": true to mean that"); return 1;
        }
        Set<Path> live = new HashSet<>(); List<Path> liveDirs = new ArrayList<>();
        for (JsonElement e : states) {
            Path sf = Path.of(e.getAsString());
            if (!Files.isRegularFile(sf)) { error("bad_request", "live state file not found (refusing to guess): " + sf); return 1; }
            JsonObject st = JsonParser.parseString(Files.readString(sf, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!st.has("cacheFiles")) { error("stale_state", "state has no cacheFiles (run warm on it first): " + sf); return 1; }
            for (JsonElement f : st.getAsJsonArray("cacheFiles")) {
                Path p = Path.of(f.getAsString()).toAbsolutePath().normalize();
                Path real = Files.exists(p) ? p.toRealPath() : p;
                live.add(real); if (Files.isDirectory(real)) liveDirs.add(real);
            }
        }
        List<Path> scan = new ArrayList<>(); for (String d : MANAGED) if (Files.isDirectory(root.resolve(d))) scan.add(root.resolve(d));
        Path mapCache = null;
        if (q.has("mappingCache")) {
            mapCache = path(q, "mappingCache");
            if (Files.isDirectory(mapCache)) { mapCache = mapCache.toRealPath(); if (mapCache.getNameCount() >= 2 && !mapCache.startsWith(root)) scan.add(mapCache); }
        }
        long now = System.currentTimeMillis();
        JsonArray remove = new JsonArray(); List<Path> victims = new ArrayList<>(); long bytes = 0; int kept = 0, skipped = 0;
        for (Path base : scan) {
            Path scope = base.startsWith(root) ? root : base;   // a separate mapping cache is its own scope
            List<Path> files = new ArrayList<>();
            int[] sk = {0};
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path d, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                    if (a.isSymbolicLink() || a.isOther() || !d.toRealPath().startsWith(scope)) { sk[0]++; return FileVisitResult.SKIP_SUBTREE; }   // junctions too
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, java.nio.file.attribute.BasicFileAttributes a) {
                    if (a.isRegularFile() && !a.isSymbolicLink()) files.add(f); else sk[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            skipped += sk[0];
            for (Path f : files) {
                Path real = f.toRealPath();
                if (!real.startsWith(scope)) { skipped++; continue; }
                if (scope != root && !(real.toString().contains("graph") || real.getFileName().toString().endsWith(".json"))) { skipped++; continue; }   // mapping cache: graphs + summaries only
                String n = real.getFileName().toString();
                String reason = null;
                if (isTemp(n)) { if (now - Files.getLastModifiedTime(real).toMillis() > maxAgeH * 3600_000) reason = "stale temp"; else { kept++; continue; } }
                else if (live.contains(real) || liveDirs.stream().anyMatch(real::startsWith) || isSidecarOfLive(real, live)) { kept++; continue; }
                else if (scope != root && n.equals("build-summary.json")) { kept++; continue; }
                else reason = "unreferenced";
                long sz = Files.size(real); bytes += sz; victims.add(real);
                JsonObject o = new JsonObject(); o.addProperty("path", real.toString()); o.addProperty("bytes", sz); o.addProperty("reason", reason); remove.add(o);
            }
        }
        int removed = 0; JsonArray failed = new JsonArray();
        if (apply) {
            for (Path v : victims) try { Files.delete(v); removed++; } catch (IOException e) { failed.add(v + ": " + e); }
            for (Path base : scan) pruneEmpty(base);
        }
        JsonObject r = result("gc");
        r.addProperty("apply", apply); r.addProperty("cacheRoot", root.toString());
        r.addProperty("liveStates", states.size()); r.addProperty("liveFiles", live.size());
        r.addProperty("kept", kept); r.addProperty("skipped", skipped);
        r.addProperty("removable", victims.size()); r.addProperty("removableBytes", bytes);
        if (apply) { r.addProperty("removed", removed); r.add("failed", failed); }
        r.add("remove", remove);
        emit(r); return failed.isEmpty() ? 0 : 1;
    }

    /** x.jar.srg.txt and the like belong to x.jar (same directory, name extends the live name). */
    static boolean isSidecarOfLive(Path f, Set<Path> live) {
        String n = f.getFileName().toString();
        for (Path l : live) if (f.getParent().equals(l.getParent()) && n.startsWith(l.getFileName().toString() + ".")) return true;
        return false;
    }

    static void pruneEmpty(Path base) throws IOException {
        List<Path> dirs = new ArrayList<>();
        try (var s = Files.walk(base)) { s.filter(Files::isDirectory).filter(d -> !d.equals(base)).forEach(dirs::add); }
        dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path d : dirs) try (var s = Files.list(d)) { if (s.findAny().isEmpty()) Files.delete(d); } catch (IOException ignored) {}
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /**
     * v1.1: where compiled mapping graphs live (MappingCache: <root>/<mc>/graph/<tableKey>.amap + .report.json).
     * Request field "mappingCache" (a directory Amber shares across instances), else <cacheRoot>/mappings.
     * Set for this process; bake-instance passes it on to the launch (jvmArgs) so a launch-time rebake uses the same.
     */
    static Path mappingCache(JsonObject q, Path cacheRoot) throws BadRequest {
        Path p = (q.has("mappingCache") ? path(q, "mappingCache") : cacheRoot.resolve("mappings")).toAbsolutePath();
        System.setProperty("amber.ul.mappingCache", p.toString());
        return p;
    }

    /** The platform's own jars that must sit on the system class path (by maven path prefix), from inst/version.json. */
    static List<Path> parentJars(Path installDir, List<String> prefixes) throws IOException {
        JsonObject vj = JsonParser.parseString(Files.readString(installDir.resolve("inst/version.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        List<Path> l = new ArrayList<>();
        for (JsonElement e : vj.getAsJsonArray("libraries")) {
            String path = e.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            if (prefixes.stream().anyMatch(path::startsWith)) l.add(installDir.resolve("libraries").resolve(path));
        }
        return l;
    }

    /** mappingsDir holds what `requirements` listed (intermediary jar + client_mappings.txt); merged once into cacheRoot. */
    static Path ensureMappings(JsonObject q, Path cacheRoot, String mc) throws Exception {
        Path dir = path(q, "mappingsDir");
        Path merged = dir.resolve("merged.tiny");
        if (Files.exists(merged)) return merged;   // prototype convenience: an already merged set
        Path inter = dir.resolve("intermediary-" + mc + "-v2.jar"), pg = dir.resolve("client_mappings.txt");
        if (!Files.exists(inter) || !Files.exists(pg)) throw new BadRequest("mappingsDir must contain intermediary-" + mc + "-v2.jar and client_mappings.txt (see requirements)");
        Path out = CacheUse.mark(cacheRoot.resolve("mappings").resolve(mc + "-" + Hash.str(Hash.file(inter) + Hash.file(pg) + Baker.PIPELINE).substring(0, 16) + ".tiny"));
        if (Files.exists(out)) return out;
        progress("bake", "mappings", 0, 1, "merge intermediary + mojmap");
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        amber.ul.tools.BuildMappings.main(new String[]{inter.toString(), pg.toString(), tmp.toString()});
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return out;
    }

    static JsonObject modJson(ModInfo m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id); o.addProperty("version", m.version); o.addProperty("kind", m.kind.name().toLowerCase(Locale.ROOT));
        o.addProperty("parent", m.parent);
        JsonArray deps = new JsonArray();
        for (Dependency d : m.deps) { JsonObject x = new JsonObject(); x.addProperty("id", d.modId()); x.addProperty("range", d.range()); x.addProperty("required", d.required()); deps.add(x); }
        o.add("deps", deps);
        JsonArray pv = new JsonArray(); m.provides.forEach(pv::add); o.add("provides", pv);
        String env = m.raw instanceof JsonObject j && j.has("environment") ? j.get("environment").getAsString() : "*";
        o.addProperty("environment", env);
        JsonArray mx = new JsonArray(); m.mixinConfigs.forEach(mx::add); o.add("mixinConfigs", mx);
        JsonArray ep = new JsonArray(); m.entrypoints.keySet().forEach(ep::add); o.add("entrypoints", ep);
        return o;
    }

    static void writeAtomic(Path f, String text) throws IOException {
        Path tmp = f.resolveSibling(f.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static final class BadRequest extends Exception { BadRequest(String m) { super(m); } }
    static JsonObject check(JsonObject q, String name) throws BadRequest {
        String want = "amber-ul/" + name + "@1";
        if (!q.has("schema")) throw new BadRequest("request has no schema (expected " + want + ")");
        if (!q.get("schema").getAsString().equals(want)) throw new BadRequest("unknown schema " + q.get("schema").getAsString() + " (expected " + want + ")");
        return q;
    }
    static String str(JsonObject q, String k) throws BadRequest { if (!q.has(k)) throw new BadRequest("missing field " + k); return q.get(k).getAsString(); }
    static Path path(JsonObject q, String k) throws BadRequest { return Path.of(str(q, k)); }
    static List<Path> paths(JsonObject q, String k) { List<Path> l = new ArrayList<>(); if (q.has(k)) for (JsonElement e : q.getAsJsonArray(k)) l.add(Path.of(e.getAsString())); return l; }

    static JsonObject result(String stage) { JsonObject r = new JsonObject(); r.addProperty("type", "result"); r.addProperty("schema", "amber-ul/" + stage + "-result@1"); return r; }
    static void emit(JsonObject o) { out.println(G.toJson(o)); }
    static void progress(String stage, String step, int done, int total, String msg) {
        JsonObject o = new JsonObject(); o.addProperty("type", "progress"); o.addProperty("stage", stage); o.addProperty("step", step);
        o.addProperty("done", done); o.addProperty("total", total); o.addProperty("message", msg); emit(o);
    }
    static void problem(String severity, String code, String modId, String file, JsonObject dep, String msg) {
        JsonObject o = new JsonObject(); o.addProperty("type", "problem"); o.addProperty("severity", severity); o.addProperty("code", code);
        o.addProperty("modId", modId); o.addProperty("file", file); if (dep != null) o.add("dependency", dep); o.addProperty("message", msg); emit(o);
    }
    static void problemOf(Resolver.Problem p) {
        JsonObject dep = null;
        if (p.depId() != null) { dep = new JsonObject(); dep.addProperty("id", p.depId()); dep.addProperty("range", p.range()); if (p.found() != null) dep.addProperty("found", p.found()); }
        problem(p.severity(), p.code(), p.modId(), p.file(), dep, p.message());
    }
    static void error(String code, String msg) { JsonObject o = new JsonObject(); o.addProperty("type", "error"); o.addProperty("code", code); o.addProperty("message", msg); emit(o); }
}
