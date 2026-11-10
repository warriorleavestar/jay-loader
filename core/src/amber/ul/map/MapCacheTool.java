package amber.ul.map;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Builds the official mapping graph of every version under a sources root into the tableKey cache and checks it.
 *   java ... amber.ul.map.MapCacheTool &lt;sourcesRoot&gt; &lt;manifest.json&gt; &lt;cacheRoot&gt; &lt;mcRoot&gt; [versions...]
 * Per version: every input verified against the manifest (sha1 + size); completed against mcRoot/versions/&lt;v&gt;/&lt;v&gt;.jar
 * and its libraries when that jar is present (its sha1 must match the version json), else a reader-only graph.
 * Then the graph is fetched a second time (must be a cache hit) and compared cell by cell with the one just composed.
 */
public final class MapCacheTool {
    public static void main(String[] a) throws Exception {
        if (a.length < 4) { System.err.println("usage: <sourcesRoot> <manifest.json> <cacheRoot> <mcRoot> [versions...]"); System.exit(2); }
        Path src = Path.of(a[0]), cache = Path.of(a[2]), mcRoot = Path.of(a[3]);
        JsonObject manifest = MappingCache.manifest(Path.of(a[1]));
        List<String> versions = new ArrayList<>();
        if (a.length > 4) versions.addAll(Arrays.asList(a).subList(4, a.length));
        else try (var s = Files.list(src)) { s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted(MapCacheTool::cmpVersion).forEach(versions::add); }
        JsonArray out = new JsonArray(); int bad = 0;
        for (String v : versions) {
            JsonObject o = new JsonObject(); o.addProperty("mc", v);
            try {
                MappingCache.Table t = MappingCache.official(v, src.resolve(v), manifest);
                if (t.steps.stream().anyMatch(s -> s.file().getFileName().toString().startsWith("mcp_config-"))) t.options.put("forgeRuntime", "true");
                Path jar = mcRoot.resolve("versions").resolve(v).resolve(v + ".jar"), vj = mcRoot.resolve("versions").resolve(v).resolve(v + ".json");
                if (Files.isRegularFile(jar) && Files.isRegularFile(vj)) {
                    JsonObject j = JsonParser.parseString(Files.readString(vj, StandardCharsets.UTF_8)).getAsJsonObject();
                    String want = j.getAsJsonObject("downloads").getAsJsonObject("client").get("sha1").getAsString();
                    if (!want.equalsIgnoreCase(MappingCache.sha1(jar))) throw new IllegalStateException("client jar sha1 does not match " + vj.getFileName());
                    t.game(jar, libs(mcRoot, v, j));
                }
                MappingCache.Result r1 = MappingCache.get(cache, t, null);
                MappingCache.Result r2 = MappingCache.get(cache, t, r1.index());
                int diff = MapTool.sameCells(r1.tree(), r2.tree());
                o.addProperty("completed", t.game != null); o.addProperty("libs", t.libs.size());
                o.addProperty("inputs", String.join(" + ", t.steps.stream().map(s -> s.file().getFileName().toString()).toList()));
                o.addProperty("namespaces", String.join(",", r1.tree().namespaces));
                o.addProperty("classes", r1.tree().classCount()); o.addProperty("methods", r1.tree().memberCount(true)); o.addProperty("fields", r1.tree().memberCount(false));
                o.addProperty("firstHit", r1.hit()); o.addProperty("firstMs", Math.round(r1.ms()));
                o.addProperty("secondHit", r2.hit()); o.addProperty("secondMs", Math.round(r2.ms()));
                o.addProperty("cellsDiffer", diff); o.addProperty("tableKey", r1.key().substring(0, 16));
                o.addProperty("amapBytes", Files.size(r1.amap()));
                if (!r2.hit() || diff != 0) bad++;
                if (r1.index() != null) r1.index().close();
            } catch (Exception e) { o.addProperty("error", e.toString()); bad++; }
            System.out.println(o); out.add(o);
        }
        Files.createDirectories(cache);
        Files.writeString(cache.resolve("build-summary.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out), StandardCharsets.UTF_8);
        System.out.println("RESULT mapcache " + (bad == 0 ? "PASS" : "FAIL") + ": " + (versions.size() - bad) + "/" + versions.size());
        System.exit(bad == 0 ? 0 : 1);
    }

    /** The version json's libraries that are on disk, from mcRoot/libraries-&lt;v&gt; when it exists, else mcRoot/libraries. */
    static List<Path> libs(Path mcRoot, String v, JsonObject j) {
        Path base = Files.isDirectory(mcRoot.resolve("libraries-" + v)) ? mcRoot.resolve("libraries-" + v) : mcRoot.resolve("libraries");
        List<Path> l = new ArrayList<>();
        for (JsonElement e : j.getAsJsonArray("libraries")) {
            JsonObject d = e.getAsJsonObject().getAsJsonObject("downloads");
            if (d == null || !d.has("artifact")) continue;
            Path p = base.resolve(d.getAsJsonObject("artifact").get("path").getAsString());
            if (Files.isRegularFile(p)) l.add(p);
        }
        return l;
    }

    static int cmpVersion(String x, String y) {
        String[] p = x.split("\\."), q = y.split("\\.");
        for (int i = 0; i < Math.max(p.length, q.length); i++) {
            int c = Integer.compare(i < p.length ? num(p[i]) : 0, i < q.length ? num(q[i]) : 0);
            if (c != 0) return c;
        }
        return x.compareTo(y);
    }
    static int num(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; } }
}
