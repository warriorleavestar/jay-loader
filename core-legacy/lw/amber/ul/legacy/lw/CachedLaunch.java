package amber.ul.legacy.lw;

import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;

/**
 * Runs LaunchWrapper's own Launch.launch with a {@link CachingLaunchClassLoader} in place of the plain LaunchClassLoader.
 * Launch's private constructor builds a LaunchClassLoader and sets Launch.classLoader; launch() reads that static field
 * for everything after, so swapping it before launch() is enough. The first, plain loader is never used.
 */
public final class CachedLaunch {
    private CachedLaunch() {}

    public static void main(String[] args, File cacheDir) throws Throwable {
        Constructor<Launch> ctor = Launch.class.getDeclaredConstructor(); ctor.setAccessible(true);
        Launch launch = ctor.newInstance();
        File gameDir = new File(arg(args, "--gameDir", "."));
        URLClassLoader app = (URLClassLoader) Launch.class.getClassLoader();
        Launch.classLoader = new CachingLaunchClassLoader(app.getURLs(), cacheDir, gameDir);
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
