package amber.ul.map;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * M1 driver: build the mapping graph for one version from mc/mappings-cache and check it (brief §7 1-4).
 *   java ... amber.ul.map.MapTool build <cacheDir/<mc>> <obf client.jar> <libsDir> <report.json> [groundTruthJar]
 * groundTruthJar = the installer-built NeoForge base (ART output, Mojmap names): every Mojmap class/member must exist there.
 * Output contains Mojang names -> local only (written under mc/, git-ignored).
 */
public final class MapTool {
    public static void main(String[] a) throws Exception {
        if (a.length < 5 || !a[0].equals("build")) { System.err.println("usage: build <mappingsDir> <obfJar> <libsDir> <report.json> [groundTruthJar]"); System.exit(2); }
        Path dir = Path.of(a[1]), obfJar = Path.of(a[2]), libsDir = Path.of(a[3]), report = Path.of(a[4]);
        long t0 = System.nanoTime();
        MappingTree t = new MappingTree();
        Readers.proguard(t, dir.resolve("mojang-client.txt"), "mojmap");
        long t1 = System.nanoTime();
        Path inter = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("intermediary-")).findFirst().orElseThrow();
        Readers.tinyJar(t, inter, Map.of("official", "obf"));
        long t2 = System.nanoTime();
        Path nf = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("neoform-")).findFirst().orElse(null);
        if (nf != null) Readers.tsrg2Zip(t, nf, List.of("srg", "srgid"));
        else {   // before NeoForge (1.20.1 and older): Forge's MCPConfig, same TSRG2 layout (classes are C_nnn_ there)
            Path mcp = Files.list(dir).filter(p -> p.getFileName().toString().startsWith("mcp_config-")).findFirst().orElse(null);
            if (mcp != null) Readers.tsrg2Zip(t, mcp, List.of("srg", "srgid"));
        }
        long t3 = System.nanoTime();
        List<Path> libs;
        try (Stream<Path> s = Files.walk(libsDir)) { libs = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        JarIndex jar = JarIndex.of(obfJar, libs);
        long t4 = System.nanoTime();
        Completer c = new Completer(t, jar); c.run();
        if (nf == null && t.namespaces.contains("srg")) deriveForgeRuntime(t);
        long t5 = System.nanoTime();

        JsonObject r = new JsonObject();
        r.addProperty("obfJar", obfJar.getFileName().toString());
        r.add("namespaces", new Gson().toJsonTree(t.namespaces));
        JsonObject ms = new JsonObject();
        ms.addProperty("readMojmap", ms(t0, t1)); ms.addProperty("readIntermediary", ms(t1, t2)); ms.addProperty("readNeoForm", ms(t2, t3));
        ms.addProperty("indexJar", ms(t3, t4)); ms.addProperty("complete", ms(t4, t5));
        r.add("ms", ms);
        r.add("readerDropped", new Gson().toJsonTree(t.dropped));
        r.add("completion", new Gson().toJsonTree(c.stats));
        r.addProperty("conflicts", c.conflicts.size());
        r.add("conflictSamples", new Gson().toJsonTree(c.conflicts.stream().limit(20).toList()));
        // coverage over game entities (excluding identity cases)
        JsonObject cov = new JsonObject();
        for (int ns = 1; ns < t.namespaces.size(); ns++) {
            if (t.namespaces.get(ns).equals("srgid")) continue;
            int cls = 0, clsN = 0, m = 0, mN = 0, f = 0, fN = 0;
            Map<String, Integer> holesByPkg = new TreeMap<>();
            for (MappingTree.ClassRow cr : t.classes.values()) {
                JarIndex.Info ji = jar.classes.get(cr.obf); if (ji == null || !ji.inGame) continue;
                clsN++; if (cr.get(ns) != null) cls++;
                for (MappingTree.MemberRow mr : cr.methods.values()) { mN++; if (mr.get(ns) != null) m++; else holesByPkg.merge(pkg(cr.get(1) != null ? cr.get(1) : cr.obf), 1, Integer::sum); }
                for (MappingTree.MemberRow fr : cr.fields.values()) { fN++; if (fr.get(ns) != null) f++; }
            }
            JsonObject o = new JsonObject();
            o.addProperty("classes", cls + "/" + clsN); o.addProperty("methods", m + "/" + mN); o.addProperty("fields", f + "/" + fN);
            o.addProperty("methodPct", Math.round(10000.0 * m / Math.max(1, mN)) / 100.0);
            o.add("methodHolesByPackageTop", new Gson().toJsonTree(holesByPkg.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(8).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x, LinkedHashMap::new))));
            o.add("injectivity", injectivity(t, ns));
            List<String> holeSamples = new ArrayList<>();
            for (MappingTree.ClassRow cr : t.classes.values()) for (MappingTree.MemberRow mr : cr.methods.values())
                if (mr.get(ns) == null && holeSamples.size() < 12) holeSamples.add(cr.obf + "(" + cr.get(1) + ")." + mr.obf + mr.desc + " moj=" + mr.get(1));
            o.add("methodHoleSamples", new Gson().toJsonTree(holeSamples));
            int srgDiff = 0;
            if (t.namespaces.get(ns).equals("srg")) for (MappingTree.ClassRow cr : t.classes.values()) for (MappingTree.MemberRow mr : cr.methods.values()) if (mr.get(ns) != null && !mr.get(ns).equals(mr.obf)) srgDiff++;
            if (srgDiff > 0) o.addProperty("methodsWhereSrgDiffersFromObf", srgDiff);
            cov.add(t.namespaces.get(ns), o);
        }
        r.add("coverage", cov);
        if (a.length > 5) r.add("groundTruth", groundTruth(t, t.nsIndex("mojmap"), Path.of(a[5])));
        r.add("roundTrip", roundTrip(t, c.attributes));
        String om = System.getProperty("amber.oracleMerged");   // installertools MERGE_MAPPING output (TSRG2 obf -> forge)
        if (om != null && t.namespaces.contains("forge")) r.add("oracleMerged", oracleMerged(t, Path.of(om)));
        // .amap: write, map back in, compare every cell with the tree
        Path amap = report.resolveSibling(report.getFileName().toString().replace("-report.json", "") + ".amap");
        long w0 = System.nanoTime(); Amap.write(t, amap); long w1 = System.nanoTime();
        Amap am = Amap.load(amap); long w2 = System.nanoTime();
        MappingTree back = am.toTree(); long w3 = System.nanoTime();
        JsonObject ao = new JsonObject();
        ao.addProperty("file", amap.getFileName().toString()); ao.addProperty("bytes", Files.size(amap));
        ao.addProperty("writeMs", ms(w0, w1)); ao.addProperty("mapMs", ms(w1, w2)); ao.addProperty("toTreeMs", ms(w2, w3));
        ao.addProperty("cellsDiffer", sameCells(t, back));
        long l0 = System.nanoTime(); int hits = 0;
        for (MappingTree.ClassRow cr : t.classes.values()) if (am.findClass(cr.obf) >= 0) hits++;
        int mojNs = t.nsIndex("mojmap"), hitsMoj = 0;
        for (MappingTree.ClassRow cr : t.classes.values()) if (cr.get(mojNs) != null && am.findClass(mojNs, cr.get(mojNs)) >= 0) hitsMoj++;
        long l1 = System.nanoTime();
        ao.addProperty("lookups", hits + " obf + " + hitsMoj + " mojmap class lookups in " + ms(l0, l1) + " ms (binary search on the mapped file)");
        r.add("amap", ao);
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(report, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r), StandardCharsets.UTF_8);
        System.out.println(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r));
        jar.close();
    }
    /**
     * Forge 1.17-1.20.x runtime names ("forge"): Mojmap class names, SRG member names. This is what Forge's installer
     * builds with installertools MERGE_MAPPING (left = MCPConfig joined.tsrg, right = Mojang's client mappings,
     * --classes): a hole in either column stays a hole.
     */
    public static void deriveForgeRuntime(MappingTree t) {
        int moj = t.nsIndex("mojmap"), srg = t.nsIndex("srg"), f = t.ns("forge");
        for (MappingTree.ClassRow c : t.classes.values()) {
            c.set(f, c.get(moj));
            for (MappingTree.MemberRow m : c.methods.values()) m.set(f, m.get(srg));
            for (MappingTree.MemberRow m : c.fields.values()) m.set(f, m.get(srg));
        }
    }
    /**
     * Cell by cell: the oracle's names for every row it lists vs Amber's "forge" column (before completion fills holes
     * the oracle doesn't have, so only rows present in the oracle are compared; rows only in Amber are counted).
     */
    static JsonObject oracleMerged(MappingTree t, Path file) throws java.io.IOException {
        MappingTree o = new MappingTree();
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { Readers.tsrg2(o, r, oracleNames(file)); }
        int on = o.nsIndex("forge"), an = t.nsIndex("forge");
        long same = 0, differ = 0, missingInAmber = 0, holeInAmber = 0, fieldDescUnknown = 0; List<String> samples = new ArrayList<>();
        long staticSame = 0, staticDiffer = 0, paramsSame = 0, paramsDiffer = 0, paramsOnlyAmber = 0; int srgNs = t.nsIndex("srg");
        for (MappingTree.ClassRow oc : o.classes.values()) {
            MappingTree.ClassRow ac = t.classes.get(oc.obf);
            if (ac == null) { missingInAmber++; if (samples.size() < 15) samples.add("class missing " + oc.obf); continue; }
            if (Objects.equals(oc.get(on), ac.get(an))) same++; else { differ++; if (samples.size() < 15) samples.add("class " + oc.obf + ": oracle " + oc.get(on) + " amber " + ac.get(an)); }
            for (MappingTree.MemberRow om : oc.methods.values()) {
                MappingTree.MemberRow am = ac.methods.get(om.obf + om.desc);
                if (am == null) { missingInAmber++; if (samples.size() < 15) samples.add("method missing " + oc.obf + "." + om.obf + om.desc); continue; }
                if (am.get(an) == null) holeInAmber++;
                if (Objects.equals(om.get(on), am.get(an))) same++; else { differ++; if (samples.size() < 15) samples.add("method " + oc.obf + "." + om.obf + om.desc + ": oracle " + om.get(on) + " amber " + am.get(an)); }
                if (om.isStatic == am.isStatic) staticSame++; else { staticDiffer++; if (samples.size() < 15) samples.add("static flag " + oc.obf + "." + om.obf + om.desc + ": oracle " + om.isStatic + " amber " + am.isStatic); }
                // parameters: MERGE_MAPPING carries the left side's (MCPConfig p_NNN_); Amber's are the srg column's
                if (om.params != null) for (var pe : om.params.entrySet()) {
                    MappingTree.Row ap = am.params == null ? null : am.params.get(pe.getKey());
                    String want = pe.getValue().get(on), got = ap == null ? null : ap.get(srgNs);
                    if (Objects.equals(want, got)) paramsSame++; else { paramsDiffer++; if (samples.size() < 15) samples.add("param " + oc.obf + "." + om.obf + om.desc + " #" + pe.getKey() + ": oracle " + want + " amber " + got); }
                }
                if (am.params != null) for (var pe : am.params.entrySet()) if (pe.getValue().get(srgNs) != null && (om.params == null || !om.params.containsKey(pe.getKey()))) paramsOnlyAmber++;
            }
            for (MappingTree.MemberRow of : oc.fields.values()) {
                // TSRG2 fields carry no desc: match by obf name (unique per class in obf jars)
                MappingTree.MemberRow af = null; int hits = 0;
                for (MappingTree.MemberRow x : ac.fields.values()) if (x.obf.equals(of.obf)) { af = x; hits++; }
                if (hits != 1) { if (hits == 0) missingInAmber++; else fieldDescUnknown++; continue; }
                if (Objects.equals(of.get(on), af.get(an))) same++; else { differ++; if (samples.size() < 15) samples.add("field " + oc.obf + "." + of.obf + ": oracle " + of.get(on) + " amber " + af.get(an)); }
            }
        }
        JsonObject j = new JsonObject();
        j.addProperty("file", file.getFileName().toString());
        j.addProperty("same", same); j.addProperty("differ", differ); j.addProperty("missingInAmber", missingInAmber);
        j.addProperty("amberHoles", holeInAmber); j.addProperty("fieldsAmbiguousByName", fieldDescUnknown);
        j.addProperty("staticSame", staticSame); j.addProperty("staticDiffer", staticDiffer);
        j.addProperty("paramsSame", paramsSame); j.addProperty("paramsDiffer", paramsDiffer); j.addProperty("paramsOnlyAmber", paramsOnlyAmber);
        j.add("samples", new Gson().toJsonTree(samples));
        return j;
    }
    /** The merged file's header names its columns; the first non-obf one is Forge's runtime namespace. */
    static List<String> oracleNames(Path file) throws java.io.IOException {
        String h; try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { h = r.readLine(); }
        String[] p = h.split(" ");
        List<String> names = new ArrayList<>();
        for (int i = 2; i < p.length; i++) names.add(i == 2 ? "forge" : "oracle" + i);
        return names;
    }
    static long ms(long a, long b) { return (b - a) / 1_000_000; }

    /** Number of cells (names, descs, static flags, params) where two trees disagree; 0 = identical. */
    public static int sameCells(MappingTree a, MappingTree b) {
        int d = 0, n = a.namespaces.size();
        if (!a.namespaces.equals(b.namespaces)) return -1;
        if (a.classes.size() != b.classes.size()) d++;
        for (MappingTree.ClassRow x : a.classes.values()) {
            MappingTree.ClassRow y = b.classes.get(x.obf); if (y == null) { d++; continue; }
            for (int i = 1; i < n; i++) if (!Objects.equals(x.get(i), y.get(i))) d++;
            if (x.methods.size() != y.methods.size() || x.fields.size() != y.fields.size()) d++;
            for (var e : x.methods.entrySet()) {
                MappingTree.MemberRow p = e.getValue(), q = y.methods.get(e.getKey()); if (q == null) { d++; continue; }
                if (p.isStatic != q.isStatic || !Objects.equals(p.desc, q.desc)) d++;
                for (int i = 1; i < n; i++) if (!Objects.equals(p.get(i), q.get(i))) d++;
                int pc = p.params == null ? 0 : p.params.size(), qc = q.params == null ? 0 : q.params.size();
                if (pc != qc) d++;
                else if (pc > 0) for (var pe : p.params.entrySet()) { MappingTree.Row qr = q.params.get(pe.getKey()); if (qr == null) { d++; continue; } for (int i = 1; i < n; i++) if (!Objects.equals(pe.getValue().get(i), qr.get(i))) d++; }
            }
            for (var e : x.fields.entrySet()) {
                MappingTree.MemberRow p = e.getValue(), q = y.fields.get(e.getKey()); if (q == null) { d++; continue; }
                if (p.isStatic != q.isStatic || !Objects.equals(p.desc, q.desc)) d++;
                for (int i = 1; i < n; i++) if (!Objects.equals(p.get(i), q.get(i))) d++;
            }
        }
        return d;
    }

    /**
     * Brief §7.3: for every pair of name namespaces A, B: look each entity up by its A name (owner + name + desc, all in
     * A) and by its B name; both lookups must land on the same row (so A->B->A is the identity and obf->A->B equals
     * obf->B). Holes in A or B are counted, not failed. Descriptors keep obf names where a namespace has a hole.
     */
    static JsonObject roundTrip(MappingTree t, Set<String> attributes) {
        List<Integer> nss = new ArrayList<>();
        for (int i = 0; i < t.namespaces.size(); i++) if (!attributes.contains(t.namespaces.get(i))) nss.add(i);
        Map<Integer, Map<String, MappingTree.ClassRow>> clsBy = new HashMap<>();
        Map<Integer, Map<String, MappingTree.MemberRow>> memBy = new HashMap<>();
        Map<Integer, Integer> dupKeys = new HashMap<>();
        for (int ns : nss) {
            Map<String, MappingTree.ClassRow> cm = new HashMap<>(); Map<String, MappingTree.MemberRow> mm = new HashMap<>(); int dup = 0;
            for (MappingTree.ClassRow c : t.classes.values()) {
                String cn = c.get(ns); if (cn == null) continue;
                if (cm.put(cn, c) != null) dup++;
                for (MappingTree.MemberRow m : c.methods.values()) { String k = memKey(t, ns, cn, m, true); if (k != null && mm.put(k, m) != null) dup++; }
                for (MappingTree.MemberRow f : c.fields.values()) { String k = memKey(t, ns, cn, f, false); if (k != null && mm.put(k, f) != null) dup++; }
            }
            clsBy.put(ns, cm); memBy.put(ns, mm); dupKeys.put(ns, dup);
        }
        JsonObject o = new JsonObject();
        for (int A : nss) for (int B : nss) {
            if (A >= B) continue;
            long ok = 0, fail = 0, holes = 0; List<String> samples = new ArrayList<>();
            for (MappingTree.ClassRow c : t.classes.values()) {
                String ca = c.get(A), cb = c.get(B);
                if (ca == null || cb == null) { holes++; continue; }
                if (clsBy.get(A).get(ca) == c && clsBy.get(B).get(cb) == c) ok++; else { fail++; if (samples.size() < 5) samples.add("class " + c.obf); }
                for (boolean meth : new boolean[]{true, false}) for (MappingTree.MemberRow m : (meth ? c.methods : c.fields).values()) {
                    String ka = memKey(t, A, ca, m, meth), kb = memKey(t, B, cb, m, meth);
                    if (ka == null || kb == null) { holes++; continue; }
                    if (memBy.get(A).get(ka) == m && memBy.get(B).get(kb) == m) ok++; else { fail++; if (samples.size() < 5) samples.add((meth ? "method " : "field ") + c.obf + "." + m.obf + m.desc); }
                }
            }
            JsonObject p = new JsonObject();
            p.addProperty("ok", ok); p.addProperty("fail", fail); p.addProperty("holes", holes);
            if (!samples.isEmpty()) p.add("failSamples", new Gson().toJsonTree(samples));
            o.add(t.namespaces.get(A) + "<->" + t.namespaces.get(B), p);
        }
        JsonObject d = new JsonObject(); for (int ns : nss) d.addProperty(t.namespaces.get(ns), dupKeys.get(ns));
        o.add("duplicateLookupKeys", d);
        return o;
    }
    private static String memKey(MappingTree t, int ns, String ownerName, MappingTree.MemberRow m, boolean method) {
        String n = m.get(ns); if (n == null || m.desc == null) return null;
        String d = Descriptors.mapClasses(m.desc, x -> { MappingTree.ClassRow c = t.classes.get(x); return c != null && c.get(ns) != null ? c.get(ns) : x; });
        return ownerName + (method ? "." : ":") + n + (method ? "" : ":") + d;
    }
    static String pkg(String n) { int i = n.lastIndexOf('/'); return i < 0 ? "" : n.substring(0, i); }

    /** One class name per class; no two members of a class with the same mapped name + mapped desc. */
    static JsonObject injectivity(MappingTree t, int ns) {
        Map<String, String> clsMap = new HashMap<>();
        for (MappingTree.ClassRow c : t.classes.values()) clsMap.put(c.obf, c.get(ns));
        Map<String, String> seen = new HashMap<>();
        int dupClasses = 0, dupMembers = 0; List<String> samples = new ArrayList<>();
        for (MappingTree.ClassRow c : t.classes.values()) {
            String n = c.get(ns); if (n == null) continue;
            String prev = seen.put(n, c.obf);
            if (prev != null) { dupClasses++; if (samples.size() < 5) samples.add("class " + n + " <- " + prev + ", " + c.obf); }
            Set<String> mem = new HashSet<>();
            for (MappingTree.MemberRow m : c.methods.values()) {
                String mn = m.get(ns); if (mn == null) continue;
                String k = mn + Descriptors.mapClasses(m.desc, x -> clsMap.getOrDefault(x, null) != null ? clsMap.get(x) : x);
                if (!mem.add(k)) { dupMembers++; if (samples.size() < 10) samples.add("method " + (c.get(ns)) + "." + k); }
            }
            Set<String> fm = new HashSet<>();
            for (MappingTree.MemberRow f : c.fields.values()) {
                String fn = f.get(ns); if (fn == null) continue;
                String k = fn + ":" + Descriptors.mapClasses(f.desc, x -> clsMap.get(x) != null ? clsMap.get(x) : x);
                if (!fm.add(k)) { dupMembers++; if (samples.size() < 10) samples.add("field " + c.get(ns) + "." + k); }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("duplicateClassNames", dupClasses); o.addProperty("duplicateMembers", dupMembers);
        o.add("samples", new Gson().toJsonTree(samples));
        return o;
    }

    /** Every Mojmap class/method/field named by the tree exists in the ART-built NeoForge base jar. */
    static JsonObject groundTruth(MappingTree t, int ns, Path jarPath) throws Exception {
        JarIndex g = JarIndex.of(jarPath, List.of());
        Map<String, String> clsMap = new HashMap<>();
        for (MappingTree.ClassRow c : t.classes.values()) clsMap.put(c.obf, c.get(ns));
        int cOk = 0, cMiss = 0, mOk = 0, mMiss = 0, fOk = 0, fMiss = 0; List<String> samples = new ArrayList<>();
        for (MappingTree.ClassRow c : t.classes.values()) {
            String n = c.get(ns); if (n == null) continue;
            JarIndex.Info gi = g.classes.get(n);
            if (gi == null) { cMiss++; if (samples.size() < 10) samples.add("class " + n); continue; }
            cOk++;
            for (MappingTree.MemberRow m : c.methods.values()) {
                String mn = m.get(ns); if (mn == null) continue;
                String k = mn + Descriptors.mapClasses(m.desc, x -> clsMap.get(x) != null ? clsMap.get(x) : x);
                if (gi.methods.containsKey(k)) mOk++; else { mMiss++; if (samples.size() < 20) samples.add("method " + n + "." + k); }
            }
            for (MappingTree.MemberRow f : c.fields.values()) {
                String fn = f.get(ns); if (fn == null) continue;
                String k = fn + ":" + Descriptors.mapClasses(f.desc, x -> clsMap.get(x) != null ? clsMap.get(x) : x);
                if (gi.fields.containsKey(k)) fOk++; else { fMiss++; if (samples.size() < 20) samples.add("field " + n + "." + k); }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("jar", jarPath.getFileName().toString());
        o.addProperty("classes", cOk + " ok / " + cMiss + " missing"); o.addProperty("methods", mOk + " ok / " + mMiss + " missing"); o.addProperty("fields", fOk + " ok / " + fMiss + " missing");
        o.add("samples", new Gson().toJsonTree(samples));
        return o;
    }
}
