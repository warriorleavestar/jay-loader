package amber.ul.map;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.Opcodes;

/**
 * Names for remapping MODS from namespace A (e.g. intermediary) to B (e.g. mojmap), brief §4.3 / §5.
 *
 * One class universe in A: the game (translated from the obf index through the graph, so no pre-remapped game jar is
 * needed), every mod being remapped (mod-to-mod inheritance, e.g. Fabric API modules), libraries and the JDK (lazy).
 *
 * Rules:
 *  - class: graph name in B; an unmapped inner class of a mapped outer becomes outer(B) + '$' + simple name.
 *  - field reference: resolved like the JVM (owner, then super, then interfaces); renamed if it lands on a game field.
 *  - method: a game declaration found along the hierarchy gives the name. A mod/library class that declares the
 *    method keeps searching its ancestors when the method can override (not private, not static, not a constructor),
 *    so a mod method that overrides a game method takes the game's name (propagation into mods). As in Loom, which
 *    produced the mod jar, package-private ancestors count from any package; private and static methods of interfaces
 *    are never inherited.
 *  - two different game names reachable for one method = conflict: the first (superclass before interfaces) wins,
 *    and it is reported.
 */
public final class ModNames implements NameSource {
    final MappingTree t; final int A, B; public final JarIndex idx;
    final Map<String, MappingTree.ClassRow> rowByA = new HashMap<>();
    final Map<String, Map<String, MappingTree.MemberRow>> memByA = new HashMap<>();   // A class -> A key -> row
    private final Map<String, String> clsCache = new ConcurrentHashMap<>();
    private final Map<String, String> memo = new ConcurrentHashMap<>();
    public final Set<String> conflicts = ConcurrentHashMap.newKeySet();
    private static final String NONE = "\0";

    public ModNames(MappingTree t, int A, int B, JarIndex obf, List<Path> libs, List<Path> mods) throws IOException {
        this(t, A, B, obf, libs, readAll(mods), true);
    }
    private static List<List<JarRemapper.Entry>> readAll(List<Path> mods) throws IOException {
        List<List<JarRemapper.Entry>> r = new ArrayList<>();
        for (Path m : mods) r.add(JarRemapper.read(m));
        return r;
    }
    /** Mods already in memory (e.g. after a pre-step), so nothing is written to disk and read back. */
    public ModNames(MappingTree t, int A, int B, JarIndex obf, List<Path> libs, List<List<JarRemapper.Entry>> mods, boolean inMemory) throws IOException {
        this.t = t; this.A = A; this.B = B;
        long t0 = System.nanoTime();
        idx = JarIndex.empty(libs);
        for (JarIndex.Info gi : obf.classes.values()) {
            if (!gi.inGame) continue;
            MappingTree.ClassRow row = t.classes.get(gi.name);
            String an = nameIn(gi.name, A);
            String[] itf = new String[gi.interfaces.length];
            for (int i = 0; i < itf.length; i++) itf[i] = nameIn(gi.interfaces[i], A);
            JarIndex.Info ai = new JarIndex.Info(an, gi.superName == null ? null : nameIn(gi.superName, A), itf, gi.access, true);
            Map<String, MappingTree.MemberRow> mem = new HashMap<>();
            for (var e : gi.methods.entrySet()) {
                int p = e.getKey().indexOf('(');
                String n = e.getKey().substring(0, p), d = e.getKey().substring(p);
                MappingTree.MemberRow r = row == null ? null : row.methods.get(e.getKey());
                String k = (r != null && r.get(A) != null ? r.get(A) : n) + descIn(d, A);
                ai.methods.put(k, e.getValue()); if (r != null) mem.put(k, r);
            }
            for (var e : gi.fields.entrySet()) {
                int p = e.getKey().indexOf(':');
                String n = e.getKey().substring(0, p), d = e.getKey().substring(p + 1);
                MappingTree.MemberRow r = row == null ? null : row.fields.get(e.getKey());
                String k = (r != null && r.get(A) != null ? r.get(A) : n) + ":" + descIn(d, A);
                ai.fields.put(k, e.getValue()); if (r != null) mem.put(k, r);
            }
            idx.put(ai); memByA.put(an, mem); if (row != null) rowByA.put(an, row);
        }
        long t1 = System.nanoTime();
        for (List<JarRemapper.Entry> m : mods) idx.addEntries(m, false);
        long t2 = System.nanoTime();
        for (List<JarRemapper.Entry> m : mods) widen(m);
        long t3 = System.nanoTime();
        timing = String.format("game %d ms, mods %d ms, AW %d ms", (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000);
    }

    public int widened;
    public String timing;
    /**
     * Apply a mod's access widener (in namespace A) to the game methods of the index: a mod may override a method that
     * is package-private in vanilla once an AW made it accessible/extendable, and override resolution must see that.
     */
    void widen(List<JarRemapper.Entry> jar) {
        Map<String, byte[]> files = new HashMap<>();
        for (JarRemapper.Entry e : jar) if (!e.name().endsWith(".class")) files.put(e.name(), e.data());
        byte[] fmj = files.get("fabric.mod.json"); if (fmj == null) return;
        String awPath;
        try {
            var o = com.google.gson.JsonParser.parseString(new String(fmj, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("accessWidener")) return;
            awPath = o.get("accessWidener").getAsString();
        } catch (RuntimeException e) { return; }
        byte[] aw = files.get(awPath); if (aw == null) return;
        String txt = new String(aw, java.nio.charset.StandardCharsets.UTF_8);
        for (String line : txt.split("\\R")) {
            int h = line.indexOf('#'); if (h >= 0) line = line.substring(0, h);
            String[] p = line.trim().split("\\s+");
            if (p.length < 5 || !p[1].equals("method")) continue;
            String kind = p[0].startsWith("transitive-") ? p[0].substring(11) : p[0];
            JarIndex.Info i = idx.classes.get(p[2]);
            if (i == null || !i.inGame) continue;
            Integer acc = i.methods.get(p[3] + p[4]); if (acc == null) continue;
            int n = acc;
            if (kind.equals("accessible")) n = n & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
            else if (kind.equals("extendable")) { n &= ~Opcodes.ACC_FINAL; if ((n & Opcodes.ACC_PUBLIC) == 0) n = n & ~Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED; }
            if (n != acc) { i.methods.put(p[3] + p[4], n); widened++; }
        }
    }

    String nameIn(String obf, int ns) {
        MappingTree.ClassRow c = t.classes.get(obf);
        if (c != null && c.get(ns) != null) return c.get(ns);
        int d = obf.lastIndexOf('$');
        return d < 0 ? obf : nameIn(obf.substring(0, d), ns) + "$" + obf.substring(d + 1);
    }
    String descIn(String desc, int ns) { return Descriptors.mapClasses(desc, x -> nameIn(x, ns)); }

    @Override public String cls(String a) {
        String r = clsCache.get(a);
        if (r != null) return r;
        MappingTree.ClassRow row = rowByA.get(a);
        if (row != null && row.get(B) != null) r = row.get(B);
        else {
            int d = a.lastIndexOf('$');
            String outer = d < 0 ? null : cls(a.substring(0, d));
            r = outer == null || outer.equals(a.substring(0, d)) ? a : outer + "$" + a.substring(d + 1);
        }
        clsCache.put(a, r);
        return r;
    }

    /** Mixin renames of mod members (shadows, overwrites, accessors): "M"/"F" + class + "." + key -> new name. */
    final Map<String, String> renames = new ConcurrentHashMap<>();
    public void rename(boolean field, String cls, String name, String desc, String to) {
        renames.put((field ? "F" : "M") + cls + "." + name + (field ? ":" : "") + desc, to);
        memo.clear();
    }

    /** B name of a game field/method reached from {@code owner} (A names), or null when it isn't a game member. */
    public String gameField(String owner, String name, String desc) {
        String d = fieldDecl(owner, name + ":" + desc);
        if (d == null || d.startsWith("=")) return null;
        MappingTree.MemberRow r = memByA.getOrDefault(d, Map.of()).get(name + ":" + desc);
        return r == null || r.get(B) == null ? name : r.get(B);
    }
    public String gameMethod(String owner, String name, String desc) {
        String d = methodDecl(owner, name + desc);
        if (d == null || d.startsWith("=")) return null;
        MappingTree.MemberRow r = memByA.getOrDefault(d, Map.of()).get(name + desc);
        return r == null || r.get(B) == null ? name : r.get(B);
    }
    /** B names of the game methods called {@code name} (any desc) in {@code owner} and its ancestors. */
    public Set<String> gameMethodsNamed(String owner, String name) { return named(owner, name, false, new HashSet<>(), new HashSet<>()); }
    public Set<String> gameFieldsNamed(String owner, String name) { return named(owner, name, true, new HashSet<>(), new HashSet<>()); }
    private Set<String> named(String owner, String name, boolean field, Set<String> out, Set<String> seen) {
        if (owner == null || !seen.add(owner)) return out;
        JarIndex.Info i = idx.get(owner); if (i == null) return out;
        if (i.inGame) for (String k : (field ? i.fields : i.methods).keySet())
            if (k.startsWith(name) && k.length() > name.length() && k.charAt(name.length()) == (field ? ':' : '(')) {
                MappingTree.MemberRow r = memByA.getOrDefault(owner, Map.of()).get(k);
                out.add(r == null || r.get(B) == null ? name : r.get(B));
            }
        named(i.superName, name, field, out, seen);
        for (String p : i.interfaces) named(p, name, field, out, seen);
        return out;
    }
    public String mapDesc(String desc) { return Descriptors.mapClasses(desc, this::cls); }

    /**
     * SRG member ids (m_NNN_ / f_NNN_) are unique over the whole game, so when the exact lookup misses - Forge patched the
     * member's descriptor (KeyMapping.f_90810_ is a KeyMappingLookup, not Vanilla's Map), or the reference goes through
     * a class the index does not connect - the id alone still names exactly one Mojmap member.
     */
    static final java.util.regex.Pattern SRG_ID = java.util.regex.Pattern.compile("[mf]_[0-9]+_");
    @Override public String field(String owner, String name, String desc) {
        String r = fieldExact(owner, name, desc);
        return r.equals(name) && name.startsWith("f_") && SRG_ID.matcher(name).matches() ? unique(true).getOrDefault(name, name) : r;
    }
    @Override public String method(String owner, String name, String desc) {
        String r = methodExact(owner, name, desc);
        return r.equals(name) && name.startsWith("m_") && SRG_ID.matcher(name).matches() ? unique(false).getOrDefault(name, name) : r;
    }
    String fieldExact(String owner, String name, String desc) {
        String d = fieldDecl(owner, name + ":" + desc);
        if (d == null) return name;
        if (d.startsWith("=")) return d.substring(1);
        MappingTree.MemberRow r = memByA.getOrDefault(d, Map.of()).get(name + ":" + desc);
        return r == null || r.get(B) == null ? name : r.get(B);
    }

    String methodExact(String owner, String name, String desc) {
        if (name.startsWith("<")) return name;
        String key = name + desc;
        String d = methodDecl(owner, key);
        if (d == null) return name;
        if (d.startsWith("=")) return d.substring(1);
        MappingTree.MemberRow r = memByA.getOrDefault(d, Map.of()).get(key);
        return r == null || r.get(B) == null ? name : r.get(B);
    }

    /** Game class whose field (key) a reference through {@code owner} resolves to, or null (mod/library field). */
    String fieldDecl(String owner, String key) {
        if (owner == null || owner.isEmpty() || owner.charAt(0) == '[') return null;
        String mk = "F" + owner + "." + key;
        String r = memo.get(mk);
        if (r != null) return r == NONE ? null : r;
        r = null;
        JarIndex.Info i = idx.get(owner);
        if (i != null) {
            if (i.fields.containsKey(key)) { String rn = renames.get("F" + owner + "." + key); r = i.inGame ? owner : rn != null ? "=" + rn : null; }
            else {
                if (i.superName != null) r = fieldDecl(i.superName, key);
                if (r == null) for (String p : i.interfaces) { r = fieldDecl(p, key); if (r != null) break; }
            }
        }
        memo.put(mk, r == null ? NONE : r);
        return r;
    }

    /** Game class whose method declaration (key) gives the name for (owner, key), or null = keep the name. */
    String methodDecl(String owner, String key) {
        if (owner == null || owner.isEmpty() || owner.charAt(0) == '[') return null;
        String mk = "M" + owner + "." + key;
        String r = memo.get(mk);
        if (r != null) return r == NONE ? null : r;
        r = null;
        JarIndex.Info i = idx.get(owner);
        if (i != null) {
            Integer own = i.methods.get(key);
            String rn = own == null || i.inGame ? null : renames.get("M" + owner + "." + key);
            if (own != null && i.inGame) r = owner;
            else if (rn != null) r = "=" + rn;
            else if (own == null || (own & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
                List<String> found = new ArrayList<>(2);
                List<String> parents = new ArrayList<>();
                if (i.superName != null) parents.add(i.superName);
                Collections.addAll(parents, i.interfaces);
                for (String p : parents) {
                    String d = methodDecl(p, key);
                    if (d == null || found.contains(d)) continue;
                    JarIndex.Info di = idx.get(d);
                    Integer acc = di == null ? null : di.methods.get(key);
                    if (acc != null) {
                        boolean itf = (di.access & Opcodes.ACC_INTERFACE) != 0;
                        if (itf && (acc & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue;
                        if ((acc & Opcodes.ACC_PRIVATE) != 0) continue;
                        // no package-private check here on purpose: a mod jar is the output of Loom's named->intermediary
                        // remap, which propagated the game name onto every same-signature method of a subclass, in any
                        // package. Undoing that means propagating the same way (the method had the vanilla name in the
                        // mod's source). Whether it overrides at runtime is then up to the runtime's access (ATs/AWs).
                        if (own != null && (acc & Opcodes.ACC_STATIC) != 0) continue;   // a static can't be overridden
                    }
                    found.add(d);
                }
                if (!found.isEmpty()) {
                    r = found.get(0);
                    if (found.size() > 1) {
                        String n0 = method0(found.get(0), key);
                        for (String f : found) if (!Objects.equals(method0(f, key), n0)) conflicts.add(owner + "." + key + ": " + found);
                    }
                }
            }
        }
        memo.put(mk, r == null ? NONE : r);
        return r;
    }
    private String method0(String d, String key) {
        MappingTree.MemberRow r = memByA.getOrDefault(d, Map.of()).get(key);
        return r == null ? null : r.get(B);
    }
    /**
     * The same tables for the text formats (AW, refmap, MixinExtras @Definition): exact lookups resolve through the class
     * universe like bytecode references; name-only lookups (refmap targets without desc) take the owner's game members of
     * that name when they agree on one B name, else the graph-wide name when the A name is unique (intermediary method_/
     * field_ names are), else the name is kept.
     */
    public TextNames text() {
        return new TextNames() {
            public String mapClass(String internal) { return cls(internal); }
            public String mapDesc(String desc) { return ModNames.this.mapDesc(desc); }
            public String mapMethod(String owner, String name, String desc) {
                if (owner != null && desc != null) { String r = method(owner, name, desc); if (!r.equals(name)) return r; }
                if (owner != null) { Set<String> s = gameMethodsNamed(owner, name); if (s.size() == 1) return s.iterator().next(); if (s.size() > 1 && !SRG_ID.matcher(name).matches()) return name; }
                return unique(false).getOrDefault(name, name);
            }
            public String mapField(String owner, String name, String desc) {
                if (owner != null && desc != null) { String r = field(owner, name, desc); if (!r.equals(name)) return r; }
                if (owner != null) { Set<String> s = gameFieldsNamed(owner, name); if (s.size() == 1) return s.iterator().next(); if (s.size() > 1 && !SRG_ID.matcher(name).matches()) return name; }
                return unique(true).getOrDefault(name, name);
            }
        };
    }
    private volatile Map<String, String> uniqM, uniqF;
    /** A member name -> B name over the whole graph, only for A names that map to exactly one B name. */
    private Map<String, String> unique(boolean field) {
        Map<String, String> u = field ? uniqF : uniqM;
        if (u != null) return u;
        synchronized (this) {
            if ((field ? uniqF : uniqM) != null) return field ? uniqF : uniqM;
            Map<String, String> one = new HashMap<>(); Set<String> many = new HashSet<>();
            for (MappingTree.ClassRow c : t.classes.values())
                for (MappingTree.MemberRow r : (field ? c.fields : c.methods).values()) {
                    String a = r.get(A), b = r.get(B);
                    if (a == null || b == null || many.contains(a)) continue;
                    String prev = one.putIfAbsent(a, b);
                    if (prev != null && !prev.equals(b)) { one.remove(a); many.add(a); }
                }
            if (field) uniqF = one; else uniqM = one;
            return one;
        }
    }
    static String pkg(String n) { int i = n.lastIndexOf('/'); return i < 0 ? "" : n.substring(0, i); }
}
