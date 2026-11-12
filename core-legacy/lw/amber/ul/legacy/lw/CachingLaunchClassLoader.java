package amber.ul.legacy.lw;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.*;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.util.*;

/**
 * forge-1.12.2 transformer cache (docs/forge-1.12.2-plan.md §1/§4). A LaunchClassLoader that remembers the final bytes the
 * whole IClassTransformer chain produced for each class (FML's binpatches + notch->SRG deobf + access transformers +
 * coremods + Mixin) and, on later launches, defines the class from those bytes without running the chain.
 *
 * Correctness rules:
 *  - The cache file is per environment: every class-path jar (path, size, mtime), the mods/ tree (same) and the config/
 *    tree (contents minus comments; .properties lines sorted), the Java version and this loader's format. Any change = a
 *    new, empty cache, except states the running game itself wrote (default configs, registry dumps, jars unpacked into
 *    mods/): those are recorded as aliases of the run's key (aliases.txt) and find the same cache.
 *  - Each entry is also keyed by the transformer chain active at load time (the class names, in order), so classes loaded
 *    while tweakers are still registering transformers never mix with full-chain output.
 *  - A miss always goes through the real LaunchClassLoader (the live chain); only its final bytes are recorded, by a
 *    pass-through transformer kept last in the chain, and only for the class this loader is currently defining.
 *  - Exclusions behave exactly like LaunchClassLoader (classLoaderExceptions -> parent, transformerExceptions -> untransformed).
 *  - A transformation that defines other classes (detected by ClassLoader.classes growth) is marked live-only.
 *  - After the chain changes, its first class runs live, so transformers initialise lazily where the cold run had them.
 * Signers are stored per entry (Forge's jar is signed). Lost on a hit: the package's manifest attributes.
 *
 * Interoperates with Mojang's LaunchWrapper (compiled against launchwrapper 1.12, which is not bundled): it subclasses
 * LaunchClassLoader and reads its private state by reflection. Its behaviour follows LaunchClassLoader.findClass; no
 * LaunchWrapper code is copied.
 */
public class CachingLaunchClassLoader extends LaunchClassLoader {
    static final String FORMAT = "amber-ul-legacy-cache/3";
    private final File file;
    private final Map<String, Entry> entries = new HashMap<String, Entry>();
    private DataOutputStream out;
    private final Recorder recorder = new Recorder();
    private List<IClassTransformer> transformers;
    private final Map<String, Class<?>> cachedClasses;
    private final Set<String> invalidClasses, classLoaderExceptions, transformerExceptions;
    private String warmedChain; public int firstCalls;
    private String chainKey = ""; private int chainSize = -1; private IClassTransformer chainLast;
    public int hits, misses, stored, live, verified, differed, sessionOnly;
    /** -Damber.ul.legacy.cacheVerify=true: every hit also runs the live chain, compares, and defines the live bytes. */
    static final boolean VERIFY = Boolean.getBoolean("amber.ul.legacy.cacheVerify");
    private java.lang.reflect.Method runTransformers, untransformName;
    private Writer verifyLog;
    /** ClassLoader.classes (Java 8): every class this loader defined, however it was defined. */
    private final Vector<Class<?>> loaded;
    /** Classes defined through findClass (hits, misses, untransformed exclusions); nested calls count themselves. */
    private final java.util.concurrent.atomic.AtomicInteger defs = new java.util.concurrent.atomic.AtomicInteger();
    public long hitNanos, missNanos;

    /** source = code source URL ("" = none); certs = the class's signers as LaunchClassLoader defined it (Forge's jar is signed,
     *  and a package must not mix signed and unsigned classes: SecurityException "signer information does not match"). */
    static final class Entry { final String source; final byte[] bytes; final Certificate[] certs; Entry(String s, byte[] b, Certificate[] c) { source = s; bytes = b; certs = c; } }

    /** Records the chain's final bytes for the class being defined by findClass on this thread. */
    final class Recorder implements IClassTransformer {
        final ThreadLocal<String> current = new ThreadLocal<String>();
        final ThreadLocal<byte[]> last = new ThreadLocal<byte[]>();
        @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (bytes != null && transformedName != null && transformedName.equals(current.get())) last.set(bytes);
            return bytes;
        }
    }

    @SuppressWarnings("unchecked")
    public CachingLaunchClassLoader(URL[] sources, File cacheDir, File gameDir) throws Exception {
        super(sources);
        transformers = (List<IClassTransformer>) field("transformers");
        cachedClasses = (Map<String, Class<?>>) field("cachedClasses");
        invalidClasses = (Set<String>) field("invalidClasses");
        classLoaderExceptions = (Set<String>) field("classLoaderExceptions");
        transformerExceptions = (Set<String>) field("transformerExceptions");
        if (VERIFY) {
            runTransformers = LaunchClassLoader.class.getDeclaredMethod("runTransformers", String.class, String.class, byte[].class); runTransformers.setAccessible(true);
            untransformName = LaunchClassLoader.class.getDeclaredMethod("untransformName", String.class); untransformName.setAccessible(true);
        }
        Field cf = ClassLoader.class.getDeclaredField("classes"); cf.setAccessible(true);
        loaded = (Vector<Class<?>>) cf.get(this);
        // The recorder must see the chain's final output, but FML reads the list itself (ModClassLoader.addModAPITransformer casts
        // get(size - 1) to the transformer it just registered), so the list stays exactly as registered and the recorder is
        // only appended when it is iterated (LaunchClassLoader.runTransformers and everyone else use for-each).
        Field f = LaunchClassLoader.class.getDeclaredField("transformers"); f.setAccessible(true);
        final IClassTransformer rec = recorder;
        List<IClassTransformer> keepLast = new ArrayList<IClassTransformer>(transformers) {
            @Override public Iterator<IClassTransformer> iterator() {
                final Iterator<IClassTransformer> it = super.iterator();
                return new Iterator<IClassTransformer>() {
                    boolean recDone;
                    @Override public boolean hasNext() { return it.hasNext() || !recDone; }
                    @Override public IClassTransformer next() { if (it.hasNext()) return it.next(); if (recDone) throw new NoSuchElementException(); recDone = true; return rec; }
                    @Override public void remove() { throw new UnsupportedOperationException(); }
                };
            }
        };
        f.set(this, keepLast); transformers = keepLast;
        // The game writes into its own inputs: default configs, registry dumps (FantasticLib's config/.../reference/*.txt,
        // in a new order every start), jars extracted into mods/memory_repo. Every state the running game leaves behind is
        // recorded as an alias of this run's key (aliases.txt), so the next start finds the same cache; a state nobody
        // wrote during a run (the user edited a config, added a mod) has no alias and gets its own key. Only the loading
        // phase counts: once FML reports AVAILABLE (main menu) one last state is recorded and recording stops, so a config
        // edited in-game or by hand while the game runs is a new key on the next start, not an alias.
        final File aliasFile = new File(cacheDir, "aliases.txt");
        final Map<String, String> aliases = new HashMap<String, String>();
        if (aliasFile.isFile()) try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(aliasFile), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) { int tab = line.indexOf('\t'); if (tab > 0) aliases.put(line.substring(0, tab), line.substring(tab + 1)); }
        }
        final String startState = environmentKey(sources, gameDir);
        final String env = aliases.containsKey(startState) ? aliases.get(startState) : startState;
        final URL[] srcs = sources; final File gd = gameDir;
        cacheDir.mkdirs();
        file = new File(cacheDir, env + ".bin");
        if (DEBUG != null) try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(cacheDir, "env-" + startState + ".txt")), StandardCharsets.UTF_8)) { w.write(DEBUG.toString()); DEBUG.setLength(0); }
        final CachingLaunchClassLoader self = this;
        load();
        out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file, true), 1 << 16));
        if (file.length() == 0) { out.writeUTF(FORMAT); out.flush(); }
        Runtime.getRuntime().addShutdownHook(new Thread("amber-ul legacy cache") {
            @Override public void run() { self.closeCache(); }
        });
        System.err.println("[amber-ul legacy] transformer cache " + file + ": " + entries.size() + " classes");
        // with cacheDebug, stats.txt next to the cache every 2 s: a test harness that kills the game (no shutdown hook)
        // still sees the numbers; without it the thread ends with the loading phase
        final File stats = DEBUG != null ? new File(cacheDir, "stats.txt") : null;
        Thread t = new Thread("amber-ul legacy cache aliases") {
            @Override public void run() {
                String lastStamp = stamp(gd);
                boolean recording = true;
                while (recording || stats != null) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                    boolean last = recording && self.loadingDone();
                    String st = recording ? stamp(gd) : lastStamp;
                    if (last) { recording = false; System.err.println("[amber-ul legacy] loading finished: no more cache aliases this run"); }
                    if (!st.equals(lastStamp)) try {
                        lastStamp = st;
                        String k = environmentKey(srcs, gd); if (DEBUG != null) DEBUG.setLength(0);
                        if (!k.equals(env) && !env.equals(aliases.get(k))) {
                            aliases.put(k, env);
                            try (Writer w = new OutputStreamWriter(new FileOutputStream(aliasFile, true), StandardCharsets.UTF_8)) { w.write(k + "\t" + env + "\n"); }
                        }
                    } catch (Exception ignored) { }
                    if (stats != null) try (Writer w = new OutputStreamWriter(new FileOutputStream(stats), StandardCharsets.UTF_8)) {
                        w.write(String.format("file=%s%nloaded=%d%nhits=%d%nhitMs=%.0f%nmisses=%d%nmissMs=%.0f%nstored=%d%nlive=%d%nverified=%d%ndiffered=%d%nsessionOnly=%d%nfirstCalls=%d%n", self.file.getName(), self.entries.size(), self.hits, self.hitNanos / 1e6, self.misses, self.missNanos / 1e6, self.stored, self.live, self.verified, self.differed, self.sessionOnly, self.firstCalls));
                    } catch (IOException ignored) { }
                }
            }
        };
        t.setDaemon(true); t.start();
    }

    /** FML has reached LoaderState.AVAILABLE (all mods loaded, main menu next). Only looks at classes already loaded. */
    boolean loadingDone() {
        try {
            Class<?> loader = findLoadedClass("net.minecraftforge.fml.common.Loader"), state = findLoadedClass("net.minecraftforge.fml.common.LoaderState");
            if (loader == null || state == null) return false;
            Object inst = loader.getMethod("instance").invoke(null);
            @SuppressWarnings({ "unchecked", "rawtypes" }) Object available = Enum.valueOf((Class) state, "AVAILABLE");
            return Boolean.TRUE.equals(loader.getMethod("hasReachedState", state).invoke(inst, available));
        } catch (Throwable e) { return false; }
    }

    private Object field(String name) throws Exception {
        Field f = LaunchClassLoader.class.getDeclaredField(name); f.setAccessible(true); return f.get(this);
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        if (invalidClasses.contains(name)) return super.findClass(name);
        for (String e : classLoaderExceptions) if (name.startsWith(e)) return super.findClass(name);
        Class<?> known = cachedClasses.get(name);
        if (known != null) return known;
        for (String e : transformerExceptions) if (name.startsWith(e)) { Class<?> c = super.findClass(name); if (c.getClassLoader() == this) defs.incrementAndGet(); return c; }
        String ck = chain(), key = ck + '|' + name;
        Entry hit;
        synchronized (entries) { hit = entries.get(key); }
        // The first class after the chain changed goes through the live chain even when cached. Transformers initialise
        // lazily on their first call (FoamFixAT's FoamFixShared.<clinit> loads ArtifactVersion): with every class a hit,
        // that first call would move to wherever the next miss happens, possibly while the class it needs is itself
        // being loaded (ClassCircularityError, RLCraft and Supernova warm runs). This keeps it where the cold run had it.
        if (!ck.equals(warmedChain)) { warmedChain = ck; if (hit != null && hit.bytes != null && !VERIFY) { hit = null; firstCalls++; } }
        if (hit != null && hit.bytes == null) {   // marked live-only: its transformation defines other classes (see below)
            Class<?> c = super.findClass(name); defs.incrementAndGet(); live++; return c;
        }
        if (hit != null && VERIFY) {
            try { hit = verify(name, hit); } catch (Exception e) { throw new ClassNotFoundException(name, e); }
        }
        if (hit != null) {
            long t = System.nanoTime();
            try {
                Class<?> c = define(name, hit);
                cachedClasses.put(name, c); defs.incrementAndGet();
                hits++; hitNanos += System.nanoTime() - t;
                return c;
            } catch (LinkageError e) {
                // e.g. defined meanwhile through another path: fall back to the live chain
                System.err.println("[amber-ul legacy] cache hit for " + name + " not usable (" + e + "), using the live chain");
            }
        }
        long t = System.nanoTime();
        String before = recorder.current.get(); byte[] beforeBytes = recorder.last.get();
        recorder.current.set(name); recorder.last.set(null);
        int sizeBefore = loaded.size(), defsBefore = defs.get();
        try {
            Class<?> c = super.findClass(name);
            defs.incrementAndGet();
            byte[] b = recorder.last.get();
            // Side effects: a transformer defined classes itself while transforming this one (MixinExtras' generated
            // LocalRefImpl, via a reflective defineClass). A cache hit would skip that, so such classes stay live-only.
            int sideEffects = (loaded.size() - sizeBefore) - (defs.get() - defsBefore);
            if (sideEffects > 0) record(key, c, null);
            else if (b != null && c.getClassLoader() == this) record(key, c, b);
            misses++; missNanos += System.nanoTime() - t;
            return c;
        } finally { recorder.current.set(before); recorder.last.set(beforeBytes); }
    }

    static final java.util.regex.Pattern UUID = java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /**
     * Mixin stamps its per-run session id into merged classes: @MixinMerged(sessionId = "<uuid>") and the prefix
     * md<last 6 hex digits> of private merged methods (lambdas), which only the class itself references. Two outputs
     * that are equal once the cached run's id is replaced by the live run's are the same transformation.
     */
    static boolean sameButMixinSession(byte[] cached, byte[] live) {
        if (cached.length != live.length) return false;
        return mixinNormal(new String(cached, StandardCharsets.ISO_8859_1)).equals(mixinNormal(new String(live, StandardCharsets.ISO_8859_1)));
    }
    /** Mixin's anonymous inner classes of a target are renamed ...$Anonymous$<32 hex>, random per run. */
    static final java.util.regex.Pattern ANON = java.util.regex.Pattern.compile("[$]Anonymous[$][0-9a-f]{32}");
    static String mixinNormal(String c) {
        java.util.regex.Matcher m = UUID.matcher(c);
        Set<String> ids = new LinkedHashSet<String>(); while (m.find()) ids.add(m.group());
        for (String id : ids) c = c.replace(id, "<session>").replace("md" + id.substring(30), "md<sess>");
        return ANON.matcher(c).replaceAll("\\$Anonymous\\$<anon>");
    }

    /** Runs the live chain for a cached class the way LaunchClassLoader.findClass would, compares, returns the live entry. */
    private Entry verify(String name, Entry cached) throws Exception {
        String untransformed = (String) untransformName.invoke(this, name);
        byte[] in = getClassBytes(untransformed);
        byte[] liveBytes = (byte[]) runTransformers.invoke(this, untransformed, name, in);
        verified++;
        if (liveBytes == null) throw new ClassNotFoundException(name + " (live chain returned nothing)");
        if (!Arrays.equals(liveBytes, cached.bytes) && sameButMixinSession(cached.bytes, liveBytes)) sessionOnly++;
        else if (!Arrays.equals(liveBytes, cached.bytes)) {
            differed++;
            synchronized (this) {
                if (verifyLog == null) verifyLog = new OutputStreamWriter(new FileOutputStream(new File(file.getParentFile(), "verify.txt")), StandardCharsets.UTF_8);
                verifyLog.write(name + " cached=" + cached.bytes.length + " live=" + liveBytes.length + System.lineSeparator()); verifyLog.flush();
                File dump = new File(file.getParentFile(), "verify-dump");   // both versions, for javap -c -p comparison
                dump.mkdirs();
                try (OutputStream o = new FileOutputStream(new File(dump, name + ".cached.class"))) { o.write(cached.bytes); }
                try (OutputStream o = new FileOutputStream(new File(dump, name + ".live.class"))) { o.write(liveBytes); }
            }
        }
        return new Entry(cached.source, liveBytes, cached.certs);
    }

    private Class<?> define(String name, Entry e) throws ClassNotFoundException {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String pkg = name.substring(0, dot);
            if (getPackage(pkg) == null) {
                try { definePackage(pkg, null, null, null, null, null, null, null); } catch (IllegalArgumentException alreadyDefined) { }
            }
        }
        URL url = null;
        try { if (e.source.length() > 0) url = new URL(e.source); } catch (IOException bad) { }
        return defineClass(name, e.bytes, 0, e.bytes.length, url == null ? null : new CodeSource(url, e.certs));   // no source: as LaunchClassLoader (null CodeSource)
    }

    private void record(String key, Class<?> c, byte[] b) {
        String src = ""; Certificate[] certs = null;
        CodeSource cs = c.getProtectionDomain() == null ? null : c.getProtectionDomain().getCodeSource();
        if (cs != null && cs.getLocation() != null) { src = cs.getLocation().toString(); certs = cs.getCertificates(); }
        synchronized (entries) {
            if (entries.containsKey(key) || out == null) return;
            entries.put(key, new Entry(src, b, certs));
            try {
                if (b == null) { out.writeUTF(key); out.writeUTF(""); out.writeInt(-1); out.writeInt(-2); out.flush(); stored++; return; }   // live-only marker
                out.writeUTF(key); out.writeUTF(src);
                out.writeInt(certs == null ? -1 : certs.length);
                if (certs != null) for (Certificate x : certs) { byte[] enc = x.getEncoded(); out.writeInt(enc.length); out.write(enc); }
                out.writeInt(b.length); out.write(b); out.flush(); stored++;
            }
            catch (java.security.cert.CertificateEncodingException e) { System.err.println("[amber-ul legacy] cache write failed: " + e); closeQuietly(); }
            catch (IOException e) { System.err.println("[amber-ul legacy] cache write failed: " + e); closeQuietly(); }
        }
    }

    /** The transformer chain as a short hash of class names (the recorder excluded); recomputed when the list changes. */
    private String chain() {
        int n = transformers.size(); IClassTransformer lastT = n == 0 ? null : transformers.get(n - 1);   // get/size: the real list, no recorder
        if (n == chainSize && lastT == chainLast) return chainKey;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) { IClassTransformer t = transformers.get(i); sb.append(t.getClass().getName()).append(';'); }
        chainKey = sha(sb.toString()).substring(0, 16); chainSize = n; chainLast = lastT;
        return chainKey;
    }

    private void load() {
        if (!file.isFile()) return;
        CertificateFactory cf;
        try { cf = CertificateFactory.getInstance("X.509"); } catch (java.security.cert.CertificateException e) { throw new IllegalStateException(e); }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            if (!FORMAT.equals(in.readUTF())) { file.delete(); return; }
            while (true) {
                String k, s; byte[] b; Certificate[] certs = null;
                try {
                    k = in.readUTF(); s = in.readUTF();
                    int nc = in.readInt(); if (nc > 64) break;
                    if (nc >= 0) {
                        certs = new Certificate[nc];
                        for (int i = 0; i < nc; i++) { int cl = in.readInt(); if (cl < 0 || cl > (1 << 20)) throw new EOFException(); byte[] enc = new byte[cl]; in.readFully(enc); certs[i] = cf.generateCertificate(new ByteArrayInputStream(enc)); }
                    }
                    int len = in.readInt();
                    if (len == -2) b = null;   // live-only marker
                    else { if (len < 0 || len > (64 << 20)) break; b = new byte[len]; in.readFully(b); }
                }
                catch (EOFException truncated) { break; }   // the game was killed mid-record: drop the tail
                entries.put(k, new Entry(s, b, certs));
            }
        } catch (IOException | java.security.cert.CertificateException e) { System.err.println("[amber-ul legacy] cache unreadable, starting empty: " + e); entries.clear(); file.delete(); }
    }

    void closeCache() {
        synchronized (entries) { closeQuietly(); }
        System.err.printf("[amber-ul legacy] cache: %d hits (%.0f ms), %d misses (%.0f ms), %d stored%n", hits, hitNanos / 1e6, misses, missNanos / 1e6, stored);
    }
    private void closeQuietly() { try { if (out != null) out.close(); } catch (IOException ignored) { } out = null; }

    // ---- environment key ------------------------------------------------------------------------------------------

    static String environmentKey(URL[] sources, File gameDir) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        upd(md, FORMAT); upd(md, System.getProperty("java.version")); upd(md, System.getProperty("os.arch"));
        for (URL u : sources) {
            upd(md, u.toString());
            try { File f = new File(u.toURI()); upd(md, f.length() + "/" + f.lastModified()); } catch (Exception notAFile) { }
        }
        tree(md, new File(gameDir, "mods"), false);
        tree(md, new File(gameDir, "config"), true);
        return hex(md.digest()).substring(0, 24);
    }

    /** Files under dir in a stable order: (relative path, size, mtime), or (relative path, content without '#' comment lines) when contents=true. */
    static void tree(MessageDigest md, File dir, boolean contents) throws IOException {
        upd(md, "tree:" + dir.getName());
        List<File> files = new ArrayList<File>(); walk(dir, files);
        Collections.sort(files, new Comparator<File>() { @Override public int compare(File a, File b) { return a.getPath().compareTo(b.getPath()); } });
        for (File f : files) {
            String r = rel(dir, f);
            if (!contents) { upd(md, r); upd(md, f.length() + "/" + f.lastModified()); continue; }
            upd(md, r); upd(md, configHash(f));
        }
    }

    /**
     * A config file's content for the key: '#' comment lines left out (java.util.Properties.store stamps the date into
     * one, and FML rewrites splash.properties on every start; comments never change what a transformer does), and
     * .properties lines sorted (they are written in hash-table order, which changes between runs).
     */
    static String configHash(File f) throws IOException {
        List<String> lines = new ArrayList<String>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.ISO_8859_1))) {
            for (String line; (line = r.readLine()) != null; ) if (!line.trim().startsWith("#")) lines.add(line);
        }
        if (f.getName().endsWith(".properties")) Collections.sort(lines);
        StringBuilder sb = new StringBuilder(); for (String line : lines) sb.append(line).append('\n');
        return sha(sb.toString()).substring(0, 32);
    }
    /** Cheap change detector for mods/ and config/: paths, sizes and modification times. */
    static String stamp(File gameDir) {
        StringBuilder sb = new StringBuilder();
        for (String d : new String[] { "mods", "config" }) {
            List<File> files = new ArrayList<File>(); walk(new File(gameDir, d), files);
            for (File f : files) sb.append(f.getPath()).append(' ').append(f.length()).append(' ').append(f.lastModified()).append('\n');
        }
        return sha(sb.toString());
    }
    static String rel(File dir, File f) { return f.getPath().substring(dir.getPath().length()).replace(File.separatorChar, '/'); }
    static void walk(File d, List<File> out) {
        File[] l = d.listFiles(); if (l == null) return;
        for (File f : l) { if (f.isDirectory()) walk(f, out); else out.add(f); }
    }
    /** -Damber.ul.legacy.cacheDebug=true: every environment input is also written to env-<key>.txt next to the cache. */
    static final StringBuilder DEBUG = Boolean.getBoolean("amber.ul.legacy.cacheDebug") ? new StringBuilder() : null;
    static void upd(MessageDigest md, String s) {
        if (DEBUG != null) DEBUG.append(s.length() > 300 ? s.substring(0, 300) + "..." : s).append('\n'); byte[] b = s.getBytes(StandardCharsets.UTF_8); for (int i = 24; i >= 0; i -= 8) md.update((byte) (b.length >>> i)); md.update(b); }
    static String sha(String s) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String hex(byte[] b) { StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString(); }
}
