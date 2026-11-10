package amber.ul.fml;

import cpw.mods.jarhandling.SecureJar;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingIssue;
import net.neoforged.fml.loading.*;
import net.neoforged.fml.loading.moddiscovery.*;
import net.neoforged.fml.loading.modscan.*;
import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.*;

/**
 * Amber's FML personality. Runs INSIDE the universal class loader, next to the real FML 4 classes, and puts FML into the
 * state its production launch (bootstraplauncher -> modlauncher -> FMLServiceProvider -> NeoForgeClientLaunchHandler)
 * would have left it in right before the game's main:
 *   FMLPaths / FMLConfig loaded, FMLLoader statics (dist, production, versionInfo, gamePath, launch handler name,
 *   language providers, background scan handler, game layer), the early-window DummyProvider, and a LoadingModList built
 *   from real ModFile objects for minecraft (= Amber's baked NeoForge game jar), neoforge (universal) and the mods.
 * FML's own discovery, modlauncher and module layers are not used. NeoForge's patched Minecraft constructor then calls
 * ClientModLoader.begin -> ModLoader.gatherAndInitializeMods itself, exactly as in production.
 */
public final class FmlBridge {
    private FmlBridge() {}

    /** cfg: gameDir, gameJar, modJars (List<Path>: neoforge universal first), neoForge, fml, mc, neoForm. Returns a short summary. */
    @SuppressWarnings("unchecked")
    public static String prepare(Map<String, Object> cfg) throws Exception {
        long t0 = System.nanoTime();
        ClassLoader ucl = FmlBridge.class.getClassLoader();
        Path gameDir = (Path) cfg.get("gameDir");
        FMLPaths.loadAbsolutePaths(gameDir);
        FMLConfig.load();

        boolean server = Boolean.TRUE.equals(cfg.get("server"));
        set("dist", server ? Dist.DEDICATED_SERVER : Dist.CLIENT);
        set("production", true);
        set("gamePath", gameDir);
        set("versionInfo", new VersionInfo((String) cfg.get("neoForge"), (String) cfg.get("fml"), (String) cfg.get("mc"), (String) cfg.get("neoForm")));
        set("launchHandlerName", server ? "forgeserver" : "forgeclient");
        set("gameLayer", ModuleLayer.boot());   // never searched: Amber's Jpms redirect answers findModule/ServiceLoader
        FMLLoader.progressWindowTick = () -> {};
        FMLLoader.backgroundScanHandler = new BackgroundScanHandler();

        // early window: the dummy provider (as with earlyWindowControl=false); it reaches NeoForge's NoVizFallback through findModule
        Class<?> dummy = Class.forName("net.neoforged.fml.loading.ImmediateWindowHandler$DummyProvider", true, ucl);
        Constructor<?> dc = dummy.getDeclaredConstructor(); dc.setAccessible(true);
        Field prov = ImmediateWindowHandler.class.getDeclaredField("provider"); prov.setAccessible(true);
        prov.set(null, dc.newInstance());
        Field ep = ImmediateWindowHandler.class.getDeclaredField("earlyProgress"); ep.setAccessible(true);   // as ImmediateWindowHandler.load does
        ep.set(null, net.neoforged.fml.loading.progress.StartupNotificationManager.addProgressBar("EARLY", 0));
        ImmediateWindowHandler.acceptGameLayer(ModuleLayer.boot());

        // language providers (javafml, lowcodefml, minecraft) via ServiceLoader on the universal loader
        ILaunchContext ctx = (ILaunchContext) Proxy.newProxyInstance(ucl, new Class<?>[]{ILaunchContext.class}, (p, m, a) -> switch (m.getName()) {
            case "loadServices" -> ServiceLoader.load((Class<?>) a[0], ucl).stream();
            case "modLists", "mods", "mavenRoots" -> List.of();
            case "isLocated", "addLocated" -> false;
            case "environment" -> null;
            case "toString" -> "AmberLaunchContext";
            case "hashCode" -> 0; case "equals" -> p == a[0];
            default -> throw new UnsupportedOperationException(m.getName());
        });
        Constructor<LanguageProviderLoader> lc = LanguageProviderLoader.class.getDeclaredConstructor(ILaunchContext.class);
        lc.setAccessible(true);
        set("languageProviderLoader", lc.newInstance(ctx));

        // mod files
        Method mcInfo = Class.forName("net.neoforged.fml.loading.moddiscovery.locators.MinecraftModInfo", true, ucl).getDeclaredMethod("buildMinecraftModInfo", IModFile.class);
        mcInfo.setAccessible(true);
        ModFileInfoParser mcParser = f -> { try { return (IModFileInfo) mcInfo.invoke(null, f); } catch (ReflectiveOperationException e) { throw new RuntimeException(e); } };
        List<ModFile> files = new ArrayList<>();
        files.add((ModFile) IModFile.create(SecureJar.from((Path) cfg.get("gameJar")), mcParser));
        for (Path p : (List<Path>) cfg.get("modJars")) files.add((ModFile) IModFile.create(SecureJar.from(p), ModFileParser::modsTomlParser));
        List<ModLoadingIssue> issues = new ArrayList<>();
        int mods = 0;
        for (ModFile f : files) {
            if (!f.identifyMods()) { issues.add(ModLoadingIssue.error("fml.modloadingissue.brokenfile", f.getFilePath())); continue; }
            f.identifyLanguage();
            f.setScanResult(new net.neoforged.fml.loading.modscan.Scanner(f).scan(), null);   // annotation scan (@Mod, @EventBusSubscriber), synchronously
            mods += f.getModInfos().size();
        }
        spiEnvironment();   // ModSorter checks side="CLIENT"/"SERVER" dependencies against neoforgespi.Environment.get().getDist()
        LoadingModList lml = ModSorter.sort(List.of(), files, issues);
        set("loadingModList", lml);
        stubModLauncher(lml);
        return String.format("FML 已就绪 (" + FMLLoader.getDist() + "): %d 个模组文件, %d 个模组 (%s), 问题 %d 条, 用时 %.1f ms",
                files.size(), mods, lml.getMods().stream().map(m -> m.getModId()).toList(), lml.getModLoadingIssues().size(), (System.nanoTime() - t0) / 1e6);
    }

    /**
     * cpw.mods.modlauncher.Launcher.INSTANCE is read by FMLLoader.getLauncherInfo / modLauncherModList (crash reports, system
     * report) and by some mods. Modlauncher never runs here, so: a Launcher allocated without its constructor, holding a real
     * Environment with the two properties those callers read.
     */
    private static void stubModLauncher(LoadingModList lml) throws Exception {
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); uf.setAccessible(true);
        Object unsafe = uf.get(null);
        var launcher = (cpw.mods.modlauncher.Launcher) unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, cpw.mods.modlauncher.Launcher.class);
        Constructor<cpw.mods.modlauncher.Environment> ec = cpw.mods.modlauncher.Environment.class.getDeclaredConstructor(cpw.mods.modlauncher.Launcher.class);
        ec.setAccessible(true);
        var env = ec.newInstance(launcher);
        Field ef = cpw.mods.modlauncher.Launcher.class.getDeclaredField("environment"); ef.setAccessible(true); ef.set(launcher, env);
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.MLIMPL_VERSION.get(), k -> "amber-ul (no modlauncher)");
        List<Map<String, String>> modlist = new ArrayList<>();
        for (var m : lml.getMods()) modlist.add(Map.of("name", m.getModId(), "type", "MOD", "file", m.getOwningFile().getFile().getFileName()));
        env.computePropertyIfAbsent(cpw.mods.modlauncher.api.IEnvironment.Keys.MODLIST.get(), k -> modlist);
        cpw.mods.modlauncher.Launcher.INSTANCE = launcher;
    }

    /**
     * net.neoforged.neoforgespi.Environment is built by modlauncher's launch (never runs here); IModInfo.DependencySide
     * reads its dist for every dependency that is not side="BOTH". One allocated without its constructor, with FML's dist.
     */
    private static void spiEnvironment() throws Exception {
        Field inst = net.neoforged.neoforgespi.Environment.class.getDeclaredField("INSTANCE"); inst.setAccessible(true);
        if (inst.get(null) != null) return;
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe"); uf.setAccessible(true);
        Object unsafe = uf.get(null);
        Object env = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, net.neoforged.neoforgespi.Environment.class);
        Field d = net.neoforged.neoforgespi.Environment.class.getDeclaredField("dist"); d.setAccessible(true);
        Object dist = FMLLoader.getDist();
        long off = (long) unsafe.getClass().getMethod("objectFieldOffset", Field.class).invoke(unsafe, d);   // final field
        unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, env, off, dist);
        inst.set(null, env);
    }

    private static void set(String field, Object v) throws ReflectiveOperationException {
        Field f = FMLLoader.class.getDeclaredField(field); f.setAccessible(true); f.set(null, v);
    }
}
