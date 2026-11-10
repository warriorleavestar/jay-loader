package amber.ul.map;

import java.util.*;

/**
 * Amber's mapping tree (docs/mapping-brief.md §1): one row per obf entity of ONE Minecraft version, one column per
 * namespace. Column 0 is "obf" (the pivot: names in that version's vanilla jar). Every other table is re-keyed onto the
 * pivot when it is read, so namespaces are joined on obf name + obf descriptor only, never on a mapped name.
 *
 * A cell is a name or null = HOLE ("this namespace says nothing"), which is not the same as "same as obf".
 * Keys: class = obf internal name; field = name + ":" + obf desc (desc may be unknown at read time: TSRG2 fields carry
 * none; resolved against the jar later); method = name + obf desc.
 *
 * Written from scratch for Amber; behaviour notes from reading mapping-io / srgutils / tiny-remapper (ideas only).
 */
public final class MappingTree {
    public final List<String> namespaces = new ArrayList<>(List.of("obf"));
    public final Map<String, ClassRow> classes = new LinkedHashMap<>();
    /** Things a reader could not place (unknown classes, ambiguous fields ...), with counts per reason. */
    public final Map<String, Integer> dropped = new TreeMap<>();

    public int ns(String name) {
        int i = namespaces.indexOf(name);
        if (i >= 0) return i;
        namespaces.add(name); return namespaces.size() - 1;
    }
    public int nsIndex(String name) {
        int i = namespaces.indexOf(name);
        if (i < 0) throw new IllegalArgumentException("no namespace " + name + " in " + namespaces);
        return i;
    }
    void drop(String why) { dropped.merge(why, 1, Integer::sum); }

    public ClassRow cls(String obf) { return classes.computeIfAbsent(obf, ClassRow::new); }

    /** Names per namespace; index 0 is always the obf name. */
    public static class Row {
        public final String obf;
        String[] names = new String[4];
        Row(String obf) { this.obf = obf; names[0] = obf; }
        public String get(int ns) { return ns < names.length ? names[ns] : null; }
        public void set(int ns, String v) {
            if (ns >= names.length) names = Arrays.copyOf(names, Math.max(ns + 1, names.length * 2));
            names[ns] = v;
        }
    }

    public static final class ClassRow extends Row {
        public final Map<String, MemberRow> fields = new LinkedHashMap<>();    // name:desc (or name: when desc unknown)
        public final Map<String, MemberRow> methods = new LinkedHashMap<>();   // name + desc
        ClassRow(String obf) { super(obf); }
        public MemberRow field(String name, String desc) { return fields.computeIfAbsent(name + ":" + (desc == null ? "" : desc), k -> new MemberRow(name, desc)); }
        public MemberRow method(String name, String desc) { return methods.computeIfAbsent(name + desc, k -> new MemberRow(name, desc)); }
    }

    public static final class MemberRow extends Row {
        public String desc;          // obf descriptor (null: unknown yet, TSRG2 field)
        public boolean isStatic;     // TSRG2 "static" flag
        /** lvIndex -> names per namespace (debug only). */
        public Map<Integer, Row> params;
        MemberRow(String obf, String desc) { super(obf); this.desc = desc; }
        public Row param(int lv) {
            if (params == null) params = new TreeMap<>();
            return params.computeIfAbsent(lv, k -> new Row(null));
        }
    }

    public int classCount() { return classes.size(); }
    public int memberCount(boolean methods) { int n = 0; for (ClassRow c : classes.values()) n += (methods ? c.methods : c.fields).size(); return n; }
}
