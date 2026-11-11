package amber.ul.bake;

import amber.ul.map.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.Stream;

/**
 * M4: the "forge platform" layer (Forge 1.20.1), option A = Mojmap everywhere. Only active with --forge.
 *
 * Inputs (Forge's official installer processors, tools/forge_processors.py -> mc/forge/platform-cache/*.json):
 *   srg     = client.jar renamed by FART to Forge's runtime names ("forge": Mojmap classes, SRG members)
 *   patched = Forge's patched classes only (binarypatcher output), overlaid on srg
 *   extra   = client.jar resources; universal = Forge itself (net.minecraftforge.*, the "forge" mod)
 * Everything Forge-side is remapped forge -> Mojmap by Amber's engine (amber.ul.map, M4 step 2 / ForgeCheck): the game
 * once per Forge version, Forge itself and the Forge mods once per mod set (one class universe, so overrides across
 * mods resolve). Cached by content.
 */
public final class ForgePlatform {
    public final Path dir, srg, patched, extra, universal, mappingsDir, obfJar; public final String forge, mc, mcp;
    public final List<Path> libraries = new ArrayList<>();
    /** The PARENT jars (securejarhandler, JarJarFileSystems): system class path at launch; the bake-time coremod engine needs them too. */
    public final List<Path> parentJars = new ArrayList<>();
    /** SRG id -> Mojmap table written next to the game jar (bakeGame). */
    public Path srgTable;
    public static final String PIPELINE = "forge-m4-6";

    /** Not in the universal loader: Amber brings its own ASM/Mixin/logging and boots no modlauncher. */
    static final List<String> EXCLUDE = List.of("/org/ow2/asm/", "/org/spongepowered/mixin/", "/org/apache/logging/", "/org/slf4j/",
            "/cpw/mods/bootstraplauncher/", "/net/minecrell/terminalconsoleappender/", "/org/jline/", "/cpw/mods/securejarhandler/",
            "/net/minecraftforge/JarJarFileSystems/", "/net/minecraftforge/forge/", "/net/minecraft/client/");
    /** On the JVM class path when the Forge platform is active (FileSystemProviders are found by the system loader only). */
    public static final List<String> PARENT = List.of("cpw/mods/securejarhandler/", "net/minecraftforge/JarJarFileSystems/");

    /** Manifest the mapping tables are checked against (null: mappingsDir/../manifest.json, else the bundled requirements). */
    final Path manifestFile;
    public ForgePlatform(Path dir, Path mappingsDir, Path obfJar) throws IOException { this(dir, mappingsDir, obfJar, null); }
    public ForgePlatform(Path dir, Path mappingsDir, Path obfJar, Path manifest) throws IOException {
        this.dir = dir; this.mappingsDir = mappingsDir; this.obfJar = obfJar; this.manifestFile = manifest;
        Path marker;
        try (var s = Files.list(dir.resolve("platform-cache"))) { marker = s.filter(p -> p.getFileName().toString().contains("-client-") && p.toString().endsWith(".json")).findFirst()
                .orElseThrow(() -> new IOException("no Forge platform base in " + dir + " (run tools/forge_processors.py)")); }
        JsonObject m = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        srg = Path.of(m.get("srg").getAsString()); extra = Path.of(m.get("extra").getAsString()); patched = Path.of(m.get("patched").getAsString());
        JsonObject vj = JsonParser.parseString(Files.readString(dir.resolve("inst/version.json"))).getAsJsonObject();
        Map<String, String> ga = new HashMap<>();
        JsonArray game = vj.getAsJsonObject("arguments").getAsJsonArray("game");
        for (int i = 0; i + 1 < game.size(); i++) if (game.get(i).getAsString().startsWith("--")) ga.put(game.get(i).getAsString(), game.get(i + 1).getAsString());
        forge = ga.get("--fml.forgeVersion"); mc = ga.get("--fml.mcVersion"); mcp = ga.get("--fml.mcpVersion");
        universal = dir.resolve("libraries/net/minecraftforge/forge/" + mc + "-" + forge + "/forge-" + mc + "-" + forge + "-universal.jar");
        for (JsonElement e : vj.getAsJsonArray("libraries")) {
            String path = e.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            if (PARENT.stream().anyMatch(path::startsWith)) parentJars.add(dir.resolve("libraries").resolve(path));
            if (EXCLUDE.stream().anyMatch(x -> ("/" + path).contains(x))) continue;
            libraries.add(dir.resolve("libraries").resolve(path));
        }
        // FML's own jars are in the installer profile only (Forge's launcher finds them by ignoreList): fmlcore, javafml ...
        JsonObject prof = JsonParser.parseString(Files.readString(dir.resolve("inst/install_profile.json"))).getAsJsonObject();
        for (JsonElement e : prof.getAsJsonArray("libraries")) {
            String n = e.getAsJsonObject().get("name").getAsString();
            if (!n.matches("net[.]minecraftforge:(fmlcore|javafmllanguage|lowcodelanguage|mclanguage):.*")) continue;
            libraries.add(dir.resolve("libraries").resolve(e.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString()));
        }
        System.setProperty("amber.ul.forgeVersion", forge); System.setProperty("amber.ul.forgeMcVersion", mc);
    }

    String baseKey() throws IOException {
        StringBuilder b = new StringBuilder(PIPELINE + "|" + Baker.PIPELINE);
        for (Path p : List.of(srg, patched, extra)) b.append('|').append(Hash.file(p));
        try (Stream<Path> s = Files.list(mappingsDir)) { for (Path p : s.sorted().toList()) if (Files.isRegularFile(p)) b.append('|').append(p.getFileName()).append('=').append(Files.size(p)); }
        return Hash.str(b.toString()).substring(0, 16);
    }

    // ---- names (built only on a cache miss) ----
    private MappingTree tree; private JarIndex obf;
    public synchronized MappingTree tree(List<Path> libs) throws IOException {
        if (tree != null) return tree;
        long t0 = System.nanoTime();
        // official tables (Mojang + intermediary + MCPConfig), each checked against the manifest; graph via the tableKey cache
        Path mf = manifestFile != null ? manifestFile : mappingsDir.resolveSibling("manifest.json");
        MappingCache.Table tb = MappingCache.official(mc, mappingsDir, Files.isRegularFile(mf) ? MappingCache.manifest(mf) : MappingCache.bundledManifest(mc));
        if (tb.steps.stream().noneMatch(x -> x.kind().equals("tsrg2Zip"))) throw new IOException("no MCPConfig in " + mappingsDir);
        tb.options.put("forgeRuntime", "true");
        tb.game(obfJar, libs);
        MappingCache.Result r = MappingCache.get(MappingCache.root(obfJar), tb, null);
        MappingTree t = r.tree();
        obf = r.index() != null ? r.index() : JarIndex.of(obfJar, libs);
        System.out.printf("[amber-ul] Forge 映射图 %.1f s (%s %s)%n", (System.nanoTime() - t0) / 1e9, r.hit() ? "缓存" : "新建", r.key().substring(0, 16));
        return tree = t;
    }

    /** srg + patched classes + extra -> one Mojmap game jar. */
    public Path bakeGame(Path cacheRoot, List<Path> libs) throws Exception {
        Path out = cacheRoot.resolve("game-forge").resolve("forge-" + mc + "-" + forge + "-" + baseKey() + ".jar");
        srgTable = out.resolveSibling(out.getFileName() + ".srg.txt");
        Path[] at = CacheUse.mark(out, srgTable); out = at[0]; srgTable = at[1];
        if (Files.exists(out) && Files.exists(srgTable)) return out;
        long t = System.nanoTime();
        Files.createDirectories(out.getParent());
        MappingTree t0 = tree(libs);
        Map<String, JarRemapper.Entry> game = new LinkedHashMap<>();
        for (JarRemapper.Entry e : JarRemapper.read(srg)) if (!e.name().startsWith("META-INF/")) game.put(e.name(), e);
        int n = 0; for (JarRemapper.Entry e : JarRemapper.read(patched)) if (!e.name().startsWith("META-INF/")) { game.put(e.name(), e); n++; }
        for (JarRemapper.Entry e : JarRemapper.read(extra)) if (!e.name().startsWith("META-INF/") || e.name().equals("META-INF/MANIFEST.MF")) game.putIfAbsent(e.name(), e);
        ModNames names = new ModNames(t0, t0.nsIndex("forge"), t0.nsIndex("mojmap"), obf, libs, List.of(), true);
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        new JarRemapper(names, JarRemapper.Options.mod(), Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()))).run(new ArrayList<>(game.values()), tmp);
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        // SRG id -> Mojmap name for the runtime "srg" name function (ObfuscationReflectionHelper, coremods' ASMAPI)
        // sorted: the file's hash keys the coremod bake, and a graph mapped in from the cache iterates in obf order
        int f = t0.nsIndex("srg"), mo = t0.nsIndex("mojmap");
        TreeMap<String, String> srgNames = new TreeMap<>();
        for (MappingTree.ClassRow c : t0.classes.values()) for (var rows : List.of(c.methods.values(), c.fields.values())) for (MappingTree.MemberRow r : rows) {
            String a = r.get(f), b = r.get(mo);
            if (a != null && b != null && !a.equals(b) && a.matches("[mf]_[0-9]+_")) srgNames.putIfAbsent(a, b);
        }
        StringBuilder sb = new StringBuilder(); srgNames.forEach((a, b) -> sb.append(a).append(' ').append(b).append((char) 10));
        Files.writeString(srgTable, sb.toString());
        System.out.printf("[amber-ul] Forge 平台游戏 jar (Mojmap): 补丁类 %d (%.1f s)%n", n, (System.nanoTime() - t) / 1e9);
        return out;
    }

    /** Forge itself + Forge mods, forge -> Mojmap, one class universe. Returns input jar -> remapped jar. */
    public Map<Path, Path> bakeMods(Path cacheRoot, List<Path> jars, List<Path> libs) throws Exception {
        StringBuilder kb = new StringBuilder(baseKey());
        List<Path> sorted = new ArrayList<>(jars); sorted.sort(Comparator.comparing(Path::toString));
        for (Path j : sorted) kb.append('|').append(Hash.file(j));
        Path dirOut = CacheUse.mark(cacheRoot.resolve("forge-mods").resolve(Hash.str(kb.toString()).substring(0, 16)));   // directory: keeps all of it
        Map<Path, Path> res = new LinkedHashMap<>();
        boolean all = true;
        for (Path j : jars) { Path o = dirOut.resolve(Hash.file(j).substring(0, 16) + "-" + j.getFileName()); res.put(j, o); all &= Files.exists(o); }
        if (all) return res;
        long t = System.nanoTime();
        Files.createDirectories(dirOut);
        MappingTree t0 = tree(libs);
        Map<Path, List<JarRemapper.Entry>> in = new LinkedHashMap<>();
        for (Path j : jars) {
            List<JarRemapper.Entry> l = JarRemapper.read(j);
            List<String> px = Unshade.mixinExtrasPrefixes(l);
            if (!px.isEmpty()) { l = Unshade.mixinExtras(l, px); System.out.println("[amber-ul] " + j.getFileName() + ": 内嵌的重定位 MixinExtras " + px + " 改用运行时的那一份"); }
            in.put(j, l);
        }
        ModNames names = new ModNames(t0, t0.nsIndex("forge"), t0.nsIndex("mojmap"), obf, libs, new ArrayList<>(in.values()), true);
        MixinPass mx = new MixinPass(names);
        for (var l : in.values()) mx.analyze(l);
        TextNames text = names.text();
        StringPass sp = new StringPass(names);
        Map<String, Integer> stats = new java.util.concurrent.ConcurrentHashMap<>(); Map<String, List<String>> left = new java.util.concurrent.ConcurrentHashMap<>();
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
        for (var e : in.entrySet()) {
            Path o = res.get(e.getKey()), tmp = o.resolveSibling(o.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            ForgeCheck.remapMod(e.getKey().getFileName().toString(), e.getValue(), tmp, names, mx, text, sp, threads, stats, left);
            Files.move(tmp, o, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.printf("[amber-ul] Forge 模组重映射 %d 个 jar (%.1f s), 文本 %s, 字符串 %s%n", jars.size(), (System.nanoTime() - t) / 1e9, new TreeMap<>(stats), new TreeMap<>(sp.stats));
        return res;
    }
}
