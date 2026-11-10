package amber.ul.map;

import amber.ul.bake.Mappings;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import net.fabricmc.tinyremapper.*;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

/**
 * M2 golden check: remap a set of Fabric mods intermediary -> mojmap with Amber's engine and with tiny-remapper
 * (configured exactly like Baker.bakeMods), then compare every class as ASM text.
 *   ModCheck <mappingsDir> <obf client.jar> <libsDir> <intermediary game jar> <merged.tiny> <modsDir> <workDir> <report.json>
 * Nested jars (META-INF/jars) are expanded and remapped as mods of their own, like Discovery does.
 */
public final class ModCheck {
    public static void main(String[] a) throws Exception {
        Path mapDir = Path.of(a[0]), obfJar = Path.of(a[1]), libsDir = Path.of(a[2]), interGame = Path.of(a[3]), mergedTiny = Path.of(a[4]);
        Path modsDir = Path.of(a[5]), work = Path.of(a[6]), report = Path.of(a[7]);
        int threads = Runtime.getRuntime().availableProcessors();
        Path in = work.resolve("in"), outA = work.resolve("amber"), outT = work.resolve("tiny");
        for (Path d : List.of(in, outA, outT)) { if (Files.exists(d)) try (Stream<Path> s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } Files.createDirectories(d); }
        List<Path> mods = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsDir)) { for (Path p : s.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) expand(p, in, mods, 0); }
        // one copy per mod id, newest version (what the resolver does): nested older Fabric API modules would otherwise
        // put two versions of the same classes into one remap (tiny-remapper then mixes them up)
        Map<String, Path> byId = new LinkedHashMap<>(); Map<String, String> ver = new HashMap<>();
        for (Path p : mods) {
            String[] iv = idVersion(p);
            Path old = byId.get(iv[0]);
            if (old == null || amber.ul.resolve.Versions.compare(iv[1], ver.get(iv[0])) > 0) { byId.put(iv[0], p); ver.put(iv[0], iv[1]); }
        }
        int before = mods.size();
        mods = new ArrayList<>(byId.values());
        List<Path> libs;
        try (Stream<Path> s = Files.walk(libsDir)) { libs = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        JsonObject r = new JsonObject();
        r.addProperty("jars", mods.size()); r.addProperty("jarsBeforeDedupe", before);

        // ---- Amber ----
        long a0 = System.nanoTime();
        MappingTree t = new MappingTree();
        Readers.proguard(t, mapDir.resolve("mojang-client.txt"), "mojmap");
        Path inter = Files.list(mapDir).filter(p -> p.getFileName().toString().startsWith("intermediary-")).findFirst().orElseThrow();
        Readers.tinyJar(t, inter, Map.of("official", "obf"));
        JarIndex obf = JarIndex.of(obfJar, libs);
        new Completer(t, obf).run();
        long a1 = System.nanoTime();
        ModNames names = new ModNames(t, t.nsIndex("intermediary"), t.nsIndex("mojmap"), obf, libs, mods);
        long a2 = System.nanoTime();
        MixinPass mx = new MixinPass(names);
        for (Path m : mods) mx.analyze(m);
        long a2b = System.nanoTime();
        StringPass sp = Boolean.getBoolean("amber.noStrings") ? null : new StringPass(names);
        for (Path m : mods) { boolean rf = hasRefmap(m); new JarRemapper(names, JarRemapper.Options.mod(), threads).pre(cv -> mx.annotations(cv, rf)).strings(sp).run(m, outA.resolve(m.getFileName())); }
        long a3 = System.nanoTime();
        JsonObject am = new JsonObject();
        am.addProperty("graphMs", (a1 - a0) / 1_000_000); am.addProperty("indexModsMs", (a2 - a1) / 1_000_000); am.addProperty("mixinAnalysisMs", (a2b - a2) / 1_000_000); am.addProperty("remapMs", (a3 - a2b) / 1_000_000);
        am.add("mixin", new Gson().toJsonTree(new TreeMap<>(mx.stats)));
        am.addProperty("totalMs", (a3 - a0) / 1_000_000); am.addProperty("conflicts", names.conflicts.size()); am.addProperty("awMethodsWidened", names.widened);
        am.add("conflictSamples", new Gson().toJsonTree(names.conflicts.stream().sorted().limit(10).toList()));
        if (sp != null) { am.add("strings", new Gson().toJsonTree(new TreeMap<>(sp.stats))); JsonObject lg = new JsonObject(); sp.log.forEach((k, v) -> lg.add(k, new Gson().toJsonTree(new TreeSet<>(v)))); am.add("stringLog", lg); }
        r.add("amber", am);

        // ---- tiny-remapper, as Baker.bakeMods ----
        long b0 = System.nanoTime();
        Mappings m = new Mappings.Set3(mergedTiny).get("intermediary", "named");
        var prov = m.provider();
        long b1 = System.nanoTime();
        Set<InputTag> refmapTags = Collections.synchronizedSet(new HashSet<>());
        TinyRemapper tr = TinyRemapper.newRemapper().withMappings(prov)
                .renameInvalidLocals(true).rebuildSourceFilenames(true).ignoreConflicts(true).propagatePrivate(true)
                .extension(new MixinExtension(EnumSet.of(MixinExtension.AnnotationTarget.HARD), net.fabricmc.tinyremapper.extension.mixin.common.Logger.Level.WARN, refmapTags::contains))
                .extension(new MixinExtension(EnumSet.of(MixinExtension.AnnotationTarget.HARD, MixinExtension.AnnotationTarget.SOFT), net.fabricmc.tinyremapper.extension.mixin.common.Logger.Level.WARN, tag -> !refmapTags.contains(tag)))
                .build();
        try {
            List<Path> cp = new ArrayList<>(); cp.add(interGame); cp.addAll(libs);
            tr.readClassPathAsync(cp.toArray(Path[]::new));
            Map<Path, InputTag> tags = new LinkedHashMap<>();
            for (Path p : mods) { InputTag tag = tr.createInputTag(); if (hasRefmap(p)) refmapTags.add(tag); tr.readInputsAsync(tag, p); tags.put(p, tag); }
            for (Path p : mods) try (OutputConsumerPath oc = new OutputConsumerPath.Builder(outT.resolve(p.getFileName())).build()) {
                oc.addNonClassFiles(p, NonClassCopyMode.FIX_META_INF, tr);
                tr.apply(oc, tags.get(p));
            }
        } finally { tr.finish(); }
        long b2 = System.nanoTime();
        JsonObject tm = new JsonObject();
        tm.addProperty("readMappingsMs", (b1 - b0) / 1_000_000); tm.addProperty("remapMs", (b2 - b1) / 1_000_000); tm.addProperty("totalMs", (b2 - b0) / 1_000_000);
        r.add("tiny", tm);

        // ---- text formats: AW / refmap / @Definition, Amber's tables vs the mapping-io tables (Mappings) ----
        r.add("text", compareText(mods, names.text(), m, work.resolve("textdiffs.txt")));

        // ---- compare ----
        r.add("compare", compare(mods, outA, outT, work.resolve("diffs.txt")));
        String js = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r);
        Files.writeString(report, js, StandardCharsets.UTF_8);
        System.out.println(js);
    }

    static JsonObject compareText(List<Path> mods, TextNames a, Mappings t, Path diffFile) throws IOException {
        int awSame = 0, awDiffer = 0, rmSame = 0, rmDiffer = 0, defSame = 0, defDiffer = 0, awLines = 0, rmValues = 0;
        try (PrintWriter dw = new PrintWriter(Files.newBufferedWriter(diffFile, StandardCharsets.UTF_8))) {
            for (Path p : mods) {
                Map<String, byte[]> e = entries(p);
                byte[] fmj = e.get("fabric.mod.json"); if (fmj == null) continue;
                JsonObject o; try { o = JsonParser.parseString(new String(fmj, StandardCharsets.UTF_8)).getAsJsonObject(); } catch (RuntimeException x) { continue; }
                if (o.has("accessWidener") && e.containsKey(o.get("accessWidener").getAsString())) {
                    String txt = new String(e.get(o.get("accessWidener").getAsString()), StandardCharsets.UTF_8);
                    String ra = amber.ul.bake.Baker.remapAccessWidener(txt, a, t.to), rt = amber.ul.bake.Baker.remapAccessWidener(txt, t);
                    awLines += ra.split("\n").length;
                    if (ra.equals(rt)) awSame++; else { awDiffer++; diffLines(dw, p.getFileName() + " AW", ra, rt); }
                }
                Set<String> refmaps = new TreeSet<>();
                if (o.has("mixins")) for (JsonElement me : o.getAsJsonArray("mixins")) {
                    String cfg = me.isJsonPrimitive() ? me.getAsString() : me.getAsJsonObject().get("config").getAsString();
                    byte[] c = e.get(cfg); if (c == null) continue;
                    try { JsonObject co = JsonParser.parseString(new String(c, StandardCharsets.UTF_8)).getAsJsonObject(); if (co.has("refmap")) refmaps.add(co.get("refmap").getAsString()); } catch (RuntimeException x) { /* malformed */ }
                }
                for (String rm : refmaps) {
                    byte[] b = e.get(rm); if (b == null) continue;
                    String txt = new String(b, StandardCharsets.UTF_8);
                    String ra = amber.ul.bake.Baker.remapRefmap(txt, a), rt = amber.ul.bake.Baker.remapRefmap(txt, t);
                    rmValues += ra.split("\n").length;
                    if (ra.equals(rt)) rmSame++; else { rmDiffer++; diffLines(dw, p.getFileName() + " " + rm, ra, rt); }
                }
                for (var ce : e.entrySet()) {
                    if (!ce.getKey().endsWith(".class") || !amber.ul.bake.Baker.contains(ce.getValue(), "Lcom/llamalad7/mixinextras/expression/Definition")) continue;
                    String ra = text(amber.ul.bake.Baker.remapDefinitions(ce.getValue(), a)), rt = text(amber.ul.bake.Baker.remapDefinitions(ce.getValue(), t));
                    if (ra.equals(rt)) defSame++; else { defDiffer++; diffLines(dw, p.getFileName() + " " + ce.getKey(), ra, rt); }
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("awSame", awSame); o.addProperty("awDiffer", awDiffer); o.addProperty("awLines", awLines);
        o.addProperty("refmapSame", rmSame); o.addProperty("refmapDiffer", rmDiffer); o.addProperty("refmapLines", rmValues);
        o.addProperty("definitionClassesSame", defSame); o.addProperty("definitionClassesDiffer", defDiffer);
        o.addProperty("diffFile", diffFile.toString());
        return o;
    }
    static void diffLines(PrintWriter dw, String what, String a, String t) {
        dw.println("=== " + what);
        Map<String, Integer> cnt = new LinkedHashMap<>();
        for (String l : a.split("\n")) cnt.merge(l, 1, Integer::sum);
        for (String l : t.split("\n")) cnt.merge(l, -1, Integer::sum);
        int shown = 0;
        for (var c : cnt.entrySet()) if (c.getValue() != 0 && shown++ < 40) dw.println((c.getValue() > 0 ? "  amber: " : "  tiny:  ") + c.getKey().trim());
    }

    static void expand(Path jar, Path dir, List<Path> out, int depth) throws IOException {
        Path copy = dir.resolve(out.size() + "-" + jar.getFileName());
        Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
        out.add(copy);
        if (depth > 4) return;
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().startsWith("META-INF/jars/") || !e.getName().endsWith(".jar")) continue;
                Path tmp = dir.resolve("nested-" + System.nanoTime() + ".jar");
                try (InputStream is = z.getInputStream(e)) { Files.copy(is, tmp); }
                Path named = tmp.resolveSibling(e.getName().substring(e.getName().lastIndexOf('/') + 1));
                if (Files.exists(named)) named = tmp.resolveSibling(System.nanoTime() + "-" + named.getFileName());
                Files.move(tmp, named);
                expand(named, dir, out, depth + 1);
                Files.delete(named);
            }
        }
    }

    /** fabric.mod.json id + version; jars without one are keyed by file name (minus the copy prefix). */
    static String[] idVersion(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fmj = z.getEntry("fabric.mod.json");
            if (fmj != null) try (InputStream is = z.getInputStream(fmj)) {
                JsonObject o = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                return new String[]{o.get("id").getAsString(), o.has("version") ? o.get("version").getAsString() : "0"};
            } catch (RuntimeException ignored) {}
        }
        String n = jar.getFileName().toString();
        return new String[]{"file:" + n.substring(n.indexOf('-') + 1), "0"};
    }

    static boolean hasRefmap(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry fmj = z.getEntry("fabric.mod.json"); if (fmj == null) return false;
            JsonObject o;
            try (InputStream is = z.getInputStream(fmj)) { o = JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject(); }
            catch (RuntimeException ex) { return false; }
            if (!o.has("mixins")) return false;
            for (JsonElement e : o.getAsJsonArray("mixins")) {
                String cfg = e.isJsonPrimitive() ? e.getAsString() : e.getAsJsonObject().get("config").getAsString();
                ZipEntry ce = z.getEntry(cfg); if (ce == null) continue;
                try (InputStream is = z.getInputStream(ce)) { if (JsonParser.parseString(new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().has("refmap")) return true; }
                catch (RuntimeException ex) { /* malformed config */ }
            }
        }
        return false;
    }

    static String text(byte[] b) { return text(b, 0); }
    /** SKIP_DEBUG drops LVT, line numbers and SourceFile, SKIP_FRAMES the verifier frames; MethodParameters ("// parameter") lines are dropped too: debug only. */
    static String semantic(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (String l : text(b, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES).split("\n")) if (!l.trim().startsWith("// parameter")) sb.append(l).append('\n');
        return sb.toString();
    }
    static final java.util.regex.Pattern QUOTED = java.util.regex.Pattern.compile("\"([^\"]*)\"");
    /** Annotation strings that are member selectors without an owner lose their descriptor: "name(desc)" == "name". */
    static String selectorForm(String text) {
        StringBuilder out = new StringBuilder();
        for (String l : text.split("\n")) {
            if (!l.trim().startsWith("@")) { out.append(l).append('\n'); continue; }
            java.util.regex.Matcher m = QUOTED.matcher(l); StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String q = m.group(1);
                if (!(q.startsWith("L") && q.indexOf(';') > 0)) { int p = q.indexOf('('); if (p >= 0) q = q.substring(0, p); }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement("\"" + q + "\""));
            }
            m.appendTail(sb); out.append(sb).append('\n');
        }
        return out.toString();
    }
    static String text(byte[] b, int flags) {
        StringWriter sw = new StringWriter();
        new ClassReader(b).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw)), flags);
        return sw.toString();
    }
    static boolean isMixin(byte[] b) {
        boolean[] m = {false};
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String d, boolean v) { if (d.equals("Lorg/spongepowered/asm/mixin/Mixin;")) m[0] = true; return null; }
        }, ClassReader.SKIP_CODE);
        return m[0];
    }

    static JsonObject compare(List<Path> mods, Path outA, Path outT, Path diffFile) throws IOException {
        long leftA = 0, leftT = 0; List<String> onlyASamples = new ArrayList<>(); int selectorOnly = 0, same = 0, debugOnly = 0, differ = 0, differMixin = 0, onlyA = 0, onlyT = 0, resSame = 0, resDiffer = 0;
        Map<String, Integer> lineKinds = new TreeMap<>();
        List<String> resDiff = new ArrayList<>();
        try (PrintWriter dw = new PrintWriter(Files.newBufferedWriter(diffFile, StandardCharsets.UTF_8))) {
            for (Path m : mods) {
                Map<String, byte[]> A = entries(outA.resolve(m.getFileName())), T = entries(outT.resolve(m.getFileName()));
                for (String k : A.keySet()) if (!T.containsKey(k)) { if (k.endsWith(".class")) { onlyA++; if (onlyASamples.size() < 20) onlyASamples.add(m.getFileName() + "!" + k); } }
                for (String k : T.keySet()) if (!A.containsKey(k)) { if (k.endsWith(".class")) onlyT++; }
                for (var e : A.entrySet()) if (e.getKey().endsWith(".class")) leftA += leftovers(e.getValue());
                for (var e : T.entrySet()) if (e.getKey().endsWith(".class")) leftT += leftovers(e.getValue());
                for (var e : A.entrySet()) {
                    byte[] tb = T.get(e.getKey()); if (tb == null) continue;
                    if (!e.getKey().endsWith(".class")) { if (Arrays.equals(e.getValue(), tb)) resSame++; else { resDiffer++; if (resDiff.size() < 20) resDiff.add(m.getFileName() + "!" + e.getKey()); } continue; }
                    if (text(e.getValue()).equals(text(tb))) { same++; continue; }
                    String ta = semantic(e.getValue()), tt = semantic(tb);
                    if (ta.equals(tt)) { debugOnly++; continue; }
                    if (selectorForm(ta).equals(selectorForm(tt))) { selectorOnly++; continue; }
                    differ++; boolean mix = isMixin(e.getValue()); if (mix) differMixin++;
                    String[] la = ta.split("\n"), lt = tt.split("\n");
                    dw.println("=== " + m.getFileName() + " ! " + e.getKey() + (mix ? " [mixin]" : ""));
                    int shown = 0;
                    // line-set difference (order-insensitive): good enough to name what differs
                    Map<String, Integer> cnt = new HashMap<>();
                    for (String l : la) cnt.merge(l, 1, Integer::sum);
                    for (String l : lt) cnt.merge(l, -1, Integer::sum);
                    for (var c : cnt.entrySet()) {
                        if (c.getValue() == 0) continue;
                        if (shown++ < 12) dw.println((c.getValue() > 0 ? "  amber: " : "  tiny:  ") + c.getKey().trim());
                        lineKinds.merge((mix ? "mixin " : "") + kind(c.getKey()), 1, Integer::sum);
                    }
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("classesSame", same); o.addProperty("classesDebugInfoOnly", debugOnly); o.addProperty("classesDiffer", differ); o.addProperty("classesDifferMixin", differMixin);
        o.addProperty("classesSelectorFormOnly", selectorOnly);
        o.add("onlyAmberSamples", new Gson().toJsonTree(onlyASamples));
        o.addProperty("classesOnlyAmber", onlyA); o.addProperty("classesOnlyTiny", onlyT);
        o.addProperty("intermediaryNamesLeftAmber", leftA); o.addProperty("intermediaryNamesLeftTiny", leftT);
        o.addProperty("resourcesSame", resSame); o.addProperty("resourcesDiffer", resDiffer);
        o.add("resourceDiffSamples", new Gson().toJsonTree(resDiff));
        o.add("differingLineKinds", new Gson().toJsonTree(lineKinds));
        o.addProperty("diffFile", diffFile.toString());
        return o;
    }
    static final java.util.regex.Pattern INTER = java.util.regex.Pattern.compile("\\b(?:method|field|comp)_\\d+|net/minecraft/class_\\d+");
    /** Intermediary tokens in member names / owners / descriptors of a remapped class (not in string constants). */
    static int leftovers(byte[] b) {
        int n = 0;
        java.util.regex.Matcher m = INTER.matcher(text(b, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES).replaceAll("(?m)^\\s*LDC \"[^\\n]*$", ""));
        while (m.find()) n++;
        return n;
    }
    static String kind(String l) {
        String s = l.trim();
        int sp = s.indexOf(' ');
        String w = sp < 0 ? s : s.substring(0, sp);
        if (s.startsWith("//")) return "comment";
        if (s.startsWith("LOCALVARIABLE")) return "LOCALVARIABLE";
        if (s.startsWith("@")) return "annotation";
        if (w.matches("[A-Z_]+")) return w;
        if (s.contains("(") && (s.startsWith("public") || s.startsWith("private") || s.startsWith("protected") || s.startsWith("static") || s.startsWith("final") || s.startsWith("abstract") || s.startsWith("synthetic") || s.startsWith("bridge"))) return "method decl";
        return "other: " + (s.length() > 30 ? s.substring(0, 30) : s);
    }
    static Map<String, byte[]> entries(Path p) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        if (!Files.exists(p)) return m;
        try (ZipFile z = new ZipFile(p.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) { if (e.isDirectory()) continue; try (InputStream is = z.getInputStream(e)) { m.put(e.getName(), is.readAllBytes()); } }
        }
        return m;
    }
}
