package amber.ul.personality;

import java.util.*;
import net.fabricmc.loader.api.*;

/** Version objects for the Fabric API shim, plus the global ObjectShare. */
final class FabricVersions {
    private FabricVersions() {}

    /** Semantic version 1.2.3[-pre][+build]; components may be x/X/* wildcards. */
    static SemanticVersion semver(String s) throws VersionParsingException {
        String core = s, pre = null, build = null;
        int plus = core.indexOf('+'); if (plus >= 0) { build = core.substring(plus + 1); core = core.substring(0, plus); }
        int dash = core.indexOf('-'); if (dash >= 0) { pre = core.substring(dash + 1); core = core.substring(0, dash); }
        if (core.isEmpty()) throw new VersionParsingException("empty version: " + s);
        String[] parts = core.split("[.]");
        int[] comps = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("x") || parts[i].equals("X") || parts[i].equals("*")) { comps[i] = SemanticVersion.COMPONENT_WILDCARD; continue; }
            try { comps[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException e) { throw new VersionParsingException("not semantic: " + s); }
        }
        String p = pre, b = build;
        return new SemanticVersion() {
            public int getVersionComponentCount() { return comps.length; }
            public int getVersionComponent(int pos) { return pos < comps.length ? comps[pos] : 0; }
            public Optional<String> getPrereleaseKey() { return Optional.ofNullable(p); }
            public Optional<String> getBuildKey() { return Optional.ofNullable(b); }
            public boolean hasWildcard() { for (int c : comps) if (c == COMPONENT_WILDCARD) return true; return false; }
            public String getFriendlyString() { return s; }
            public String toString() { return s; }
            public boolean equals(Object o) { return o instanceof Version v && v.getFriendlyString().equals(s); }
            public int hashCode() { return s.hashCode(); }
        };
    }

    static Version string(String s) {
        return new Version() {
            public String getFriendlyString() { return s; }
            public String toString() { return s; }
            public boolean equals(Object o) { return o instanceof Version v && v.getFriendlyString().equals(s); }
            public int hashCode() { return s.hashCode(); }
        };
    }

    static final ObjectShare OBJECT_SHARE = new ObjectShare() {
        private final Map<String, Object> map = new HashMap<>();
        private final Map<String, List<java.util.function.BiConsumer<String, Object>>> pending = new HashMap<>();
        public synchronized Object get(String k) { return map.get(k); }
        public void whenAvailable(String k, java.util.function.BiConsumer<String, Object> c) {
            Object v;
            synchronized (this) { v = map.get(k); if (v == null) { pending.computeIfAbsent(k, x -> new ArrayList<>()).add(c); return; } }
            c.accept(k, v);
        }
        public Object put(String k, Object v) {
            Object old; List<java.util.function.BiConsumer<String, Object>> l;
            synchronized (this) { old = map.put(k, Objects.requireNonNull(v)); l = pending.remove(k); }
            if (l != null) l.forEach(c -> c.accept(k, v));
            return old;
        }
        public Object putIfAbsent(String k, Object v) { synchronized (this) { if (map.containsKey(k)) return map.get(k); } put(k, v); return null; }
        public synchronized Object remove(String k) { return map.remove(k); }
    };
}
