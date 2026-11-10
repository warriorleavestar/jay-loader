package amber.ul.map;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

/**
 * Compiled mapping graphs on disk, keyed by tableKey (docs/mapping-brief.md §1 "Cache"):
 * <pre>
 *   &lt;root&gt;/&lt;mc&gt;/graph/&lt;tableKey&gt;.amap          the graph (Amap format)
 *   &lt;root&gt;/&lt;mc&gt;/graph/&lt;tableKey&gt;.report.json   what went in (every input with its sha1, url, licence), counts, timings, amap sha256
 * </pre>
 * tableKey = sha256 over a canonical text: key format, {@link #COMPOSER}, each reader step in order (kind, arguments, input
 * sha1), the official game jar's sha1 (or "none": reader-only graph, no completion), the library set used for
 * completion (file name + size, the same rule as the bake context), and the options. Anything that changes the graph
 * changes the key; a key never changes meaning (bump COMPOSER when Readers/Completer/deriveForgeRuntime change output).
 *
 * Sources stay where they were fetched (prototype: mc/mappings-cache/&lt;mc&gt;/ + manifest.json); {@link #official} picks
 * the official tables (Mojang's client mappings, Fabric intermediary, NeoForm or else Forge MCPConfig) and refuses any
 * file whose sha1 or size differs from the manifest. Community name sets (Yarn, Quilt, Parchment, hashed) are not inputs.
 * Every graph here carries Mojang names: local only, never shipped (report "localOnly": true).
 */
public final class MappingCache {
    /** Bump when the composed graph for the same inputs would differ. */
    public static final String COMPOSER = "amber-map-1";
    static final String KEY_FORMAT = "amber-tablekey/1";

    /** One reader call: kind = proguard | tinyJar | tiny | tsrg2Zip, args = its namespace arguments. */
    public record Step(String kind, String args, Path file, String sha1, String url, String licence) {}

    public static final class Table {
        public final String mc; public final List<Step> steps = new ArrayList<>();
        public Path game; public String gameSha1 = "none"; public List<Path> libs = List.of();
        public final TreeMap<String, String> options = new TreeMap<>();
        public Table(String mc) { this.mc = mc; }
        public Table step(String kind, String args, Path file, String url, String licence) throws IOException {
            steps.add(new Step(kind, args, file, sha1(file), url, licence)); return this;
        }
        /** Completion against the official jar (+ libraries for supertypes). */
        public Table game(Path jar, List<Path> libs) throws IOException { game = jar; gameSha1 = sha1(jar); this.libs = libs; return this; }
        public String keyText() throws IOException {
            StringBuilder b = new StringBuilder(KEY_FORMAT).append("\ncomposer=").append(COMPOSER).append("\nmc=").append(mc);
            for (Step s : steps) b.append("\nstep=").append(s.kind).append(' ').append(s.args).append(' ').append(s.sha1);
            b.append("\ngame=").append(gameSha1);
            if (game != null) { List<String> l = new ArrayList<>(); for (Path p : libs) l.add(p.getFileName() + "=" + Files.size(p)); Collections.sort(l); b.append("\nlibs=").append(String.join(",", l)); }
            options.forEach((k, v) -> b.append("\noption=").append(k).append('=').append(v));
            return b.toString();
        }
        public String key() throws IOException { return sha256(keyText().getBytes(StandardCharsets.UTF_8)); }
    }

    public record Result(MappingTree tree, String key, boolean hit, Path amap, double ms, JarIndex index) {}

    /**
     * The graph for {@code t}: mapped in from the cache when present and intact, else composed, written and reported.
     * {@code index}: an already built JarIndex of t.game + t.libs to reuse (null = build one when completing).
     */
    public static Result get(Path root, Table t, JarIndex index) throws IOException {
        long t0 = System.nanoTime();
        String key = t.key();
        Path dir = root.resolve(t.mc).resolve("graph"), amap = dir.resolve(key + ".amap"), rep = dir.resolve(key + ".report.json");
        amber.ul.bake.CacheUse.mark(amap); amber.ul.bake.CacheUse.mark(rep);
        if (Files.isRegularFile(amap) && Files.isRegularFile(rep)) {
            try {
                JsonObject r = JsonParser.parseString(Files.readString(rep, StandardCharsets.UTF_8)).getAsJsonObject();
                if (key.equals(r.get("tableKey").getAsString()) && r.get("amapSha256").getAsString().equals(sha256(amap))) {
                    MappingTree tree = Amap.load(amap).toTree();
                    return new Result(tree, key, true, amap, (System.nanoTime() - t0) / 1e6, index);
                }
                System.out.println("[amber-ul] 映射图缓存损坏或不符，重建: " + amap);
            } catch (RuntimeException | IOException e) { System.out.println("[amber-ul] 映射图缓存不可读，重建: " + amap + " (" + e + ")"); }
        }
        MappingTree tree = new MappingTree();
        JsonObject ms = new JsonObject();
        for (Step s : t.steps) {
            long a = System.nanoTime();
            switch (s.kind) {
                case "proguard" -> Readers.proguard(tree, s.file, s.args);
                case "tinyJar" -> Readers.tinyJar(tree, s.file, nsMap(s.args));
                case "tiny" -> { try (BufferedReader r = Files.newBufferedReader(s.file, StandardCharsets.UTF_8)) { Readers.tiny(tree, r, nsMap(s.args)); } }
                case "tsrg2Zip" -> Readers.tsrg2Zip(tree, s.file, List.of(s.args.split(",")));
                default -> throw new IllegalArgumentException("unknown step " + s.kind);
            }
            ms.addProperty(s.kind + " " + s.args, Math.round((System.nanoTime() - a) / 1e6));
        }
        Completer c = null;
        if (t.game != null) {
            long a = System.nanoTime();
            if (index == null) index = JarIndex.of(t.game, t.libs);
            c = new Completer(tree, index); c.run();
            ms.addProperty("complete", Math.round((System.nanoTime() - a) / 1e6));
        }
        if ("true".equals(t.options.get("forgeRuntime"))) MapTool.deriveForgeRuntime(tree);
        Files.createDirectories(dir);
        long w = System.nanoTime();
        Amap.write(tree, amap);
        ms.addProperty("writeAmap", Math.round((System.nanoTime() - w) / 1e6));
        JsonObject r = new JsonObject();
        r.addProperty("tableKey", key); r.addProperty("keyText", t.keyText());
        r.addProperty("mc", t.mc); r.addProperty("composer", COMPOSER);
        r.addProperty("completed", t.game != null);
        r.addProperty("localOnly", true);   // the Mojang column; see class doc
        JsonArray in = new JsonArray();
        for (Step s : t.steps) { JsonObject o = new JsonObject(); o.addProperty("kind", s.kind); o.addProperty("args", s.args); o.addProperty("file", s.file.getFileName().toString());
            o.addProperty("sha1", s.sha1); if (s.url != null) o.addProperty("url", s.url); if (s.licence != null) o.addProperty("licence", s.licence); in.add(o); }
        r.add("inputs", in);
        if (t.game != null) { r.addProperty("game", t.game.getFileName().toString()); r.addProperty("gameSha1", t.gameSha1); r.addProperty("libs", t.libs.size()); }
        r.add("namespaces", new Gson().toJsonTree(tree.namespaces));
        r.addProperty("classes", tree.classCount()); r.addProperty("methods", tree.memberCount(true)); r.addProperty("fields", tree.memberCount(false));
        r.add("readerDropped", new Gson().toJsonTree(tree.dropped));
        if (c != null) { r.add("completion", new Gson().toJsonTree(c.stats)); r.addProperty("conflicts", c.conflicts.size()); }
        r.add("ms", ms);
        r.addProperty("amapBytes", Files.size(amap)); r.addProperty("amapSha256", sha256(amap));
        Path tmp = rep.resolveSibling(rep.getFileName() + ".tmp" + ProcessHandle.current().pid());
        Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r), StandardCharsets.UTF_8);
        Files.move(tmp, rep, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return new Result(tree, key, false, amap, (System.nanoTime() - t0) / 1e6, index);
    }

    /**
     * The cache root for an instance: -Damber.ul.mappingCache, else &lt;mc&gt;/cache/mappings next to versions/ of the
     * official jar (versions/&lt;v&gt;/&lt;v&gt;.jar). Shared by every bake cache of that game directory.
     */
    public static Path root(Path officialJar) {
        String p = System.getProperty("amber.ul.mappingCache");
        if (p != null) return Path.of(p);
        Path v = officialJar.toAbsolutePath().getParent();
        return v.getParent().getParent().resolve("cache").resolve("mappings");
    }

    static Map<String, String> nsMap(String args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : args.split(",")) { String[] p = kv.split("=", 2); m.put(p[0], p[1]); }
        return m;
    }

    // ---- official sources ----------------------------------------------------------------------------------------------

    /**
     * The official tables of one version from {@code sourcesDir} (= &lt;cache&gt;/&lt;mc&gt;), each checked against
     * {@code manifest} (sha1 + size; a file the manifest does not list is refused). Order = column order of the graph:
     * mojmap, intermediary, srg (+ srgid). NeoForm when present, else Forge's MCPConfig (1.20.1 and older).
     */
    public static Table official(String mc, Path sourcesDir, JsonObject manifest) throws IOException {
        Table t = new Table(mc);
        Path moj = sourcesDir.resolve("mojang-client.txt");   // prototype cache name; Amber's requirements name it client_mappings.txt
        if (!Files.exists(moj) && Files.exists(sourcesDir.resolve("client_mappings.txt"))) moj = sourcesDir.resolve("client_mappings.txt");
        t.step("proguard", "mojmap", verified(manifest, mc, moj), url(manifest, mc, moj), licence(manifest, mc, moj));
        Path inter = first(sourcesDir, "intermediary-");
        if (inter != null) t.step("tinyJar", "official=obf", verified(manifest, mc, inter), url(manifest, mc, inter), licence(manifest, mc, inter));
        Path srg = first(sourcesDir, "neoform-");
        if (srg == null) srg = first(sourcesDir, "mcp_config-");
        if (srg != null) t.step("tsrg2Zip", "srg,srgid", verified(manifest, mc, srg), url(manifest, mc, srg), licence(manifest, mc, srg));
        return t;
    }

    static JsonObject entry(JsonObject manifest, String mc, Path f) throws IOException {
        JsonObject files = manifest.getAsJsonObject("files");
        JsonElement e = files == null ? null : files.get(mc + "/" + f.getFileName());
        if (e == null) throw new IOException("映射源不在清单里，拒绝使用: " + mc + "/" + f.getFileName());
        return e.getAsJsonObject();
    }
    static Path verified(JsonObject manifest, String mc, Path f) throws IOException {
        JsonObject e = entry(manifest, mc, f);
        if (!Files.isRegularFile(f)) throw new IOException("缺少映射源: " + f);
        long size = Files.size(f);
        if (e.has("size") && e.get("size").getAsLong() != size) throw new IOException("映射源大小不符: " + f + " " + size + " != " + e.get("size"));
        String sha = sha1(f);
        if (!e.get("sha1").getAsString().equalsIgnoreCase(sha)) throw new IOException("映射源 sha1 不符: " + f + " " + sha + " != " + e.get("sha1").getAsString());
        return f;
    }
    static String url(JsonObject manifest, String mc, Path f) throws IOException { JsonObject e = entry(manifest, mc, f); return e.has("url") ? e.get("url").getAsString() : null; }
    static String licence(JsonObject manifest, String mc, Path f) throws IOException { JsonObject e = entry(manifest, mc, f); return e.has("licence") ? e.get("licence").getAsString() : null; }
    static Path first(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) { return s.filter(p -> p.getFileName().toString().startsWith(prefix)).sorted().findFirst().orElse(null); }
    }
    /**
     * The loader's own requirements table for {@code mc} (/amber-ul/requirements-&lt;mc&gt;.json) as a manifest: every
     * "mappings" entry under &lt;mc&gt;/&lt;file name&gt; with its sha1, size and url. Used when the caller gives no manifest.
     */
    public static JsonObject bundledManifest(String mc) throws IOException {
        try (InputStream in = MappingCache.class.getResourceAsStream("/amber-ul/requirements-" + mc + ".json")) {
            if (in == null) throw new IOException("no bundled requirements for Minecraft " + mc);
            JsonObject t = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(), files = new JsonObject();
            for (JsonElement e : t.getAsJsonArray("files")) {
                JsonObject f = e.getAsJsonObject();
                if (!"mappings".equals(f.get("kind").getAsString())) continue;
                String path = f.get("path").getAsString();
                JsonObject o = new JsonObject(); o.addProperty("sha1", f.get("sha1").getAsString()); o.addProperty("size", f.get("size").getAsLong()); o.addProperty("url", f.get("url").getAsString());
                if (f.has("licence")) o.addProperty("licence", f.get("licence").getAsString());
                files.add(mc + "/" + path.substring(path.lastIndexOf('/') + 1), o);
            }
            JsonObject m = new JsonObject(); m.add("files", files); return m;
        }
    }
    public static JsonObject manifest(Path f) throws IOException { return JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject(); }

    // ---- hashing (memoised per path + size + mtime: the key needs input hashes on every launch) --------------------------

    private static final Map<String, String> memo = new java.util.concurrent.ConcurrentHashMap<>();
    static String digest(String alg, Path p) throws IOException {
        String k = alg + "|" + p.toAbsolutePath() + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        String v = memo.get(k); if (v != null) return v;
        try {
            MessageDigest md = MessageDigest.getInstance(alg);
            try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
            v = HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        memo.put(k, v); return v;
    }
    public static String sha1(Path p) throws IOException { return digest("SHA-1", p); }
    static String sha256(Path p) throws IOException {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
            return HexFormat.of().formatHex(md.digest()); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String sha256(byte[] x) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
