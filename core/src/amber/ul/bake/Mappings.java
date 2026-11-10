package amber.ul.bake;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.tree.*;
import net.fabricmc.tinyremapper.IMappingProvider;
import net.fabricmc.tinyremapper.TinyUtils;
import org.objectweb.asm.commons.Remapper;

/**
 * One direction (from -> to) of the combined official/intermediary/named(Mojmap) mapping set.
 * Bytecode remapping goes through tiny-remapper (inheritance-aware, see provider()); the lookup maps here are for
 * text formats tiny-remapper doesn't know about: access wideners and mixin refmaps.
 */
public final class Mappings implements amber.ul.map.TextNames {
    public final String from, to, fingerprint; public final Path file;
    private final java.util.function.Supplier<MappingTree> treeSource;
    private MappingTree tree;
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> members = new HashMap<>();   // owner.name+desc / owner.name:desc  (from ns)
    private final Map<String, String> byName = new HashMap<>();    // name -> to-name, only when the name is unambiguous (intermediary method_/field_)
    private final Remapper descRemapper = new Remapper() { @Override public String map(String n) { return classes.getOrDefault(n, n); } };

    private Mappings(String from, String to, String fp, Path file, java.util.function.Supplier<MappingTree> tree) { this.from = from; this.to = to; this.fingerprint = fp; this.file = file; this.treeSource = tree; }

    public static Mappings identity(String ns) { return new Mappings(ns, ns, "identity", null, null); }

    /** Several directions of one file share one lazily read tree: on a warm start (all bakes cached) it is never read. */
    public static final class Set3 {
        private final Path file; private final String hash; private MemoryMappingTree tree;
        public Set3(Path file) throws IOException { this.file = file; this.hash = Hash.file(file); }
        synchronized MappingTree tree() {
            if (tree == null) try { long t = System.nanoTime(); tree = new MemoryMappingTree(); MappingReader.read(file, tree);
                System.out.printf("[amber-ul] 读取映射表 %.1f s%n", (System.nanoTime() - t) / 1e9); }
            catch (IOException e) { throw new UncheckedIOException(e); }
            return tree;
        }
        public Mappings get(String from, String to) { return new Mappings(from, to, hash + ":" + from + ">" + to, file, this::tree); }
    }

    private synchronized void ensure() {
        if (tree != null || treeSource == null) return;
        MappingTree t = treeSource.get();
        int f = t.getNamespaceId(from), d = t.getNamespaceId(to);
        if (f == MappingTreeView.NULL_NAMESPACE_ID || d == MappingTreeView.NULL_NAMESPACE_ID) throw new IllegalStateException("namespace missing: " + from + "/" + to);
        Mappings m = this;
        Map<String, Set<String>> seen = new HashMap<>();
        for (MappingTree.ClassMapping c : t.getClasses()) {
            String cf = c.getName(f), cd = c.getName(d);
            if (cf == null) continue;
            if (cd == null) cd = cf;
            m.classes.put(cf, cd);
            for (MappingTree.MethodMapping mm : c.getMethods()) put(m, seen, cf + "." + mm.getName(f) + mm.getDesc(f), mm.getName(f), mm.getName(d));
            for (MappingTree.FieldMapping fm : c.getFields()) put(m, seen, cf + "." + fm.getName(f) + ":" + fm.getDesc(f), fm.getName(f), fm.getName(d));
        }
        seen.forEach((n, targets) -> { if (targets.size() == 1) m.byName.put(n, targets.iterator().next()); });
        tree = t;
    }

    private static void put(Mappings m, Map<String, Set<String>> seen, String key, String fromName, String toName) {
        if (fromName == null) return;
        if (toName == null) toName = fromName;
        m.members.put(key, toName);
        seen.computeIfAbsent(fromName, k -> new HashSet<>()).add(toName);
    }

    public IMappingProvider provider() { ensure(); return TinyUtils.createMappingProvider(tree, from, to); }
    public boolean isIdentity() { return treeSource == null; }
    @Override public String mapClass(String internal) { ensure(); return classes.getOrDefault(internal, internal); }
    @Override public String mapDesc(String desc) { ensure(); return desc.startsWith("(") ? descRemapper.mapMethodDesc(desc) : descRemapper.mapDesc(desc); }
    /** owner may be null; desc may be null (name-only lookup, used by refmaps / wildcard targets). */
    @Override public String mapMethod(String owner, String name, String desc) { ensure();
        String r = owner != null && desc != null ? members.get(owner + "." + name + desc) : null;
        return r != null ? r : byName.getOrDefault(name, name);
    }
    @Override public String mapField(String owner, String name, String desc) { ensure();
        String r = owner != null && desc != null ? members.get(owner + "." + name + ":" + desc) : null;
        return r != null ? r : byName.getOrDefault(name, name);
    }
}
