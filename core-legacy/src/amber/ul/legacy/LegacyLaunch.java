package amber.ul.legacy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runtime entry for the forge-1.12.2 platform (docs/forge-1.12.2-plan.md, step 2). Java 8 bytecode (built with
 * --release 8): this module runs inside the game's JVM, which for 1.12.2 is Java 8. The bake tools stay Java 21 in core.
 *
 * Phase 0: checks the JVM and hands over to Mojang's LaunchWrapper with FML's tweaker, i.e. exactly what the official
 * Forge profile does. Later phases put Amber's baked game jar and transformer cache in front of this.
 *
 *   java -cp "core-legacy.jar;launchwrapper;forge;libs...;game.jar" amber.ul.legacy.LegacyLaunch [minecraft args]
 */
public final class LegacyLaunch {
    private LegacyLaunch() {}
    public static final String LAUNCH = "net.minecraft.launchwrapper.Launch";
    public static final String FML_TWEAKER = "net.minecraftforge.fml.common.launcher.FMLTweaker";
    /** -Damber.ul.legacy.allowNewJava=true runs on Java 9+ anyway (most 1.12 coremods break there). */
    public static final String ALLOW_NEW_JAVA = "amber.ul.legacy.allowNewJava";
    /** -Damber.ul.legacy.cache=<dir> turns on the transformer cache (amber.ul.legacy.lw.CachingLaunchClassLoader). */
    public static final String CACHE = "amber.ul.legacy.cache";

    /** -Damber.ul.loaderVersion (set by the launch block) names the loader in the problems file's launch line. */
    public static final String LOADER_VERSION = "amber.ul.loaderVersion";

    /**
     * Problems before the game starts are reported as @amber-ul/problem lines (bake interface, runtime problems):
     * legacy_java_unsupported (wrong Java), legacy_launchwrapper_missing (class path), legacy_launch_failed (LaunchWrapper
     * or FML threw before the game took over; FML's own crash reports cover what happens after).
     */
    public static void main(String[] args) throws Throwable {
        LegacyProblems.open(System.getProperty(LOADER_VERSION, "?"));
        String problem = javaProblem(System.getProperty("java.specification.version"), Boolean.getBoolean(ALLOW_NEW_JAVA));
        if (problem != null) {
            LegacyProblems.report("error", "legacy_java_unsupported", problem, "javaVersion", System.getProperty("java.version"), "required", "8");
            System.err.println("[amber-ul legacy] " + problem); System.exit(3);
        }
        Class<?> launch;
        try { launch = Class.forName(LAUNCH, false, LegacyLaunch.class.getClassLoader()); }
        catch (ClassNotFoundException e) {
            String m = "LaunchWrapper is not on the class path (" + LAUNCH + ")";
            LegacyProblems.report("error", "legacy_launchwrapper_missing", m);
            System.err.println("[amber-ul legacy] " + m); System.exit(3); return;
        }
        String quick = System.getProperty(LegacyQuickPlay.PROPERTY);
        if (quick != null && !quick.isEmpty()) LegacyQuickPlay.start(quick);   // v1.3.3: open this world once the main menu is up
        String maint = System.getProperty(LegacyMaintenance.PROPERTY);
        if (maint != null && !maint.isEmpty()) LegacyMaintenance.start(maint);   // v1.3.3 maintenance mode
        try { run(launch, args); }
        catch (Throwable t) {
            Throwable root = t; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            // LaunchWrapper catches a tweaker's exception itself, logs "Unable to launch" and calls System.exit, which FML's
            // security manager turns into ExitTrappedException: the real cause is only in logs/latest.log then
            boolean trapped = t.getClass().getName().endsWith("ExitTrappedException");
            String m = trapped ? "LaunchWrapper could not start the game (see \"Unable to launch\" in logs/latest.log)" : "the game did not start: " + t;
            LegacyProblems.report("error", "legacy_launch_failed", m, "exception", t.getClass().getName(), "rootCause", trapped ? null : String.valueOf(root), "log", "logs/latest.log");
            throw t;
        }
    }

    private static void run(Class<?> launch, String[] args) throws Throwable {
        String cache = System.getProperty(CACHE);
        if (cache != null && !cache.isEmpty()) {
            // compiled against LaunchWrapper (core-legacy/lw): reached by name so this class keeps no LaunchWrapper dependency
            Method cached;
            try { cached = Class.forName("amber.ul.legacy.lw.CachedLaunch").getMethod("main", String[].class, java.io.File.class); }
            catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {   // a bridge built without the LaunchWrapper part
                LegacyProblems.report("warning", "legacy_cache_disabled", "transformer cache not in this bridge: " + e, "cacheDir", cache);
                cached = null;
            }
            if (cached != null) {
                try { cached.invoke(null, launchArgs(args), new java.io.File(cache)); }
                catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                return;
            }
        }
        Method main = launch.getMethod("main", String[].class);
        try { main.invoke(null, (Object) launchArgs(args)); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }

    /** Null when this JVM can run 1.12.2; otherwise why not. spec is java.specification.version ("1.8", "9", "21"...). */
    public static String javaProblem(String spec, boolean allowNew) {
        int major = javaMajor(spec);
        if (major < 0) return "unknown Java version '" + spec + "'";
        if (major < 8) return "Java " + major + " is too old: Minecraft 1.12.2 with Forge needs Java 8";
        if (major > 8 && !allowNew) return "Java " + major + " is not supported: Minecraft 1.12.2 with Forge needs Java 8 (many coremods break on 9+); set -D" + ALLOW_NEW_JAVA + "=true to try anyway";
        return null;
    }

    public static int javaMajor(String spec) {
        if (spec == null) return -1;
        String s = spec.trim();
        if (s.startsWith("1.")) s = s.substring(2);
        int end = 0; while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return -1;
        try { return Integer.parseInt(s.substring(0, end)); } catch (NumberFormatException e) { return -1; }
    }

    /** The arguments for Launch.main: the caller's, plus FML's tweaker unless a --tweakClass already names it. */
    public static String[] launchArgs(String[] args) {
        List<String> out = new ArrayList<String>(Arrays.asList(args));
        for (int i = 0; i + 1 < out.size(); i++) if ("--tweakClass".equals(out.get(i)) && FML_TWEAKER.equals(out.get(i + 1))) return out.toArray(new String[0]);
        out.add("--tweakClass"); out.add(FML_TWEAKER);
        return out.toArray(new String[0]);
    }
}
