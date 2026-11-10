package amber.ul.bake;

import amber.ul.map.JarRemapper;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * One MixinExtras per class universe. Some mods shade a relocated MixinExtras (MemoryLeakFix: ca/fxco/memoryleakfix/mixinextras).
 * On Forge/Fabric every copy lives in the mods' own loader and the copies negotiate one active service. In Amber the
 * runtime's MixinExtras sits in the parent loader; when it wins the negotiation it also serves the relocated copy's
 * types and defines generated sugar classes (LocalRefImpl...) in its own loader, where the relocated interfaces are
 * invisible -> NoClassDefFoundError for every mod's @Local/@Share. Bake-time fix: map the relocated package back to
 * com/llamalad7/mixinextras and drop the relocated classes, so the mod uses the runtime's copy (MixinExtras keeps its
 * annotations and ref types backward compatible).
 */
public final class Unshade {
    private Unshade() {}
    static final String CANON = "com/llamalad7/mixinextras/";

    /** Relocated MixinExtras prefixes in this jar ("ca/fxco/memoryleakfix/mixinextras/"), canonical one excluded. */
    public static List<String> mixinExtrasPrefixes(List<JarRemapper.Entry> jar) {
        List<String> l = new ArrayList<>();
        for (JarRemapper.Entry e : jar) {
            String n = e.name();
            if (n.endsWith("/MixinExtrasBootstrap.class") && !n.startsWith(CANON)) l.add(n.substring(0, n.length() - "MixinExtrasBootstrap.class".length()));
        }
        return l;
    }

    public static List<JarRemapper.Entry> mixinExtras(List<JarRemapper.Entry> jar, List<String> prefixes) {
        if (prefixes.isEmpty()) return jar;
        Remapper r = new Remapper() {
            @Override public String map(String internal) {
                for (String p : prefixes) if (internal.startsWith(p)) return CANON + internal.substring(p.length());
                return internal;
            }
        };
        List<JarRemapper.Entry> out = new ArrayList<>(jar.size());
        for (JarRemapper.Entry e : jar) {
            String n = e.name();
            if (prefixes.stream().anyMatch(n::startsWith)) continue;   // the relocated copy itself
            if (n.endsWith(".class")) {
                ClassReader cr = new ClassReader(e.data());
                ClassWriter cw = new ClassWriter(0);
                cr.accept(new ClassRemapper(cw, r), 0);
                out.add(new JarRemapper.Entry(n, e.time(), cw.toByteArray()));
            } else out.add(e);
        }
        return out;
    }
}
