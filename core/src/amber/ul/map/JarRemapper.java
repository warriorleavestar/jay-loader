package amber.ul.map;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.AnnotationNode;

/**
 * Amber's jar remapper: one parallel pass over every entry, then a single-threaded deterministic write.
 *
 * "ART mode" ({@link Options#art()}) reproduces NeoForge's installer step (AutoRenamingTool 2.0.3 with --ann-fix
 * --ids-fix --src-fix --record-fix) so the NeoForge base jar can be built without running ART; the goal is a
 * byte-identical file (brief M1). Behaviour was read from ART's source (LGPL-2.1) and re-implemented here; nothing
 * is copied. Points that decide the bytes:
 *  - rename: fresh ClassWriter(0), ClassReader flags 0; lambda indy names are mapped against the functional
 *    interface (return type of the indy desc + first bsm arg); handles map field vs method names by tag;
 *  - each fixer re-reads the class with ClassWriter(reader, 0) and keeps the old bytes when it changed nothing;
 *    the order is ann-fix, record-fix, ids-fix, src-fix; which method visitors are wrapped matters (ASM copies an
 *    unwrapped method's bytes verbatim);
 *  - parameter names of abstract/native methods go to fernflower_abstract_parameter_names.txt;
 *  - output entries sorted by name (MANIFEST first), parent directory entries added with the 2000-01-01 timestamp,
 *    file timestamps kept, default deflate (done in parallel; {@link ZipWriter} lays the file out like ZipOutputStream).
 */
public final class JarRemapper {
    public static final long STABLE_TIME = 0x386D4380L;
    static final String MANIFEST = "META-INF/MANIFEST.MF";
    static final String ABSTRACT_FILE = "fernflower_abstract_parameter_names.txt";

    public record Options(boolean annFix, boolean recordFix, boolean idsFix, boolean srcFix, boolean abstractParams, boolean stripSignatures, boolean resources) {
        public static Options art() { return new Options(true, true, true, true, true, false, false); }
        /** Mods: no ProGuard repairs (mods aren't ProGuarded), invalid locals renamed; SourceFile kept (mod classes keep
         *  their names, and rebuilding would turn Kotlin's "Readers.kt" into "ReadersKt.java"). */
        public static Options mod() { return new Options(false, false, true, false, false, true, true); }
    }
    /** One jar entry in memory (name, modification time, bytes). */
    public record Entry(String name, long time, byte[] data) {}
    /** Every non-directory entry of a jar, in jar order. */
    public static List<Entry> read(Path in) throws IOException {
        List<Entry> old = new ArrayList<>();
        try (ZipFile z = new ZipFile(in.toFile())) {
            for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream is = z.getInputStream(e)) { old.add(new Entry(e.getName(), e.getTime(), amber.ul.bake.SafeFiles.boundedRead(is, amber.ul.bake.SafeFiles.ENTRY_MAX, e.getName()))); }
            }
        }
        return old;
    }

    final NameSource names; final Options opt; final int threads;
    final Set<String> abstractParams = ConcurrentHashMap.newKeySet();
    public final Map<String, Long> ms = new LinkedHashMap<>();

    public JarRemapper(NameSource names, Options opt, int threads) { this.names = names; this.opt = opt; this.threads = threads; }
    /** Optional visitor stage before the renamer (sees input names), e.g. {@link MixinPass#annotations}. */
    public java.util.function.UnaryOperator<ClassVisitor> pre;
    public JarRemapper pre(java.util.function.UnaryOperator<ClassVisitor> p) { pre = p; return this; }
    /** Optional last step per output entry (output name, bytes) -> bytes, run in the parallel pipeline before deflate. */
    public java.util.function.BiFunction<String, byte[], byte[]> post;
    public JarRemapper post(java.util.function.BiFunction<String, byte[], byte[]> p) { post = p; return this; }
    /** Mod mode: reflection strings (before rename) and record/switch bootstrap names (during rename). Null = off (ART mode). */
    public StringPass strings;
    public JarRemapper strings(StringPass s) { strings = s; return this; }

    public void run(Path in, Path out) throws Exception {
        long t0 = System.nanoTime();
        List<Entry> old = read(in);
        ms.put("read", (System.nanoTime() - t0) / 1_000_000);
        run(old, out);
    }

    public void run(List<Entry> old, Path out) throws Exception {
        long t1 = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<ZipWriter.Packed> neu = new ArrayList<>();
        ThreadLocal<Deflater> defl = ThreadLocal.withInitial(() -> new Deflater(Deflater.DEFAULT_COMPRESSION, true));
        try {
            List<Future<ZipWriter.Packed>> fs = new ArrayList<>();
            for (Entry e : old) fs.add(pool.submit(() -> { Entry r = process(e); if (r != null && post != null) r = new Entry(r.name, r.time, post.apply(r.name, r.data)); return r == null ? null : ZipWriter.deflate(r.name, r.time, r.data, defl.get()); }));
            for (Future<ZipWriter.Packed> f : fs) { ZipWriter.Packed r = f.get(); if (r != null) neu.add(r); }
        } finally { pool.shutdown(); }
        if (opt.abstractParams && !abstractParams.isEmpty())
            neu.add(ZipWriter.deflate(ABSTRACT_FILE, STABLE_TIME, String.join("\n", new TreeSet<>(abstractParams)).getBytes(StandardCharsets.UTF_8), new Deflater(Deflater.DEFAULT_COMPRESSION, true)));
        long t2 = System.nanoTime();
        Set<String> seen = new HashSet<>(); List<String> dup = new ArrayList<>();
        for (ZipWriter.Packed e : neu) if (!seen.add(e.name())) dup.add(e.name());
        if (!dup.isEmpty()) throw new IllegalStateException("duplicate entries after remap: " + dup.stream().sorted().limit(20).toList());
        neu.sort((a, b) -> a.name().equals(b.name()) ? 0 : a.name().equals(MANIFEST) ? -1 : b.name().equals(MANIFEST) ? 1 : a.name().compareTo(b.name()));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Set<String> dirs = new HashSet<>();
        try (ZipWriter zw = new ZipWriter(new BufferedOutputStream(Files.newOutputStream(out), 1 << 20))) {
            for (ZipWriter.Packed e : neu) {
                int i = e.name().lastIndexOf('/');
                if (i >= 0) dir(zw, dirs, e.name().substring(0, i));
                zw.add(e);
            }
        }
        long t3 = System.nanoTime();
        ms.put("process", (t2 - t1) / 1_000_000); ms.put("write", (t3 - t2) / 1_000_000);
    }

    private static void dir(ZipWriter zw, Set<String> dirs, String d) throws IOException {
        if (!dirs.add(d)) return;
        int i = d.lastIndexOf('/');
        if (i >= 0) dir(zw, dirs, d.substring(0, i));
        zw.directory(d + "/", STABLE_TIME);
    }

    Entry process(Entry e) {
        if (e.name.endsWith(".class")) {
            String prefix = "", cls = e.name.substring(0, e.name.length() - 6);
            if (e.name.startsWith("META-INF/versions/")) {
                int s = e.name.indexOf('/', "META-INF/versions/".length());
                prefix = e.name.substring(0, s + 1); cls = e.name.substring(s + 1, e.name.length() - 6);
            }
            byte[] b = rename(strings == null ? e.data : strings.apply(e.data));
            String n = prefix + names.cls(cls) + ".class";
            // a fixer only re-writes the class when a cheap scan (no writer, skipping what it doesn't look at) says it changes it
            if (opt.annFix) b = fix(b, ParamAnnotationFix::new, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            if (opt.recordFix) b = fix(b, RecordFix::new, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            if (opt.idsFix) b = fix(b, LocalNameFix::new, ClassReader.SKIP_FRAMES);
            if (opt.srcFix) b = fix(b, SourceFileFix::new, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
            return new Entry(n, e.time, b);
        }
        if (opt.abstractParams && e.name.equals(ABSTRACT_FILE)) return null;   // regenerated
        if (opt.stripSignatures && signatureFile(e.name)) return null;
        if (e.name.equals(MANIFEST)) {
            byte[] b = e.data;
            if (opt.stripSignatures) b = unsign(b);
            if (opt.resources) b = manifestClasses(b);
            return b == e.data ? e : new Entry(e.name, e.time, b);
        }
        if (opt.resources && e.name.startsWith(SERVICES) && e.name.indexOf('/', SERVICES.length()) < 0 && e.name.length() > SERVICES.length())
            return service(e);
        return e;
    }

    static final String SERVICES = "META-INF/services/";
    String dotted(String binary) { return names.cls(binary.replace('.', '/')).replace('/', '.'); }

    /** ServiceLoader file: the interface is the file name, each line an implementation (comments after '#').
     *  Lines are rewritten in place so the original line endings and comments survive; unchanged files keep their bytes. */
    Entry service(Entry e) {
        String n = e.name.substring(SERVICES.length()), nn = dotted(n);
        String text = new String(e.data, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(text.length());
        Matcher m = SERVICE_LINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start(1)).append(dotted(m.group(1)));
            last = m.end(1);
        }
        out.append(text, last, text.length());
        String t = out.toString();
        if (nn.equals(n) && t.equals(text)) return e;
        return new Entry(SERVICES + nn, e.time, t.getBytes(StandardCharsets.UTF_8));
    }
    // a provider name at the start of a line (after spaces), up to whitespace or '#'
    static final Pattern SERVICE_LINE = Pattern.compile("(?m)^[ \\t]*([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}.$]*)");

    static final String[] MANIFEST_CLASSES = {"Main-Class", "Launcher-Agent-Class", "Premain-Class", "Agent-Class"};
    /** Main-Class and the agent attributes name classes; rewritten only when one is renamed. */
    byte[] manifestClasses(byte[] b) {
        try {
            java.util.jar.Manifest m = new java.util.jar.Manifest(new ByteArrayInputStream(b));
            boolean changed = false;
            for (String k : MANIFEST_CLASSES) {
                String v = m.getMainAttributes().getValue(k);
                if (v == null) continue;
                String nv = dotted(v.trim());
                if (!nv.equals(v.trim())) { m.getMainAttributes().putValue(k, nv); changed = true; }
            }
            if (!changed) return b;
            ByteArrayOutputStream o = new ByteArrayOutputStream(); m.write(o); return o.toByteArray();
        } catch (IOException e) { return b; }
    }

    /** Jar signature files: classes change, so a kept signature would make the JVM reject the jar. */
    static boolean signatureFile(String n) {
        if (!n.startsWith("META-INF/") || n.indexOf('/', 9) >= 0) return false;
        String u = n.toUpperCase(Locale.ROOT);
        return u.endsWith(".SF") || u.endsWith(".RSA") || u.endsWith(".DSA") || u.endsWith(".EC") || u.startsWith("META-INF/SIG-");
    }
    /** Manifest without per-entry digests (sections left empty are dropped); unchanged bytes when there were none. */
    static byte[] unsign(byte[] b) {
        try {
            java.util.jar.Manifest m = new java.util.jar.Manifest(new ByteArrayInputStream(b));
            boolean changed = false;
            for (Iterator<Map.Entry<String, java.util.jar.Attributes>> it = m.getEntries().entrySet().iterator(); it.hasNext(); ) {
                java.util.jar.Attributes a = it.next().getValue();
                changed |= a.keySet().removeIf(k -> k.toString().toUpperCase(Locale.ROOT).endsWith("-DIGEST"));
                if (a.isEmpty()) { it.remove(); changed = true; }
            }
            if (!changed) return b;
            ByteArrayOutputStream o = new ByteArrayOutputStream(); m.write(o); return o.toByteArray();
        } catch (IOException e) { return b; }
    }

    // ---- rename ----------------------------------------------------------------------------------------------------

    final class Names extends Remapper {
        Names() { super(Opcodes.ASM9); }
        @Override public String map(String n) { return names.cls(n); }
        @Override public String mapMethodName(String owner, String name, String desc) { return names.method(owner, name, desc); }
        @Override public String mapFieldName(String owner, String name, String desc) { return names.field(owner, name, desc); }
        @Override public String mapRecordComponentName(String owner, String name, String desc) { return names.field(owner, name, desc); }
        @Override public String mapPackageName(String n) { return n; }
        @Override public String mapModuleName(String n) { return n; }
        @Override public String mapAnnotationAttributeName(String desc, String n) { return n; }
        @Override public String mapInvokeDynamicMethodName(String n, String desc) { return n; }
        @Override public String mapInvokeDynamicMethodName(String n, String desc, Handle bsm, Object... args) { return n; }
        @Override public Object mapValue(Object v) {
            if (v instanceof Handle h) {
                boolean fieldHandle = h.getTag() <= Opcodes.H_PUTSTATIC;
                return new Handle(h.getTag(), mapType(h.getOwner()),
                        fieldHandle ? mapFieldName(h.getOwner(), h.getName(), h.getDesc()) : mapMethodName(h.getOwner(), h.getName(), h.getDesc()),
                        fieldHandle ? mapDesc(h.getDesc()) : mapMethodDesc(h.getDesc()), h.isInterface());
            }
            return super.mapValue(v);
        }
    }

    static boolean lambdaFactory(Handle h) {
        return h.getTag() == Opcodes.H_INVOKESTATIC && !h.isInterface() && h.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                && (h.getName().equals("metafactory") && h.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;")
                 || h.getName().equals("altMetafactory") && h.getDesc().equals("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;"));
    }

    byte[] rename(byte[] in) {
        ClassReader r = new ClassReader(in);
        ClassWriter w = new ClassWriter(0);
        Names m = new Names();
        ClassVisitor cr = new ClassRemapper(Opcodes.ASM9, w, m) {
            @Override public MethodVisitor visitMethod(int access, String mname, String mdesc, String sig, String[] exc) {
                MethodVisitor mv = cv.visitMethod(access, m.mapMethodName(className, mname, mdesc), m.mapMethodDesc(mdesc), m.mapSignature(sig, false), exc == null ? null : m.mapTypes(exc));
                if (mv == null) return null;
                if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) abstractNames(className, access, mname, mdesc, m);
                String owner = className;
                return new MethodRemapper(Opcodes.ASM9, mv, m) {
                    @Override public void visitLocalVariable(String n, String d, String s, Label a, Label b, int idx) {
                        super.visitLocalVariable(names.param(owner, mname, mdesc, idx, n), d, s, a, b, idx);
                    }
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        if (!lambdaFactory(bsm)) { super.visitInvokeDynamicInsn(n, d, bsm, strings == null ? args : bootstrapNames(n, d, bsm, args, m, owner)); return; }
                        String itf = Type.getReturnType(d).getInternalName();
                        String samDesc = ((Type) args[0]).getDescriptor();
                        Object[] a2 = new Object[args.length];
                        for (int i = 0; i < args.length; i++) a2[i] = m.mapValue(args[i]);
                        mv.visitInvokeDynamicInsn(m.mapMethodName(itf, n, samDesc), m.mapMethodDesc(d), (Handle) m.mapValue(bsm), a2);
                    }
                };
            }
        };
        r.accept(pre == null ? cr : pre.apply(cr), 0);
        return w.toByteArray();
    }

    static final String CONST_BOOT = "java/lang/invoke/ConstantBootstraps";
    /**
     * Names that bootstrap methods take as strings (input names; the caller's remapper still maps types and handles):
     *  - ObjectMethods.bootstrap(record, "a;b;c", getters...): the component list is rebuilt from the mapped getter handles.
     *  - SwitchBootstraps.enumSwitch(labels...): String labels are constants of the enum switched on (first indy arg).
     *  - SwitchBootstraps.typeSwitch: EnumDesc constants (condy EnumDesc.of(ClassDesc.of("a.b.C"), "NAME")) map class and
     *    constant; plain String labels are string cases and are kept.
     */
    Object[] bootstrapNames(String n, String d, Handle bsm, Object[] args, Remapper m, String where) {
        Object[] a = args.clone();
        if (bsm.getOwner().equals("java/lang/runtime/ObjectMethods") && bsm.getName().equals("bootstrap") && a.length >= 2 && a[1] instanceof String list) {
            List<String> comps = list.isEmpty() ? List.of() : Arrays.asList(list.split(";", -1));
            if (comps.size() != a.length - 2) { strings.note("ObjectMethods: list/handle count differ", where + " \"" + list + "\""); return a; }
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i < a.length; i++) {
                if (!(a[i] instanceof Handle h) || h.getTag() != Opcodes.H_GETFIELD || !h.getName().equals(comps.get(i - 2))) { strings.note("ObjectMethods: unexpected handle", where + " " + a[i]); return a; }
                if (i > 2) sb.append(';');
                sb.append(m.mapFieldName(h.getOwner(), h.getName(), h.getDesc()));
            }
            if (!sb.toString().equals(list)) { a[1] = sb.toString(); strings.count("ObjectMethods names"); }
            return a;
        }
        if (bsm.getOwner().equals("java/lang/runtime/SwitchBootstraps")) {
            if (bsm.getName().equals("enumSwitch")) {
                Type[] at = Type.getArgumentTypes(d);
                if (at.length == 0 || at[0].getSort() != Type.OBJECT) return a;
                String e = at[0].getInternalName();
                for (int i = 0; i < a.length; i++) if (a[i] instanceof String c) {
                    String r = m.mapFieldName(e, c, "L" + e + ";");
                    if (!r.equals(c)) { a[i] = r; strings.count("enumSwitch labels"); }
                }
            }
            for (int i = 0; i < a.length; i++) if (a[i] instanceof ConstantDynamic cd) a[i] = enumDesc(cd, m);
        }
        return a;
    }
    /** condy invoke(EnumDesc.of, condy invoke(ClassDesc.of, "a.b.C"), "NAME") with both strings mapped; anything else unchanged. */
    ConstantDynamic enumDesc(ConstantDynamic cd, Remapper m) {
        Handle b = cd.getBootstrapMethod();
        if (!b.getOwner().equals(CONST_BOOT) || !b.getName().equals("invoke") || cd.getBootstrapMethodArgumentCount() != 3) return cd;
        if (!(cd.getBootstrapMethodArgument(0) instanceof Handle of) || !of.getOwner().equals("java/lang/Enum$EnumDesc") || !of.getName().equals("of")) return cd;
        if (!(cd.getBootstrapMethodArgument(1) instanceof ConstantDynamic cls) || !(cd.getBootstrapMethodArgument(2) instanceof String name)) return cd;
        Handle cb = cls.getBootstrapMethod();
        if (!cb.getOwner().equals(CONST_BOOT) || !cb.getName().equals("invoke") || cls.getBootstrapMethodArgumentCount() != 2
                || !(cls.getBootstrapMethodArgument(0) instanceof Handle co) || !co.getOwner().equals("java/lang/constant/ClassDesc") || !co.getName().equals("of")
                || !(cls.getBootstrapMethodArgument(1) instanceof String dotted)) { strings.note("EnumDesc: unexpected shape", cd.toString()); return cd; }
        String internal = dotted.replace('.', '/');
        String nc = m.map(internal).replace('/', '.'), nn = m.mapFieldName(internal, name, "L" + internal + ";");
        if (nc.equals(dotted) && nn.equals(name)) return cd;
        strings.count("EnumDesc labels");
        ConstantDynamic cls2 = new ConstantDynamic(cls.getName(), cls.getDescriptor(), cb, co, nc);
        return new ConstantDynamic(cd.getName(), cd.getDescriptor(), b, of, cls2, nn);
    }

    void abstractNames(String owner, int access, String name, String desc, Remapper m) {
        Type[] args = Type.getArgumentTypes(desc);
        if (args.length == 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append(m.mapType(owner)).append(' ').append(m.mapMethodName(owner, name, desc)).append(' ').append(m.mapMethodDesc(desc));
        int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type t : args) { sb.append(' ').append(names.param(owner, name, desc, slot, "var" + slot)); slot += t.getSize(); }
        abstractParams.add(sb.toString());
    }

    // ---- fixers: re-read only if changed ---------------------------------------------------------------------------

    abstract static class Fix extends ClassVisitor {
        boolean changed;
        Fix(ClassVisitor cv) { super(Opcodes.ASM9, cv); }
    }
    interface FixFactory { Fix make(ClassVisitor cv); }

    static byte[] fix(byte[] in, FixFactory f, int scanFlags) {
        ClassReader r = new ClassReader(in);
        Fix scan = f.make(null);
        r.accept(scan, scanFlags);
        if (!scan.changed) return in;
        ClassWriter w = new ClassWriter(r, 0);
        Fix x = f.make(w);
        r.accept(x, 0);
        return x.changed ? w.toByteArray() : in;
    }

    /**
     * ProGuard shifts parameter annotations of constructors with synthetic leading params (enum name/ordinal, outer
     * instance): when the annotated-parameter count equals the full argument count, move every annotation down.
     */
    static final class ParamAnnotationFix extends Fix {
        String name, outer; boolean isEnum;
        ParamAnnotationFix(ClassVisitor cv) { super(cv); }
        @Override public void visit(int v, int access, String name, String sig, String sup, String[] itf) {
            this.name = name; if ((access & Opcodes.ACC_ENUM) != 0) isEnum = true;
            super.visit(v, access, name, sig, sup, itf);
        }
        @Override public void visitInnerClass(String n, String outerName, String innerName, int access) {
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_INTERFACE)) == 0 && name.equals(n) && innerName != null) {
                if (outerName != null) outer = outerName;
                else { int i = n.lastIndexOf('$'); if (i >= 0) outer = n.substring(0, i); }
            }
            super.visitInnerClass(n, outerName, innerName, access);
        }
        @Override public MethodVisitor visitMethod(int access, String mname, String desc, String sig, String[] exc) {
            MethodVisitor mv = super.visitMethod(access, mname, desc, sig, exc);
            if (!mname.equals("<init>")) return mv;
            Type[] synth = isEnum ? new Type[]{Type.getObjectType("java/lang/String"), Type.INT_TYPE} : outer != null ? new Type[]{Type.getObjectType(outer)} : null;
            if (synth == null) return mv;
            Type[] args = Type.getArgumentTypes(desc);
            if (args.length < synth.length) return mv;
            for (int i = 0; i < synth.length; i++) if (!args[i].equals(synth[i])) return mv;
            return new Shift(mv, args.length, synth.length);
        }
        final class Shift extends MethodVisitor {
            final int argc, nsynth; final Object[][] held; int count; boolean visible, any;
            Shift(MethodVisitor mv, int argc, int nsynth) { super(Opcodes.ASM9, mv); this.argc = argc; this.nsynth = nsynth; held = new Object[argc][]; }
            @Override public void visitAnnotableParameterCount(int n, boolean vis) { count = n; visible = vis; }
            @Override public AnnotationVisitor visitParameterAnnotation(int p, String desc, boolean vis) {
                any = true; AnnotationNode node = new AnnotationNode(Opcodes.ASM9, desc);
                held[p] = new Object[]{p, desc, vis, node};
                return node;
            }
            @Override public void visitEnd() {
                int off = any && count == argc ? nsynth : 0;
                if (off != 0) changed = true;
                super.visitAnnotableParameterCount(count - off, visible);
                for (Object[] a : held) {
                    if (a == null) continue;
                    int p = (Integer) a[0] - off;
                    if (p >= 0) ((AnnotationNode) a[3]).accept(super.visitParameterAnnotation(p, (String) a[1], (Boolean) a[2]));
                }
                super.visitEnd();
            }
        }
    }

    /** Records whose Record attribute / private field access ProGuard stripped: restore both. */
    static final class RecordFix extends Fix {
        boolean record, hasComponents; LinkedHashMap<String, String[]> comps;
        RecordFix(ClassVisitor cv) { super(cv); }
        @Override public void visit(int v, int access, String name, String sig, String sup, String[] itf) {
            record = "java/lang/Record".equals(sup);
            if (record && (access & Opcodes.ACC_RECORD) == 0) { changed = true; access |= Opcodes.ACC_RECORD; }
            super.visit(v, access, name, sig, sup, itf);
        }
        @Override public RecordComponentVisitor visitRecordComponent(String n, String d, String s) { hasComponents = true; return super.visitRecordComponent(n, d, s); }
        @Override public FieldVisitor visitField(int access, String n, String d, String s, Object val) {
            if (record && (access & (Opcodes.ACC_FINAL | Opcodes.ACC_STATIC)) == Opcodes.ACC_FINAL) {
                int a2 = access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE;
                if (a2 != access) { changed = true; access = a2; }
                if (comps == null) comps = new LinkedHashMap<>();
                comps.put(n + d, new String[]{n, d, s});
            }
            return super.visitField(access, n, d, s, val);
        }
        @Override public void visitEnd() {
            // like ART: the class visitEnd is not forwarded (ClassWriter.visitEnd does nothing)
            if (record && !hasComponents && comps != null) for (String[] c : comps.values()) { visitRecordComponent(c[0], c[1], c[2]); changed = true; }
        }
    }

    /** Local variable names that aren't Java identifiers (ProGuard's snowmen, empty) -> lvt_<slot>_<n>_. */
    static final class LocalNameFix extends Fix {
        LocalNameFix(ClassVisitor cv) { super(cv); }
        @Override public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exc) {
            return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, sig, exc)) {
                final Map<Integer, Integer> n = new HashMap<>();
                @Override public void visitLocalVariable(String nm, String d, String s, Label a, Label b, int idx) {
                    if (!javaIdentifier(nm)) { changed = true; nm = "lvt_" + idx + "_" + n.merge(idx, 1, Integer::sum) + "_"; }
                    super.visitLocalVariable(nm, d, s, a, b, idx);
                }
            };
        }
        static boolean javaIdentifier(String s) {
            if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) return false;
            for (int i = 1; i < s.length(); i++) if (!Character.isJavaIdentifierPart(s.charAt(i))) return false;
            return true;
        }
    }

    /** SourceFile = outermost simple class name + ".java" (added when missing). */
    static final class SourceFileFix extends Fix {
        String cls; boolean had;
        SourceFileFix(ClassVisitor cv) { super(cv); }
        @Override public void visit(int v, int access, String name, String sig, String sup, String[] itf) { super.visit(v, access, name, sig, sup, itf); cls = name; }
        @Override public void visitSource(String src, String dbg) { super.visitSource(want(src), dbg); had = true; }
        @Override public void visitEnd() { if (!had) super.visitSource(want(null), null); super.visitEnd(); }
        String want(String existing) {
            String n = cls.substring(cls.lastIndexOf('/') + 1);
            int d = n.indexOf('$'); if (d >= 0) n = n.substring(0, d);
            n += ".java";
            if (!n.equals(existing)) changed = true;
            return n;
        }
    }
}
