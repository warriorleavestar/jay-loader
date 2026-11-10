package amber.ul.map;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Readers that put one mapping file into a {@link MappingTree}, re-keyed onto the obf pivot.
 * Own implementations of the published formats (format behaviour learned from mapping-io, srgutils, Loom; no code copied):
 *  - ProGuard (Mojang client.txt / server.txt): named -> obf, Java-syntax types, line-number prefixes and inline suffixes.
 *  - Tiny v1 / v2 (intermediary, Yarn, Quilt): any number of namespaces, the first column must be the pivot (or a namespace
 *    already in the tree; then rows are re-keyed through it).
 *  - TSRG2 (MCPConfig, NeoForm): obf + srg + id columns, static flag, params.
 */
public final class Readers {
    private Readers() {}

    // ---- ProGuard ----------------------------------------------------------------------------------------------------

    /** Mojang mappings into namespace {@code ns} (e.g. "mojmap"). Two passes: classes first (needed to turn Java types into obf descriptors). */
    public static void proguard(MappingTree t, BufferedReader inPass1, BufferedReader inPass2, String ns) throws IOException {
        int c = t.ns(ns);
        Map<String, String> named2obf = new HashMap<>();   // dotted named -> internal obf
        for (String l; (l = inPass1.readLine()) != null; ) {
            if (l.isEmpty() || l.charAt(0) == '#' || l.charAt(0) == ' ') continue;
            int a = l.indexOf(" -> ");
            if (a < 0 || !l.endsWith(":")) continue;
            named2obf.put(l.substring(0, a), l.substring(a + 4, l.length() - 1).replace('.', '/'));
        }
        MappingTree.ClassRow cur = null;
        for (String l; (l = inPass2.readLine()) != null; ) {
            if (l.isEmpty() || l.charAt(0) == '#') continue;
            if (l.charAt(0) != ' ') {
                int a = l.indexOf(" -> ");
                if (a < 0) { cur = null; continue; }
                String named = l.substring(0, a), obf = l.substring(a + 4, l.length() - 1).replace('.', '/');
                cur = t.cls(obf); cur.set(c, named.replace('.', '/'));
                continue;
            }
            if (cur == null) continue;
            String s = l.trim();
            int hash = s.indexOf('#'); if (hash == 0) continue;   // member-level metadata comment
            int arrow = s.lastIndexOf(" -> ");
            if (arrow < 0) continue;
            String left = s.substring(0, arrow), obfName = s.substring(arrow + 4);
            // strip "12:34:" line prefix
            int p = 0; while (p < left.length() && (Character.isDigit(left.charAt(p)) || left.charAt(p) == ':')) p++;
            left = left.substring(p);
            int sp = left.indexOf(' ');
            String type = left.substring(0, sp), rest = left.substring(sp + 1);
            int paren = rest.indexOf('(');
            if (paren < 0) {   // field
                MappingTree.MemberRow m = cur.field(obfName, desc(type, named2obf));
                m.set(c, rest);
            } else {
                String name = rest.substring(0, paren);
                int close = rest.indexOf(')', paren);
                String args = rest.substring(paren + 1, close);
                StringBuilder d = new StringBuilder("(");
                if (!args.isEmpty()) for (String x : args.split(",")) d.append(desc(x, named2obf));
                d.append(')').append(desc(type, named2obf));
                MappingTree.MemberRow m = cur.method(obfName, d.toString());
                // a method can appear several times (inlined line ranges): same mapping each time
                m.set(c, name);
            }
        }
    }

    static String desc(String javaType, Map<String, String> named2obf) {
        int dims = 0; String b = javaType;
        while (b.endsWith("[]")) { dims++; b = b.substring(0, b.length() - 2); }
        String core = switch (b) {
            case "void" -> "V"; case "boolean" -> "Z"; case "byte" -> "B"; case "char" -> "C"; case "short" -> "S";
            case "int" -> "I"; case "long" -> "J"; case "float" -> "F"; case "double" -> "D";
            default -> "L" + named2obf.getOrDefault(b, b.replace('.', '/')) + ";";
        };
        return "[".repeat(dims) + core;
    }

    public static void proguard(MappingTree t, Path file, String ns) throws IOException {
        try (BufferedReader a = Files.newBufferedReader(file, StandardCharsets.UTF_8); BufferedReader b = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { proguard(t, a, b, ns); }
    }

    // ---- Tiny v1 / v2 --------------------------------------------------------------------------------------------------

    /**
     * Tiny file. {@code nsMap} renames file namespaces (e.g. "official" -> "obf", "named" -> "yarn"); unlisted ones keep
     * their name. The file's first namespace must map to "obf" or to a namespace already filled in the tree (re-keyed through
     * the tree's class map; members by name+desc in that namespace).
     */
    public static void tiny(MappingTree t, BufferedReader in, Map<String, String> nsMap) throws IOException {
        String header = in.readLine();
        if (header == null) throw new IOException("empty tiny file");
        String[] h = header.split("\t");
        boolean v2 = h[0].equals("tiny");
        if (!v2 && !h[0].equals("v1")) throw new IOException("not a tiny file: " + header);
        List<String> fileNs = Arrays.asList(h).subList(v2 ? 3 : 1, h.length);
        String src = nsMap.getOrDefault(fileNs.get(0), fileNs.get(0));
        int[] col = new int[fileNs.size()];
        for (int i = 1; i < col.length; i++) col[i] = t.ns(nsMap.getOrDefault(fileNs.get(i), fileNs.get(i)));
        Rekey rk = src.equals("obf") ? null : new Rekey(t, t.nsIndex(src));
        MappingTree.ClassRow cur = null; MappingTree.MemberRow curM = null;
        List<String[]> v1Members = new ArrayList<>();
        for (String l; (l = in.readLine()) != null; ) {
            if (l.isEmpty()) continue;
            String[] p = l.split("\t", -1);
            if (v2) {
                int depth = 0; while (depth < p.length && p[depth].isEmpty()) depth++;
                String kind = p[depth];
                if (depth == 0 && kind.equals("c")) {
                    String key = p[1];
                    cur = rk == null ? t.cls(key) : rk.cls(key);
                    if (cur == null) { t.drop("tiny: class not in pivot"); continue; }
                    for (int i = 1; i < col.length; i++) put(cur, col[i], p[1 + i]);
                } else if (depth == 1 && (kind.equals("f") || kind.equals("m"))) {
                    if (cur == null) { curM = null; continue; }
                    boolean isM = kind.equals("m");
                    String d = rk == null ? p[2] : rk.desc(p[2]);
                    String name = rk == null ? p[3] : rk.member(cur, p[3], p[2], isM);
                    if (name == null) { t.drop("tiny: member not in pivot"); curM = null; continue; }
                    curM = isM ? cur.method(name, d) : cur.field(name, d);
                    for (int i = 1; i < col.length; i++) put(curM, col[i], p[3 + i]);
                } else if (depth == 2 && kind.equals("p") && curM != null) {
                    MappingTree.Row r = curM.param(Integer.parseInt(p[3]));
                    for (int i = 1; i < col.length; i++) if (4 + i < p.length) put(r, col[i], p[4 + i]);
                }
            } else {
                switch (p[0]) {
                    case "CLASS" -> {
                        MappingTree.ClassRow c = rk == null ? t.cls(p[1]) : rk.cls(p[1]);
                        if (c == null) { t.drop("tiny: class not in pivot"); continue; }
                        for (int i = 1; i < col.length; i++) put(c, col[i], p[1 + i]);
                    }
                    case "FIELD", "METHOD" -> v1Members.add(p);
                    default -> {}
                }
            }
        }
        for (String[] p : v1Members) {   // v1: members after all classes (owner by source name)
            MappingTree.ClassRow c = rk == null ? t.classes.get(p[1]) : rk.cls(p[1]);
            if (c == null) { t.drop("tiny: member owner not in pivot"); continue; }
            boolean m = p[0].equals("METHOD");
            String d = rk == null ? p[2] : rk.desc(p[2]);
            String name = rk == null ? p[3] : rk.member(c, p[3], p[2], m);
            if (name == null) { t.drop("tiny: member not in pivot"); continue; }
            MappingTree.MemberRow r = m ? c.method(name, d) : c.field(name, d);
            for (int i = 1; i < col.length; i++) put(r, col[i], p[3 + i]);
        }
    }
    private static void put(MappingTree.Row r, int col, String v) { if (v != null && !v.isEmpty()) r.set(col, v); }

    /** Tiny file inside a maven jar (mappings/mappings.tiny). */
    public static void tinyJar(MappingTree t, Path jar, Map<String, String> nsMap) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            ZipEntry e = z.getEntry("mappings/mappings.tiny");
            if (e == null) throw new IOException("no mappings/mappings.tiny in " + jar);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8))) { tiny(t, r, nsMap); }
        }
    }

    /** Re-keys rows written in a non-pivot namespace back onto obf. */
    static final class Rekey {
        final MappingTree t; final int ns;
        final Map<String, MappingTree.ClassRow> byName = new HashMap<>();
        final Map<String, String> toObf = new HashMap<>();
        Rekey(MappingTree t, int ns) {
            this.t = t; this.ns = ns;
            for (MappingTree.ClassRow c : t.classes.values()) { String n = c.get(ns); if (n != null) { byName.put(n, c); toObf.put(n, c.obf); } }
        }
        MappingTree.ClassRow cls(String name) { return byName.get(name); }
        String desc(String d) { return Descriptors.mapClasses(d, n -> toObf.getOrDefault(n, n)); }
        /** obf member name for a member named {@code name} with descriptor {@code srcDesc} in this namespace (or null). */
        String member(MappingTree.ClassRow c, String name, String srcDesc, boolean method) {
            String od = desc(srcDesc);
            for (MappingTree.MemberRow m : (method ? c.methods : c.fields).values()) if (name.equals(m.get(ns)) && (m.desc == null || m.desc.equals(od))) return m.obf;
            return null;
        }
    }

    // ---- TSRG2 ----------------------------------------------------------------------------------------------------------

    /** TSRG2 ("tsrg2 obf srg id"): columns after obf go to {@code nsNames} in order (e.g. ["srg", "srgid"]). */
    public static void tsrg2(MappingTree t, BufferedReader in, List<String> nsNames) throws IOException {
        String header = in.readLine();
        if (header == null || !header.startsWith("tsrg2 ")) throw new IOException("not a TSRG2 file: " + header);
        String[] h = header.split(" ");
        // NeoForge's merged file calls the obf column "left"
        if (!h[1].equals("obf") && !h[1].equals("left")) throw new IOException("TSRG2 source namespace must be obf: " + header);
        int[] col = new int[h.length - 1];   // col[i] for file column i (0 = obf)
        for (int i = 1; i < col.length; i++) col[i] = t.ns(nsNames.get(i - 1));
        MappingTree.ClassRow cur = null; MappingTree.MemberRow curM = null;
        for (String l; (l = in.readLine()) != null; ) {
            if (l.isEmpty()) continue;
            int depth = 0; while (depth < l.length() && l.charAt(depth) == '\t') depth++;
            String[] p = l.substring(depth).split(" ");
            if (depth == 0) {
                cur = t.cls(p[0]); curM = null;
                for (int i = 1; i < col.length; i++) cur.set(col[i], p[i]);
            } else if (depth == 1 && cur != null) {
                if (p.length == col.length) {           // field: obf name...
                    curM = cur.field(p[0], null);
                    for (int i = 1; i < col.length; i++) curM.set(col[i], p[i]);
                } else {                                 // method: obf desc name...
                    curM = cur.method(p[0], p[1]);
                    for (int i = 1; i < col.length; i++) curM.set(col[i], p[i + 1]);
                }
            } else if (depth == 2 && curM != null) {
                if (p.length == 1 && p[0].equals("static")) { curM.isStatic = true; continue; }
                MappingTree.Row r = curM.param(Integer.parseInt(p[0]));   // index obf names...
                for (int i = 1; i < col.length && i + 1 < p.length; i++) r.set(col[i], p[i + 1]);
            }
        }
    }

    /** TSRG2 from an MCPConfig / NeoForm zip (config/joined.tsrg, or config.json "data.mappings"). */
    public static void tsrg2Zip(MappingTree t, Path zip, List<String> nsNames) throws IOException {
        try (ZipFile z = new ZipFile(zip.toFile())) {
            ZipEntry e = z.getEntry("config/joined.tsrg");
            if (e == null) throw new IOException("no config/joined.tsrg in " + zip);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8))) { tsrg2(t, r, nsNames); }
        }
    }
}
