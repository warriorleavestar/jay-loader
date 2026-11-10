package amber.ul.map;

import java.util.*;
import org.objectweb.asm.Opcodes;

/**
 * Completes a {@link MappingTree} against the obf jar (docs/mapping-brief.md §4.2 steps 3-6), before any table is used:
 *  1. Field descriptors for desc-less rows (TSRG2) from the jar: by (owner, name) only when unique.
 *  2. Stale rows (not in the jar) are dropped and counted.
 *  3. Override groups (union-find over every virtual method of the game and the library/JDK ancestors it overrides):
 *     a name from any member of the group is copied onto every member; a group rooted outside the game jar keeps the
 *     identity (a library/JDK name can't be renamed); two different names in one group = conflict (reported).
 *  4. Identity for <init>/<clinit>, enum values/valueOf, and overrides of library methods.
 *  5. Inner classes without an entry: map(outer) + '$' + obf simple name.
 * Rules (brief §4.3): private methods are their own group; static methods don't join groups; package-private methods
 * only override within the same package.
 */
public final class Completer {
    public final MappingTree t; public final JarIndex jar;
    public final Map<String, Integer> stats = new TreeMap<>();
    public final List<String> conflicts = new ArrayList<>();
    /** Namespaces that are per-declaration attributes, not names (TSRG2 "id"): never copied across an override group. */
    public final Set<String> attributes = new HashSet<>(Set.of("srgid"));
    public Completer(MappingTree t, JarIndex jar) { this.t = t; this.jar = jar; }
    void count(String k) { stats.merge(k, 1, Integer::sum); }

    public void run() {
        fieldDescs();
        dropStale();
        methodGroups();
        identities();
        innerClasses();
    }

    void fieldDescs() {
        for (MappingTree.ClassRow c : t.classes.values()) {
            JarIndex.Info ji = jar.classes.get(c.obf);
            List<MappingTree.MemberRow> fix = new ArrayList<>();
            for (MappingTree.MemberRow f : c.fields.values()) if (f.desc == null) fix.add(f);
            if (fix.isEmpty()) continue;
            for (MappingTree.MemberRow f : fix) {
                c.fields.remove(f.obf + ":");
                if (ji == null) { count("field desc: owner not in jar"); continue; }
                String found = null; int n = 0;
                for (String k : ji.fields.keySet()) if (k.startsWith(f.obf + ":")) { found = k.substring(f.obf.length() + 1); n++; }
                if (n != 1) { count(n == 0 ? "field desc: not in jar" : "field desc: ambiguous name"); continue; }
                MappingTree.MemberRow target = c.field(f.obf, found);
                for (int i = 1; i < t.namespaces.size(); i++) if (f.get(i) != null && target.get(i) == null) target.set(i, f.get(i));
                target.isStatic |= f.isStatic;
                count("field desc: resolved from jar");
            }
        }
    }

    void dropStale() {
        for (Iterator<MappingTree.ClassRow> it = t.classes.values().iterator(); it.hasNext(); ) {
            MappingTree.ClassRow c = it.next();
            JarIndex.Info ji = jar.classes.get(c.obf);
            if (ji == null) { it.remove(); count("stale class dropped"); continue; }
            c.methods.values().removeIf(m -> { boolean s = !ji.methods.containsKey(m.obf + m.desc); if (s) count("stale method dropped"); return s; });
            c.fields.values().removeIf(f -> { boolean s = !ji.fields.containsKey(f.obf + ":" + f.desc); if (s) count("stale field dropped"); return s; });
        }
        // every jar class and member gets a row (holes where no namespace speaks)
        for (JarIndex.Info ji : jar.classes.values()) {
            if (!ji.inGame) continue;
            MappingTree.ClassRow c = t.cls(ji.name);
            for (String k : ji.methods.keySet()) { int p = k.indexOf('('); c.method(k.substring(0, p), k.substring(p)); }
            for (String k : ji.fields.keySet()) { int p = k.indexOf(':'); c.field(k.substring(0, p), k.substring(p + 1)); }
        }
    }

    // ---- override groups ----
    private final Map<String, String> parent = new HashMap<>();
    private String find(String x) {
        String r = x;
        while (true) { String p = parent.get(r); if (p == null || p.equals(r)) break; r = p; }
        // path compression
        String y = x; while (!y.equals(r)) { String p = parent.get(y); parent.put(y, r); y = p; }
        return r;
    }
    private void union(String a, String b) { String ra = find(a), rb = find(b); if (!ra.equals(rb)) parent.put(ra, rb); }
    static boolean virtual(int acc) { return (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0; }

    void methodGroups() {
        Set<String> external = new HashSet<>();   // group members declared outside the game jar
        List<JarIndex.Info> game = jar.classes.values().stream().filter(i -> i.inGame).toList();
        for (JarIndex.Info c : game) {
            List<JarIndex.Info> anc = jar.ancestors(c);
            for (var e : c.methods.entrySet()) {
                String md = e.getKey();
                if (md.startsWith("<") || !virtual(e.getValue())) continue;
                String me = c.name + "." + md;
                parent.putIfAbsent(me, me);
                for (JarIndex.Info a : anc) {
                    Integer acc = a.methods.get(md);
                    if (acc == null || !virtual(acc)) continue;
                    boolean pkgPrivate = (acc & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0;
                    if (pkgPrivate && !a.pkg().equals(c.pkg())) continue;
                    String ae = a.name + "." + md;
                    parent.putIfAbsent(ae, ae);
                    union(me, ae);
                    if (!a.inGame) external.add(ae);
                }
            }
        }
        Map<String, List<String>> groups = new HashMap<>();
        for (String k : parent.keySet()) groups.computeIfAbsent(find(k), x -> new ArrayList<>()).add(k);
        Set<String> extRoots = new HashSet<>(); for (String e : external) extRoots.add(find(e));
        int nsN = t.namespaces.size();
        int multi = 0;
        for (var g : groups.entrySet()) {
            List<String> members = g.getValue();
            if (members.size() > 1) multi++;
            boolean ext = extRoots.contains(g.getKey());
            for (int ns = 1; ns < nsN; ns++) {
                if (attributes.contains(t.namespaces.get(ns))) continue;
                String name = null; boolean conflict = false;
                for (String m : members) {
                    MappingTree.MemberRow r = row(m); if (r == null) continue;
                    String v = r.get(ns); if (v == null) continue;
                    if (name == null) name = v; else if (!name.equals(v)) conflict = true;
                }
                if (ext) {
                    String obfName = members.get(0).substring(members.get(0).lastIndexOf('.') + 1, members.get(0).indexOf('('));
                    if (name != null && !name.equals(obfName)) { conflicts.add(t.namespaces.get(ns) + ": library override renamed " + members.get(0) + " -> " + name); count("conflict: library override renamed"); }
                    name = obfName;
                } else if (conflict) {
                    conflicts.add(t.namespaces.get(ns) + ": names differ in override group of " + members.get(0)); count("conflict: override group names differ (" + t.namespaces.get(ns) + ")");
                    continue;   // keep each member's own name; holes stay holes
                }
                if (name == null) continue;
                for (String m : members) {
                    MappingTree.MemberRow r = row(m); if (r == null) continue;
                    if (r.get(ns) == null) { r.set(ns, name); count("method name from override group (" + t.namespaces.get(ns) + ")"); }
                }
            }
        }
        stats.put("override groups with >1 member", multi);
    }
    private MappingTree.MemberRow row(String key) {
        int dot = key.lastIndexOf('.', key.indexOf('('));
        MappingTree.ClassRow c = t.classes.get(key.substring(0, dot));
        return c == null ? null : c.methods.get(key.substring(dot + 1));
    }

    /** Shape of a ProGuard-obfuscated member name in Mojang's jars: a, b, ..., aa, ab_ (1-3 lowercase letters, optional '_'). */
    static final java.util.regex.Pattern OBF_MEMBER = java.util.regex.Pattern.compile("[a-z]{1,3}_?");

    void identities() {
        int nsN = t.namespaces.size();
        for (MappingTree.ClassRow c : t.classes.values()) {
            JarIndex.Info ji = jar.classes.get(c.obf);
            // kept member names inside obfuscated classes (ProGuard keep rules, e.g. methods of kept interfaces): a hole
            // there means "not renamed". Decided from the obf name's shape only (licence rule R5).
            for (int ns = 1; ns < nsN; ns++) {
                if (attributes.contains(t.namespaces.get(ns))) continue;
                for (MappingTree.MemberRow m : c.methods.values()) if (m.get(ns) == null && !m.obf.startsWith("<") && !OBF_MEMBER.matcher(m.obf).matches()) { m.set(ns, m.obf); count("identity: kept member name (" + t.namespaces.get(ns) + ")"); }
                for (MappingTree.MemberRow f : c.fields.values()) if (f.get(ns) == null && !OBF_MEMBER.matcher(f.obf).matches()) { f.set(ns, f.obf); count("identity: kept member name (" + t.namespaces.get(ns) + ")"); }
            }
            // unobfuscated code (kept names, e.g. com/mojang/blaze3d/**): the obf jar itself shows it - the class has a package.
            // Decided from the obf jar only, never from Mojang names (licence rule R5).
            if (c.obf.indexOf('/') >= 0) {
                for (int ns = 1; ns < nsN; ns++) {
                    if (attributes.contains(t.namespaces.get(ns))) continue;
                    if (c.get(ns) == null) { c.set(ns, c.obf); count("identity: unobfuscated class (" + t.namespaces.get(ns) + ")"); }
                    for (MappingTree.MemberRow m : c.methods.values()) if (m.get(ns) == null && !m.obf.startsWith("lambda$") || m.get(ns) == null) { m.set(ns, m.obf); count("identity: unobfuscated member (" + t.namespaces.get(ns) + ")"); }
                    for (MappingTree.MemberRow f : c.fields.values()) if (f.get(ns) == null) { f.set(ns, f.obf); count("identity: unobfuscated member (" + t.namespaces.get(ns) + ")"); }
                }
            }
            boolean isEnum = ji != null && (ji.access & Opcodes.ACC_ENUM) != 0;
            for (MappingTree.MemberRow m : c.methods.values()) {
                boolean id = m.obf.equals("<init>") || m.obf.equals("<clinit>")
                        || isEnum && (m.obf.equals("values") && m.desc.equals("()[L" + c.obf + ";") || m.obf.equals("valueOf") && m.desc.equals("(Ljava/lang/String;)L" + c.obf + ";"));
                if (!id) continue;
                for (int ns = 1; ns < nsN; ns++) if (m.get(ns) == null) m.set(ns, m.obf);
            }
        }
    }

    void innerClasses() {
        int nsN = t.namespaces.size();
        List<MappingTree.ClassRow> byLen = new ArrayList<>(t.classes.values());
        byLen.sort(Comparator.comparingInt(c -> c.obf.length()));
        for (MappingTree.ClassRow c : byLen) {
            int d = c.obf.lastIndexOf('$');
            if (d < 0) continue;
            MappingTree.ClassRow outer = t.classes.get(c.obf.substring(0, d));
            if (outer == null) continue;
            for (int ns = 1; ns < nsN; ns++) {
                if (c.get(ns) != null || outer.get(ns) == null) continue;
                c.set(ns, outer.get(ns) + "$" + c.obf.substring(d + 1));
                count("inner class name from outer (" + t.namespaces.get(ns) + ")");
            }
        }
    }
}
