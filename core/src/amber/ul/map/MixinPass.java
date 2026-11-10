package amber.ul.map;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.*;
import org.objectweb.asm.*;

/**
 * Mixin-aware renames for mod remapping (brief §5.1 "mixin json/refmaps need dedicated passes"). Behaviour follows
 * Mixin's own rules (SpongePowered Mixin docs/source, read for behaviour) and what tiny-remapper's mixin extension does;
 * the code is Amber's.
 *
 * Analysis (A namespace, before the jar pass): for every class annotated @Mixin, its targets (value = classes,
 * targets = names) and then
 *  - @Shadow fields/methods (prefix, default "shadow$", stripped for the lookup and kept): renamed to the target's name;
 *  - @Overwrite methods: renamed to the target's name;
 *  - @Accessor / @Invoker: target named by value, or inferred from the method name (get/is/set, call/invoke);
 *    without a value the accessor METHOD is renamed (getField_1359 -> getStorage), with a value the value string is
 *    rewritten (only without a refmap: with one it is a dev name the refmap translates);
 *  - remap=false on @Mixin or on the annotation turns a rename off.
 * The renames go into {@link ModNames} so every reference (inner classes, other classes calling an accessor) follows.
 *
 * Annotation strings (during the jar pass, before names change): @Mixin targets always; for jars WITHOUT a refmap also
 * accessor/invoker values and injector selectors ("method" of any injector, @At "target", incl. MixinExtras),
 * because Loom wrote intermediary strings there instead of a refmap.
 */
public final class MixinPass {
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;", SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;",
            OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;", ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;", AT = "Lorg/spongepowered/asm/mixin/injection/At;";

    final ModNames n;
    /** mixin class (A) -> its target classes (A) */
    public final Map<String, List<String>> targets = new ConcurrentHashMap<>();
    /** "mixin.name+desc" -> rewritten accessor/invoker value (B) */
    final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Integer> stats = new ConcurrentHashMap<>();
    void count(String k) { stats.merge(k, 1, Integer::sum); }

    public MixinPass(ModNames n) { this.n = n; }

    // ---- analysis -----------------------------------------------------------------------------------------------

    public void analyze(List<JarRemapper.Entry> jar) {
        for (JarRemapper.Entry e : jar) {
            if (!e.name().endsWith(".class") || e.name().startsWith("META-INF/")) continue;
            if (mentions(e.data(), "org/spongepowered/asm/mixin/Mixin")) analyzeClass(e.data());
        }
    }
    public void analyze(Path jar) throws IOException {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                byte[] b; try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); }
                if (!mentions(b, "org/spongepowered/asm/mixin/Mixin")) continue;
                analyzeClass(b);
            }
        }
    }
    static boolean mentions(byte[] b, String s) {
        byte[] x = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + x.length <= b.length; i++) { for (int k = 0; k < x.length; k++) if (b[i + k] != x[k]) continue outer; return true; }
        return false;
    }

    /** One annotation's values, collected with a tiny visitor (no tree API needed). */
    static final class Ann {
        final String desc; final Map<String, Object> v = new HashMap<>();
        Ann(String d) { desc = d; }
        AnnotationVisitor visitor() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String k, Object val) { v.put(k, val); }
                @Override public AnnotationVisitor visitArray(String k) {
                    List<Object> l = new ArrayList<>(); v.put(k, l);
                    return new AnnotationVisitor(Opcodes.ASM9) { @Override public void visit(String k2, Object val) { l.add(val); } };
                }
            };
        }
        boolean remap() { return !Boolean.FALSE.equals(v.get("remap")); }
        String str(String k) { Object o = v.get(k); return o instanceof String s ? s : null; }
    }
    record Member(boolean field, int access, String name, String desc, List<Ann> anns) {}

    void analyzeClass(byte[] b) {
        String[] cls = new String[1]; List<Ann> classAnns = new ArrayList<>(); List<Member> members = new ArrayList<>();
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String name, String s, String sup, String[] itf) { cls[0] = name; }
            @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) { Ann a = new Ann(d); classAnns.add(a); return a.visitor(); }
            @Override public FieldVisitor visitField(int acc, String name, String d, String s, Object val) {
                Member m = new Member(true, acc, name, d, new ArrayList<>()); members.add(m);
                return new FieldVisitor(Opcodes.ASM9) { @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) { Ann a = new Ann(ad); m.anns.add(a); return a.visitor(); } };
            }
            @Override public MethodVisitor visitMethod(int acc, String name, String d, String s, String[] ex) {
                Member m = new Member(false, acc, name, d, new ArrayList<>()); members.add(m);
                return new MethodVisitor(Opcodes.ASM9) { @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) { Ann a = new Ann(ad); m.anns.add(a); return a.visitor(); } };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Ann mixin = classAnns.stream().filter(a -> a.desc.equals(MIXIN)).findFirst().orElse(null);
        if (mixin == null) return;
        List<String> ts = new ArrayList<>();
        if (mixin.v.get("value") instanceof List<?> l) for (Object o : l) if (o instanceof Type t) ts.add(t.getInternalName());
        if (mixin.v.get("targets") instanceof List<?> l) for (Object o : l) if (o instanceof String s) ts.add(s.replace('.', '/'));
        targets.put(cls[0], ts);
        count("mixins");
        if (!mixin.remap()) { count("mixins remap=false"); return; }
        for (Member m : members) for (Ann a : m.anns) {
            if (!a.remap()) continue;
            switch (a.desc) {
                case SHADOW -> shadow(cls[0], ts, m, a.str("prefix") == null ? "shadow$" : a.str("prefix"));
                case OVERWRITE -> shadow(cls[0], ts, m, null);
                case ACCESSOR -> accessor(cls[0], ts, m, a.str("value"));
                case INVOKER -> invoker(cls[0], ts, m, a.str("value"));
                default -> {}
            }
        }
    }

    void shadow(String mixin, List<String> ts, Member m, String prefix) {
        String base = prefix != null && m.name.startsWith(prefix) ? m.name.substring(prefix.length()) : m.name;
        String kept = base == m.name ? "" : prefix;
        String b = m.field ? targetField(ts, base, m.desc) : targetMethod(ts, base, m.desc);
        if (b == null) { count(m.field ? "shadow field: target not in game" : "shadow method: target not in game"); return; }
        if (b.equals(base)) return;
        n.rename(m.field, mixin, m.name, m.desc, kept + b);
        count(m.field ? "shadow field renamed" : "shadow/overwrite method renamed");
    }

    static final String[] GET = {"get", "is"}, SET = {"set"}, CALL = {"call", "invoke"}, NEW = {"new", "create"};

    void accessor(String mixin, List<String> ts, Member m, String value) {
        Type[] args = Type.getArgumentTypes(m.desc); Type ret = Type.getReturnType(m.desc);
        boolean setter = ret.getSort() == Type.VOID && args.length == 1;
        String fdesc = setter ? args[0].getDescriptor() : ret.getDescriptor();
        String prefix = null, fname;
        if (value != null && !value.isEmpty()) fname = value;
        else {
            for (String p : setter ? SET : GET) if (m.name.startsWith(p) && m.name.length() > p.length()) { prefix = p; break; }
            if (prefix == null) { count("accessor: name not understood"); return; }
            fname = decap(m.name.substring(prefix.length()));
        }
        String b = targetField(ts, fname, fdesc);
        if (b == null && prefix != null) b = targetField(ts, m.name.substring(prefix.length()), fdesc);   // CONSTANT_NAME style
        if (b == null) { count("accessor: target not in game"); return; }
        if (b.equals(fname)) return;
        if (prefix != null) { n.rename(false, mixin, m.name, m.desc, prefix + cap(b)); count("accessor method renamed"); }
        else { values.put(mixin + "." + m.name + m.desc, b); count("accessor value rewritten"); }
    }

    void invoker(String mixin, List<String> ts, Member m, String value) {
        String prefix = null, mname;
        if (value != null && !value.isEmpty()) mname = value;
        else {
            for (String p : NEW) if (m.name.startsWith(p) && m.name.length() > p.length()) { count("invoker: constructor"); return; }
            for (String p : CALL) if (m.name.startsWith(p) && m.name.length() > p.length()) { prefix = p; break; }
            if (prefix == null) { count("invoker: name not understood"); return; }
            mname = decap(m.name.substring(prefix.length()));
        }
        if (mname.equals("<init>")) return;
        String b = targetMethod(ts, mname, m.desc);
        if (b == null) { count("invoker: target not in game"); return; }
        if (b.equals(mname)) return;
        if (prefix != null) { n.rename(false, mixin, m.name, m.desc, prefix + cap(b)); count("invoker method renamed"); }
        else { values.put(mixin + "." + m.name + m.desc, b); count("invoker value rewritten"); }
    }

    static String decap(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }
    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    String targetField(List<String> ts, String name, String desc) {
        for (String t : ts) { String b = n.gameField(t, name, desc); if (b != null) return b; }
        return null;
    }
    String targetMethod(List<String> ts, String name, String desc) {
        for (String t : ts) { String b = n.gameMethod(t, name, desc); if (b != null) return b; }
        return null;
    }
    /** Name-only method lookup (selector without a descriptor): the B name when every overload agrees. */
    String targetMethodByName(List<String> ts, String name) {
        Set<String> seen = new HashSet<>();
        for (String t : ts) seen.addAll(n.gameMethodsNamed(t, name));
        return seen.size() == 1 ? seen.iterator().next() : null;
    }

    // ---- annotation strings during the jar pass -------------------------------------------------------------------

    /** A visitor (A names) placed before the remapper; {@code refmap} = the jar has a refmap (selectors left alone). */
    public ClassVisitor annotations(ClassVisitor next, boolean refmap) {
        return new ClassVisitor(Opcodes.ASM9, next) {
            String cls; List<String> ts;
            @Override public void visit(int v, int a, String name, String s, String sup, String[] itf) {
                cls = name; ts = targets.get(name); currentTargets.set(ts == null ? List.of() : ts);
                super.visit(v, a, name, s, sup, itf);
            }
            @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) {
                AnnotationVisitor av = super.visitAnnotation(d, vis);
                return ts != null && d.equals(MIXIN) ? mixinTargets(av) : av;
            }
            @Override public MethodVisitor visitMethod(int acc, String name, String d, String s, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, d, s, ex);
                if (ts == null) return mv;
                String key = cls + "." + name + d;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override public AnnotationVisitor visitAnnotation(String ad, boolean vis) {
                        AnnotationVisitor av = super.visitAnnotation(ad, vis);
                        if ((ad.equals(ACCESSOR) || ad.equals(INVOKER)) && values.containsKey(key) && !refmap) return replace(av, "value", values.get(key));
                        return refmap ? av : selectors(av, ad);
                    }
                };
            }
        };
    }

    AnnotationVisitor mixinTargets(AnnotationVisitor av) {
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override public AnnotationVisitor visitArray(String k) {
                AnnotationVisitor a = super.visitArray(k);
                if (!"targets".equals(k)) return a;
                return new AnnotationVisitor(Opcodes.ASM9, a) {
                    @Override public void visit(String k2, Object v) {
                        if (v instanceof String s) { boolean dots = s.indexOf('.') >= 0; String m = n.cls(s.replace('.', '/')); v = dots ? m.replace('/', '.') : m; count("mixin target string"); }
                        super.visit(k2, v);
                    }
                };
            }
        };
    }

    static AnnotationVisitor replace(AnnotationVisitor av, String key, String val) {
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override public void visit(String k, Object v) { super.visit(k, key.equals(k) ? val : v); }
        };
    }

    /** Injector "method" selectors and @At "target" references (recursively: @Slice(from=@At), @At arrays, MixinExtras). */
    AnnotationVisitor selectors(AnnotationVisitor av, String desc) {
        List<String> ts = currentTargets.get();
        return new AnnotationVisitor(Opcodes.ASM9, av) {
            @Override public void visit(String k, Object v) {
                if (v instanceof String s) {
                    if ("method".equals(k)) v = selector(s, ts);
                    else if ("target".equals(k) && AT.equals(desc)) v = reference(s, ts);
                }
                super.visit(k, v);
            }
            @Override public AnnotationVisitor visitArray(String k) {
                AnnotationVisitor a = super.visitArray(k);
                boolean isMethod = "method".equals(k), isTarget = "target".equals(k) && AT.equals(desc);
                return new AnnotationVisitor(Opcodes.ASM9, a) {
                    @Override public void visit(String k2, Object v) {
                        if (v instanceof String s) { if (isMethod) v = selector(s, ts); else if (isTarget) v = reference(s, ts); }
                        super.visit(k2, v);
                    }
                    @Override public AnnotationVisitor visitAnnotation(String k2, String d2) { return selectors(super.visitAnnotation(k2, d2), d2); }
                };
            }
            @Override public AnnotationVisitor visitAnnotation(String k, String d) { return selectors(super.visitAnnotation(k, d), d); }
        };
    }
    /** The targets of the class being visited (set around the jar pass per class; selectors need them). */
    final ThreadLocal<List<String>> currentTargets = ThreadLocal.withInitial(List::of);

    /**
     * A target selector: [Lowner;]name[(desc)ret] | [Lowner;]name:desc | name, maybe with Mixin's quantifier suffix.
     * Owner-less names are looked up in the mixin's targets. Anything that doesn't parse is left as it is.
     */
    String selector(String s, List<String> ts) {
        String r = reference(s, ts);
        if (!r.equals(s)) count("selector rewritten");
        return r;
    }
    String reference(String s, List<String> ts) {
        if (s.isEmpty() || s.indexOf('*') >= 0 || s.indexOf('{') >= 0) return s;
        String owner = null, rest = s;
        if (s.startsWith("L")) { int semi = s.indexOf(';'); if (semi < 0) return s; owner = s.substring(1, semi); rest = s.substring(semi + 1); }
        if (rest.isEmpty()) return owner == null ? s : "L" + n.cls(owner) + ";";   // class reference (@At NEW)
        int p = rest.indexOf('('), c = rest.indexOf(':');
        List<String> look = owner != null ? List.of(owner) : ts;
        String no = owner == null ? "" : "L" + n.cls(owner) + ";";
        if (p >= 0) {
            String name = rest.substring(0, p), desc = rest.substring(p);
            String b = name.startsWith("<") ? name : targetMethod(look, name, desc);
            return no + (b == null ? name : b) + n.mapDesc(desc);
        }
        if (c >= 0) {
            String name = rest.substring(0, c), desc = rest.substring(c + 1);
            String b = targetField(look, name, desc);
            return no + (b == null ? name : b) + ":" + n.mapDesc(desc);
        }
        if (rest.startsWith("<")) return no + rest;
        String b = targetMethodByName(look, rest);
        if (b == null) b = targetFieldByName(look, rest);
        return no + (b == null ? rest : b);
    }
    String targetFieldByName(List<String> ts, String name) {
        Set<String> seen = new HashSet<>();
        for (String t : ts) seen.addAll(n.gameFieldsNamed(t, name));
        return seen.size() == 1 ? seen.iterator().next() : null;
    }
}
