package amber.ul;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the loader's own files are. Dev build: build/loader (a directory) with the bridge jars next to it in build/.
 * Packaged layout (interface v1.3, tools/package.py): loader.jar, bridges/*.jar, libs/*.jar, manifest.json.
 */
public final class LoaderHome {
    private LoaderHome() {}
    /** The loader's code location (build/loader or loader.jar). */
    public static Path code() {
        try { return Path.of(LoaderHome.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new IllegalStateException("loader location", e); }
    }
    /** A bridge jar by file name: bridges/<name> next to the loader (packaged), else <name> next to it (dev build). */
    public static Path bridge(String name) {
        Path c = code(), packaged = c.resolveSibling("bridges").resolve(name);
        return Files.exists(packaged) ? packaged : c.resolveSibling(name);
    }
}
