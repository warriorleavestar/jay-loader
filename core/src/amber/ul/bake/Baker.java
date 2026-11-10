package amber.ul.bake;

import amber.ul.model.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.fabricmc.tinyremapper.*;
import net.fabricmc.tinyremapper.extension.mixin.MixinExtension;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Bake stages, all content-addressed so they run once and are reused forever:
 *  0. game remap: official client.jar -> runtime namespace (Mojmap) with tiny-remapper, signatures stripped.
 *     (also official -> intermediary, used only as the remap classpath for Fabric mods)
 *  1. mod bake (at download time): remap a mod jar from its own namespace into the runtime namespace, including its
 *     access widener and mixin refmaps. Key = sha256(jar) + mapping fingerprint + PIPELINE.
 *  2. game bake (when the instance's mod set changes): apply all access rules + loader hooks to the remapped game jar.
 */
public final class Baker {
    public static final String PIPELINE = "ul-proto-9";
    private final Path cacheDir;
    public int hits, misses;
    /** Official game jar + libraries, for Amber's own mod remapper (amber.ul.map). Null = tiny-remapper only. */
    public Path obfGame; public List<Path> libs = List.of();
    /** -Damber.remapper=tiny switches mod remapping back to tiny-remapper. */
    static boolean useAmber() { return !"tiny".equals(System.getProperty("amber.remapper")); }
    private amber.ul.map.MappingTree graph; private amber.ul.map.JarIndex obfIndex;
    /** The graph from the merged tiny file (official/intermediary/named), completed against the official jar. Built once per Baker. */
    private synchronized amber.ul.map.MappingTree graph(Mappings m) throws IOException {
        if (graph != null) return graph;
        long t = System.nanoTime();
        // merged tiny (official/intermediary/named) completed against the official jar, via the tableKey cache
        amber.ul.map.MappingCache.Table tb = new amber.ul.map.MappingCache.Table(obfGame.getFileName().toString().replaceFirst("[.]jar$", ""))
                .step("tiny", "official=obf,named=mojmap", m.file, null, null).game(obfGame, libs);
        amber.ul.map.MappingCache.Result r = amber.ul.map.MappingCache.get(amber.ul.map.MappingCache.root(obfGame), tb, null);
        amber.ul.map.MappingTree g = r.tree();
        obfIndex = r.index() != null ? r.index() : amber.ul.map.JarIndex.of(obfGame, libs);
        System.out.printf("[amber-ul] 映射图 %.1f s (%s %s)%n", (System.nanoTime() - t) / 1e9, r.hit() ? "缓存" : "新建", r.key().substring(0, 16));
        return graph = g;
    }
    /** warm (interface v1.2): build or map in the mod remapper's graph now, even when every mod bake is a cache hit. */
    public void warmGraph(Mappings m) throws IOException { if (useAmber() && !m.isIdentity()) graph(m); }
    public Baker(Path cacheDir) throws IOException { this.cacheDir = Files.createDirectories(cacheDir); }

    /** Stage 0. */
    public Path remapGame(Path official, List<Path> classpath, Mappings m) throws IOException {
        requireFile(official, "game jar");
        String key = Hash.str(Hash.file(official) + "|" + m.fingerprint + "|" + PIPELINE);
        Path out = CacheUse.mark(cacheDir.resolve("game-" + m.to).resolve(official.getFileName().toString().replace(".jar", "") + "-" + key.substring(0, 16) + ".jar"));
        if (Files.exists(out)) { hits++; return out; }
        misses++;
        long t = System.nanoTime();
        remapJar(official, out, classpath, m, false);
        System.out.printf("[amber-ul] 游戏重映射 %s -> %s: %.1f s%n", m.from, m.to, (System.nanoTime() - t) / 1e9);
        return out;
    }

    public static void remapJar(Path in, Path out, List<Path> classpath, Mappings m, boolean mixin) throws IOException {
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + "." + System.nanoTime() + ".tmp.jar"); Files.deleteIfExists(tmp);
        TinyRemapper.Builder b = TinyRemapper.newRemapper().withMappings(m.provider())
                .renameInvalidLocals(true).rebuildSourceFilenames(true).ignoreConflicts(true)
                .propagatePrivate(true);
        if (mixin) b.extension(new MixinExtension());
        TinyRemapper r = b.build();
        try (OutputConsumerPath oc = new OutputConsumerPath.Builder(tmp).build()) {
            oc.addNonClassFiles(in, NonClassCopyMode.FIX_META_INF, r);
            r.readClassPath(classpath.toArray(Path[]::new));
            r.readInputs(in);
            r.apply(oc);
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(tmp); throw e; }
        finally { r.finish(); }
        fixTimes(tmp);
        SafeFiles.publish(tmp, out);
    }

    /** tiny-remapper writes entries in thread order with the current time: rewrite sorted by name (manifest first) with SafeFiles.entry's fixed time, so the same input gives the same bytes. */
    static void fixTimes(Path jar) throws IOException {
        Path t = jar.resolveSibling(jar.getFileName() + ".t");
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(jar.toFile()); JarOutputStream o = new JarOutputStream(Files.newOutputStream(t))) {
            List<java.util.zip.ZipEntry> all = new ArrayList<>(Collections.list(z.entries()));
            all.sort(Comparator.comparing((java.util.zip.ZipEntry e) -> !e.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")).thenComparing(java.util.zip.ZipEntry::getName));
            for (java.util.zip.ZipEntry e : all) {
                o.putNextEntry(SafeFiles.entry(e.getName()));
                if (!e.isDirectory()) try (InputStream in = z.getInputStream(e)) { in.transferTo(o); }
                o.closeEntry();
            }
        } catch (IOException | RuntimeException e) { Files.deleteIfExists(t); throw e; }
        Files.move(t, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * What the mod bake resolved against besides the mod itself: the game jar the remapper read (content hash) and the
     * library set (maven file names + sizes). Part of every mod bake key (security scan H16: a bake without the game classpath
     * must never be served to a run that has one, and vice versa).
     */
    public String context = "";
    public static String context(Path gameJar, List<Path> libs) throws IOException {
        requireFile(gameJar, "game jar");
        StringBuilder sb = new StringBuilder(Hash.file(gameJar));
        for (Path l : libs.stream().sorted().toList()) sb.append('|').append(l.getFileName()).append(':').append(Files.isRegularFile(l) ? Files.size(l) : -1);
        return Hash.str(sb.toString());
    }
    static void requireFile(Path p, String what) throws IOException {
        if (p == null || !Files.isRegularFile(p)) throw new IOException(what + " missing or not a regular file: " + p);
    }

    /** Where a mod's baked jar lives (content-addressed), or the jar itself when no bake is needed. */
    public Path bakedPath(ModInfo mod, Mappings m) throws IOException {
        if (m.isIdentity() || mod.kind == LoaderKind.LIBRARY) return mod.jar;            // already runtime namespace (NeoForge 1.20.5+) / plain library
        // named by content only (a mod id from metadata never becomes a path); context = game jar + libraries the remap resolved against
        String key = Hash.str(mod.sha256 + "|" + m.fingerprint + "|" + PIPELINE + "|" + context + (useAmber() ? "" : "|tiny"));
        return CacheUse.mark(SafeFiles.under(cacheDir, cacheDir.resolve("mods").resolve(key.substring(0, 32) + ".jar")));
    }

    /**
     * Stage 1 for many mods at once: ONE tiny-remapper instance reads the classpath (game in the mods' namespace + libraries)
     * once, all mods are inputs with their own InputTag, so mod-to-mod inheritance (e.g. Fabric API modules) resolves too.
     * Returns mod -> baked jar. Only cache misses are remapped.
     */
    public Map<ModInfo, Path> bakeMods(List<ModInfo> mods, Mappings m, java.util.function.Supplier<List<Path>> classpath) throws IOException {
        Map<ModInfo, Path> out = new LinkedHashMap<>();
        List<ModInfo> todo = new ArrayList<>();
        for (ModInfo mod : mods) {
            Path p = bakedPath(mod, m);
            if (p.equals(mod.jar) || Files.exists(p)) { hits++; out.put(mod, p); continue; }
            if (!referencesMinecraft(mod.jar)) { hits++; out.put(mod, mod.jar); continue; }   // e.g. Loom-generated library mods
            todo.add(mod);
        }
        if (todo.isEmpty()) return out;
        misses += todo.size();
        long t = System.nanoTime();
        Path dir = Files.createDirectories(cacheDir.resolve("mods"));
        // HARD (@Shadow/@Accessor/@Invoker/@Overwrite member names) always. SOFT (@Inject/@At strings) only for mods WITHOUT a
        // refmap (e.g. Lithium: Loom already wrote intermediary strings into the annotations). With a refmap the strings are
        // dev names (Yarn) that only the refmap translates; SOFT would rewrite "Owner.name(desc)" into "LOwner;name(desc)"
        // and break the refmap key.
        if (useAmber() && obfGame != null && m.file != null && m.from.equals("intermediary") && m.to.equals("named")) {
            bakeModsAmber(todo, m, dir, out);
            System.out.printf("[amber-ul] 模组烘焙 %d 个 (%s -> %s, Amber 重映射器): %.1f s%n", todo.size(), m.from, m.to, (System.nanoTime() - t) / 1e9);
            return out;
        }
        Set<InputTag> refmapTags = Collections.synchronizedSet(new HashSet<>());
        TinyRemapper r = TinyRemapper.newRemapper().withMappings(m.provider())
                .renameInvalidLocals(true).rebuildSourceFilenames(true).ignoreConflicts(true).propagatePrivate(true)
                .extension(new MixinExtension(EnumSet.of(MixinExtension.AnnotationTarget.HARD), net.fabricmc.tinyremapper.extension.mixin.common.Logger.Level.WARN, refmapTags::contains))
                .extension(new MixinExtension(EnumSet.of(MixinExtension.AnnotationTarget.HARD, MixinExtension.AnnotationTarget.SOFT), net.fabricmc.tinyremapper.extension.mixin.common.Logger.Level.WARN, tag -> !refmapTags.contains(tag)))
                .build();
        Map<ModInfo, Path> pre = new LinkedHashMap<>();
        Map<ModInfo, InputTag> tags = new LinkedHashMap<>();
        try {
            r.readClassPathAsync(classpath.get().toArray(Path[]::new));
            for (ModInfo mod : todo) {
                Path pj = resolveMixinTargets(mod, dir.resolve("bake-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".pre.jar"));
                InputTag tag = r.createInputTag();
                if (hasRefmap(mod)) refmapTags.add(tag);
                r.readInputsAsync(tag, pj);
                pre.put(mod, pj); tags.put(mod, tag);
            }
            for (ModInfo mod : todo) {
                Path remapped = dir.resolve("bake-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".cls.jar");
                try (OutputConsumerPath oc = new OutputConsumerPath.Builder(remapped).build()) {
                    oc.addNonClassFiles(pre.get(mod), NonClassCopyMode.FIX_META_INF, r);
                    r.apply(oc, tags.get(mod));
                }
                Path fin = bakedPath(mod, m);
                postProcess(mod, m, remapped, fin);
                Files.delete(remapped); Files.delete(pre.get(mod));
                out.put(mod, fin);
            }
        } finally { r.finish(); }
        System.out.printf("[amber-ul] 模组烘焙 %d 个 (%s -> %s, 一个 tiny-remapper 实例): %.1f s%n", todo.size(), m.from, m.to, (System.nanoTime() - t) / 1e9);
        return out;
    }

    /**
     * Same stages as the tiny-remapper path (resolveMixinTargets -> remap -> postProcess), with amber.ul.map doing the
     * bytecode: one class universe (game from the obf index + all mods), Mixin member names, @Mixin targets and, for
     * mods without a refmap, selector strings. Checked against tiny-remapper on the golden set (ModCheck, 阶段6报告 M2).
     */
    private void bakeModsAmber(List<ModInfo> todo, Mappings m, Path dir, Map<ModInfo, Path> out) throws IOException {
        long t0 = System.nanoTime();
        amber.ul.map.MappingTree g = graph(m);
        Map<ModInfo, List<amber.ul.map.JarRemapper.Entry>> pre = new LinkedHashMap<>();
        {
            long t1 = System.nanoTime();
            // jars are read once and stay in memory through the pre-step, index, mixin analysis and remap: no temp jars
            // (fresh temp jars were also slow to reopen on Windows, ~10 ms per class, presumably the virus scanner)
            for (ModInfo mod : todo) pre.put(mod, resolveMixinTargetsInMemory(mod));
            long t2 = System.nanoTime();
            amber.ul.map.ModNames names = new amber.ul.map.ModNames(g, g.nsIndex("intermediary"), g.nsIndex("mojmap"), obfIndex, libs, new ArrayList<>(pre.values()), true);
            long t2b = System.nanoTime();
            System.out.println("[amber-ul] 模组索引: " + names.timing);
            amber.ul.map.MixinPass mx = new amber.ul.map.MixinPass(names);
            // AW/refmap/@Definition text uses the same tables as the bytecode (no mapping-io read of the tiny file)
            amber.ul.map.TextNames text = Boolean.getBoolean("amber.textTiny") ? m : names.text();
            amber.ul.map.StringPass sp = Boolean.getBoolean("amber.noStrings") ? null : new amber.ul.map.StringPass(names);
            for (var pj : pre.values()) mx.analyze(pj);
            long t3 = System.nanoTime();
            System.out.printf("[amber-ul] 烘焙准备: 映射图 %.1f s, mixin 目标 %.1f s, 模组索引 %.1f s, mixin 分析 %.1f s%n", (t1 - t0) / 1e9, (t2 - t1) / 1e9, (t2b - t2) / 1e9, (t3 - t2b) / 1e9);
            int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));
            for (ModInfo mod : todo) {
                boolean rf = hasRefmap(mod);
                Path remapped = dir.resolve("bake-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".cls.jar");
                Set<String> refmaps = refmapNames(mod);
                // the text formats (AW, refmap, MixinExtras @Definition) are rewritten in the same parallel pass: no second jar rewrite
                java.util.function.BiFunction<String, byte[], byte[]> post = (name, b) -> {
                    if (name.endsWith(".class")) return contains(b, "Lcom/llamalad7/mixinextras/expression/Definition") ? remapDefinitions(b, text) : b;
                    if (mod.accessFiles.contains(name)) return remapAccessWidener(new String(b, StandardCharsets.UTF_8), text, m.to).getBytes(StandardCharsets.UTF_8);
                    if (refmaps.contains(name)) return remapRefmap(new String(b, StandardCharsets.UTF_8), text).getBytes(StandardCharsets.UTF_8);
                    return b;
                };
                try { new amber.ul.map.JarRemapper(names, amber.ul.map.JarRemapper.Options.mod(), threads).pre(cv -> mx.annotations(cv, rf)).post(post).strings(sp).run(pre.get(mod), remapped); }
                catch (IOException | RuntimeException e) { throw e; }
                catch (Exception e) { throw new IOException("remap " + mod.jar.getFileName() + ": " + e, e); }
                Path fin = bakedPath(mod, m);
                SafeFiles.publish(remapped, fin);
                out.put(mod, fin);
            }
            System.out.printf("[amber-ul] 烘焙: 重映射+后处理 %.1f s%n", (System.nanoTime() - t3) / 1e9);
            if (sp != null && !sp.stats.isEmpty()) System.out.println("[amber-ul] 字符串: " + new TreeMap<>(sp.stats));
            if (!names.conflicts.isEmpty()) System.out.printf("[amber-ul] 重映射冲突 %d 个 (取父类优先)%n", names.conflicts.size());
        }
    }

    private static boolean hasRefmap(ModInfo mod) throws IOException {
        try (JarFile in = new JarFile(mod.jar.toFile())) {
            for (String cfg : mod.mixinConfigs) {
                JarEntry e = in.getJarEntry(cfg); if (e == null) continue;
                if (JsonParser.parseString(read(in, e)).getAsJsonObject().has("refmap")) return true;
            }
        }
        return false;
    }

    /**
     * MixinExtras Expressions: @Definition(method = "Lowner;name(desc)", field = "Lowner;name:desc") strings are member
     * references tiny-remapper 0.10.x does not know. Without a refmap they are intermediary names (Fabric runs in
     * intermediary, so there they just work); on Amber's Mojmap runtime they must be remapped like refmap values.
     */
    public static byte[] remapDefinitions(byte[] b, amber.ul.map.TextNames m) { return remapDefinitions(b, m, Baker::looksIntermediary); }
    /** Forge 1.20.1 mods: the same strings carry SRG member ids (m_NNN_ / f_NNN_). */
    public static byte[] remapDefinitionsSrg(byte[] b, amber.ul.map.TextNames m) { return remapDefinitions(b, m, s -> amber.ul.map.ForgeCheck.SRG.matcher(s).find()); }
    static byte[] remapDefinitions(byte[] b, amber.ul.map.TextNames m, java.util.function.Predicate<String> looks) {
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        boolean[] changed = {false};
        java.util.function.Consumer<List<AnnotationNode>> walk = new java.util.function.Consumer<List<AnnotationNode>>() {
            public void accept(List<AnnotationNode> l) { if (l != null) for (AnnotationNode a : l) visit(a); }
            @SuppressWarnings("unchecked") void visit(AnnotationNode a) {
                if (a.values == null) return;
                boolean def = a.desc.equals("Lcom/llamalad7/mixinextras/expression/Definition;");
                for (int i = 1; i < a.values.size(); i += 2) {
                    Object v = a.values.get(i);
                    if (v instanceof AnnotationNode n) visit(n);
                    else if (v instanceof List<?> lst) for (int k = 0; k < lst.size(); k++) {
                        Object e = lst.get(k);
                        if (e instanceof AnnotationNode n) visit(n);
                        else if (def && e instanceof String str && looks.test(str)) { ((List<Object>) lst).set(k, remapRef(str, m)); changed[0] = true; }
                    }
                    else if (def && v instanceof String str && looks.test(str)) { a.values.set(i, remapRef(str, m)); changed[0] = true; }
                }
            }
        };
        for (MethodNode mn : cn.methods) { walk.accept(mn.visibleAnnotations); walk.accept(mn.invisibleAnnotations); }
        for (FieldNode fn : cn.fields) { walk.accept(fn.visibleAnnotations); walk.accept(fn.invisibleAnnotations); }
        if (!changed[0]) return b;
        ClassWriter cw = new ClassWriter(0); cn.accept(cw); return cw.toByteArray();
    }
    static boolean looksIntermediary(String s) { return s.contains("class_") || s.contains("method_") || s.contains("field_") || s.contains("comp_"); }
    public static boolean contains(byte[] b, String s) {
        byte[] n = s.getBytes(StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + n.length <= b.length; i++) { for (int k = 0; k < n.length; k++) if (b[i + k] != n[k]) continue outer; return true; }
        return false;
    }

    /** Refmap files named by the mod's mixin configs. */
    private static Set<String> refmapNames(ModInfo mod) throws IOException {
        Set<String> refmaps = new HashSet<>();
        try (JarFile in = new JarFile(mod.jar.toFile())) {
            for (String cfg : mod.mixinConfigs) {
                JarEntry e = in.getJarEntry(cfg); if (e == null) continue;
                JsonObject o = JsonParser.parseString(read(in, e)).getAsJsonObject();
                if (o.has("refmap")) refmaps.add(o.get("refmap").getAsString());
            }
        }
        return refmaps;
    }

    /** Text formats tiny-remapper doesn't know: access wideners, mixin refmaps. */
    private void postProcess(ModInfo mod, Mappings m, Path remapped, Path out) throws IOException {
        Set<String> refmaps = new HashSet<>();
        Path tmp = out.resolveSibling(out.getFileName() + "." + System.nanoTime() + ".tmp");
        try (JarFile in = new JarFile(remapped.toFile())) {
            for (String cfg : mod.mixinConfigs) {
                JarEntry e = in.getJarEntry(cfg); if (e == null) continue;
                JsonObject o = JsonParser.parseString(read(in, e)).getAsJsonObject();
                if (o.has("refmap")) refmaps.add(o.get("refmap").getAsString());
            }
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp))) {
                for (JarEntry e : Collections.list(in.entries())) {
                    if (e.isDirectory()) continue;
                    byte[] b; try (InputStream is = in.getInputStream(e)) { b = SafeFiles.boundedRead(is, SafeFiles.ENTRY_MAX, e.getName()); }
                    String name = e.getName();
                    if (name.endsWith(".class") && contains(b, "Lcom/llamalad7/mixinextras/expression/Definition")) b = remapDefinitions(b, m);
                    if (mod.accessFiles.contains(name)) b = remapAccessWidener(new String(b, StandardCharsets.UTF_8), m).getBytes(StandardCharsets.UTF_8);
                    else if (refmaps.contains(name)) b = remapRefmap(new String(b, StandardCharsets.UTF_8), m).getBytes(StandardCharsets.UTF_8);
                    // TODO(real impl): reflection string literals
                    jos.putNextEntry(SafeFiles.entry(name)); jos.write(b); jos.closeEntry();
                }
            }
        }
        SafeFiles.publish(tmp, out);
    }

    /**
     * Mixins written against a dev namespace (Mojmap/Yarn) name string targets in that namespace (@Mixin(targets = "...")),
     * which only the refmap translates. tiny-remapper's MixinExtension must know the target to remap @Shadow/@Accessor
     * members, so first rewrite those strings to the refmap's (intermediary) value.
     */
    /** {@link #resolveMixinTargets} without the temp jar: entries stay in memory for the Amber remapper. */
    private List<amber.ul.map.JarRemapper.Entry> resolveMixinTargetsInMemory(ModInfo mod) throws IOException {
        Map<String, JsonObject> refByMixin = new HashMap<>(); Set<String> pkgs = new HashSet<>();
        List<amber.ul.map.JarRemapper.Entry> all = amber.ul.map.JarRemapper.read(mod.jar);
        Map<String, byte[]> byName = new HashMap<>();
        for (var e : all) byName.put(e.name(), e.data());
        for (String cfg : mod.mixinConfigs) {
            byte[] c = byName.get(cfg); if (c == null) continue;
            JsonObject o = JsonParser.parseString(new String(c, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("package")) pkgs.add(o.get("package").getAsString().replace('.', '/') + "/");
            byte[] r = o.has("refmap") ? byName.get(o.get("refmap").getAsString()) : null;
            if (r == null) continue;
            JsonObject rm = JsonParser.parseString(new String(r, StandardCharsets.UTF_8)).getAsJsonObject();
            if (rm.has("mappings")) for (var x : rm.getAsJsonObject("mappings").entrySet()) refByMixin.put(x.getKey(), x.getValue().getAsJsonObject());
        }
        if (refByMixin.isEmpty()) return all;
        List<amber.ul.map.JarRemapper.Entry> out = new ArrayList<>(all.size());
        for (var e : all) {
            String n = e.name();
            JsonObject refs = n.endsWith(".class") && pkgs.stream().anyMatch(n::startsWith) ? refByMixin.get(n.substring(0, n.length() - 6)) : null;
            out.add(refs == null ? e : new amber.ul.map.JarRemapper.Entry(n, e.time(), rewriteTargets(e.data(), refs)));
        }
        return out;
    }

    private Path resolveMixinTargets(ModInfo mod, Path out) throws IOException {
        Map<String, JsonObject> refByMixin = new HashMap<>(); Set<String> pkgs = new HashSet<>();
        try (JarFile in = new JarFile(mod.jar.toFile())) {
            for (String cfg : mod.mixinConfigs) {
                JarEntry e = in.getJarEntry(cfg); if (e == null) continue;
                JsonObject o = JsonParser.parseString(read(in, e)).getAsJsonObject();
                if (o.has("package")) pkgs.add(o.get("package").getAsString().replace('.', '/') + "/");
                JarEntry r = o.has("refmap") ? in.getJarEntry(o.get("refmap").getAsString()) : null;
                if (r == null) continue;
                JsonObject rm = JsonParser.parseString(read(in, r)).getAsJsonObject();
                if (rm.has("mappings")) for (var x : rm.getAsJsonObject("mappings").entrySet()) refByMixin.put(x.getKey(), x.getValue().getAsJsonObject());
            }
            try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
                for (JarEntry e : Collections.list(in.entries())) {
                    if (e.isDirectory()) continue;
                    byte[] b; try (InputStream is = in.getInputStream(e)) { b = SafeFiles.boundedRead(is, SafeFiles.ENTRY_MAX, e.getName()); }
                    String n = e.getName();
                    if (n.endsWith(".class") && pkgs.stream().anyMatch(n::startsWith)) {
                        JsonObject refs = refByMixin.get(n.substring(0, n.length() - 6));
                        if (refs != null) b = rewriteTargets(b, refs);
                    }
                    jos.putNextEntry(SafeFiles.entry(n)); jos.write(b); jos.closeEntry();
                }
            }
        }
        return out;
    }

    private static byte[] rewriteTargets(byte[] b, JsonObject refs) {
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        boolean changed = false;
        for (List<AnnotationNode> l : Arrays.asList(cn.invisibleAnnotations, cn.visibleAnnotations)) if (l != null) for (AnnotationNode an : l) {
            if (!an.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;") || an.values == null) continue;
            for (int i = 0; i < an.values.size(); i += 2) if ("targets".equals(an.values.get(i))) {
                @SuppressWarnings("unchecked") List<Object> ts = (List<Object>) an.values.get(i + 1);
                for (int j = 0; j < ts.size(); j++) {
                    JsonElement v = refs.get((String) ts.get(j));
                    if (v != null) { ts.set(j, v.getAsString()); changed = true; }
                }
            }
        }
        if (!changed) return b;
        ClassWriter cw = new ClassWriter(0); cn.accept(cw); return cw.toByteArray();
    }

    /** Cheap scan: does any class in the jar mention a net/minecraft/ type? (constant pool bytes, no parsing) */
    static boolean referencesMinecraft(Path jar) throws IOException {
        byte[] needle = "net/minecraft/".getBytes(StandardCharsets.US_ASCII);
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                byte[] b; try (InputStream is = jf.getInputStream(e)) { b = SafeFiles.boundedRead(is, SafeFiles.ENTRY_MAX, e.getName()); }
                outer: for (int i = 0; i + needle.length <= b.length; i++) { for (int k = 0; k < needle.length; k++) if (b[i + k] != needle[k]) continue outer; return true; }
            }
        }
        return false;
    }

    private static String read(JarFile jf, JarEntry e) throws IOException { try (InputStream is = jf.getInputStream(e)) { return new String(SafeFiles.boundedRead(is, SafeFiles.METADATA_MAX, e.getName()), StandardCharsets.UTF_8); } }

    public static String remapAccessWidener(String text, Mappings m) { return remapAccessWidener(text, m, m.to); }
    public static String remapAccessWidener(String text, amber.ul.map.TextNames m, String to) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\r?\n")) {
            String body = line.replaceAll("#.*", "").trim();
            if (body.isEmpty()) { sb.append(line).append('\n'); continue; }
            String[] p = body.split("\\s+");
            if (p[0].startsWith("accessWidener")) { sb.append("accessWidener\tv2\t").append(to).append('\n'); continue; }
            if (p.length >= 3) {
                String owner = p[2];
                if (p[1].equals("field") && p.length >= 5) { p[3] = m.mapField(owner, p[3], p[4]); p[4] = m.mapDesc(p[4]); }
                if (p[1].equals("method") && p.length >= 5) { p[3] = m.mapMethod(owner, p[3], p[4]); p[4] = m.mapDesc(p[4]); }
                p[2] = m.mapClass(owner);
            }
            sb.append(String.join("\t", p)).append('\n');
        }
        return sb.toString();
    }

    /** Refmap values are intermediary references; the runtime namespace is named, so every value is remapped. Keys stay. */
    public static String remapRefmap(String json, amber.ul.map.TextNames m) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (o.has("mappings")) remapRefTable(o.getAsJsonObject("mappings"), m);
        if (o.has("data")) for (var ns : o.getAsJsonObject("data").entrySet()) remapRefTable(ns.getValue().getAsJsonObject(), m);
        return new GsonBuilder().setPrettyPrinting().create().toJson(o);
    }
    private static void remapRefTable(JsonObject t, amber.ul.map.TextNames m) {
        for (var mixin : t.entrySet()) {
            JsonObject refs = mixin.getValue().getAsJsonObject();
            Map<String, String> extra = new LinkedHashMap<>();
            for (var e : refs.entrySet()) {
                String v = e.getValue().getAsString(), nv = remapRef(v, m);
                refs.addProperty(e.getKey(), nv);
                if (!v.contains(";") && !v.contains("(") && !v.contains(":")) extra.put(v, nv); // class target that resolveMixinTargets wrote into @Mixin
            }
            extra.forEach((k, v) -> { if (!refs.has(k)) refs.addProperty(k, v); });
        }
    }
    /** Forms: a/b/C | La/b/C; | La/b/C;name(desc)ret | La/b/C;name:desc | name(desc) | name:desc | name */
    static String remapRef(String s, amber.ul.map.TextNames m) {
        String owner = null, rest = s;
        int semi = s.indexOf(';'), paren = s.indexOf('(');
        if (s.startsWith("L") && semi > 0 && (paren < 0 || semi < paren)) { owner = s.substring(1, semi); rest = s.substring(semi + 1); }
        else if (paren < 0 && s.indexOf(':') < 0 && s.contains("/")) return m.mapClass(s);
        String prefix = owner == null ? "" : "L" + m.mapClass(owner) + ";";
        if (rest.isEmpty()) return prefix;
        int p = rest.indexOf('('), c = rest.indexOf(':');
        if (p >= 0) { String n = rest.substring(0, p), d = rest.substring(p); return prefix + m.mapMethod(owner, n, d) + m.mapDesc(d); }
        if (c >= 0) { String n = rest.substring(0, c), d = rest.substring(c + 1); return prefix + m.mapField(owner, n, d) + ":" + m.mapDesc(d); }
        String n = m.mapMethod(owner, rest, null); if (n.equals(rest)) n = m.mapField(owner, rest, null);
        return prefix + n;
    }

    /**
     * A static call the loader injects into a game method (raw ASM at bake time, i.e. before Mixin, so a Mixin @Inject at the
     * same instruction always runs AFTER it). at: head | after_super | after_putfield | after_invoke | before_last_invoke_before_ldc;
     * target names the field (Owner.name:desc) or method (Owner.name(desc)) for the after_* forms. passThis passes `this`.
     * The table lives in amber-ul/hooks/<mc>.json so a new MC version is a data change, not a code change.
     */
    public record Hook(String id, String owner, String method, String desc, String at, String target, String ldc, boolean passThis, String call) {}

    /** Stage 2: the game jar for one exact mod set. */
    public Path bakeGame(Path gameJar, AccessRules rules, List<Hook> hooks) throws IOException {
        requireFile(gameJar, "game jar");   // fail before anything is written; never a placeholder hash
        String key = Hash.str(Hash.file(gameJar) + "|" + rules.fingerprint() + "|" + hooks + "|" + PIPELINE);
        Path out = CacheUse.mark(cacheDir.resolve("game").resolve("game-" + key.substring(0, 16) + ".jar"));
        if (Files.exists(out)) { hits++; return out; }
        misses++;
        Files.createDirectories(out.getParent());
        Set<String> owners = rules.owners();
        hooks.forEach(h -> owners.add(h.owner()));
        Path tmp = Files.createTempFile(out.getParent(), "bake", ".tmp");
        try (JarFile in = new JarFile(gameJar.toFile()); JarOutputStream jos = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (JarEntry e : Collections.list(in.entries())) {
                if (e.isDirectory()) continue;
                byte[] b; try (InputStream is = in.getInputStream(e)) { b = SafeFiles.boundedRead(is, SafeFiles.ENTRY_MAX, e.getName()); }
                String n = e.getName();
                String internal = n.endsWith(".class") ? n.substring(0, n.length() - 6) : null;
                if (internal != null && owners.contains(internal)) b = patchClass(b, internal, rules, hooks);
                if (internal != null) { byte[] h = CtorSelfNew.hoist(b); if (h != null) { b = h; hoisted++; } }
                jos.putNextEntry(SafeFiles.entry(n)); jos.write(b); jos.closeEntry();
            }
        }
        SafeFiles.publish(tmp, out);
        if (hoisted > 0) System.out.printf("[amber-ul] 游戏烘焙: %d 个类的构造器里自身类的 new 已移到静态工厂 (Mixin 字段初始化位置)%n", hoisted);
        return out;
    }
    public int hoisted;

    private static byte[] patchClass(byte[] b, String internal, AccessRules rules, List<Hook> hooks) {
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        for (AccessRules.Rule r : rules.rules) {
            if (!r.owner().equals(internal)) continue;
            switch (r.kind()) {
                case CLASS -> { cn.access = AccessRules.widen(cn.access, r.makePublic(), r.stripFinal());
                    // its own InnerClasses entry too (Class.getModifiers reads it), as access-widener's visitInnerClass
                    for (var ic : cn.innerClasses) if (ic.name.equals(internal)) ic.access = AccessRules.widen(ic.access, r.makePublic(), r.stripFinal()); }
                case FIELD -> cn.fields.stream().filter(f -> r.name().equals("*") || f.name.equals(r.name()) && (r.desc() == null || f.desc.equals(r.desc())))
                        .forEach(f -> f.access = AccessRules.widen(f.access, r.makePublic(), r.stripFinal()));
                case METHOD -> cn.methods.stream().filter(mm -> r.name().equals("*") ? !mm.name.startsWith("<") : mm.name.equals(r.name()) && mm.desc.equals(r.desc()))
                        .forEach(mm -> mm.access = AccessRules.widen(mm.access, r.makePublic(), r.stripFinal()));
            }
        }
        for (Hook h : hooks) {
            if (!h.owner().equals(internal)) continue;
            boolean done = false;
            for (MethodNode mn : cn.methods) if (mn.name.equals(h.method()) && mn.desc.equals(h.desc())) {
                int dot = h.call().lastIndexOf('.');
                InsnList call = new InsnList();
                if (h.passThis()) call.add(new VarInsnNode(Opcodes.ALOAD, 0));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, h.call().substring(0, dot), h.call().substring(dot + 1), h.passThis() ? "(Ljava/lang/Object;)V" : "()V", false));
                AbstractInsnNode at = null;
                if (h.at().equals("before_last_invoke_before_ldc")) {
                    // Fabric's EntrypointPatch anchor: the last `target` call before LDC `ldc` (Minecraft.<init>: Thread.currentThread()
                    // before "Backend library: {}"), inserted BEFORE that call.
                    AbstractInsnNode cand = null;
                    for (AbstractInsnNode i : mn.instructions) {
                        if (i instanceof LdcInsnNode l && h.ldc().equals(l.cst)) { at = cand; break; }
                        if (i instanceof MethodInsnNode mi && (mi.owner + "." + mi.name + mi.desc).equals(h.target())) cand = i;
                    }
                    if (at != null) { mn.instructions.insertBefore(at, call); done = true; }
                    continue;
                }
                for (AbstractInsnNode i : mn.instructions) {
                    boolean hit = switch (h.at()) {
                        case "after_super" -> i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && mi.owner.equals(cn.superName);
                        case "after_putfield" -> i instanceof FieldInsnNode fi && (fi.getOpcode() == Opcodes.PUTFIELD || fi.getOpcode() == Opcodes.PUTSTATIC) && (fi.owner + "." + fi.name + ":" + fi.desc).equals(h.target());
                        case "after_invoke" -> i instanceof MethodInsnNode mi && (mi.owner + "." + mi.name + mi.desc).equals(h.target());
                        default -> false;
                    };
                    if (hit) { at = i; break; }
                }
                if (at != null) { mn.instructions.insert(at, call); done = true; }
                else if (h.at().equals("head")) { mn.instructions.insert(call); done = true; }
            }
            if (!done) throw new IllegalStateException("hook " + h.id() + " found no injection point in " + internal + "." + h.method() + h.desc());
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); cn.accept(cw); return cw.toByteArray();
    }
}
