package amber.ul.map;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * M1 check: build the NeoForge base jar with Amber's remapper and compare it with the installer's ART output.
 *   ArtCheck merged <mappings-merged.txt> <slim.jar> <oracle srg.jar> <out.jar> <report.json> [threads]
 *       names + parameter names straight from NeoForge's merged TSRG2 (tests the remapper alone)
 *   ArtCheck forge <mappingsDir 1.20.1> <client.jar> <oracle fart.jar> <out.jar> <report.json> [threads]
 *       Forge 1.20.1 runtime names from Amber's graph (forge column) vs ForgeAutoRenamingTool 1.0.2 with MERGE_MAPPING output
 *   ArtCheck tree <mappingsDir> <slim.jar> <oracle srg.jar> <out.jar> <report.json> [threads]
 *       names from Amber's own graph: Mojang's ProGuard file for classes/members, NeoForm (TSRG2) for parameters
 *       (tests graph + remapper; NeoForge's merge step is not used)
 * Output jars contain Mojang names: local only (under mc/).
 */
public final class ArtCheck {
    public static void main(String[] a) throws Exception {
        if (a.length < 6) { System.err.println("usage: merged|tree <src> <slim.jar> <oracle.jar> <out.jar> <report.json> [threads]"); System.exit(2); }
        String mode = a[0]; Path src = Path.of(a[1]), slim = Path.of(a[2]), oracle = Path.of(a[3]), out = Path.of(a[4]), report = Path.of(a[5]);
        int threads = a.length > 6 ? Integer.parseInt(a[6]) : Runtime.getRuntime().availableProcessors();
        long t0 = System.nanoTime();
        MappingTree t = new MappingTree(), ps = new MappingTree();
        int ns, pns;
        if (mode.equals("merged")) {
            try (BufferedReader r = Files.newBufferedReader(src, StandardCharsets.UTF_8)) { Readers.tsrg2(t, r, List.of("moj")); }
            try (BufferedReader r = Files.newBufferedReader(src, StandardCharsets.UTF_8)) { Readers.tsrg2(ps, r, List.of("moj")); }
            ns = t.nsIndex("moj"); pns = ps.nsIndex("moj");
        } else if (mode.equals("amap")) {
            ns = pns = 0;
        } else if (mode.equals("forge")) {   // Forge 1.17-1.20.x (M3): Amber's own graph, "forge" column (Mojmap classes + MCPConfig SRG members)
            Readers.proguard(t, src.resolve("mojang-client.txt"), "mojmap");
            Path mcp = Files.list(src).filter(p -> p.getFileName().toString().startsWith("mcp_config-")).findFirst().orElseThrow();
            Readers.tsrg2Zip(t, mcp, List.of("srg", "srgid"));
            ns = pns = 0;   // set after completion (the forge column is derived from the completed tree)
        } else {
            Readers.proguard(t, src.resolve("mojang-client.txt"), "mojmap");
            Path nf = Files.list(src).filter(p -> p.getFileName().toString().startsWith("neoform-")).findFirst().orElseThrow();
            Readers.tsrg2Zip(ps, nf, List.of("srg", "srgid"));
            ns = t.nsIndex("mojmap"); pns = ps.nsIndex("srg");
        }
        if (mode.equals("graph")) {   // one completed graph (Mojmap + NeoForm in the same tree) supplies names AND params: what an .amap holds
            Path nf = Files.list(src).filter(p -> p.getFileName().toString().startsWith("neoform-")).findFirst().orElseThrow();
            Readers.tsrg2Zip(t, nf, List.of("srg", "srgid"));
            ps = t; pns = t.nsIndex("srg");
        }
        long t1 = System.nanoTime();
        List<Path> libs = new ArrayList<>();   // -Damber.libsDir: library jars for the class hierarchy (Forge's rename step passes the game's libraries)
        String ld = System.getProperty("amber.libsDir");
        if (ld != null) try (var w = Files.walk(Path.of(ld))) { libs = w.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        JarIndex jar = JarIndex.of(slim, libs);
        Completer c = new Completer(t, jar); if (!mode.equals("amap")) c.run();
        if (mode.equals("forge")) { MapTool.deriveForgeRuntime(t); ns = t.nsIndex("forge"); ps = t; pns = t.nsIndex("srg"); }   // parameters: MCPConfig's p_NNN_ (MERGE_MAPPING carries the left side's)
        if (mode.equals("amap")) {   // names + params from a compiled .amap (src = the .amap file)
            t = Amap.load(src).toTree(); ns = t.nsIndex("mojmap"); ps = t; pns = t.nsIndex("srg");
        }
        RemapNames names = new RemapNames(t, ns, jar, ps, pns);
        long t2 = System.nanoTime();
        JarRemapper rm = new JarRemapper(names, JarRemapper.Options.art(), threads);
        rm.run(slim, out);
        long t3 = System.nanoTime();
        // AMBER_REPEAT=n: run the jar pass n more times in this JVM (warm JIT) and print each, for profiling only
        int rep = Integer.parseInt(System.getenv().getOrDefault("AMBER_REPEAT", "0"));
        for (int i = 0; i < rep; i++) { JarRemapper w = new JarRemapper(new RemapNames(t, ns, jar, ps, pns), JarRemapper.Options.art(), threads); w.run(slim, out); System.err.println("warm " + i + ": " + w.ms); }

        JsonObject r = new JsonObject();
        r.addProperty("mode", mode); r.addProperty("threads", threads);
        JsonObject ms = new JsonObject();
        ms.addProperty("readMappings", (t1 - t0) / 1_000_000); ms.addProperty("indexAndComplete", (t2 - t1) / 1_000_000);
        ms.addProperty("remap", (t3 - t2) / 1_000_000); rm.ms.forEach(ms::addProperty);
        ms.addProperty("total", (t3 - t0) / 1_000_000);
        r.add("ms", ms);
        r.addProperty("completionConflicts", c.conflicts.size());
        if (!oracle.toString().equals("-")) r.add("compare", compare(out, oracle));   // "-": timing run, no compare
        String js = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, js, StandardCharsets.UTF_8);
        System.out.println(js);
        jar.close();
    }

    static String sha(byte[] b) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }

    /** File-level sha + per-entry: missing/extra names, differing bytes (with a first-difference sample), metadata. */
    static JsonObject compare(Path mine, Path theirs) throws Exception {
        JsonObject o = new JsonObject();
        byte[] A = Files.readAllBytes(mine), B = Files.readAllBytes(theirs);
        o.addProperty("byteIdentical", Arrays.equals(A, B));
        o.addProperty("shaMine", sha(A)); o.addProperty("shaArt", sha(B));
        o.addProperty("sizeMine", A.length); o.addProperty("sizeArt", B.length);
        Map<String, byte[]> ma = entries(mine), mb = entries(theirs);
        List<String> order = new ArrayList<>(ma.keySet()), orderB = new ArrayList<>(mb.keySet());
        o.addProperty("entriesMine", ma.size()); o.addProperty("entriesArt", mb.size());
        o.addProperty("sameOrder", order.equals(orderB));
        List<String> missing = new ArrayList<>(), extra = new ArrayList<>(), differ = new ArrayList<>();
        for (String k : mb.keySet()) if (!ma.containsKey(k)) missing.add(k);
        for (String k : ma.keySet()) if (!mb.containsKey(k)) extra.add(k);
        for (String k : ma.keySet()) if (mb.containsKey(k) && !Arrays.equals(ma.get(k), mb.get(k))) differ.add(k);
        o.addProperty("missing", missing.size()); o.add("missingSamples", new Gson().toJsonTree(missing.stream().limit(15).toList()));
        o.addProperty("extra", extra.size()); o.add("extraSamples", new Gson().toJsonTree(extra.stream().limit(15).toList()));
        o.addProperty("differ", differ.size()); o.add("differSamples", new Gson().toJsonTree(differ.stream().limit(25).toList()));
        // metadata of the zip entries (time, method, extra) for the first few
        List<String> meta = new ArrayList<>();
        try (ZipFile za = new ZipFile(mine.toFile()); ZipFile zb = new ZipFile(theirs.toFile())) {
            for (String k : order) {
                ZipEntry x = za.getEntry(k), y = zb.getEntry(k); if (y == null) continue;
                if (x.getTime() != y.getTime() || x.getMethod() != y.getMethod() || x.getCompressedSize() != y.getCompressedSize()) {
                    if (meta.size() < 10) meta.add(k + " time " + x.getTime() + "/" + y.getTime() + " csize " + x.getCompressedSize() + "/" + y.getCompressedSize());
                }
            }
        }
        o.add("metaDiffSamples", new Gson().toJsonTree(meta));
        return o;
    }

    static Map<String, byte[]> entries(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(Files.newInputStream(p)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) m.put(e.getName(), z.readAllBytes());
        }
        return m;
    }
}
