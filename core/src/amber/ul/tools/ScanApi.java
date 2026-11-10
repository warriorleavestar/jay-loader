package amber.ul.tools;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Lists every bytecode reference to a package prefix (default net/fabricmc/loader/) in the given jars, recursing into
 * nested jars (META-INF/jars, META-INF/jarjar). Output: one line per member with the count and which jars use it.
 *   java amber.ul.tools.ScanApi [--prefix net/fabricmc/loader/] a.jar b.jar ...
 */
public final class ScanApi {
    static String prefix = "net/fabricmc/loader/";
    static final Map<String, Set<String>> users = new TreeMap<>();

    public static void main(String[] a) throws IOException {
        List<String> jars = new ArrayList<>(Arrays.asList(a));
        if (!jars.isEmpty() && jars.get(0).equals("--prefix")) { prefix = jars.get(1); jars = jars.subList(2, jars.size()); }
        for (String j : jars) try (InputStream in = Files.newInputStream(Path.of(j))) { scan(in, Path.of(j).getFileName().toString()); }
        for (var e : users.entrySet()) System.out.printf("%-110s %3d  %s%n", e.getKey(), e.getValue().size(), String.join(",", e.getValue().stream().limit(4).toList()) + (e.getValue().size() > 4 ? ",..." : ""));
    }

    static void scan(InputStream jar, String name) throws IOException {
        ZipInputStream z = new ZipInputStream(jar);
        for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
            if (e.getName().endsWith(".jar")) { scan(new ByteArrayInputStream(z.readAllBytes()), e.getName().substring(e.getName().lastIndexOf('/') + 1)); continue; }
            if (!e.getName().endsWith(".class")) continue;
            String jarName = name.replaceAll("[-+][0-9].*", "");
            new ClassReader(z.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int v, int acc, String n, String sig, String sup, String[] itf) {
                    if (sup != null && sup.startsWith(prefix)) add("extends " + sup, jarName);
                    if (itf != null) for (String i : itf) if (i.startsWith(prefix)) add("implements " + i, jarName);
                }
                @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) { if (owner.startsWith(prefix)) add(owner + "." + mn + md, jarName); }
                        @Override public void visitFieldInsn(int op, String owner, String fn, String fd) { if (owner.startsWith(prefix)) add(owner + "." + fn + ":" + fd, jarName); }
                        @Override public void visitTypeInsn(int op, String t) { if (t.startsWith(prefix)) add("type " + t, jarName); }
                        @Override public void visitLdcInsn(Object c) { if (c instanceof Type t && t.getInternalName().startsWith(prefix)) add("class-literal " + t.getInternalName(), jarName); }
                    };
                }
            }, ClassReader.SKIP_FRAMES);
        }
    }
    static void add(String k, String jar) { users.computeIfAbsent(k, x -> new TreeSet<>()).add(jar); }
}
