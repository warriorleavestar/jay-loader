package amber.ul.legacy.lw;

import amber.ul.legacy.LegacyProblems;
import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;

/**
 * Runs LaunchWrapper's own Launch.launch with a {@link CachingLaunchClassLoader} in place of the plain LaunchClassLoader.
 * Launch's private constructor builds a LaunchClassLoader and sets Launch.classLoader; launch() reads that static field
 * for everything after, so swapping it before launch() is enough. If the cache cannot be set up (directory not writable,
 * disk full, a corrupt file), the plain loader stays and the game starts exactly as the official profile would; the
 * launcher sees a legacy_cache_disabled warning.
 */
public final class CachedLaunch {
    private CachedLaunch() {}

    public static void main(String[] args, File cacheDir) throws Throwable {
        Constructor<Launch> ctor = Launch.class.getDeclaredConstructor(); ctor.setAccessible(true);
        Launch launch = ctor.newInstance();
        File gameDir = new File(arg(args, "--gameDir", "."));
        URLClassLoader app = (URLClassLoader) Launch.class.getClassLoader();
        try {
            Launch.classLoader = new CachingLaunchClassLoader(app.getURLs(), cacheDir, gameDir);
        } catch (Throwable t) {   // the plain LaunchClassLoader from Launch's constructor is still in place
            LegacyProblems.report("warning", "legacy_cache_disabled", "transformer cache not used for this launch: " + t, "cacheDir", cacheDir.getPath());
            System.err.println("[amber-ul legacy] transformer cache disabled: " + t);
        }
        Thread.currentThread().setContextClassLoader(Launch.classLoader);
        Method m = Launch.class.getDeclaredMethod("launch", String[].class); m.setAccessible(true);
        try { m.invoke(launch, (Object) args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }

    static String arg(String[] a, String k, String d) {
        for (int i = 0; i + 1 < a.length; i++) if (k.equals(a[i])) return a[i + 1];
        return d;
    }
}
