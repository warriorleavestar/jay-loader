package amber.ul.map;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.*;
import org.objectweb.asm.*;

/**
 * M4 step 2 (option A, Mojmap everywhere): Forge 1.20.1 runtime names ("forge" = Mojmap classes + SRG members) -> Mojmap,
 * with Amber's engine, for the whole Forge side of an instance:
 *   game     = client-srg.jar overlaid with Forge's patched classes (forge-*-client.jar), as Forge's runtime sees it
 *   universal= forge-*-universal.jar (net.minecraftforge.*, Forge's own AT)
 *   mods     = every jar in the corpus dir, plus nested META-INF/jarjar/*.jar (JarJar), recursively
 * Bytecode (members), @Mixin selectors (MixinPass), refmaps (all namespaces), access transformers, MixinExtras
 * @Definition strings, reflection strings (StringPass). No oracle exists for this direction, so the check is a link check:
 * every field/method reference whose owner is a game class must resolve in the remapped game (walking supers and
 * interfaces, incl. libraries and Forge), and no SRG id may be left on a game owner, in an AT or in a refmap.
 *   ForgeCheck <mappingsDir 1.20.1> <obf client.jar> <libsDir...;sep> <srg.jar> <patched.jar> <universal.jar> <modsDir> <workDir> <report.json>
 */
public final class ForgeCheck {
    public static final Pattern SRG = Pattern.compile("\\b[mf]_\\d+_\\b");

    public static void main(String[] a) throws Exception {
        Path mapDir = Path.of(a[0]), obfJar = Path.of(a[1]);
        Path srgJar = Path.of(a[3]), patchedJar = Path.of(a[4]), universal = Path.of(a[5]), modsDir = Path.of(a[6]), work = Path.of(a[7]), report = Path.of(a[8]);
        List<Path> libs = new ArrayList<>();
        for (String d : a[2].split(";")) try (Stream<Path> s = Files.walk(Path.of(d))) {
            s.filter(p -> p.toString().endsWith(".jar")).filter(p -> !p.toString().replace('\\', '/').contains("/net/minecraft/client/"))
             .filter(p -> !p.getFileName().toString().startsWith("forge-1.20.1-")).sorted().forEach(libs::add);
        }
        int threads = Runtime.getRuntime().availableProcessors();
        Path in = work.resolve("in"), out = work.resolve("out");
        for (Path d : List.of(in, out)) { if (Files.exists(d)) try (Stream<Path> s = Files.walk(d)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); } Files.createDirectories(d); }
        JsonObject r = new JsonObject();

        // ---- inputs ----
        List<Path> mods = new ArrayList<>();
        try (Stream<Path> s = Files.list(modsDir)) { for (Path p : s.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) expand(p, in, mods, 0); }
        r.addProperty("modJars", mods.size());
        Map<String, JarRemapper.Entry> game = new LinkedHashMap<>();
        for (JarRemapper.Entry e : JarRemapper.read(srgJar)) game.put(e.name(), e);
        int patched = 0; for (JarRemapper.Entry e : JarRemapper.read(patchedJar)) { if (game.put(e.name(), e) != null) patched++; else patched++; }
        r.addProperty("gamePatchedEntries", patched);
        List<JarRemapper.Entry> gameEntries = new ArrayList<>(game.values());

        // ---- graph ----
        long t0 = System.nanoTime();
        MappingTree t = new MappingTree();
        Readers.proguard(t, mapDir.resolve("mojang-client.txt"), "mojmap");
        Path inter; try (Stream<Path> s = Files.list(mapDir)) { inter = s.filter(p -> p.getFileName().toString().startsWith("intermediary-")).findFirst().orElseThrow(); }
        Readers.tinyJar(t, inter, Map.of("official", "obf"));
        Path mcp; try (Stream<Path> s = Files.list(mapDir)) { mcp = s.filter(p -> p.getFileName().toString().startsWith("mcp_config-")).findFirst().orElseThrow(); }
        Readers.tsrg2Zip(t, mcp, List.of("srg", "srgid"));
        JarIndex obf = JarIndex.of(obfJar, libs);
        new Completer(t, obf).run();
        MapTool.deriveForgeRuntime(t);
        long t1 = System.nanoTime();

        // ---- names: one class universe = game (from obf) + Forge universal + all mods ----
        List<List<JarRemapper.Entry>> modEntries = new ArrayList<>();
        List<JarRemapper.Entry> uni = JarRemapper.read(universal); modEntries.add(uni);
        Map<Path, List<JarRemapper.Entry>> byMod = new LinkedHashMap<>();
        for (Path m : mods) { List<JarRemapper.Entry> l = JarRemapper.read(m); byMod.put(m, l); modEntries.add(l); }
        ModNames names = new ModNames(t, t.nsIndex("forge"), t.nsIndex("mojmap"), obf, libs, modEntries, true);
        long t2 = System.nanoTime();
        MixinPass mx = new MixinPass(names);
        for (var l : modEntries) mx.analyze(l);
        TextNames text = names.text();
        StringPass sp = new StringPass(names);
        long t3 = System.nanoTime();

        JsonObject perJar = new JsonObject();
        Map<String, Integer> textStats = new ConcurrentHashMap<>();
        Map<String, List<String>> textLeft = new ConcurrentHashMap<>();
        Path gameOut = out.resolve("game-mojmap.jar");
        new JarRemapper(names, JarRemapper.Options.mod(), threads).run(gameEntries, gameOut);
        long t4 = System.nanoTime();
        Path uniOut = out.resolve(universal.getFileName());
        remapMod("forge-universal", uni, uniOut, names, mx, text, sp, threads, textStats, textLeft);
        Map<Path, Path> outs = new LinkedHashMap<>();
        for (var e : byMod.entrySet()) {
            Path o = out.resolve(e.getKey().getFileName());
            remapMod(e.getKey().getFileName().toString(), e.getValue(), o, names, mx, text, sp, threads, textStats, textLeft);
            outs.put(e.getKey(), o);
        }
        long t5 = System.nanoTime();

        // ---- link check against the remapped game + remapped Forge + libraries ----
        Hier h = new Hier();
        h.addJar(gameOut, true); h.addJar(uniOut, false); for (Path l : libs) h.addJar(l, false);
        for (Path o : outs.values()) h.addJar(o, false);
        JsonObject link = new JsonObject();
        JsonObject gl = h.check(uniOut); link.add("forge-universal", gl);
        int bad = gl.get("unresolved").getAsInt(), refs = gl.get("gameRefs").getAsInt(), srgLeft = gl.get("srgOnGameOwner").getAsInt();
        for (var e : outs.entrySet()) {
            JsonObject c = h.check(e.getValue());
            bad += c.get("unresolved").getAsInt(); refs += c.get("gameRefs").getAsInt(); srgLeft += c.get("srgOnGameOwner").getAsInt();
            if (c.get("unresolved").getAsInt() > 0 || c.get("srgOnGameOwner").getAsInt() > 0) perJar.add(e.getKey().getFileName().toString(), c);
        }
        JsonObject gameSelf = h.check(gameOut);
        r.add("gameSelfCheck", gameSelf);
        r.addProperty("gameRefsChecked", refs); r.addProperty("unresolved", bad); r.addProperty("srgOnGameOwner", srgLeft);
        r.add("problemJars", perJar);
        r.add("text", new Gson().toJsonTree(new TreeMap<>(textStats)));
        r.add("textSrgLeft", new Gson().toJsonTree(textLeft));
        r.add("mixin", new Gson().toJsonTree(new TreeMap<>(mx.stats)));
        r.add("strings", new Gson().toJsonTree(new TreeMap<>(sp.stats)));
        JsonObject lg = new JsonObject(); sp.log.forEach((k, v) -> lg.add(k, new Gson().toJsonTree(new TreeSet<>(v)))); r.add("stringLog", lg);
        r.addProperty("conflicts", names.conflicts.size());
        r.add("conflictSamples", new Gson().toJsonTree(names.conflicts.stream().sorted().limit(20).toList()));
        JsonObject ms = new JsonObject();
        ms.addProperty("graph", (t1 - t0) / 1_000_000); ms.addProperty("modIndex", (t2 - t1) / 1_000_000); ms.addProperty("mixinAnalysis", (t3 - t2) / 1_000_000);
        ms.addProperty("game", (t4 - t3) / 1_000_000); ms.addProperty("forgeAndMods", (t5 - t4) / 1_000_000);
        r.add("ms", ms);
        String js = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r);
        Files.writeString(report, js, StandardCharsets.UTF_8);
        System.out.println(js.length() > 6000 ? js.substring(0, 6000) + "\n..." : js);
        obf.close();
    }

    /** Mixin configs: top-level json with "package" (and usually "mixins"); refmaps are what their "refmap" names. */
    public static void remapMod(String label, List<JarRemapper.Entry> jar, Path out, ModNames names, MixinPass mx, TextNames text, StringPass sp, int threads,
                         Map<String, Integer> stats, Map<String, List<String>> left) throws Exception {
        Set<String> refmaps = new HashSet<>(), ats = new HashSet<>();
        for (JarRemapper.Entry e : jar) {
            if (e.name().equals("META-INF/accesstransformer.cfg") || e.name().matches("META-INF/[^/]*accesstransformer[^/]*\\.cfg")) ats.add(e.name());
            if (e.name().endsWith(".json") && !e.name().contains("/") || e.name().endsWith(".mixins.json") || e.name().matches("[^/]*mixins?[^/]*\\.json")) {
                try {
                    JsonElement j = JsonParser.parseString(new String(e.data(), StandardCharsets.UTF_8));
                    if (j.isJsonObject() && j.getAsJsonObject().has("package") && j.getAsJsonObject().has("refmap")) refmaps.add(j.getAsJsonObject().get("refmap").getAsString());
                    // refmaps named by a mixin plugin's getRefMapperConfig() (ModernFix) are not in any config: the shape decides
                    if (!e.name().contains("/") && j.isJsonObject() && j.getAsJsonObject().has("mappings") && j.getAsJsonObject().get("mappings").isJsonObject()) refmaps.add(e.name());
                } catch (Exception ignored) {}
            }
        }
        boolean rf = !refmaps.isEmpty();
        java.util.function.BiFunction<String, byte[], byte[]> post = (name, b) -> {
            if (name.endsWith(".class")) return amber.ul.bake.Baker.contains(b, "Lcom/llamalad7/mixinextras/expression/Definition") ? amber.ul.bake.Baker.remapDefinitionsSrg(b, text) : b;
            if (ats.contains(name)) { String s = remapAT(new String(b, StandardCharsets.UTF_8), text, stats); leftover(label + "!" + name, s, left); stats.merge("atFiles", 1, Integer::sum); return s.getBytes(StandardCharsets.UTF_8); }
            if (name.equals("META-INF/mods.toml")) { String s0 = new String(b, StandardCharsets.UTF_8), s = neoForgeDepsAsForge(s0); if (!s.equals(s0)) { stats.merge("neoforgeDeps", 1, Integer::sum); return s.getBytes(StandardCharsets.UTF_8); } return b; }
            if (refmaps.contains(name)) { String s = amber.ul.bake.Baker.remapRefmap(new String(b, StandardCharsets.UTF_8), text); leftover(label + "!" + name, s, left); stats.merge("refmaps", 1, Integer::sum); return s.getBytes(StandardCharsets.UTF_8); }
            return b;
        };
        new JarRemapper(names, JarRemapper.Options.mod(), threads).pre(cv -> mx.annotations(cv, rf)).post(post).strings(sp).run(jar, out);
    }

    /**
     * NeoForge 1.20.1 mods on Forge 47: NeoForge 1.20.1 is Forge 47.1 forked (same packages, same mods.toml), and the
     * usual reason such a jar does not load on Forge is a mandatory [[dependencies.x]] block with modId="neoforge", which
     * FML's sorter reports as missing. Those blocks get modId="forge" and versionRange "[47,)". Everything else is kept
     * byte for byte (line based, so comments and layout survive).
     */
    public static String neoForgeDepsAsForge(String toml) {
        if (!toml.contains("neoforge") || Boolean.getBoolean("amber.ul.noNeoForgeAlias")) return toml;   // test switch: control runs
        String[] lines = toml.split("\n", -1);
        Pattern mod = Pattern.compile("^(\\s*modId\\s*=\\s*)([\"'])neoforge\\2(.*?)(\r?)$"), range = Pattern.compile("^(\\s*versionRange\\s*=\\s*)([\"']).*?\\2(.*?)(\r?)$");
        int start = -1;
        for (int i = 0; i <= lines.length; i++) {
            if (i < lines.length && !lines[i].trim().startsWith("[")) continue;
            if (start >= 0) {
                boolean hit = false;
                for (int k = start; k < i; k++) { var m = mod.matcher(lines[k]); if (m.matches()) { lines[k] = m.group(1) + m.group(2) + "forge" + m.group(2) + m.group(3) + m.group(4); hit = true; } }
                if (hit) for (int k = start; k < i; k++) { var m = range.matcher(lines[k]); if (m.matches()) lines[k] = m.group(1) + m.group(2) + amber.ul.meta.MetadataReaders.NEOFORGE_1201_AS_FORGE + m.group(2) + m.group(3) + m.group(4); }
            }
            start = i < lines.length && lines[i].trim().startsWith("[[dependencies") ? i : -1;
        }
        return String.join("\n", lines);
    }

    static void leftover(String where, String s, Map<String, List<String>> left) {
        var m = SRG.matcher(s); List<String> l = new ArrayList<>();
        while (m.find() && l.size() < 10) l.add(m.group());
        if (!l.isEmpty()) left.put(where, l);
    }

    /** Forge AT lines: <access> <class.dotted> [<field> | <method>(<desc>)] [# comment]; wildcards * and *() kept. */
    static String remapAT(String s, TextNames n, Map<String, Integer> stats) {
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String raw = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            int hash = raw.indexOf('#'); String body = hash >= 0 ? raw.substring(0, hash) : raw, cmt = hash >= 0 ? raw.substring(hash) : "";
            String[] p = body.trim().split("\\s+");
            if (p.length >= 2 && !p[0].isEmpty()) {
                String owner = n.mapClass(p[1].replace('.', '/')); String res = p[0] + " " + owner.replace('/', '.');
                if (p.length >= 3) {
                    String m = p[2]; int par = m.indexOf('(');
                    if (m.startsWith("*")) res += " " + m;
                    else if (par >= 0) { String nm = m.substring(0, par), d = m.substring(par); String to = n.mapMethod(owner, nm, d); res += " " + to + n.mapDesc(d); stats.merge(to.equals(nm) ? "atMethodSame" : "atMethodRenamed", 1, Integer::sum); }
                    else { String to = n.mapField(owner, m, null); res += " " + to; stats.merge(to.equals(m) ? "atFieldSame" : "atFieldRenamed", 1, Integer::sum); }
                }
                b.append(res).append(cmt.isEmpty() ? "" : " " + cmt);
            } else b.append(raw);
            b.append('\n');
        }
        if (!s.endsWith("\n")) b.setLength(b.length() - 1);
        return b.toString();
    }

    static void expand(Path jar, Path dir, List<Path> out, int depth) throws IOException {
        Path p = jar;
        if (depth > 0) p = jar; // already extracted into dir
        out.add(p);
        try (var z = new java.util.zip.ZipFile(p.toFile())) {
            var es = z.entries();
            while (es.hasMoreElements()) {
                var e = es.nextElement();
                if (e.getName().startsWith("META-INF/jarjar/") && e.getName().endsWith(".jar")) {
                    Path t = dir.resolve(depth + "-" + p.getFileName() + "--" + e.getName().substring(e.getName().lastIndexOf('/') + 1));
                    try (InputStream is = z.getInputStream(e)) { Files.copy(is, t, StandardCopyOption.REPLACE_EXISTING); }
                    expand(t, dir, out, depth + 1);
                }
            }
        }
    }

    /** Class hierarchy over the remapped outputs and libraries, for resolving member references like the JVM does. */
    static final class Hier {
        record C(String sup, String[] itf, Set<String> m, Set<String> f) {}
        final Map<String, C> cls = new HashMap<>();
        final Set<String> gameClasses = new HashSet<>();
        void addJar(Path j, boolean game) throws IOException {
            for (JarRemapper.Entry e : JarRemapper.read(j)) {
                if (!e.name().endsWith(".class") || e.name().startsWith("META-INF/")) continue;
                ClassReader cr = new ClassReader(e.data());
                Set<String> m = new HashSet<>(), f = new HashSet<>();
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) { m.add(n + d); return null; }
                    @Override public FieldVisitor visitField(int acc, String n, String d, String s, Object v) { f.add(n + ":" + d); return null; }
                }, ClassReader.SKIP_CODE);
                if (cls.putIfAbsent(cr.getClassName(), new C(cr.getSuperName(), cr.getInterfaces(), m, f)) == null && game) gameClasses.add(cr.getClassName());
            }
        }
        boolean has(String owner, String key, boolean field, Set<String> seen) {
            if (owner == null || !seen.add(owner)) return false;
            if (owner.startsWith("[")) return true;   // array clone() etc.
            C c = cls.get(owner);
            if (c == null) return jdk(owner, key, field);
            if ((field ? c.f : c.m).contains(key)) return true;
            if (has(c.sup, key, field, seen)) return true;
            for (String i : c.itf) if (has(i, key, field, seen)) return true;
            return false;
        }
        boolean jdk(String owner, String key, boolean field) {
            try {
                Class<?> k = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                String name = field ? key.substring(0, key.indexOf(':')) : key.substring(0, key.indexOf('('));
                for (Class<?> c = k; c != null; c = c.getSuperclass()) {
                    if (field) { for (var fl : c.getDeclaredFields()) if (fl.getName().equals(name)) return true; }
                    else { for (var me : c.getDeclaredMethods()) if (me.getName().equals(name)) return true; if (name.equals("<init>")) return true; }
                }
                for (Class<?> i : k.getInterfaces()) if (jdk(i.getName().replace('.', '/'), key, field)) return true;
                return !field && (k.isInterface() || name.startsWith("lambda$"));
            } catch (Throwable e) { return true; }   // unknown non-game owner (optional dependency): not this check's business
        }
        boolean isGame(String owner) { return gameClasses.contains(owner); }
        JsonObject check(Path j) throws IOException {
            int[] refs = {0}, bad = {0}, srg = {0};
            Set<String> samples = new TreeSet<>();
            for (JarRemapper.Entry e : JarRemapper.read(j)) {
                if (!e.name().endsWith(".class") || e.name().startsWith("META-INF/versions/")) continue;
                ClassReader cr = new ClassReader(e.data());
                String me = cr.getClassName();
                cr.accept(new ClassVisitor(Opcodes.ASM9) {
                    void ref(String owner, String name, String desc, boolean field) {
                        String o = owner;
                        while (o.startsWith("[")) o = o.substring(1);
                        if (o.startsWith("L") && o.endsWith(";")) o = o.substring(1, o.length() - 1);
                        if (!isGame(o)) return;
                        refs[0]++;
                        if (SRG.matcher(name).matches()) { srg[0]++; if (samples.size() < 15) samples.add("srg " + me + " -> " + o + "." + name + desc); }
                        if (!has(o, field ? name + ":" + desc : name + desc, field, new HashSet<>())) { bad[0]++; if (samples.size() < 15) samples.add(me + " -> " + o + "." + name + (field ? ":" : "") + desc); }
                    }
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int op, String o, String nm, String ds, boolean itf) { ref(o, nm, ds, false); }
                            @Override public void visitFieldInsn(int op, String o, String nm, String ds) { ref(o, nm, ds, true); }
                            @Override public void visitInvokeDynamicInsn(String nm, String ds, Handle bsm, Object... args) {
                                for (Object x : args) if (x instanceof Handle hd) ref(hd.getOwner(), hd.getName(), hd.getDesc(), hd.getTag() <= Opcodes.H_PUTSTATIC);
                            }
                            @Override public void visitLdcInsn(Object v) { if (v instanceof Handle hd) ref(hd.getOwner(), hd.getName(), hd.getDesc(), hd.getTag() <= Opcodes.H_PUTSTATIC); }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            JsonObject o = new JsonObject();
            o.addProperty("gameRefs", refs[0]); o.addProperty("unresolved", bad[0]); o.addProperty("srgOnGameOwner", srg[0]);
            o.add("samples", new Gson().toJsonTree(samples));
            return o;
        }
    }
}
