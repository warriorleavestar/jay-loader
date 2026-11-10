package amber.ul.bake;

import java.io.*;
import java.nio.file.*;
import java.util.regex.Pattern;

/**
 * Hardening helpers for everything the loader writes into or reads out of its caches (2026-11-05, security scan H13-H15).
 *  - under(): cache paths must stay inside their cache root, whatever a mod's metadata says.
 *  - boundedRead(): jar entries are read with a cap on the bytes actually inflated (ZipEntry.getSize() is never trusted).
 *  - publish(): temp file -> final name, atomic; another process winning the race is fine (content-addressed), the temp is
 *    always removed.
 *  - validModId(): the id rules of each loader, checked at discovery.
 */
public final class SafeFiles {
    private SafeFiles() {}

    /** A jar entry with a fixed time: baked jars are hashed into later cache keys, so the same input must give the same bytes. */
    public static java.util.jar.JarEntry entry(String name) {
        java.util.jar.JarEntry e = new java.util.jar.JarEntry(name);
        e.setTimeLocal(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        return e;
    }

    public static final long METADATA_MAX = 1L << 20;          // fabric.mod.json, mods.toml, jarjar metadata, mixin configs, refmaps
    public static final long NESTED_JAR_MAX = 256L << 20;      // one nested jar
    public static final long ROOT_INFLATED_MAX = 1L << 30;     // all nested jars under one top-level jar
    public static final int ROOT_NESTED_MAX = 1024;            // nested jars under one top-level jar
    public static final int NEST_DEPTH_MAX = 8;
    public static final long ENTRY_MAX = 256L << 20;           // one class / resource entry in a game or mod jar

    /** A read or a nesting tree went over one of the limits above. */
    public static final class LimitException extends IOException {
        public LimitException(String m) { super(m); }
    }

    public static Path under(Path root, Path p) throws IOException {
        Path r = root.toAbsolutePath().normalize(), q = p.toAbsolutePath().normalize();
        if (!q.startsWith(r)) throw new IOException("path escapes its cache root: " + p + " (root " + root + ")");
        return p;
    }

    public static byte[] boundedRead(InputStream in, long max, String what) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16]; long n = 0; int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > max) throw new LimitException(what + ": more than " + max + " bytes");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    /** Moves tmp to out atomically. If another process already published out (Windows: AccessDenied/AlreadyExists), keep theirs. */
    public static Path publish(Path tmp, Path out) throws IOException {
        try {
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException | AccessDeniedException e) {
            if (!Files.isRegularFile(out)) throw e;   // a real failure, not a lost race
        } finally {
            Files.deleteIfExists(tmp);
        }
        return out;
    }

    /** A temp file next to out, unique per process and call. */
    public static Path tempFor(Path out) throws IOException {
        Files.createDirectories(out.getParent());
        return Files.createTempFile(out.getParent(), "amber", ".tmp");
    }

    // Fabric Loader: ^[a-z][a-z0-9-_]{1,63}$ ; FML (NeoForge/Forge) modId: ^[a-z][a-z0-9_]{1,63}$
    private static final Pattern FABRIC_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern FML_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    public static boolean validModId(amber.ul.model.LoaderKind kind, String id) {
        if (id == null) return false;
        return switch (kind) {
            case FABRIC, QUILT -> FABRIC_ID.matcher(id).matches();
            case NEOFORGE, FORGE -> FML_ID.matcher(id).matches();
            default -> true;   // LIBRARY entries are named by the loader itself
        };
    }
}
