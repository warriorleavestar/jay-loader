package amber.ul.map;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The names one remap run applies: obf -> target, for classes, members and method parameters, with member references
 * resolved through the class hierarchy (a call to B.foo where foo is declared in A uses A's name).
 *
 * Resolution order, written to give the same answer as NeoForge's ART 2.0.3 (behaviour read from its source, not code):
 * a member is looked up in the owner, then depth-first in the superclass and then each interface in declaration order;
 * the first hit wins; a static or private method reached through an interface is skipped (JVMS 5.4.3.3). Fields match
 * on name + desc. Parameter names are indexed by local-variable slot ("this" at 0 for instance methods, two slots for
 * long/double), built only for methods the parameter source lists and that take arguments.
 *
 * Unknown classes: an inner class takes its outer's mapped name + '$' + its own simple name; anything else is kept.
 */
public final class RemapNames implements NameSource {
    final MappingTree t; final int ns; final JarIndex h;
    /** owner.name+desc -> slot names (null entries possible); a key present = the method is listed by the parameter source. */
    final Map<String, String[]> params = new HashMap<>();
    private final Map<String, String> clsCache = new ConcurrentHashMap<>();
    private final Map<String, String> declCache = new ConcurrentHashMap<>();
    private static final String NONE = "\0";

    /**
     * @param t tree with names in namespace {@code ns} (already completed against the jar)
     * @param paramSource raw (uncompleted) tree whose method rows define "listed" + parameter names, column {@code paramNs}
     */
    public RemapNames(MappingTree t, int ns, JarIndex h, MappingTree paramSource, int paramNs) {
        this.t = t; this.ns = ns; this.h = h;
        for (MappingTree.ClassRow c : paramSource.classes.values()) {
            JarIndex.Info ji = h.get(c.obf);
            for (MappingTree.MemberRow m : c.methods.values()) {
                if (m.desc.contains("()")) { params.put(c.obf + "." + m.obf + m.desc, null); continue; }
                Integer acc = ji == null ? null : ji.methods.get(m.obf + m.desc);
                List<String> slots = new ArrayList<>();
                if (acc != null && (acc & Opcodes.ACC_STATIC) == 0) slots.add("this");
                Type[] args = Type.getArgumentTypes(m.desc);
                for (int x = 0; x < args.length; x++) {
                    MappingTree.Row p = m.params == null ? null : m.params.get(x);
                    String n = p == null ? null : p.get(paramNs);
                    slots.add(n);
                    if (args[x].getSize() == 2) slots.add(n);
                }
                params.put(c.obf + "." + m.obf + m.desc, slots.toArray(new String[0]));
            }
        }
    }

    @Override public String cls(String obf) {
        String r = clsCache.get(obf);
        if (r != null) return r;
        MappingTree.ClassRow c = t.classes.get(obf);
        if (c != null && c.get(ns) != null) r = c.get(ns);
        else { int d = obf.lastIndexOf('$'); r = d < 0 ? obf : cls(obf.substring(0, d)) + "$" + obf.substring(d + 1); }
        clsCache.put(obf, r);
        return r;
    }

    @Override public String method(String owner, String name, String desc) {
        String d = declaring(owner, name + desc, false);
        if (d == null) return name;
        MappingTree.ClassRow c = t.classes.get(d); if (c == null) return name;
        MappingTree.MemberRow m = c.methods.get(name + desc);
        return m == null || m.get(ns) == null ? name : m.get(ns);
    }

    @Override public String field(String owner, String name, String desc) {
        String d = declaring(owner, name + ":" + desc, true);
        if (d == null) return name;
        MappingTree.ClassRow c = t.classes.get(d); if (c == null) return name;
        MappingTree.MemberRow f = c.fields.get(name + ":" + desc);
        return f == null || f.get(ns) == null ? name : f.get(ns);
    }

    /** Slot name for a parameter of a method declared in {@code owner}; {@code def} when the method has no list or the slot is out of range. */
    @Override public String param(String owner, String name, String desc, int slot, String def) {
        String key = owner + "." + name + desc;
        if (!params.containsKey(key)) return def;
        String[] s = params.get(key);
        return s != null && slot >= 0 && slot < s.length ? s[slot] : def;
    }

    /** The class whose declaration a reference (owner, key) resolves to, or null. */
    String declaring(String owner, String key, boolean field) {
        if (owner == null || owner.isEmpty() || owner.charAt(0) == '[') return null;
        String ck = owner + "." + key;
        String r = declCache.get(ck);
        if (r != null) return r == NONE ? null : r;
        r = resolve(owner, key, field);
        declCache.put(ck, r == null ? NONE : r);
        return r;
    }

    private String resolve(String cls, String key, boolean field) {
        JarIndex.Info i = h.get(cls);
        if (i == null) {
            MappingTree.ClassRow c = t.classes.get(cls);
            return c != null && (field ? c.fields : c.methods).containsKey(key) ? cls : null;
        }
        if ((field ? i.fields : i.methods).containsKey(key)) return cls;
        List<String> parents = new ArrayList<>();
        if (i.superName != null) parents.add(i.superName);
        Collections.addAll(parents, i.interfaces);
        for (String p : parents) {
            String d = declaring(p, key, field);   // memoised per (class, member), like ART's per-class tables
            if (d == null) continue;
            if (!field) {
                JarIndex.Info pi = h.get(p);
                if (pi != null && (pi.access & Opcodes.ACC_INTERFACE) != 0) {
                    Integer acc = h.get(d).methods.get(key);
                    if (acc != null && (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue;
                }
            }
            return d;
        }
        return null;
    }
}
