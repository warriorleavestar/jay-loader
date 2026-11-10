package amber.ul;

import amber.ul.bake.*;
import amber.ul.meta.MetadataReaders;
import amber.ul.mixin.MixinRuntime;
import amber.ul.model.*;
import amber.ul.personality.*;
import amber.ul.resolve.Resolver;
import amber.ul.runtime.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Amber universal loader entry point. Amber puts this loader + the game's libraries on the classpath and uses
 * amber.ul.Launcher as the main class; the game jar itself is NOT on the classpath.
 *
 *   --client <official client.jar> --mappings <official/intermediary/named tiny> --mods <dir> --cache <dir>
 *   [--main net.minecraft.client.main.Main] [--mc 1.21.1] [--classcache safe|full|off] [-- game args]
 */
public final class Launcher {
    public static final String RUNTIME_NS = "named";

    public static void main(String[] argv) throws Throwable {
        long t0 = System.nanoTime();
        Map<String, String> a = new HashMap<>(); List<String> gameArgs = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) { if (argv[i].equals("--")) { gameArgs.addAll(Arrays.asList(argv).subList(i + 1, argv.length)); break; } a.put(argv[i].substring(2), argv[++i]); }
        String mainClass = a.getOrDefault("main", "net.minecraft.client.main.Main");
        Instance.Request q = new Instance.Request();
        for (String e : System.getProperty("java.class.path").split(File.pathSeparator)) if (e.endsWith(".jar")) { if (q.libs.isEmpty()) q.libs = new ArrayList<>(); q.libs.add(Path.of(e)); }
        RuntimeProblems.open(a.containsKey("state") ? Path.of(a.get("state")) : null);   // interface v1.3: problems as JSON lines
        if (a.containsKey("state")) {
            // written by `Bake bake-instance`; inputs are re-checked through the content-addressed caches (all hits normally)
            var st = com.google.gson.JsonParser.parseString(Files.readString(Path.of(a.get("state")))).getAsJsonObject();
            q.mc = st.get("mcVersion").getAsString(); q.client = Path.of(st.get("gameJar").getAsString());
            q.mappings = Path.of(st.get("mappings").getAsString()); q.cacheRoot = Path.of(st.get("cacheRoot").getAsString());
            if (st.has("libRoot")) q.libRoot = Path.of(st.get("libRoot").getAsString());
            q.modJars = new ArrayList<>(); for (var m : st.getAsJsonArray("mods")) q.modJars.add(Path.of(m.getAsString()));
            if (st.has("libraries")) { q.bakeLibs = new ArrayList<>(); for (var l : st.getAsJsonArray("libraries")) q.bakeLibs.add(Path.of(l.getAsString())); }
            // interface v1.1: platform + mapping cache (absent in v1 state files = vanilla base, default cache root)
            if (st.has("mappingCache") && System.getProperty("amber.ul.mappingCache") == null) System.setProperty("amber.ul.mappingCache", st.get("mappingCache").getAsString());
            if (st.has("platform")) {
                var pf = st.getAsJsonObject("platform"); String kind = pf.get("kind").getAsString();
                if (kind.equals("forge")) { a.putIfAbsent("forge", pf.get("installDir").getAsString());
                    if (pf.has("mappingsDir")) q.forgeMappings = Path.of(pf.get("mappingsDir").getAsString());
                    if (pf.has("manifest")) q.forgeManifest = Path.of(pf.get("manifest").getAsString()); }
                if (kind.equals("neoforge")) a.putIfAbsent("neoforge", pf.get("installDir").getAsString());
            }
        } else {
            q.client = Path.of(a.get("client")); q.modsDir = Path.of(a.get("mods")); q.cacheRoot = Path.of(a.get("cache"));
            q.mappings = Path.of(a.get("mappings")); q.mc = a.getOrDefault("mc", "1.21.1");
            if (a.containsKey("libroot")) q.libRoot = Path.of(a.get("libroot"));
        }
        q.server = mainClass.equals("net.minecraft.server.Main");
        if (a.containsKey("forge")) { q.forge = Path.of(a.get("forge")); q.forgeBridge = LoaderHome.bridge("fmlbridge-forge.jar");
            q.fabricBridge = LoaderHome.bridge("fabricbridge-forge.jar"); }   // Fabric mods on Forge 47
        if (a.containsKey("neoforge")) {
            q.neoforge = Path.of(a.get("neoforge"));
            q.fmlBridge = LoaderHome.bridge("fmlbridge.jar");
            q.fabricBridge = LoaderHome.bridge("fabricbridge.jar");
        }
        Path cache = q.cacheRoot; String mc = q.mc;
        Instance.Prepared prep = Instance.prepare(q);
        for (var pr : prep.problems) {
            System.err.println("[amber-ul] 依赖问题 (" + pr.code() + ", " + pr.severity() + "): " + pr.message());
            RuntimeProblems.report(pr.severity(), pr.code(), pr.modId(), pr.message(), "file", pr.file(), "dependency", pr.depId(), "range", pr.range(), "found", pr.found());
        }
        // a launch that had to bake something the state's bake did not leave (mods changed, cache cleaned): warm again later
        if (prep.baker != null && prep.baker.misses > 0) RuntimeProblems.report("info", "launch_rebaked", null, prep.baker.misses + " bake(s) done at launch; run warm again", "bakeMisses", String.valueOf(prep.baker.misses));
        if (Resolver.blocking(prep.problems) && !a.containsKey("census")) System.exit(2);   // census: mixin data only, dependency problems are reported, not fatal
        List<ModInfo> baked = prep.baked; List<Path> jars = prep.jars; List<Path> libs = q.libs;
        Map<String, String> builtins = prep.builtins; AccessRules rules = prep.rules; Baker baker = prep.baker; Mappings.Set3 mset = prep.mset;
        long t1 = t0 + prep.tDiscover, t2 = t1 + prep.tResolve, t3 = t2 + prep.tBake;
        System.out.printf("[amber-ul] 游戏库进入通用类加载器: %d 个 jar%n", prep.gameLibs);

        // 5. one class loader, real Mixin, launch-time class cache
        boolean mixin = baked.stream().anyMatch(m -> !m.mixinConfigs.isEmpty()) || Boolean.getBoolean("amber.ul.forceMixin");
        List<ClassTransformer> tx = new ArrayList<>();
        if (prep.neoforge != null) tx.add(amber.ul.fml.Jpms.redirect());   // FML: module-layer lookups -> universal loader
        if (prep.forge != null) { tx.add(amber.ul.fml.Jpms.redirect()); tx.add(amber.ul.fml.Jpms.forgeLauncher()); }   // M4: FML 47 + modlauncher's Launcher answers
        // isolation + bake-time mixin fixes for exactly this platform + mod set (phase 4)
        String isoKey = isolationKey(prep, mc);
        Path isoFile = cache.toAbsolutePath().getParent().resolve("mixin-isolation").resolve(isoKey + ".json");
        Path fixJar = isoFile.resolveSibling(isoKey + ".fixes.jar");
        if (a.containsKey("census") && Boolean.getBoolean("amber.ul.censusFresh")) { Files.deleteIfExists(isoFile); Files.deleteIfExists(fixJar); }   // census round 1 starts clean
        String fixFp = (Files.exists(isoFile) ? Hash.file(isoFile) : "-") + "|" + (Files.exists(fixJar) ? Hash.file(fixJar) : "-") + "|compat=" + compatFingerprint(mc) + "|bridge=" + BridgeManifest.fingerprint(q.neoforge != null ? "neoforge" : q.forge != null ? "forge" : null, mc);
        if (mixin) tx.add(MixinRuntime.transformer(RuntimeVersions.fingerprint() + "|side=" + (q.server ? "SERVER" : "CLIENT") + "|loader=" + loaderBuild() + "|fixes=" + fixFp));
        if (prep.forge != null) tx.add(new amber.ul.fml.LaunchPluginHost("forge-" + prep.forge.forge + "|side=" + (q.server ? "SERVER" : "CLIENT")));   // M4: modlauncher launch plugins
        String cacheMode = a.getOrDefault("classcache", "safe");
        UniversalClassLoader cl = new UniversalClassLoader(jars, tx, cache.resolve("classcache"), cacheMode, Launcher.class.getClassLoader());
        boolean warm = cl.cacheWarm();
        cl.alsoInParent.addAll(libs);
        Thread.currentThread().setContextClassLoader(cl);
        amber.ul.fml.Jpms.loader = cl;
        long t4 = System.nanoTime();
        if (prep.neoforge != null) applyCompatRules(mc, baked, builtins);   // phase 4: semantic-risk feature vetoes (before mods read options)
        ModRegistry.server = q.server; ModRegistry.gameJar = prep.bakedGame;
        ModRegistry.init(baked, builtins);
        FabricFacade.mappings = mset;
        ModRegistry.gameDir = Path.of(arg(gameArgs, "--gameDir", "."));
        System.setProperty("amber.ul.isoFile", isoFile.toString());
        if (Files.exists(fixJar)) try (var jf = new java.util.jar.JarFile(fixJar.toFile(), false)) {
            for (var e : Collections.list(jf.entries())) if (e.getName().endsWith(".class")) cl.overrides.put(e.getName(), amber.ul.bake.SafeFiles.boundedRead(jf.getInputStream(e), amber.ul.bake.SafeFiles.ENTRY_MAX, e.getName()));
            System.out.printf("[amber-ul] 烘焙时 mixin 修正: %d 个 mixin 类 (%s)%n", cl.overrides.size(), fixJar.getFileName());
        }
        if (prep.neoforge != null) applyInjectorDrops(mc, baked, cl);   // compat rules: injectors superseded by a NeoForge system
        if (prep.neoforge != null || prep.forge != null) applyBridgeManifest(prep.neoforge != null ? "neoforge" : "forge", mc, baked, cl);   // phase 5: Fabric API bridge vetoes / strips / skipped entrypoints
        for (ModInfo m : baked) amber.ul.mixin.AmberMixinErrorHandler.BAKED_JARS.add(m.jar.toAbsolutePath().toString());
        if (!a.containsKey("census") && System.getProperty("amber.ul.mixinExclude") == null && Files.exists(isoFile)) {
            System.setProperty("amber.ul.mixinExclude", isoFile.toString());
            amber.ul.mixin.AmberMixinErrorHandler.reportIsolated(isoFile);
        }
        if (prep.forge != null) {   // M4: Forge mods' mixin plugins read FML (LoadingModList, versions) at Mixin boot: FML first
            long s = System.nanoTime();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("gameDir", Path.of(arg(gameArgs, "--gameDir", ".")).toAbsolutePath()); cfg.put("gameJar", prep.bakedGame);
            List<Path> fj = new ArrayList<>();
            for (ModInfo m : baked) if (m.kind == LoaderKind.FORGE && !fj.contains(m.jar)) { if (m.id.equals("forge")) fj.add(0, m.jar); else fj.add(m.jar); }
            cfg.put("modJars", fj); cfg.put("forge", prep.forge.forge); cfg.put("mc", prep.forge.mc); cfg.put("mcp", prep.forge.mcp); cfg.put("srgTable", prep.forge.srgTable);
            Object r = Class.forName("amber.ul.fmlforge.ForgeFmlBridge", true, cl).getMethod("prepare", Map.class).invoke(null, cfg);
            System.out.printf("[amber-ul] %s (%.1f ms)%n", r, ms(System.nanoTime() - s));
        }
        // Mixin boots after the mod registry: config plugins run in select() (triggered at boot) and query FabricLoader
        MixinRuntime.server = q.server; MixinRuntime.forge = prep.forge != null;
        if (mixin) MixinRuntime.boot(cl, baked);
        if (prep.forge != null) {
            amber.ul.fml.LaunchPluginHost.install(cl);   // after Mixin's generator: launch plugins first, then Mixin
            Class.forName("amber.ul.fmlforge.ForgeFmlBridge", true, cl).getMethod("afterMixinBoot").invoke(null);
        }
        if (a.containsKey("census")) {   // offline mixin census: apply every mixin to its targets (no game window), write JSON, exit
            long s0 = System.nanoTime();
            amber.ul.mixin.AmberMixinService.noInit = true;
            if (mixin) MixinRuntime.censusAudit(cl);
            amber.ul.mixin.AmberMixinErrorHandler.writeCensus(Path.of(a.get("census")), cl.changed, prep.neoforge != null ? "neoforge-" + prep.neoforge.neoForge : prep.forge != null ? "forge-" + prep.forge.forge : "vanilla", (System.nanoTime() - s0) / 1_000_000);
            amber.ul.mixin.AmberMixinErrorHandler.writeIsolation(isoFile, cl);
            System.out.println("[amber-ul] 隔离文件: " + isoFile);
            System.out.printf("[amber-ul] 普查完成: 失败 %d, 被改动类 %d -> %s%n", amber.ul.mixin.AmberMixinErrorHandler.RECORDS.size(), cl.changed.size(), a.get("census"));
            Runtime.getRuntime().halt(0);
        }
        List<Personality> ps = List.of(new FabricPersonality());   // NeoForge/Forge mods: real FML, driven by NeoForge's own hooks (fml bridge)
        long[] tInit = new long[1], tPre = new long[1];
        Runnable saveCache = () -> { try { cl.saveCache(); } catch (Exception e) { e.printStackTrace(); } };
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.write(cache.resolve("changed-classes-exit.txt"), new ArrayList<>(cl.changed)); } catch (Exception e) { e.printStackTrace(); }
            System.out.printf("[amber-ul] 退出: 本次共被 Mixin 改动的类 %d%n", cl.changed.size());
            saveCache.run();
        }, "amber-ul-cache"));
        boolean fabricBridged = (prep.neoforge != null || prep.forge != null) && baked.stream().anyMatch(m -> m.id.equals("amber_fabric_bridge"));
        Personality.Side side = mainClass.equals("net.minecraft.server.Main") ? Personality.Side.SERVER : Personality.Side.CLIENT;
        GameHooks.listen(ev -> {
            switch (ev) {
                case START_CLIENT, START_SERVER -> {
                    long s = System.nanoTime();
                    for (Personality p : ps) {
                        Runnable run = () -> { long s1 = System.nanoTime(); try { p.start(side, baked.stream().filter(m -> p.accepts(m.kind)).toList(), cl); } catch (Exception e) { throw new RuntimeException("mod init failed", e); } tInit[0] += System.nanoTime() - s1; };
                        // phase 5: on NeoForge, Fabric init runs in NeoForge's registration window (bridge: HEAD of GameData.postRegisterEvents)
                        if (p instanceof FabricPersonality && fabricBridged) FabricPersonality.defer(() -> { System.out.println("[amber-fabric-bridge] Fabric 入口点在 " + (prep.forge != null ? "Forge" : "NeoForge") + " 注册窗口运行 (GameData.postRegisterEvents 开头)"); run.run(); });
                        else run.run();
                    }
                }
                case TITLE_SCREEN -> {
                    if (FabricPersonality.pending()) { System.err.println("[amber-ul] 问题 fabric_init_not_run (error): 平台注册窗口 (GameData.postRegisterEvents) 没有到达, Fabric 入口点没有运行");
                        RuntimeProblems.report("error", "fabric_init_not_run", null, "the platform registration window (GameData.postRegisterEvents) was not reached; Fabric entrypoints did not run"); }
                    System.out.printf("[amber-ul] 到达标题画面: 自 JVM 启动 %d ms | 加载器: 发现 %.1f | 解析 %.1f | 烘焙 %.1f | 类加载器+Mixin 引导 %.1f | preLaunch %.1f | 模组初始化 %.1f (ms)%n",
                            GameHooks.sinceJvmStart(), ms(t1 - t0), ms(t2 - t1), ms(t3 - t2), ms(t4 - t3), ms(tPre[0]), ms(tInit[0]));
                    System.out.printf("[amber-ul] 类: 走缓存(跳过变换) %d, 现场变换 %d; 类缓存 %s (%s)%n", cl.fromCache, cl.transformed, warm ? "热" : "冷", cacheMode);
                    if (mixin && Boolean.getBoolean("amber.ul.mixinAudit")) {
                        long s = System.nanoTime(); int before = cl.changed.size();
                        MixinRuntime.audit();
                        System.out.printf("[amber-ul] Mixin 审计: 强制加载其余全部目标类, 新增被改动类 %d, 用时 %.1f ms%n", cl.changed.size() - before, ms(System.nanoTime() - s));
                    }
                    System.out.printf("[amber-ul] 本次被 Mixin 改动的类: %d%n", cl.changed.size());
                    try { Files.write(cache.resolve("changed-classes.txt"), new ArrayList<>(cl.changed)); } catch (Exception e) { e.printStackTrace(); }
                    new Thread(saveCache, "amber-ul-cache-save").start();
                }
                default -> {}
            }
        });
        { long s = System.nanoTime(); for (Personality p : ps) p.preLaunch(baked.stream().filter(m -> p.accepts(m.kind)).toList(), cl); tPre[0] = System.nanoTime() - s; }
        if (prep.neoforge != null) {   // real FML, prepared by Amber's bridge inside the universal loader; NeoForge's own hooks take it from here
            long s = System.nanoTime();
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("gameDir", ModRegistry.gameDir.toAbsolutePath()); cfg.put("gameJar", prep.bakedGame);
            List<Path> nfJars = new ArrayList<>();
            for (ModInfo m : baked) if (m.kind == LoaderKind.NEOFORGE) { if (m.id.equals("neoforge")) nfJars.add(0, m.jar); else nfJars.add(m.jar); }
            cfg.put("modJars", nfJars);
            cfg.put("server", q.server); cfg.put("neoForge", prep.neoforge.neoForge); cfg.put("fml", prep.neoforge.fml); cfg.put("mc", prep.neoforge.mc); cfg.put("neoForm", prep.neoforge.neoForm);
            Object r = Class.forName("amber.ul.fml.FmlBridge", true, cl).getMethod("prepare", Map.class).invoke(null, cfg);
            System.out.printf("[amber-ul] %s (含桥接 %.1f ms)%n", r, ms(System.nanoTime() - s));
        }
        System.out.printf("[amber-ul] %d 个模组: %s%n", baked.size(), baked);
        System.out.printf("[amber-ul] 访问规则 %d 条, 烘焙缓存 命中 %d / 未命中 %d, Mixin %s, 类缓存 %s (%s)%n", rules.rules.size(), baker.hits, baker.misses, mixin ? "开" : "关", warm ? "热" : "冷", cacheMode);
        GameHooks.class.getName();
        Class.forName(mainClass, true, cl).getMethod("main", String[].class).invoke(null, (Object) gameArgs.toArray(new String[0]));
    }

    /** Identity of the loader build itself: a new loader may transform differently, so it must invalidate the class cache. */
    static String loaderBuild() throws Exception {
        Path src = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(src)) return Files.size(src) + ":" + Files.getLastModifiedTime(src).toMillis();
        try (var w = Files.walk(src)) { return String.valueOf(w.filter(Files::isRegularFile).mapToLong(p -> { try { return Files.getLastModifiedTime(p).toMillis(); } catch (Exception e) { return 0; } }).max().orElse(0)); }
    }

    static String compatFingerprintQuiet(String mc) { try { return compatFingerprint(mc); } catch (Exception e) { return "-"; } }
    static String compatFingerprint(String mc) throws Exception {
        var in = Launcher.class.getResourceAsStream("/amber-ul/compat/neoforge-" + mc + ".json");
        return in == null ? "-" : Hash.str(new String(in.readAllBytes(), StandardCharsets.UTF_8)).substring(0, 12);
    }

    /** Compat rules "via": "drop_injector": the named handler's injector annotation is removed (bake-time, on the mixin class bytes the
     *  class loader serves), because a NeoForge system already does what it did and keeping it would double the effect. */
    static void applyInjectorDrops(String mc, List<ModInfo> mods, UniversalClassLoader cl) throws Exception {
        var in = Launcher.class.getResourceAsStream("/amber-ul/compat/neoforge-" + mc + ".json");
        if (in == null) return;
        for (var e : com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rules")) {
            var r = e.getAsJsonObject(); String mod = r.get("mod").getAsString();
            if (!"drop_injector".equals(r.get("via").getAsString())) continue;
            if (mods.stream().noneMatch(m -> m.id.equals(mod) && (m.kind == LoaderKind.FABRIC || m.kind == LoaderKind.QUILT))) continue;
            String res = r.get("mixin").getAsString().replace('.', '/') + ".class";
            byte[] b = cl.overrides.containsKey(res) ? cl.overrides.get(res) : cl.classBytes(res.substring(0, res.length() - 6));
            if (b == null) continue;
            byte[] nb = amber.ul.bake.MixinFixer.dropInjector(b, r.get("handler").getAsString());
            if (nb == null) continue;
            cl.overrides.put(res, nb);
            System.out.printf("[amber-ul] 问题 mixin_semantic_risk (warning) 模组=%s mixin=%s 注入器 %s 已移除: %s%n", mod, r.get("mixin").getAsString(), r.get("handler").getAsString(), r.get("reason").getAsString());
            RuntimeProblems.report("warning", "mixin_semantic_risk", mod, "injector removed: " + r.get("reason").getAsString(), "mixin", r.get("mixin").getAsString(), "handler", r.get("handler").getAsString(), "action", "injector_removed");
        }
    }

    /** Phase 5: Fabric API bridge manifest (vetoed mixins, stripped injectors, skipped entrypoints) for the modules present. */
    static void applyBridgeManifest(String platform, String mc, List<ModInfo> mods, UniversalClassLoader cl) throws Exception {
        BridgeManifest bm = BridgeManifest.load(platform, mc, mods);
        for (String v : bm.versionMismatch) { System.out.printf("[amber-ul] 问题 fabric_api_bridge_version (warning): %s: 桥接清单不适用于这个版本, 按原样运行%n", v);
            RuntimeProblems.report("warning", "fabric_api_bridge_version", null, v + ": the bridge manifest does not apply to this version; run as is", "detail", v); }
        if (bm.bridgedModules.isEmpty()) return;
        amber.ul.mixin.AmberMixinErrorHandler.VETO.putAll(bm.veto);
        FabricFacade.SKIP_ENTRYPOINTS.addAll(bm.skipEntrypoints);
        int stripped = 0;
        for (BridgeManifest.Strip st : bm.strips) {
            String res = st.mixin().replace('.', '/') + ".class";
            byte[] b = cl.overrides.containsKey(res) ? cl.overrides.get(res) : cl.classBytes(res.substring(0, res.length() - 6));
            byte[] nb = b == null ? null : st.invoker() != null ? amber.ul.bake.MixinFixer.accessorToInvoker(b, st.handler(), st.invoker()) : st.retarget() == null ? amber.ul.bake.MixinFixer.dropInjector(b, st.handler()) : amber.ul.bake.MixinFixer.retargetInjector(b, st.handler(), st.retarget());
            if (nb == null) { System.out.printf("[amber-ul] 桥接: 未找到注入器 %s#%s%n", st.mixin(), st.handler()); continue; }
            cl.overrides.put(res, nb); stripped++;
        }
        // re-based impl classes: amber-impl/<module>/** in the (unbaked) bridge jar, served instead of the official impl.*
        int impl = 0;
        ModInfo bridge = mods.stream().filter(m -> m.id.equals("amber_fabric_bridge")).findFirst().orElse(null);
        if (bridge != null && !bm.replaceImpl.isEmpty()) try (var jf = new java.util.jar.JarFile((bridge.originalJar != null ? bridge.originalJar : bridge.jar).toFile(), false)) {
            Set<String> present = new HashSet<>(); for (ModInfo m : mods) present.add(m.id);
            for (var e : Collections.list(jf.entries())) for (String mod : bm.replaceImpl) {
                if (!present.contains(mod)) continue;   // only for Fabric API modules that are actually installed (4a: no networking module)
                String pre = "amber-impl/" + mod + "/";
                if (e.getName().startsWith(pre) && e.getName().endsWith(".class")) { cl.overrides.put(e.getName().substring(pre.length()), amber.ul.bake.SafeFiles.boundedRead(jf.getInputStream(e), amber.ul.bake.SafeFiles.ENTRY_MAX, e.getName())); impl++; }
            }
        }
        for (var e : bm.bridgedModules.entrySet()) { System.out.printf("[amber-ul] 问题 fabric_api_bridged (info) 模块=%s: 停用 %d 个 mixin, 由 %s 系统/Amber 桥接代替%n", e.getKey(), e.getValue(), platform.equals("forge") ? "Forge" : "NeoForge");
            RuntimeProblems.report("info", "fabric_api_bridged", e.getKey(), e.getValue() + " mixin(s) disabled, replaced by the " + platform + " bridge", "mixins", String.valueOf(e.getValue()), "platform", platform); }
        System.out.printf("[amber-ul] 桥接清单: 停用 %d 个 mixin, 移除 %d 个单独注入器, 跳过 %d 个入口点, 替换 %d 个 impl 类%n", bm.veto.size(), stripped, bm.skipEntrypoints.size(), impl);
    }

    /** Compat rules for Fabric mods on the NeoForge platform: amber-ul/compat/neoforge-<mc>.json -> options on a synthetic mod. */
    static void applyCompatRules(String mc, List<ModInfo> mods, Map<String, String> builtins) throws Exception {
        var in = Launcher.class.getResourceAsStream("/amber-ul/compat/neoforge-" + mc + ".json");
        if (in == null) return;
        var rules = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("rules");
        com.google.gson.JsonObject custom = new com.google.gson.JsonObject();
        for (var e : rules) {
            var r = e.getAsJsonObject(); String mod = r.get("mod").getAsString();
            if (mods.stream().noneMatch(m -> m.id.equals(mod) && (m.kind == LoaderKind.FABRIC || m.kind == LoaderKind.QUILT))) continue;
            String via = r.get("via").getAsString();
            if (via.equals("drop_injector")) continue;   // handled on the mixin class bytes (applyInjectorDrops)
            if (!custom.has(via)) custom.add(via, new com.google.gson.JsonObject());
            custom.getAsJsonObject(via).addProperty(r.get("option").getAsString(), false);
            System.out.printf("[amber-ul] 问题 mixin_semantic_risk (warning) 模组=%s 选项=%s 已关闭: %s%n", mod, r.get("option").getAsString(), r.get("reason").getAsString());
            RuntimeProblems.report("warning", "mixin_semantic_risk", mod, "option turned off: " + r.get("reason").getAsString(), "option", r.get("option").getAsString(), "action", "option_disabled");
        }
        if (custom.size() == 0) return;
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("id", "amber_ul_compat"); json.add("custom", custom);
        FabricFacade.SYNTHETIC.put("amber_ul_compat", json);
        builtins.put("amber_ul_compat", "1");
    }

    /** Fixer/isolation results are valid for exactly this platform + mod files + pipeline (phase 4). */
    public static final String FIXER_VERSION = amber.ul.bake.MixinFixer.VERSION;
    static String isolationKey(Instance.Prepared p, String mc) {
        List<String> shas = new ArrayList<>();
        for (ModInfo m : p.baked) shas.add(m.sha256);
        Collections.sort(shas);
        return Hash.str((p.neoforge != null ? "neoforge-" + p.neoforge.neoForge + (p.neoforge.server ? "-server" : "") : p.forge != null ? "forge-" + p.forge.forge : "vanilla") + "|" + String.join(",", shas) + "|" + Baker.PIPELINE + "|" + FIXER_VERSION + "|" + compatFingerprintQuiet(mc) + "|" + BridgeManifest.fingerprint(p.neoforge != null ? "neoforge" : p.forge != null ? "forge" : null, mc)
                // H16: loader/Mixin/ASM versions + what the game bake is made from (game jar + libraries; NeoForge base + patches + side).
                // Inputs, not the baked file: the coremod-patched jar is not byte-reproducible across caches.
                + "|" + RuntimeVersions.fingerprint() + "|ctx=" + p.baker.context
                + (p.neoforge != null ? "|base=" + p.neoforge.srg.getFileName() + "|" + p.neoforge.lzma.getFileName() : "")).substring(0, 20);
    }

    /** Hook points for this MC version, from amber-ul/hooks/<mc>.json (data, not code). */
    static List<Baker.Hook> hookTable(String mc) throws Exception {
        var in = Launcher.class.getResourceAsStream("/amber-ul/hooks/" + mc + ".json");
        if (in == null) throw new IllegalStateException("no hook table for Minecraft " + mc);
        com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        List<Baker.Hook> l = new ArrayList<>();
        for (var e : o.getAsJsonArray("hooks")) {
            var h = e.getAsJsonObject();
            l.add(new Baker.Hook(h.get("id").getAsString(), h.get("owner").getAsString(), h.get("method").getAsString(), h.get("desc").getAsString(),
                    h.get("at").getAsString(), h.has("target") ? h.get("target").getAsString() : null, h.has("ldc") ? h.get("ldc").getAsString() : null, h.has("passThis") && h.get("passThis").getAsBoolean(), h.get("call").getAsString()));
        }
        return l;
    }

    private static String arg(List<String> args, String key, String def) { int i = args.indexOf(key); return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : def; }

    private static double ms(long ns) { return ns / 1e6; }
}
