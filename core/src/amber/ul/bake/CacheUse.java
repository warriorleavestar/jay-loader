package amber.ul.bake;

import java.nio.file.Path;
import java.util.*;

/**
 * Every cache product this process used, hit or built (baked jars, nested jars, platform jars, mapping tables and graphs).
 * bake-instance and warm write the list into the state file ("cacheFiles"); `Bake gc` keeps exactly what live states list
 * (interface v1.2). A directory entry keeps everything under it.
 */
public final class CacheUse {
    private CacheUse() {}
    private static final Set<Path> used = new TreeSet<>();

    public static synchronized Path mark(Path p) {
        if (p != null) used.add(p.toAbsolutePath().normalize());
        return p;
    }
    public static synchronized List<Path> snapshot() { return new ArrayList<>(used); }
    public static synchronized void clear() { used.clear(); }
}
