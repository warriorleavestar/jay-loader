package amber.ul.resolve;

import java.util.*;

/** One version-range evaluator for both dialects: Maven ranges (Forge/NeoForge) and SemVer predicates (Fabric/Quilt). */
public final class Versions {
    private Versions() {}

    /** SemVer order: build metadata (+...) ignored, x.y.z compared numerically (missing parts = 0), a prerelease (-...) sorts before its release. */
    public static int compare(String a, String b) {
        a = stripBuild(a); b = stripBuild(b);
        int da = a.indexOf('-'), db = b.indexOf('-');
        String ca = da < 0 ? a : a.substring(0, da), cb = db < 0 ? b : b.substring(0, db);
        int c = idents(ca.split("\\."), cb.split("\\."), "0");
        if (c != 0) return c;
        if (da < 0 || db < 0) return da < 0 && db < 0 ? 0 : da < 0 ? 1 : -1;
        return idents(a.substring(da + 1).split("[.-]"), b.substring(db + 1).split("[.-]"), null);
    }
    private static String stripBuild(String v) { int p = v.indexOf('+'); return p < 0 ? v.trim() : v.substring(0, p).trim(); }
    /** pad == null: prerelease rules (shorter list sorts first); numeric < alphanumeric. */
    private static int idents(String[] x, String[] y, String pad) {
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (pad == null && (i >= x.length || i >= y.length)) return i >= x.length ? -1 : 1;
            String p = i < x.length ? x[i] : pad, q = i < y.length ? y[i] : pad;
            boolean np = p.matches("\\d+"), nq = q.matches("\\d+");
            int c = np && nq ? new java.math.BigInteger(p).compareTo(new java.math.BigInteger(q)) : np ? -1 : nq ? 1 : p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    public static boolean matches(String version, String range) {
        range = range.trim();
        if (range.isEmpty() || range.equals("*")) return true;
        if (range.contains("||")) { for (String r : range.split("\\|\\|")) if (matches(version, r)) return true; return false; }
        if (range.startsWith("[") || range.startsWith("(")) return maven(version, range);
        for (String part : range.split("\\s+")) if (!semverOne(version, part)) return false;
        return true;
    }

    private static boolean maven(String v, String r) {
        // supports a single [a,b) style interval or [x] exact
        boolean lo = r.startsWith("["), hi = r.endsWith("]");
        String body = r.substring(1, r.length() - 1);
        if (!body.contains(",")) return compare(v, body.trim()) == 0;
        String[] ab = body.split(",", -1);
        String a = ab[0].trim(), b = ab[1].trim();
        if (!a.isEmpty()) { int c = compare(v, a); if (lo ? c < 0 : c <= 0) return false; }
        if (!b.isEmpty()) { int c = compare(v, b); if (hi ? c > 0 : c >= 0) return false; }
        return true;
    }

    private static boolean semverOne(String v, String p) {
        if (p.startsWith(">=")) return compare(v, p.substring(2)) >= 0;
        if (p.startsWith("<=")) return compare(v, p.substring(2)) <= 0;
        if (p.startsWith(">")) return compare(v, p.substring(1)) > 0;
        if (p.startsWith("<")) return compare(v, p.substring(1)) < 0;
        if (p.startsWith("=")) return compare(v, p.substring(1)) == 0;
        if (p.startsWith("~")) { String b = p.substring(1); String[] s = b.split("\\."); String next = s[0] + "." + (s.length > 1 ? Integer.parseInt(s[1]) + 1 : 0); return compare(v, b) >= 0 && compare(v, next) < 0; }
        if (p.startsWith("^")) { String b = p.substring(1); String next = (Integer.parseInt(b.split("\\.")[0]) + 1) + ""; return compare(v, b) >= 0 && compare(v, next) < 0; }
        if (p.endsWith(".x") || p.endsWith(".*")) return v.startsWith(p.substring(0, p.length() - 1));
        return compare(v, p) == 0;
    }
}
