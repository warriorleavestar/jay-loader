package amber.ul.map;

import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * .amap: Amber's compiled mapping graph (brief §4.2 step 9), memory-mapped on load, no parsing.
 *
 * Little-endian. All names live once in a string pool; a cell is a string id or HOLE (-1).
 * <pre>
 * header   "AMAP" u32 version  u32 nsCount  u32 strings  u32 classes  u32 methods  u32 fields  u32 params
 *          u64 offset of each section: strings, namespaces, classes, methods, fields, params, perms
 * strings  u32 end offset of string i (count) | UTF-8 bytes
 * ns       u32 string id per namespace (0 = obf)
 * classes  per class, sorted by obf name (UTF-8 byte order): u32 obf, u32 firstMethod, u32 methodCount,
 *          u32 firstField, u32 fieldCount, i32 names[nsCount-1]
 * methods  per method, sorted by (name, desc) inside its class: u32 name, u32 desc, u32 flags (1 = static),
 *          u32 firstParam, u32 paramCount, i32 names[nsCount-1]
 * fields   u32 name, u32 desc (or -1), u32 flags, i32 names[nsCount-1]
 * params   u32 index, i32 names[nsCount-1]
 * perms    per namespace 1..n-1: u32 count, u32 class index[count] sorted by that namespace's name (holes left out),
 *          so a name in any namespace finds its row by binary search without building a map
 * </pre>
 * Format designed for Amber; nothing taken from other tools.
 */
public final class Amap {
    static final int MAGIC = 0x50414D41;   // "AMAP" little-endian
    static final int VERSION = 1;
    static final int HOLE = -1;

    // ---- write -----------------------------------------------------------------------------------------------------

    public static void write(MappingTree t, Path out) throws IOException {
        int nsN = t.namespaces.size();
        Map<String, Integer> pool = new HashMap<>(); List<byte[]> strs = new ArrayList<>();
        java.util.function.ToIntFunction<String> id = s -> s == null ? HOLE : pool.computeIfAbsent(s, k -> { strs.add(k.getBytes(StandardCharsets.UTF_8)); return strs.size() - 1; });
        for (String n : t.namespaces) id.applyAsInt(n);
        List<MappingTree.ClassRow> cls = new ArrayList<>(t.classes.values());
        cls.sort((a, b) -> cmp(a.obf, b.obf));
        ByteArrayOutputStream cb = new ByteArrayOutputStream(), mb = new ByteArrayOutputStream(), fb = new ByteArrayOutputStream(), pb = new ByteArrayOutputStream();
        DataOut c = new DataOut(cb), m = new DataOut(mb), f = new DataOut(fb), p = new DataOut(pb);
        int nm = 0, nf = 0, np = 0;
        for (MappingTree.ClassRow r : cls) {
            List<MappingTree.MemberRow> ms = new ArrayList<>(r.methods.values());
            ms.sort((a, b) -> { int x = cmp(a.obf, b.obf); return x != 0 ? x : cmp(a.desc, b.desc); });
            List<MappingTree.MemberRow> fs = new ArrayList<>(r.fields.values());
            fs.sort((a, b) -> { int x = cmp(a.obf, b.obf); return x != 0 ? x : cmp(String.valueOf(a.desc), String.valueOf(b.desc)); });
            c.i32(id.applyAsInt(r.obf)); c.i32(nm); c.i32(ms.size()); c.i32(nf); c.i32(fs.size());
            for (int i = 1; i < nsN; i++) c.i32(id.applyAsInt(r.get(i)));
            for (MappingTree.MemberRow x : ms) {
                int pc = x.params == null ? 0 : x.params.size();
                m.i32(id.applyAsInt(x.obf)); m.i32(id.applyAsInt(x.desc)); m.i32(x.isStatic ? 1 : 0); m.i32(np); m.i32(pc);
                for (int i = 1; i < nsN; i++) m.i32(id.applyAsInt(x.get(i)));
                if (pc > 0) for (var e : x.params.entrySet()) { p.i32(e.getKey()); for (int i = 1; i < nsN; i++) p.i32(id.applyAsInt(e.getValue().get(i))); np++; }
                nm++;
            }
            for (MappingTree.MemberRow x : fs) {
                f.i32(id.applyAsInt(x.obf)); f.i32(id.applyAsInt(x.desc)); f.i32(x.isStatic ? 1 : 0);
                for (int i = 1; i < nsN; i++) f.i32(id.applyAsInt(x.get(i)));
                nf++;
            }
        }
        // permutations per namespace
        ByteArrayOutputStream qb = new ByteArrayOutputStream(); DataOut q = new DataOut(qb);
        for (int ns = 1; ns < nsN; ns++) {
            final int k = ns;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < cls.size(); i++) if (cls.get(i).get(k) != null) idx.add(i);
            idx.sort((a, b) -> cmp(cls.get(a).get(k), cls.get(b).get(k)));
            q.i32(idx.size()); for (int i : idx) q.i32(i);
        }
        ByteArrayOutputStream sb = new ByteArrayOutputStream(); DataOut s = new DataOut(sb);
        int end = 0; for (byte[] b : strs) { end += b.length; s.i32(end); }
        for (byte[] b : strs) sb.write(b);
        ByteArrayOutputStream nb = new ByteArrayOutputStream(); DataOut n = new DataOut(nb);
        for (String x : t.namespaces) n.i32(id.applyAsInt(x));

        byte[][] sections = {sb.toByteArray(), nb.toByteArray(), cb.toByteArray(), mb.toByteArray(), fb.toByteArray(), pb.toByteArray(), qb.toByteArray()};
        int headerLen = 4 * 8 + 8 * sections.length;
        ByteArrayOutputStream hb = new ByteArrayOutputStream(); DataOut h = new DataOut(hb);
        h.i32(MAGIC); h.i32(VERSION); h.i32(nsN); h.i32(strs.size()); h.i32(cls.size()); h.i32(nm); h.i32(nf); h.i32(np);
        long off = headerLen;
        for (byte[] x : sections) { h.i64(off); off += x.length; }
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp" + ProcessHandle.current().pid());
        try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20)) {
            o.write(hb.toByteArray()); for (byte[] x : sections) o.write(x);
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** UTF-8 byte order (= code point order), the order binary search on the mapped file uses. */
    static int cmp(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; ) {
            int x = a.codePointAt(i), y = b.codePointAt(i);
            if (x != y) return Integer.compare(x, y);
            i += Character.charCount(x);
        }
        return Integer.compare(a.length(), b.length());
    }

    static final class DataOut {
        final OutputStream o; DataOut(OutputStream o) { this.o = o; }
        void i32(int v) throws IOException { o.write(v); o.write(v >>> 8); o.write(v >>> 16); o.write(v >>> 24); }
        void i64(long v) throws IOException { i32((int) v); i32((int) (v >>> 32)); }
    }

    // ---- read (mapped) ---------------------------------------------------------------------------------------------

    public final ByteBuffer b; public final int nsN, strings, classes, methods, fields, params;
    final int sStr, sNs, sCls, sM, sF, sP, sQ;
    final int cRec, mRec, fRec, pRec;
    private final String[] strCache;

    private Amap(ByteBuffer b) throws IOException {
        this.b = b.order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != MAGIC || b.getInt(4) != VERSION) throw new IOException("not an .amap v" + VERSION);
        nsN = b.getInt(8); strings = b.getInt(12); classes = b.getInt(16); methods = b.getInt(20); fields = b.getInt(24); params = b.getInt(28);
        sStr = (int) b.getLong(32); sNs = (int) b.getLong(40); sCls = (int) b.getLong(48); sM = (int) b.getLong(56); sF = (int) b.getLong(64); sP = (int) b.getLong(72); sQ = (int) b.getLong(80);
        cRec = 4 * (5 + nsN - 1); mRec = 4 * (5 + nsN - 1); fRec = 4 * (3 + nsN - 1); pRec = 4 * (1 + nsN - 1);
        strCache = new String[strings];
        // bounds: every section inside the file (data is untrusted once it's on disk)
        long[] ends = {sNs, sCls, sM, sF, sP, sQ, b.limit()};
        long[] starts = {sStr, sNs, sCls, sM, sF, sP, sQ};
        for (int i = 0; i < starts.length; i++) if (starts[i] < 88 || starts[i] > ends[i] || ends[i] > b.limit()) throw new IOException("bad .amap section " + i);
        if (sNs - sStr < 4L * strings || sM - sCls != (long) classes * cRec || sF - sM != (long) methods * mRec || sP - sF != (long) fields * fRec || sQ - sP != (long) params * pRec)
            throw new IOException("bad .amap table sizes");
    }

    public static Amap load(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            if (ch.size() > Integer.MAX_VALUE) throw new IOException(".amap too large");
            return new Amap(ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()));
        }
    }

    public String str(int id) {
        if (id == HOLE) return null;
        if (id < 0 || id >= strings) throw new IndexOutOfBoundsException("string " + id);
        String s = strCache[id];
        if (s != null) return s;
        int start = id == 0 ? 0 : b.getInt(sStr + 4 * (id - 1)), end = b.getInt(sStr + 4 * id);
        int base = sStr + 4 * strings;
        if (start < 0 || end < start || base + end > sNs) throw new IndexOutOfBoundsException("string " + id);
        byte[] x = new byte[end - start]; b.get(base + start, x);
        return strCache[id] = new String(x, StandardCharsets.UTF_8);
    }
    public String ns(int i) { return str(b.getInt(sNs + 4 * i)); }
    public String className(int c, int ns) { return ns == 0 ? str(b.getInt(sCls + c * cRec)) : str(b.getInt(sCls + c * cRec + 20 + 4 * (ns - 1))); }
    public int firstMethod(int c) { return b.getInt(sCls + c * cRec + 4); }
    public int methodCount(int c) { return b.getInt(sCls + c * cRec + 8); }
    public int firstField(int c) { return b.getInt(sCls + c * cRec + 12); }
    public int fieldCount(int c) { return b.getInt(sCls + c * cRec + 16); }
    public String methodName(int m, int ns) { return ns == 0 ? str(b.getInt(sM + m * mRec)) : str(b.getInt(sM + m * mRec + 20 + 4 * (ns - 1))); }
    public String methodDesc(int m) { return str(b.getInt(sM + m * mRec + 4)); }
    public boolean methodStatic(int m) { return (b.getInt(sM + m * mRec + 8) & 1) != 0; }
    public int firstParam(int m) { return b.getInt(sM + m * mRec + 12); }
    public int paramCount(int m) { return b.getInt(sM + m * mRec + 16); }
    public int paramIndex(int p) { return b.getInt(sP + p * pRec); }
    public String paramName(int p, int ns) { return str(b.getInt(sP + p * pRec + 4 + 4 * (ns - 1))); }
    public String fieldName(int f, int ns) { return ns == 0 ? str(b.getInt(sF + f * fRec)) : str(b.getInt(sF + f * fRec + 12 + 4 * (ns - 1))); }
    public String fieldDesc(int f) { return str(b.getInt(sF + f * fRec + 4)); }
    public boolean fieldStatic(int f) { return (b.getInt(sF + f * fRec + 8) & 1) != 0; }

    /** Class index by obf name (binary search over the sorted table), -1 if absent. */
    public int findClass(String obf) {
        int lo = 0, hi = classes - 1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; int x = cmp(className(mid, 0), obf); if (x == 0) return mid; if (x < 0) lo = mid + 1; else hi = mid - 1; }
        return -1;
    }
    /** Class index by its name in namespace {@code ns} (via that namespace's permutation), -1 if absent. */
    public int findClass(int ns, String name) {
        if (ns == 0) return findClass(name);
        int base = sQ;
        for (int i = 1; i < ns; i++) base += 4 + 4 * b.getInt(base);
        int n = b.getInt(base), lo = 0, hi = n - 1;
        while (lo <= hi) { int mid = (lo + hi) >>> 1; int c = b.getInt(base + 4 + 4 * mid); int x = cmp(className(c, ns), name); if (x == 0) return c; if (x < 0) lo = mid + 1; else hi = mid - 1; }
        return -1;
    }

    /** Back to a tree (e.g. to feed {@link RemapNames}); the table is complete, so no Completer run is needed. */
    public MappingTree toTree() {
        MappingTree t = new MappingTree();
        for (int i = 1; i < nsN; i++) t.ns(ns(i));
        for (int c = 0; c < classes; c++) {
            MappingTree.ClassRow r = t.cls(className(c, 0));
            for (int i = 1; i < nsN; i++) { String v = className(c, i); if (v != null) r.set(i, v); }
            for (int m = firstMethod(c), e = m + methodCount(c); m < e; m++) {
                MappingTree.MemberRow x = r.method(methodName(m, 0), methodDesc(m));
                x.isStatic = methodStatic(m);
                for (int i = 1; i < nsN; i++) { String v = methodName(m, i); if (v != null) x.set(i, v); }
                for (int p = firstParam(m), pe = p + paramCount(m); p < pe; p++) {
                    MappingTree.Row pr = x.param(paramIndex(p));
                    for (int i = 1; i < nsN; i++) { String v = paramName(p, i); if (v != null) pr.set(i, v); }
                }
            }
            for (int f = firstField(c), e = f + fieldCount(c); f < e; f++) {
                MappingTree.MemberRow x = r.field(fieldName(f, 0), fieldDesc(f));
                x.isStatic = fieldStatic(f);
                for (int i = 1; i < nsN; i++) { String v = fieldName(f, i); if (v != null) x.set(i, v); }
            }
        }
        return t;
    }
}
