package amber.ul.map;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Class hierarchy + member declarations of a set of jars (game first, then libraries), read with ASM (no code, no
 * frames). JDK classes are resolved lazily from the running JVM. Used for hierarchy completion (brief §4.2 step 3) and
 * propagation (§4.3).
 */
public final class JarIndex {
    public static final class Info {
        public final String name, superName; public final String[] interfaces; public final int access;
        public final Map<String, Integer> methods = new LinkedHashMap<>();   // name+desc -> access
        public final Map<String, Integer> fields = new LinkedHashMap<>();    // name:desc -> access
        public final boolean inGame;
        public Info(String name, String superName, String[] interfaces, int access, boolean inGame) {
            this.name = name; this.superName = superName; this.interfaces = interfaces == null ? new String[0] : interfaces; this.access = access; this.inGame = inGame;
        }
        public String pkg() { int i = name.lastIndexOf('/'); return i < 0 ? "" : name.substring(0, i); }
    }

    public final Map<String, Info> classes = new ConcurrentHashMap<>();
    private final List<ZipFile> libs = new ArrayList<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    public static JarIndex of(Path game, List<Path> libraries) throws IOException {
        JarIndex x = new JarIndex();
        try (ZipFile z = new ZipFile(game.toFile())) {
            List<byte[]> all = new ArrayList<>();
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                try (InputStream in = z.getInputStream(e)) { all.add(in.readAllBytes()); }
            }
            all.parallelStream().forEach(b -> x.add(b, true));
        }
        for (Path l : libraries) if (Files.isRegularFile(l)) x.libs.add(new ZipFile(l.toFile()));
        return x;
    }

    /** An index with no game jar: classes are added with {@link #put} / {@link #addJar}; libraries and the JDK resolve lazily. */
    public static JarIndex empty(List<Path> libraries) throws IOException {
        JarIndex x = new JarIndex();
        for (Path l : libraries) if (Files.isRegularFile(l)) x.libs.add(new ZipFile(l.toFile()));
        return x;
    }
    public void put(Info i) { classes.put(i.name, i); }
    /** Every class of a jar (not META-INF/), parsed in parallel; returns their names. */
    public List<String> addJar(Path jar, boolean inGame) throws IOException {
        List<byte[]> all = new ArrayList<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                try (InputStream in = z.getInputStream(e)) { all.add(in.readAllBytes()); }
            }
        }
        return all.parallelStream().map(b -> add(b, inGame)).toList();
    }

    /** Same as {@link #addJar} for a jar already in memory. */
    public List<String> addEntries(List<JarRemapper.Entry> jar, boolean inGame) {
        return jar.parallelStream().filter(e -> e.name().endsWith(".class") && !e.name().startsWith("META-INF/")).map(e -> add(e.data(), inGame)).toList();
    }

    String add(byte[] b, boolean inGame) {
        ClassReader r = new ClassReader(b);
        Info[] out = new Info[1];
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int access, String name, String sig, String sup, String[] itf) { out[0] = new Info(name, sup, itf, access, inGame); }
            @Override public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) { out[0].fields.put(name + ":" + desc, access); return null; }
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) { out[0].methods.put(name + desc, access); return null; }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        classes.put(out[0].name, out[0]);
        return out[0].name;
    }

    /** Info for a class from the game, a library or the JDK; null if nowhere. */
    public Info get(String name) {
        Info i = classes.get(name);
        if (i != null || missing.contains(name)) return i;
        byte[] b = null;
        synchronized (libs) {
            for (ZipFile z : libs) {
                ZipEntry e = z.getEntry(name + ".class");
                if (e != null) try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); break; } catch (IOException ignored) {}
            }
        }
        if (b == null) try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) { if (in != null) b = in.readAllBytes(); } catch (IOException ignored) {}
        if (b == null) { missing.add(name); return null; }
        add(b, false);
        return classes.get(name);
    }

    /** All supertypes (transitively, super + interfaces), nearest first, without the class itself. */
    public List<Info> ancestors(Info c) {
        List<Info> out = new ArrayList<>(); Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        if (c.superName != null) q.add(c.superName);
        Collections.addAll(q, c.interfaces);
        while (!q.isEmpty()) {
            String n = q.poll();
            if (!seen.add(n)) continue;
            Info i = get(n);
            if (i == null) continue;
            out.add(i);
            if (i.superName != null) q.add(i.superName);
            Collections.addAll(q, i.interfaces);
        }
        return out;
    }

    public void close() throws IOException { for (ZipFile z : libs) z.close(); }
}
