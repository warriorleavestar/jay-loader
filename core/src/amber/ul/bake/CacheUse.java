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

    /** Marks {@code p} and returns where to use it: {@code p} itself, or its overlay path (see {@link #overlay}). */
    public static synchronized Path mark(Path p) {
        if (p == null) return null;
        used.add(p.toAbsolutePath().normalize());
        return place(new Path[] { p })[0];
    }
    /** Files that are built together (a jar and its side file): all from the shared cache, or all from the overlay. */
    public static synchronized Path[] mark(Path first, Path... rest) {
        Path[] all = new Path[rest.length + 1]; all[0] = first; System.arraycopy(rest, 0, all, 1, rest.length);
        for (Path p : all) used.add(p.toAbsolutePath().normalize());
        return place(all);
    }
    public static synchronized List<Path> snapshot() { return new ArrayList<>(used); }
    public static synchronized void clear() { used.clear(); }

    // ---- launch overlay (sandbox: the shared caches are read-only for the game process) --------------------------------
    /** Shared cache root -> its writable overlay directory. Empty = off (bake stages, dev runs): everything is written in place. */
    private static final Map<Path, Path> overlays = new LinkedHashMap<>();

    /**
     * A launch from a state file reads the shared caches (cacheRoot, mappingCache) but never writes into them: a cache product
     * it has to build (mods changed since warm, cache cleaned) goes to {@code overlay} inside the instance directory instead,
     * so the game can run in a sandbox that only lets it write its own instance. {@code warm} later builds the same product
     * in the shared cache. Several roots may nest (mappingCache = cacheRoot/mappings); the longest match wins.
     */
    public static synchronized void overlay(Path sharedRoot, Path overlayDir) {
        if (sharedRoot != null) overlays.put(sharedRoot.toAbsolutePath().normalize(), overlayDir.toAbsolutePath().normalize());
    }
    public static synchronized void overlayOff() { overlays.clear(); }
    public static synchronized boolean overlayOn() { return !overlays.isEmpty(); }

    /** Where to write {@code p}: its overlay path when {@code p} is under a shared root and the overlay is on, else {@code p}. */
    public static synchronized Path writeTarget(Path p) {
        if (p == null || overlays.isEmpty()) return p;
        Path a = p.toAbsolutePath().normalize(), best = null;
        for (Path r : overlays.keySet()) if (a.startsWith(r) && (best == null || r.getNameCount() > best.getNameCount())) best = r;
        return best == null ? p : overlays.get(best).resolve(best.relativize(a));
    }

    /** A cache directory to create: in place, or nothing when it lies in a (read-only) shared root under the overlay. */
    public static Path ensureDir(Path dir) throws java.io.IOException {
        return writeTarget(dir) != dir ? dir : java.nio.file.Files.createDirectories(dir);
    }

    private static Path[] place(Path[] ps) {
        if (overlays.isEmpty()) return ps;
        boolean shared = true;
        Path[] o = new Path[ps.length];
        for (int i = 0; i < ps.length; i++) {
            o[i] = writeTarget(ps[i]);
            if (o[i] == ps[i]) return ps;   // not in a shared root: as is
            shared &= java.nio.file.Files.exists(ps[i]);
        }
        return shared ? ps : o;   // complete in the shared cache: read it there; else build (or reuse) the overlay copy
    }
}
