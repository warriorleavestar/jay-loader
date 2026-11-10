package amber.ul.fml;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/**
 * Services a jar declares only in module-info ("provides X with Y", e.g. eventbus 6's IEventBusEngine). In the universal
 * loader every jar is on one unnamed class path, where ServiceLoader reads META-INF/services only. This writes one small
 * jar with the missing META-INF/services files (content-keyed, cached) that goes into the universal loader too.
 */
public final class ModuleServices {
    private ModuleServices() {}

    public static Path generate(List<Path> jars, Path dir) throws IOException {
        Map<String, Set<String>> add = new TreeMap<>();
        StringBuilder key = new StringBuilder("module-services-2");
        for (Path j : jars) {
            if (!Files.isRegularFile(j)) continue;
            try (JarFile jf = new JarFile(j.toFile(), false)) {
                JarEntry mi = jf.getJarEntry("module-info.class");
                if (mi == null) continue;
                Map<String, List<String>> prov = new HashMap<>();
                try (InputStream in = jf.getInputStream(mi)) {
                    new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public ModuleVisitor visitModule(String n, int a, String v) {
                            return new ModuleVisitor(Opcodes.ASM9) {
                                @Override public void visitProvide(String svc, String... impls) {
                                    List<String> l = prov.computeIfAbsent(svc.replace('/', '.'), k -> new ArrayList<>());
                                    for (String i : impls) l.add(i.replace('/', '.'));
                                }
                            };
                        }
                    }, 0);
                }
                for (var e : prov.entrySet()) {
                    if (jf.getJarEntry("META-INF/services/" + e.getKey()) != null || e.getKey().startsWith("java.")) continue;   // JDK service types: modlauncher's own launch consumer etc., not ours to offer
                    add.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).addAll(e.getValue());
                    key.append('|').append(j.getFileName()).append(':').append(e.getKey()).append('=').append(e.getValue());
                }
            }
        }
        if (add.isEmpty()) return null;
        Path out = amber.ul.bake.CacheUse.mark(dir.resolve("module-services-" + amber.ul.bake.Hash.str(key.toString()).substring(0, 16) + ".jar"));
        if (Files.exists(out)) return out;
        Files.createDirectories(dir);
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (var e : add.entrySet()) {
                jo.putNextEntry(amber.ul.bake.SafeFiles.entry("META-INF/services/" + e.getKey()));
                jo.write((String.join("\n", e.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
                jo.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[amber-ul] 只在 module-info 里声明的服务: " + add);
        return out;
    }
}
