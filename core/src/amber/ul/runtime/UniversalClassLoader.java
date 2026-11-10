package amber.ul.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.function.Function;
import java.util.jar.*;

/**
 * One class loader for the game and every mod, whatever loader it was written for. No modlauncher, no JPMS layers:
 * this sidesteps the split-package / module-layer problems Connector documents.
 *
 * Launch-time class cache, keyed by (baked jars + transformer fingerprints):
 *  - mode "safe" (default): remembers which classes no transformer changed; on a warm start those are defined straight
 *    from the jar and no transformer (Mixin) is consulted. Classes a transformer did change are transformed live every
 *    start, so Mixin still sees every target class (its config plugins, synthetic classes etc. keep working).
 *  - mode "full": also stores the transformed bytes of changed classes and defines them without Mixin on a warm start.
 *  - mode "off".
 */
public final class UniversalClassLoader extends ClassLoader {
    static { registerAsParallelCapable(); }
    private final Map<String, Path> index = new HashMap<>();              // first jar wins (classes)
    private final Map<String, List<Path>> all = new HashMap<>();          // every jar (resources)
    private final Map<Path, JarFile> open = new HashMap<>();
    private final Map<Path, ProtectionDomain> domains = new HashMap<>();
    private final List<ClassTransformer> transformers;
    private final Path cacheFile; private final String mode;
    private final Set<String> untouched = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, byte[]> touched = Collections.synchronizedMap(new HashMap<>());
    private volatile boolean dirty;
    private final boolean warm;
    /** Last resort for classes nobody has bytes for (Mixin synthetic classes). */
    public volatile Function<String, byte[]> generator = n -> null;
    public volatile int fromCache, transformed, untouchedLive;
    /** Classes a transformer actually changed in this run. */
    public final Set<String> changed = Collections.synchronizedSet(new TreeSet<>());

    public UniversalClassLoader(List<Path> jars, List<ClassTransformer> transformers, Path cacheDir, String mode, ClassLoader parent) throws IOException {
        super("amber-universal", parent);
        this.transformers = transformers; this.mode = mode;
        StringBuilder fp = new StringBuilder("v2|" + mode);
        for (Path j : jars) {
            JarFile jf = new JarFile(j.toFile()); open.put(j, jf);
            domains.put(j, new ProtectionDomain(new CodeSource(j.toUri().toURL(), (CodeSigner[]) null), null, this, null));
            for (JarEntry e : Collections.list(jf.entries())) if (!e.isDirectory()) { index.putIfAbsent(e.getName(), j); all.computeIfAbsent(e.getName(), k -> new ArrayList<>(1)).add(j); }
            fp.append('|').append(j.getFileName()).append(':').append(Files.size(j)).append(':').append(Files.getLastModifiedTime(j).toMillis());
        }
        for (ClassTransformer t : transformers) fp.append('|').append(t.fingerprint());
        String key = amber.ul.bake.Hash.str(fp.toString());
        Files.createDirectories(cacheDir);
        this.cacheFile = cacheDir.resolve("classes-" + key.substring(0, 16) + ".bin");
        if (!mode.equals("off") && Files.exists(cacheFile)) {
            try { readCache(cacheFile, untouched, touched); }
            catch (Exception e) {   // torn / foreign / corrupt file: start cold
                untouched.clear(); touched.clear();
                System.out.println("[amber-ul] 类缓存无效, 冷启动: " + cacheFile.getFileName() + " (" + e + ")");
                try { Files.deleteIfExists(cacheFile); } catch (IOException ignored) {}
            }
        }
        warm = !untouched.isEmpty();
    }

    public boolean cacheWarm() { return warm; }
    public boolean isClassLoaded(String name) { return findLoadedClass(name) != null; }
    public boolean owns(String internalName) { return index.containsKey(internalName + ".class"); }

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                String res = name.replace('.', '/') + ".class";
                // overrides may add classes no jar has (the bridge's amber-impl replacements bring new impl classes, e.g.
                // networking's AmberAddon): those are ours too, never the parent's
                if (index.containsKey(res) || overrides.containsKey(res)) c = findClass(name);
                else try { c = getParent().loadClass(name); }
                catch (ClassNotFoundException e) {
                    byte[] b = generator.apply(name);
                    if (b == null) throw e;
                    c = defineClass(name, b, 0, b.length);
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
        String internal = name.replace('.', '/');
        Path jar = index.get(internal + ".class");
        byte[] b = mode.equals("full") ? touched.get(internal) : null;
        if (b != null) fromCache++;
        else {
            b = raw(internal + ".class");
            if (b == null) throw new ClassNotFoundException(name);
            if (untouched.contains(internal)) fromCache++;
            else {
                byte[] in = b;
                try { for (ClassTransformer t : transformers) b = t.transform(internal, b); }
                catch (RuntimeException | Error e) {   // static initialisers swallow the cause; report it once, in full
                    System.err.println("[amber-ul] 变换 " + internal + " 失败:"); e.printStackTrace();
                    throw e;
                }
                transformed++;
                if (b != in) changed.add(internal);
                if (!mode.equals("off")) {
                    if (b == in) { if (untouched.add(internal)) dirty = true; else untouchedLive++; }
                    else if (mode.equals("full")) { touched.put(internal, b); dirty = true; }
                }
            }
        }
        definePkg(name, jar);
        return defineClass(name, b, 0, b.length, jar == null ? null : domains.get(jar));
    }

    private void definePkg(String name, Path jar) {
        int i = name.lastIndexOf('.'); if (i < 0) return;
        String pkg = name.substring(0, i);
        if (getDefinedPackage(pkg) != null) return;
        Manifest mf = null;
        try { if (jar != null) mf = open.get(jar).getManifest(); } catch (IOException ignored) {}
        // as URLClassLoader: the package's own manifest section ("Name: a/b/c/") first, then the main attributes
        // (Forge: ForgeVersion reads net/minecraftforge/versions/forge/'s Specification-Version - "Missing forge spec")
        Attributes main = mf == null ? new Attributes() : mf.getMainAttributes(), sec = mf == null ? null : mf.getAttributes(pkg.replace('.', '/') + "/");
        java.util.function.Function<Attributes.Name, String> at = n -> { String v = sec == null ? null : sec.getValue(n); return v != null ? v : main.getValue(n); };
        try { definePackage(pkg, at.apply(Attributes.Name.SPECIFICATION_TITLE), at.apply(Attributes.Name.SPECIFICATION_VERSION), at.apply(Attributes.Name.SPECIFICATION_VENDOR),
                at.apply(Attributes.Name.IMPLEMENTATION_TITLE), at.apply(Attributes.Name.IMPLEMENTATION_VERSION), at.apply(Attributes.Name.IMPLEMENTATION_VENDOR), null); }
        catch (IllegalArgumentException ignored) {} // raced
    }

    /** Class bytes that replace a jar's (bake-time mixin fixes): "a/b/C.class" -> bytes. */
    public final Map<String, byte[]> overrides = new HashMap<>();

    private byte[] raw(String res) {
        byte[] o = overrides.get(res); if (o != null) return o;
        Path j = index.get(res); if (j == null) return null;
        JarFile jf = open.get(j);
        try (InputStream in = jf.getInputStream(jf.getJarEntry(res))) { return amber.ul.bake.SafeFiles.boundedRead(in, amber.ul.bake.SafeFiles.ENTRY_MAX, res); } catch (IOException e) { return null; }
    }

    /** Bytes before any runtime transformer (for Mixin's bytecode provider); falls back to the parent for libs/JDK. */
    public byte[] classBytes(String internalName) {
        byte[] b = raw(internalName + ".class");
        if (b != null) return b;
        try (InputStream in = getParent().getResourceAsStream(internalName + ".class")) { return in == null ? null : in.readAllBytes(); }
        catch (IOException e) { return null; }
    }

    @Override protected URL findResource(String name) {
        if (name.startsWith("/")) name = name.substring(1);   // modlauncher's loaders accept "/a/B.class" (ModernFix's Mojmap probe)
        Path j = index.get(name); return j == null ? null : url(j, name);
    }
    /** Jars that are also on the parent's classpath (game libraries): the parent already reports their resources. */
    public final Set<Path> alsoInParent = new HashSet<>();
    @Override protected Enumeration<URL> findResources(String name) {
        if (name.startsWith("/")) name = name.substring(1);
        List<URL> l = new ArrayList<>();
        for (Path j : all.getOrDefault(name, List.of())) if (!alsoInParent.contains(j)) l.add(url(j, name));
        return Collections.enumeration(l);
    }
    private static URL url(Path j, String name) {
        try { return new URL("jar:" + j.toUri() + "!/" + name); } catch (MalformedURLException e) { return null; }
    }

    public byte[] resourceBytes(String name) { return raw(name); }
    public Set<String> entries(Path jar) { Set<String> s = new LinkedHashSet<>(); index.forEach((k, v) -> { if (v.equals(jar)) s.add(k); }); return s; }

    /*
     * Class cache file (security scan H15): magic "AMBRCC", format version, entry counts, entries, then a SHA-256 of
     * everything before it. Counts and lengths are capped before anything is allocated. Written to a unique temp file,
     * moved atomically; two game processes never share a temp name.
     */
    static final byte[] MAGIC = {'A', 'M', 'B', 'R', 'C', 'C'};
    static final int FORMAT = 3, MAX_ENTRIES = 1 << 20, MAX_CLASS = 64 << 20;

    public static void readCache(Path f, Set<String> untouched, Map<String, byte[]> touched) throws IOException {
        byte[] all = Files.readAllBytes(f);
        if (all.length < MAGIC.length + 4 + 8 + 32) throw new IOException("too short");
        byte[] body = Arrays.copyOf(all, all.length - 32), sum = Arrays.copyOfRange(all, all.length - 32, all.length);
        if (!MessageDigest.isEqual(sha256(body), sum)) throw new IOException("checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(body));
        byte[] m = new byte[MAGIC.length]; in.readFully(m);
        if (!Arrays.equals(m, MAGIC) || in.readInt() != FORMAT) throw new IOException("not a class cache of format " + FORMAT);
        int n = in.readInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IOException("bad entry count " + n);
        for (int i = 0; i < n; i++) untouched.add(in.readUTF());
        n = in.readInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IOException("bad entry count " + n);
        for (int i = 0; i < n; i++) {
            String k = in.readUTF(); int len = in.readInt();
            if (len < 0 || len > MAX_CLASS || len > in.available()) throw new IOException("bad entry length " + len + " for " + k);
            byte[] b = new byte[len]; in.readFully(b); touched.put(k, b);
        }
        if (in.available() != 0) throw new IOException("trailing bytes");
    }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public static void writeCache(Path f, Collection<String> u, Map<String, byte[]> t) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        try (DataOutputStream out = new DataOutputStream(bo)) {
            out.write(MAGIC); out.writeInt(FORMAT);
            out.writeInt(u.size()); for (String s : u) out.writeUTF(s);
            out.writeInt(t.size());
            for (var e : t.entrySet()) { out.writeUTF(e.getKey()); out.writeInt(e.getValue().length); out.write(e.getValue()); }
        }
        byte[] body = bo.toByteArray();
        Path tmp = Files.createTempFile(f.getParent(), f.getFileName().toString(), ".tmp");
        try {
            try (OutputStream o = Files.newOutputStream(tmp)) { o.write(body); o.write(sha256(body)); }
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }

    public synchronized void saveCache() throws IOException {
        if (!dirty || mode.equals("off")) return;
        List<String> u; Map<String, byte[]> t;
        synchronized (untouched) { u = new ArrayList<>(untouched); }
        synchronized (touched) { t = new HashMap<>(touched); }
        writeCache(cacheFile, u, t);
        dirty = false;
    }
    public int cachedEntries() { return untouched.size() + touched.size(); }
}
