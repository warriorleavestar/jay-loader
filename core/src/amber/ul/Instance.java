package amber.ul;

import amber.ul.bake.*;
import amber.ul.meta.Discovery;
import amber.ul.model.*;
import amber.ul.resolve.Resolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Everything before the class loader: discovery (incl. nested jars) -> resolve -> game remap (once per MC version) ->
 * mod bakes (once per mod file, content-addressed) -> per-mod-set game bake (access rules + hooks).
 * Shared by the Launcher (inline, all cache hits on a normal launch) and `Bake bake-instance` (ahead of time).
 */
public final class Instance {
    public static final String RUNTIME_NS = "named";

    public static final class Request {
        public String mc = "1.21.1";
        public Path client, mappings, cacheRoot, modsDir, libRoot;
        public List<Path> modJars;           // either modsDir or explicit jars
        public List<Path> libs = List.of();  // game libraries (remap classpath + transformable in the universal loader)
        /** The state's libraries (launch from a state): the library set the bake keyed the mod remapper's graph with. */
        public List<Path> bakeLibs;
        /** NeoForge install dir (mc/neoforge: inst/, libraries/, platform-cache/); null = vanilla base. */
        public Path neoforge;
        /** M4: Forge install dir (mc/forge: inst/, libraries/, platform-cache/); Forge 1.20.1, everything remapped to Mojmap. */
        public Path forge;
        /** M4: Amber's Forge FML 47 bridge jar (runs inside the universal loader). */
        public Path forgeBridge;
        /** Forge platform mapping tables (mojang client mappings + intermediary + MCPConfig) and the manifest they are checked
         *  against. Null = prototype layout: <mc>/mappings-cache/<v>/ next to versions/, its manifest.json, else the
         *  loader's bundled requirements table (interface v1.1). */
        public Path forgeMappings, forgeManifest;
        /** Amber's FML bridge jar (runs inside the universal loader, next to the real FML). */
        public Path fmlBridge;
        /** Amber's Fabric bridge mod jar (NeoForge platform + Fabric mods). */
        public Path fabricBridge;
        /** Dedicated server (net.minecraft.server.Main): NeoForge server patches, Dist.DEDICATED_SERVER, Mixin side SERVER. */
        public boolean server;
        public Consumer<String> log = System.out::println;
    }

    public static final class Prepared {
        public final List<ModInfo> baked = new ArrayList<>();
        public final List<Path> jars = new ArrayList<>();   // baked game, baked mods, game libraries (universal loader order)
        public final AccessRules rules = new AccessRules();
        public final Map<String, String> builtins = new LinkedHashMap<>();
        public List<Resolver.Problem> problems = List.of();
        public Mappings.Set3 mset; public Baker baker; public Path bakedGame, namedGame;
        /** Bake-time lambda retargets (mixin class resource -> fixed bytes), served by the class loader. */
        public final Map<String, byte[]> lambdaFixes = new LinkedHashMap<>();
        public String lambdaFingerprint = "-";
        public long tDiscover, tResolve, tBake;   // ns
        public int gameLibs;
        public NeoForgePlatform neoforge;   // non-null when the NeoForge platform layer is active
        public ForgePlatform forge;         // non-null when the Forge (1.20.1) platform layer is active
    }

    /**
     * Dedicated server, as Fabric Loader: mods whose fabric.mod.json environment is "client" are not loaded, and the mixin
     * config list is the server one (object entries with environment "client" dropped, "server" ones kept; the metadata reader
     * builds the client list).
     */
    static List<ModInfo> serverSide(List<ModInfo> found, Request q) {
        List<ModInfo> out = new ArrayList<>();
        for (ModInfo m : found) {
            if (!(m.raw instanceof com.google.gson.JsonObject o) || m.kind != LoaderKind.FABRIC && m.kind != LoaderKind.QUILT) { out.add(m); continue; }
            if (o.has("environment") && o.get("environment").getAsString().equals("client")) { q.log.accept("[amber-ul] 服务端: 跳过仅客户端模组 " + m.id); continue; }
            if (o.has("mixins")) {
                m.mixinConfigs.clear();
                for (var el : o.getAsJsonArray("mixins")) {
                    if (!el.isJsonObject()) { m.mixinConfigs.add(el.getAsString()); continue; }
                    var x = el.getAsJsonObject();
                    if (x.has("environment") && x.get("environment").getAsString().equals("client")) continue;
                    m.mixinConfigs.add(x.get("config").getAsString());
                }
            }
            out.add(m);
        }
        return out;
    }

    public static Prepared prepare(Request q) throws Exception {
        Prepared p = new Prepared();
        long t0 = System.nanoTime();
        p.builtins.put("minecraft", q.mc); p.builtins.put("java", String.valueOf(Runtime.version().feature()));
        p.builtins.put("fabricloader", RuntimeVersions.FABRIC_LOADER);  // platforms are only advertised when emulated: mods probe isModLoaded("quilt_loader"/"neoforge")
        p.builtins.put("mixinextras", RuntimeVersions.MIXINEXTRAS);

        // 1. discovery: every jar, every metadata format, nested jars
        Discovery disc = new Discovery(q.cacheRoot, p.builtins);
        List<Path> jars = new ArrayList<>();
        if (q.modJars != null) jars.addAll(q.modJars);
        else { Files.createDirectories(q.modsDir); try (var s = Files.list(q.modsDir)) { s.filter(x -> x.toString().endsWith(".jar")).sorted().forEach(jars::add); } }
        if (q.neoforge != null) { p.neoforge = new NeoForgePlatform(q.neoforge, q.server); jars.add(0, p.neoforge.universal); }   // NeoForge itself is the "neoforge" mod
        if (q.forge != null) {   // Forge itself is the "forge" mod
            Path fm = q.forgeMappings != null ? q.forgeMappings : q.client.toAbsolutePath().getParent().getParent().getParent().resolve("mappings-cache").resolve(q.mc);
            p.forge = new ForgePlatform(q.forge, fm, q.client, q.forgeManifest);
            jars.add(0, p.forge.universal);
        }
        List<Resolver.Problem> compatProblems = new ArrayList<>();
        if (p.neoforge != null && !Boolean.getBoolean("amber.ul.noForgeCompat")) jars = forgeCompat(q, p, jars, compatProblems);
        List<ModInfo> found = disc.discover(jars);
        // phase 5: Fabric mods on the NeoForge platform get Amber's Fabric bridge (entrypoint window, Fabric API re-bases)
        // and on Forge 47 (fabricbridge-forge.jar: the same entrypoint window)
        if ((p.neoforge != null || p.forge != null) && q.fabricBridge != null && Files.exists(q.fabricBridge)
                && found.stream().anyMatch(m -> m.kind == LoaderKind.FABRIC || m.kind == LoaderKind.QUILT)) found = disc.discover(List.of(q.fabricBridge));
        if (q.server) found = serverSide(found, q);
        // item 3: Quilt mods go through the Fabric pipeline; quilt_loader / QSL dependencies mapped, Quilt API users refused
        found = amber.ul.meta.QuiltCompat.apply(found, p.neoforge != null ? "neoforge" : p.forge != null ? "forge" : "vanilla", compatProblems);
        if (amber.ul.meta.QuiltCompat.aliased + amber.ul.meta.QuiltCompat.refused > 0)
            q.log.accept("[amber-ul] Quilt 模组: " + amber.ul.meta.QuiltCompat.aliased + " 个依赖按 Amber/Fabric API 处理, " + amber.ul.meta.QuiltCompat.refused + " 个用到 Quilt API 的模组不加载");
        new LinkedHashSet<>(disc.log).forEach(l -> q.log.accept("[amber-ul] 发现: " + l));
        if (found.stream().anyMatch(m -> m.kind == LoaderKind.NEOFORGE) && found.stream().noneMatch(m -> m.id.equals("neoforge"))) p.builtins.put("neoforge", "21.1.99");
        if (disc.nestedJars > 0) q.log.accept(String.format("[amber-ul] 嵌套 jar %d 个 -> 模组 %d / 纯库 %d", disc.nestedJars,
                found.stream().filter(m -> m.parent != null && m.kind != LoaderKind.LIBRARY).count(), found.stream().filter(m -> m.kind == LoaderKind.LIBRARY).count()));
        long t1 = System.nanoTime();

        // 2. one resolver for all loaders
        Resolver.Result r = Resolver.resolve(found, p.builtins);
        List<Resolver.Problem> all = new ArrayList<>(disc.problems); all.addAll(compatProblems); all.addAll(r.problems());
        p.problems = all;
        long t2 = System.nanoTime();
        p.tDiscover = t1 - t0; p.tResolve = t2 - t1;
        if (Resolver.blocking(p.problems) && !Boolean.getBoolean("amber.ul.ignoreDeps")) return p;
        List<ModInfo> ordered = new ArrayList<>(r.ordered());
        if (Boolean.getBoolean("amber.ul.ignoreDeps")) {   // census: drop mods this JVM cannot run at all (java requirement unmet)
            Set<String> drop = new HashSet<>();
            for (var pr : p.problems) if ("java".equals(pr.depId()) && pr.severity().equals("error")) drop.add(pr.modId());
            ordered.removeIf(m -> drop.contains(m.id) || drop.contains(m.parent));
            if (!drop.isEmpty()) q.log.accept("[amber-ul] 普查: 跳过 Java 版本不满足的模组 " + drop);
        }

        // 3. game remap (once per MC version) + mod bakes (once per mod file)
        p.baker = new Baker(q.cacheRoot.resolve("baked"));
        p.baker.context = Baker.context(q.client, q.libs);
        p.baker.obfGame = q.client; p.baker.libs = q.bakeLibs != null ? q.bakeLibs : q.libs;   // same graph key at bake and at launch
        p.mset = new Mappings.Set3(q.mappings);
        // vanilla: our tiny-remapper Mojmap jar; NeoForge: ART Mojmap jar + binpatches (its own cache key)
        p.namedGame = p.neoforge != null ? p.neoforge.bakeGame(q.cacheRoot.resolve("baked")) : p.forge != null ? p.forge.bakeGame(q.cacheRoot.resolve("baked"), forgeLibs(p, q))
                : p.baker.remapGame(q.client, q.libs, p.mset.get("official", RUNTIME_NS));
        Map<String, List<ModInfo>> byNs = new LinkedHashMap<>();   // each namespace group is baked by one remapper instance
        for (ModInfo m : ordered) byNs.computeIfAbsent(m.kind == LoaderKind.LIBRARY ? (p.forge != null ? "forge" : RUNTIME_NS) : m.kind == LoaderKind.FORGE && p.forge != null ? "forge" : m.kind.sourceNamespace(), k -> new ArrayList<>()).add(m);
        Map<ModInfo, Path> bakedJars = new HashMap<>();
        for (var g : byNs.entrySet()) {
            String ns = g.getKey();
            if (ns.equals("forge")) {   // M4: Forge + Forge mods (+ their JarJar libraries), one Amber remap forge -> Mojmap
                List<Path> in = new ArrayList<>(); for (ModInfo m : g.getValue()) if (!in.contains(m.jar)) in.add(m.jar);
                Map<Path, Path> outJars = p.forge.bakeMods(q.cacheRoot.resolve("baked"), in, forgeLibs(p, q));
                for (ModInfo m : g.getValue()) bakedJars.put(m, outJars.get(m.jar));
                continue;
            }
            Mappings mp = ns.equals(RUNTIME_NS) ? Mappings.identity(ns) : p.mset.get(ns, RUNTIME_NS);
            bakedJars.putAll(p.baker.bakeMods(g.getValue(), mp, () -> {
                try { List<Path> l = new ArrayList<>(); l.add(p.baker.remapGame(q.client, q.libs, p.mset.get("official", ns))); l.addAll(q.libs); return l; }
                catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        for (ModInfo m : ordered) {
            Path bj = bakedJars.get(m);
            p.baked.add(rebind(m, bj)); p.jars.add(bj);
            try (var jf = new java.util.jar.JarFile(bj.toFile())) {
                for (String f : m.accessFiles) {
                    String txt = new String(jf.getInputStream(jf.getJarEntry(f)).readAllBytes(), StandardCharsets.UTF_8);
                    if (txt.startsWith("accessWidener")) p.rules.parseAccessWidener(txt, m.id); else p.rules.parseAccessTransformer(txt, m.id);
                }
            }
        }
        // 4. game bake for this exact mod set: access rules + the loader's own hooks (cheap pass over the remapped jar)
        // NeoForge coremods run on the patched platform jar BEFORE access rules: a Fabric access widener can make a field the
        // coremod rewrites non-private (e.g. Biome.climateSettings), and ReplaceFieldWithGetterAccess refuses such a field
        if (p.neoforge != null && q.fmlBridge != null) p.namedGame = applyCoreMods(p, q, p.namedGame);
        if (p.forge != null && q.forgeBridge != null && !Boolean.getBoolean("amber.ul.noForgeCoreMods")) p.namedGame = applyForgeCoreMods(p, q, p.namedGame);
        p.bakedGame = p.baker.bakeGame(p.namedGame, p.rules, Launcher.hookTable(q.mc));
        p.jars.add(0, p.bakedGame);
        if (p.neoforge != null && !Boolean.getBoolean("amber.ul.noLambdaRetarget")) lambdaRetarget(q, p);
        // Game libraries (DFU, Brigadier, authlib, LWJGL, netty ...) also live in the universal loader, as with Fabric's Knot,
        // so mixins can target them. Logging stays parent-only (one log4j/slf4j for loader and game).
        Path libRoot = q.libRoot != null ? q.libRoot.toAbsolutePath().normalize()
                : q.client.toAbsolutePath().getParent().getParent().getParent().resolve("libraries").normalize();
        for (Path l : q.libs) {
            String s = l.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (!l.toAbsolutePath().normalize().startsWith(libRoot) || s.contains("/org/apache/logging/") || s.contains("/org/slf4j/")) continue;
            p.jars.add(l); p.gameLibs++;
        }
        if (p.forge != null) {   // FML 47, eventbus, coremods, night-config ... + bridge, + module-info-only services
            p.jars.addAll(p.forge.libraries); if (q.forgeBridge != null) p.jars.add(q.forgeBridge);
            Path ms = amber.ul.fml.ModuleServices.generate(p.forge.libraries, q.cacheRoot.resolve("baked").resolve("module-services"));
            if (ms != null) p.jars.add(ms);
        }
        if (p.neoforge != null) {   // FML, bus, night-config, securejarhandler ... and Amber's bridge live in the universal loader too
            p.jars.addAll(p.neoforge.libraries);
            if (q.fmlBridge != null) p.jars.add(q.fmlBridge);
        }
        p.tBake = System.nanoTime() - t2;
        return p;
    }

    /**
     * Fabric/Quilt mixins naming a game lambda ({@code lambda$tick$32}) on the NeoForge platform: NeoForge's patches renumber
     * a class's lambdas, so the name can be gone or point at another lambda. MixinFixer's lambda pass matches each one by
     * outer method, descriptor and body against the patched game and retargets the selector at bake time (no census of the
     * mod set needed). Cached per baked mod jar + game. A lambda that cannot be matched is a bake problem
     * {@code mixin_lambda_unresolved} instead of a Mixin crash at launch. Test switch: -Damber.ul.noLambdaRetarget=true.
     */
    static void lambdaRetarget(Request q, Prepared p) throws Exception {
        Path dir = q.cacheRoot.resolve("lambda-retarget");
        Path clean = p.neoforge.srg, patched = p.namedGame;   // clean Mojmap game / patched + coremods (lambdas are not touched by access rules)
        StringBuilder fp = new StringBuilder(MixinFixer.LAMBDA_PASS);
        int retargeted = 0;
        // injectors the Fabric API bridge manifest or a compat rule already takes out (vetoed mixin, stripped/dropped handler):
        // their lambda does not matter at launch
        BridgeManifest bm = BridgeManifest.load("neoforge", q.mc, p.baked);
        Set<String> gone = new HashSet<>(bm.veto.keySet());
        for (BridgeManifest.Strip s : bm.strips) gone.add(s.mixin() + "#" + s.handler());
        try (var in = Launcher.class.getResourceAsStream("/amber-ul/compat/neoforge-" + q.mc + ".json")) {
            if (in != null) for (var e : com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rules")) {
                var r = e.getAsJsonObject();
                if ("drop_injector".equals(r.get("via").getAsString())) gone.add(r.get("mixin").getAsString() + "#" + r.get("handler").getAsString());
            }
        }
        for (ModInfo m : p.baked) {
            if ((m.kind != LoaderKind.FABRIC && m.kind != LoaderKind.QUILT) || m.mixinConfigs.isEmpty()) continue;
            String key = Hash.str(MixinFixer.LAMBDA_PASS + "|" + m.jar.getFileName() + "|" + m.sha256 + "|" + patched.getFileName() + "|" + clean.getFileName()).substring(0, 20);
            Path out = CacheUse.mark(dir.resolve(key + ".jar"));
            if (!Files.exists(out)) MixinFixer.lambdaPass(clean, patched, m.jar, out);
            try (var jf = new java.util.jar.JarFile(out.toFile(), false)) {
                for (var e : java.util.Collections.list(jf.entries())) {
                    byte[] b = jf.getInputStream(e).readAllBytes();
                    if (e.getName().endsWith(".class")) { p.lambdaFixes.put(e.getName(), b); fp.append('|').append(key); continue; }
                    if (!e.getName().equals("amber-lambda.json")) continue;
                    for (var el : com.google.gson.JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonArray()) {
                        var r = el.getAsJsonObject(); String cat = r.get("category").getAsString();
                        if (gone.contains(r.get("mixin").getAsString()) || gone.contains(r.get("mixin").getAsString() + "#" + r.get("handler").getAsString())) continue;
                        String what = r.get("mixin").getAsString() + "#" + r.get("handler").getAsString() + " -> " + r.get("target").getAsString() + ": " + r.get("detail").getAsString();
                        if (cat.equals("lambda_retarget")) { retargeted++; q.log.accept("[amber-ul] mixin lambda 重定向 (" + m.id + "): " + what); }
                        else if (cat.equals("lambda_unresolved"))
                            p.problems.add(new Resolver.Problem("mixin_lambda_unresolved", "error", m.id, m.jar.toString(), "minecraft", "", "",
                                "模组 " + m.id + " 的 mixin 指向的游戏 lambda 在 NeoForge 上找不到唯一对应, 启动时会失败: " + what));
                        else if (cat.equals("lambda_unverified")) q.log.accept("[amber-ul] mixin lambda 未能确认 (" + m.id + "), 保持原样: " + what);
                        else if (r.get("fixed").getAsBoolean()) { retargeted++; q.log.accept("[amber-ul] mixin 烘焙时修正 " + cat + " (" + m.id + "): " + what); }
                        else p.problems.add(new Resolver.Problem("mixin_target_changed", "warning", m.id, m.jar.toString(), "minecraft", "", "",
                                "模组 " + m.id + " 的 mixin 注入点在 NeoForge 上变了, 烘焙时无法自动修正 (" + cat + "), 启动时可能失败: " + what));
                    }
                }
            }
        }
        p.lambdaFingerprint = Hash.str(fp.toString()).substring(0, 12);
        if (retargeted > 0) q.log.accept("[amber-ul] mixin 烘焙时修正 (lambda 重定向等): " + retargeted + " 个注入器 (烘焙时, " + p.lambdaFixes.size() + " 个 mixin 类)");
    }

    /** Remap class path for the Forge side: game libraries + Forge's runtime libraries. */
    /** warm (interface v1.2): the mapping graphs this instance's bakes use, built or mapped in from the mapping cache. */
    public static void warmGraphs(Prepared p, Request q) throws java.io.IOException {
        if (p.forge != null) p.forge.tree(forgeLibs(p, q));
        // the mod remapper's graph: always on the vanilla base; on Forge when Fabric/Quilt mods are baked there (Fabric bridge)
        boolean fabricMods = p.baked != null && p.baked.stream().anyMatch(m -> m.kind == LoaderKind.FABRIC || m.kind == LoaderKind.QUILT);
        if (p.baker != null && p.mset != null && (p.forge == null || fabricMods)) p.baker.warmGraph(p.mset.get("intermediary", Instance.RUNTIME_NS));
    }

    static List<Path> forgeLibs(Prepared p, Request q) {
        List<Path> l = new ArrayList<>(q.libs);
        for (Path x : p.forge.libraries) if (!l.contains(x)) l.add(x);
        return l;
    }

    /**
     * NeoForge's Java coremods (ICoreMod services in nested jars, e.g. neoforge-coremods) applied to the patched platform jar at
     * bake time, before access rules and Mixin. Cached by (platform jar, coremod jars).
     */
    static Path applyCoreMods(Prepared p, Request q, Path in) throws Exception {
        List<Path> cms = new ArrayList<>();
        for (ModInfo m : p.baked) try (var jf = new java.util.jar.JarFile(m.jar.toFile())) {
            if (jf.getJarEntry("META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod") != null) cms.add(m.jar);
        }
        if (cms.isEmpty()) return in;
        StringBuilder key = new StringBuilder(in.getFileName().toString());
        for (Path c : cms) key.append('|').append(Hash.file(c));
        Path out = CacheUse.mark(in.resolveSibling(in.getFileName().toString().replace(".jar", "") + "-cm-" + Hash.str(key.toString()).substring(0, 12) + ".jar"));
        if (Files.exists(out)) return out;
        List<java.net.URL> urls = new ArrayList<>();
        for (Path c : cms) urls.add(c.toUri().toURL());
        urls.add(q.fmlBridge.toUri().toURL());
        for (Path l : p.neoforge.libraries) {
            String s = l.toString().replace(java.io.File.separatorChar, '/');
            if (s.contains("/cpw/mods/modlauncher/") || s.contains("/fancymodloader/loader/") || s.contains("/net/neoforged/coremods/") || s.contains("/accesstransformers/")) urls.add(l.toUri().toURL());
        }
        long t = System.nanoTime();
        try (var cl = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), Instance.class.getClassLoader())) {
            Object r = Class.forName("amber.ul.fml.CoreMods", true, cl).getMethod("apply", Path.class, Path.class, ClassLoader.class).invoke(null, in, out, cl);
            q.log.accept(String.format("[amber-ul] %s (%.1f s)", r, (System.nanoTime() - t) / 1e9));
        }
        return out;
    }

    /**
     * Forge 1.20.1's JS coremods (Forge universal + any mod jar with META-INF/coremods.json) applied to the Mojmap game jar at
     * bake time by the real coremods engine (amber.ul.fmlforge.ForgeCoreMods), before access rules and Mixin.
     * Isolated loader: Forge's runtime libraries + the Forge bridge; modlauncher's Launcher gets Jpms.forgeLauncher (srg names).
     */
    static Path applyForgeCoreMods(Prepared p, Request q, Path in) throws Exception {
        List<Path> srcs = new ArrayList<>(); srcs.add(p.forge.universal);
        for (ModInfo m : p.baked) {
            Path j = m.originalJar != null ? m.originalJar : m.jar;
            if (srcs.contains(j)) continue;
            try (var jf = new java.util.jar.JarFile(j.toFile())) { if (jf.getJarEntry("META-INF/coremods.json") != null) srcs.add(j); }
        }
        StringBuilder key = new StringBuilder(in.getFileName().toString()).append("|fcm-1|").append(Hash.file(p.forge.srgTable));
        for (Path c : srcs) key.append('|').append(Hash.file(c));
        Path out = CacheUse.mark(in.resolveSibling(in.getFileName().toString().replace(".jar", "") + "-fcm-" + Hash.str(key.toString()).substring(0, 12) + ".jar"));
        if (Files.exists(out)) return out;
        List<java.net.URL> urls = new ArrayList<>();
        urls.add(q.forgeBridge.toUri().toURL());
        for (Path l : p.forge.libraries) urls.add(l.toUri().toURL());
        for (Path l : p.forge.parentJars) urls.add(l.toUri().toURL());   // parent-first: a launch already has them on the system class path
        for (Path l : q.libs) urls.add(l.toUri().toURL());                // log4j, guava ...: game libraries (system class path at launch, not in a Bake process)
        var launcherTx = amber.ul.fml.Jpms.forgeLauncher();
        long t = System.nanoTime();
        try (var cl = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new), Instance.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (!name.equals("cpw.mods.modlauncher.Launcher")) return super.findClass(name);
                try (var s = getResourceAsStream(name.replace('.', '/') + ".class")) {
                    if (s == null) throw new ClassNotFoundException(name);
                    byte[] b = launcherTx.transform(name.replace('.', '/'), s.readAllBytes());
                    return defineClass(name, b, 0, b.length);
                } catch (java.io.IOException e) { throw new ClassNotFoundException(name, e); }
            }
        }) {
            Object r = Class.forName("amber.ul.fmlforge.ForgeCoreMods", true, cl).getMethod("apply", Path.class, Path.class, List.class, Path.class).invoke(null, in, out, srcs, p.forge.srgTable);
            if (r == null) return in;
            q.log.accept(String.format("[amber-ul] %s (%.1f s)", r, (System.nanoTime() - t) / 1e9));
        }
        return out;
    }

    /**
     * Forge 52 (Forge 1.21.1) mods on the NeoForge 21.1 platform: each Forge-format jar is converted once (ForgeCompat:
     * Forge API references -> NeoForge or Amber's forgecompat classes, mods.toml -> neoforge.mods.toml) and then is an
     * ordinary NeoForge mod. A jar whose converted references do not all resolve is left out with an error naming them.
     * Test switch: -Damber.ul.noForgeCompat=true (Forge jars are passed through unconverted).
     */
    static List<Path> forgeCompat(Request q, Prepared p, List<Path> jars, List<Resolver.Problem> problems) throws Exception {
        List<Path> forge = new ArrayList<>();
        for (Path j : jars) if (!j.equals(p.neoforge.universal) && ForgeCompat.isForgeJar(j)) forge.add(j);
        if (forge.isEmpty()) return jars;
        Path shim = q.fmlBridge != null ? LoaderHome.bridge("forgecompat.jar") : null;
        if (shim == null || !Files.exists(shim)) { q.log.accept("[amber-ul] Forge 1.21.1 模组: 缺少 forgecompat.jar, 不转换"); return jars; }
        Set<String> shims = ForgeCompat.shimIndex(shim);
        Path dir = q.cacheRoot.resolve("forgecompat");
        ForgeCompat.Index platform = null;
        List<Path> out = new ArrayList<>(jars);
        for (Path j : forge) {
            String key = Hash.str(ForgeCompat.PIPELINE + "|" + Hash.file(shim) + "|" + Hash.file(j)).substring(0, 16);
            Path conv = dir.resolve(key + "-" + j.getFileName().toString().replace(' ', '_')), bad = conv.resolveSibling(conv.getFileName() + ".unresolved.txt");
            CacheUse.mark(conv); CacheUse.mark(bad);
            if (!Files.exists(conv) || !Files.exists(bad)) {
                int n = ForgeCompat.convert(j, conv, shims);
                if (platform == null) {
                    platform = new ForgeCompat.Index().add(shim).add(p.neoforge.universal).add(p.neoforge.bakeGame(q.cacheRoot.resolve("baked")));
                    for (Path l : p.neoforge.libraries) if (l.toString().replace('\\', '/').contains("/net/neoforged/")) platform.add(l);
                }
                Files.writeString(bad, String.join("\n", ForgeCompat.verify(conv, platform)));
                q.log.accept("[amber-ul] Forge 1.21.1 模组 " + j.getFileName() + " 转成 NeoForge 格式: " + n + " 个类改了引用");
            }
            String txt = Files.readString(bad).strip();
            out.remove(j);
            if (txt.isEmpty()) out.add(conv);
            else {
                List<String> l = List.of(txt.split("\n"));
                problems.add(new Resolver.Problem("forge_api_missing", "error", j.getFileName().toString(), j.toString(), "forge", "", "", "Forge 1.21.1 模组 " + j.getFileName()
                    + " 用到了 Amber 还没在 NeoForge 上提供的 Forge API (" + l.size() + " 处), 先不加载: " + String.join(", ", l.subList(0, Math.min(6, l.size()))) + (l.size() > 6 ? " ..." : "")));
            }
        }
        if (out.stream().anyMatch(x -> x.startsWith(dir))) out.add(shim);
        return out;
    }

    static ModInfo rebind(ModInfo m, Path jar) {
        ModInfo n = new ModInfo(m.id, m.version, m.kind, jar);
        n.deps.addAll(m.deps); n.entrypoints.putAll(m.entrypoints); n.mixinConfigs.addAll(m.mixinConfigs); n.accessFiles.addAll(m.accessFiles); n.provides.addAll(m.provides); n.sha256 = m.sha256;
        n.raw = m.raw; n.parent = m.parent; n.name = m.name; n.description = m.description; n.originalJar = m.jar;
        return n;
    }
}
