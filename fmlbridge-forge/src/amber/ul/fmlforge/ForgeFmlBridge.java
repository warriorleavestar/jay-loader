package amber.ul.fmlforge;

import cpw.mods.jarhandling.SecureJar;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.*;
import net.minecraftforge.fml.loading.moddiscovery.*;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * M4: Amber's FML personality for Forge 1.20.1 (FML 47). Runs INSIDE the universal class loader next to the real FML
 * classes and leaves FML in the state its production launch (bootstraplauncher -> modlauncher -> FMLServiceProvider ->
 * ForgeClientLaunchHandler) would, right before the game's main; Forge's patched Minecraft constructor then calls
 * ClientModLoader.begin itself. Called BEFORE Mixin boots: Forge mods' mixin plugins read LoadingModList/FMLLoader.
 *
 * Runtime names are Mojmap (option A), i.e. what a Forge userdev with official mappings sees: naming "mcp"; but
 * production true (a player's launch, see prepare), and the "srg" name function maps SRG ids to Mojmap (ForgeShim).
 */
public final class ForgeFmlBridge {
    private ForgeFmlBridge() {}

    /** cfg: gameDir, gameJar, modJars (List<Path>: forge universal first), forge, mc, mcp, srgTable. */
    @SuppressWarnings("unchecked")
    public static String prepare(Map<String, Object> cfg) throws Exception {
        long t0 = System.nanoTime();
        int names = ForgeShim.load((Path) cfg.get("srgTable"));
        Path gameDir = (Path) cfg.get("gameDir");
        stubModLauncher();
        FMLPaths.loadAbsolutePaths(gameDir);
        FMLConfig.load();
        set("dist", Dist.CLIENT);
        // production true, as Forge's own client launch (ForgeClientLaunchHandler.isProduction): this is a player's game, not a
        // userdev workspace. Mods branch on it: ModernFix turns on its dev-only options (mixin.devenv, perf.remove_spawn_chunks)
        // when false, and remove_spawn_chunks + YungsApi's prepareLevels constants (11 -> 0, 441 -> 0) never finish "Preparing
        // start region". -Damber.forge.production=false restores the userdev answer.
        set("production", !"false".equals(System.getProperty("amber.forge.production")));
        set("naming", "mcp");
        set("gamePath", gameDir);
        set("versionInfo", new VersionInfo((String) cfg.get("forge"), (String) cfg.get("mc"), (String) cfg.get("mcp"), "net.minecraftforge"));
        set("launchHandlerName", "forgeclient");
        set("commonLaunchHandler", new net.minecraftforge.fml.loading.targets.ForgeClientLaunchHandler());
        set("moduleLayerManager", ForgeShim.LAYERS);
        FMLLoader.progressWindowTick = () -> {};
        // early window: the dummy provider (as with earlyWindowControl=false), as ImmediateWindowHandler.load would leave it
        ClassLoader ucl = ForgeFmlBridge.class.getClassLoader();
        Class<?> dummy = Class.forName("net.minecraftforge.fml.loading.ImmediateWindowHandler$DummyProvider", true, ucl);
        Constructor<?> dc = dummy.getDeclaredConstructor(); dc.setAccessible(true);
        Field prov = ImmediateWindowHandler.class.getDeclaredField("provider"); prov.setAccessible(true); prov.set(null, dc.newInstance());
        Field ep = ImmediateWindowHandler.class.getDeclaredField("earlyProgress"); ep.setAccessible(true);
        ep.set(null, net.minecraftforge.fml.loading.progress.StartupNotificationManager.addProgressBar("EARLY", 0));
        ImmediateWindowHandler.acceptGameLayer(ModuleLayer.boot());
        Constructor<LanguageLoadingProvider> lc = LanguageLoadingProvider.class.getDeclaredConstructor(); lc.setAccessible(true);
        set("languageLoadingProvider", lc.newInstance());

        // launch plugins (eventbus, enum extender, capability tokens, object holders, dist cleaner) from here on
        ForgeLaunchPlugins lp = new ForgeLaunchPlugins(ucl, Dist.CLIENT);
        amber.ul.fml.LaunchPluginHost.impl = lp; amber.ul.fml.LaunchPluginHost.synth = lp::synthesize;

        // mod files: the baked game as "minecraft", Forge universal, the mods
        MinecraftLocator mcl = new MinecraftLocator();
        Method mcInfo = MinecraftLocator.class.getDeclaredMethod("buildMinecraftTOML", IModFile.class); mcInfo.setAccessible(true);
        List<ModFile> files = new ArrayList<>();
        files.add(new ModFile(SecureJar.from((Path) cfg.get("gameJar")), mcl, f -> { try { return (IModFileInfo) mcInfo.invoke(mcl, f); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); } }));
        for (Path p : (List<Path>) cfg.get("modJars")) files.add(new ModFile(SecureJar.from(p), mcl, ModFileParser::modsTomlParser));
        List<String> broken = new ArrayList<>(); List<IModFile> brokenFiles = new ArrayList<>();
        int mods = 0;
        List<ModFile> ok = new ArrayList<>();
        for (ModFile f : files) {
            if (!f.identifyMods()) { broken.add(f.getFileName()); brokenFiles.add(f); continue; }
            f.identifyLanguage();
            mods += f.getModInfos().size(); ok.add(f);
        }
        LoadingModList lml = ModSorter.sort(ok, new ArrayList<>());
        lml.setBrokenFiles(brokenFiles);   // as FMLLoader.completeScan does
        set("loadingModList", lml);
        BackgroundScanHandler bsh = new BackgroundScanHandler(ok);
        FMLLoader.backgroundScanHandler = bsh;
        pendingScan = () -> lml.addForScanning(bsh);   // started after Mixin boot (afterMixinBoot): the scan thread loads classes
        return String.format("Forge FML 已就绪 (%s, naming=%s): %d 个模组文件, %d 个模组, 加载 %d (%s), 错误 %d, 坏文件 %s, srg 名字 %d, 用时 %.1f ms",
                FMLLoader.getDist(), FMLLoader.getNaming(), files.size(), mods, lml.getMods().size(), lml.getMods().stream().map(m -> m.getModId()).toList(),
                lml.getErrors().size(), broken, names, (System.nanoTime() - t0) / 1e6) + errors(lml);
    }

    private static Runnable pendingScan;
    /** Annotation scan (@Mod, @EventBusSubscriber) in the background, as FML does - but only once Mixin has booted: classes
     *  the scan thread loads while Mixin is still selecting configs race its transformer (ConcurrentModificationException, hang). */
    public static void afterMixinBoot() { Runnable r = pendingScan; pendingScan = null; if (r != null) r.run(); }

    static String errors(LoadingModList lml) {
        StringBuilder b = new StringBuilder();
        for (var e : lml.getErrors()) b.append("\n  FML 错误: ").append(e.getMessage()).append(" ").append(e.getAllData());
        return b.toString();
    }

    /** Launcher.INSTANCE allocated without its constructor, with a real Environment (crash reports, mods read it). */
    static void stubModLauncher() throws Exception {
        if (cpw.mods.modlauncher.Launcher.INSTANCE != null) return;
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); uf.setAccessible(true);
        Object unsafe = uf.get(null);
        var launcher = (cpw.mods.modlauncher.Launcher) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, cpw.mods.modlauncher.Launcher.class);
        Constructor<cpw.mods.modlauncher.Environment> ec = cpw.mods.modlauncher.Environment.class.getDeclaredConstructor(cpw.mods.modlauncher.Launcher.class);
        ec.setAccessible(true);
        var env = ec.newInstance(launcher);
        Field ef = cpw.mods.modlauncher.Launcher.class.getDeclaredField("environment"); ef.setAccessible(true); ef.set(launcher, env);
        // the blackboard (coremods' CoreModEngine reads a flag from it in its static init)
        Field bf = cpw.mods.modlauncher.Launcher.class.getDeclaredField("blackboard"); bf.setAccessible(true); bf.set(launcher, new cpw.mods.modlauncher.api.TypesafeMap());
        // an empty LaunchPluginHandler: mods that add their own launch plugin by reflection (ModernFix CoreLaunchPluginService:
        // Launcher.launchPlugins.plugins = copy + itself) find a map; ForgeLaunchPlugins runs whatever appears there
        Object lph = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, cpw.mods.modlauncher.LaunchPluginHandler.class);
        Field pf = cpw.mods.modlauncher.LaunchPluginHandler.class.getDeclaredField("plugins"); pf.setAccessible(true); pf.set(lph, new LinkedHashMap<>());
        Field lf = cpw.mods.modlauncher.Launcher.class.getDeclaredField("launchPlugins"); lf.setAccessible(true); lf.set(launcher, lph);
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.MLIMPL_VERSION.get(), k -> "amber-ul (no modlauncher)");
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.MODLIST.get(), k -> new ArrayList<>());
        // what FMLServiceProvider/FMLLoader.onInitialLoad put there: the forgespi Environment (dist, mod file factory)
        env.computePropertyIfAbsent(net.minecraftforge.forgespi.Environment.Keys.DIST.get(), k -> Dist.CLIENT);
        env.computePropertyIfAbsent(net.minecraftforge.forgespi.Environment.Keys.MODFILEFACTORY.get(),
                k -> (net.minecraftforge.forgespi.locating.ModFileFactory) (jar, loc, parser) -> new ModFile(jar, loc, parser));
        env.computePropertyIfAbsent(net.minecraftforge.forgespi.Environment.Keys.PROGRESSMESSAGE.get(), k -> (java.util.function.Consumer<String>) m -> {});
        cpw.mods.modlauncher.Launcher.INSTANCE = launcher;
        net.minecraftforge.forgespi.Environment.build(env);
    }

    private static void set(String field, Object v) throws ReflectiveOperationException {
        Field f = FMLLoader.class.getDeclaredField(field); f.setAccessible(true); f.set(null, v);
    }
}
