package amber.ul.bake;

import amber.ul.map.JarRemapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Forge 1.21.1 (Forge 52) mods on the NeoForge 21.1 platform. Both run Mojmap names, so the game side needs nothing; the
 * difference is the loader API. After the 2023 split NeoForge moved Forge's packages (net.minecraftforge.* ->
 * net.neoforged.*) and changed some of the API. The jar is converted once, at bake time:
 * <ul>
 * <li>class references: a Forge class Amber's forgecompat jar provides ({@code amber/forgecompat/<path below
 *     net/minecraftforge/>}) goes there; otherwise the package rules below move it to NeoForge's equivalent (1:1 classes
 *     such as events, Mod, SubscribeEvent, ForgeConfigSpec -> ModConfigSpec);</li>
 * <li>{@code @Mod.EventBusSubscriber(bus = FORGE)}: NeoForge calls that bus GAME;</li>
 * <li>{@code META-INF/mods.toml} becomes {@code META-INF/neoforge.mods.toml}: dependency forge -> neoforge,
 *     mandatory -> type, loaderVersion for FML 4, MixinConfigs from the manifest as [[mixins]].</li>
 * </ul>
 * {@link #verify} then lists every reference into NeoForge, the game or forgecompat that does not resolve, so a mod that
 * needs a Forge API nobody bridged yet is refused with a message instead of crashing in game.
 */
public final class ForgeCompat {
    private ForgeCompat() {}
    public static final String PIPELINE = "forgecompat-3";
    public static final String SHIM = "amber/forgecompat/";
    static final String FORGE = "net/minecraftforge/";

    /** Package/class rules, most specific first: Forge internal name prefix -> NeoForge prefix. */
    static final String[][] RULES = {
        {"net/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus", "net/neoforged/fml/common/EventBusSubscriber$Bus"},
        {"net/minecraftforge/fml/common/Mod$EventBusSubscriber", "net/neoforged/fml/common/EventBusSubscriber"},
        {"net/minecraftforge/common/MinecraftForge", "net/neoforged/neoforge/common/NeoForge"},
        {"net/minecraftforge/common/ForgeConfigSpec", "net/neoforged/neoforge/common/ModConfigSpec"},
        {"net/minecraftforge/eventbus/api/", "net/neoforged/bus/api/"},
        {"net/minecraftforge/api/distmarker/", "net/neoforged/api/distmarker/"},
        {"net/minecraftforge/fml/", "net/neoforged/fml/"},
        {"net/minecraftforge/client/event/", "net/neoforged/neoforge/client/event/"},
        {"net/minecraftforge/client/", "net/neoforged/neoforge/client/"},
        {"net/minecraftforge/event/", "net/neoforged/neoforge/event/"},
        {"net/minecraftforge/common/", "net/neoforged/neoforge/common/"},
        {"net/minecraftforge/fluids/", "net/neoforged/neoforge/fluids/"},
        {"net/minecraftforge/items/", "net/neoforged/neoforge/items/"},
        {"net/minecraftforge/server/", "net/neoforged/neoforge/server/"},
        {"net/minecraftforge/registries/", "net/neoforged/neoforge/registries/"},
        {"net/minecraftforge/network/", "net/neoforged/neoforge/network/"},
    };

    /** Member renames, keyed by the Forge owner + name + descriptor (Forge names): API members NeoForge renamed. */
    static final Map<String, String> MEMBERS = Map.of(
        "net/minecraftforge/event/entity/player/BonemealEvent.getBlock()Lnet/minecraft/world/level/block/state/BlockState;", "getState",
        "net/minecraftforge/event/entity/player/BonemealEvent.getEntity()Lnet/minecraft/world/entity/player/Player;", "getPlayer",
        "net/minecraftforge/event/entity/player/BonemealEvent.getStack()Lnet/minecraft/world/item/ItemStack;", "getStack");

    /** True when a jar's metadata is a Forge 52 mod (mods.toml, no neoforge.mods.toml). */
    public static boolean isForgeJar(Path jar) throws IOException {
        try (JarFile j = new JarFile(jar.toFile())) { return j.getJarEntry("META-INF/mods.toml") != null && j.getJarEntry("META-INF/neoforge.mods.toml") == null; }
    }

    /** Names of the classes the forgecompat jar provides (internal names). */
    public static Set<String> shimIndex(Path shimJar) throws IOException {
        Set<String> s = new HashSet<>();
        if (shimJar == null || !Files.exists(shimJar)) return s;
        try (JarFile j = new JarFile(shimJar.toFile())) {
            for (var e = j.entries(); e.hasMoreElements(); ) { String n = e.nextElement().getName(); if (n.startsWith(SHIM) && n.endsWith(".class")) s.add(n.substring(0, n.length() - 6)); }
        }
        return s;
    }

    public static String map(String name, Set<String> shims) {
        if (name == null || !name.startsWith(FORGE)) return name;
        String shim = SHIM + name.substring(FORGE.length());
        if (shims.contains(shim)) return shim;
        for (String[] r : RULES) if (name.startsWith(r[0])) return r[1] + name.substring(r[0].length());
        return name;
    }

    /** Converts one Forge 52 mod jar. Returns the number of classes whose references changed. */
    public static int convert(Path in, Path out, Set<String> shims) throws IOException {
        Remapper rm = new Remapper() {
            @Override public String map(String internalName) { return ForgeCompat.map(internalName, shims); }
            @Override public String mapMethodName(String owner, String name, String desc) { return MEMBERS.getOrDefault(owner + "." + name + desc, name); }
        };
        String mixinCfg = null;
        try (JarFile j = new JarFile(in.toFile())) { Manifest mf = j.getManifest(); if (mf != null) mixinCfg = mf.getMainAttributes().getValue("MixinConfigs"); }
        int changed = 0;
        Files.createDirectories(out.getParent());
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(tmp))) {
            for (JarRemapper.Entry e : JarRemapper.read(in)) {
                String n = e.name(); byte[] b = e.data();
                if (n.equals("META-INF/mods.toml")) { n = "META-INF/neoforge.mods.toml"; b = toml(new String(b, StandardCharsets.UTF_8), mixinCfg).getBytes(StandardCharsets.UTF_8); }
                else if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                    ClassReader cr = new ClassReader(b);
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(new BusEnum(new ModCtorBridge(cw, isMod(cr))), rm), 0);
                    byte[] nb = cw.toByteArray();
                    if (!Arrays.equals(nb, b)) changed++;
                    b = nb;
                } else if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))) continue;   // signatures no longer match
                ZipEntry ze = new ZipEntry(n); ze.setTime(e.time()); zo.putNextEntry(ze); zo.write(b); zo.closeEntry();
            }
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return changed;
    }

    /** {@code EventBusSubscriber.Bus.FORGE} is called GAME on NeoForge (runs after the remap, so it sees NeoForge's name). */
    static final class BusEnum extends ClassVisitor {
        BusEnum(ClassVisitor cv) { super(Opcodes.ASM9, cv); }
        static AnnotationVisitor wrap(AnnotationVisitor av) {
            return av == null ? null : new AnnotationVisitor(Opcodes.ASM9, av) {
                @Override public void visitEnum(String name, String desc, String value) {
                    super.visitEnum(name, desc, desc.equals("Lnet/neoforged/fml/common/EventBusSubscriber$Bus;") && value.equals("FORGE") ? "GAME" : value);
                }
                @Override public AnnotationVisitor visitAnnotation(String name, String desc) { return wrap(super.visitAnnotation(name, desc)); }
                @Override public AnnotationVisitor visitArray(String name) { return wrap(super.visitArray(name)); }
            };
        }
        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) { return wrap(super.visitAnnotation(desc, visible)); }
    }

    static final String FMLJ_CTX = SHIM + "fml/javafmlmod/FMLJavaModLoadingContext";

    /**
     * Forge 52 @Mod classes may take an FMLJavaModLoadingContext in their constructor; NeoForge only injects IEventBus,
     * ModContainer, FMLModContainer and Dist. Such a constructor becomes private and a public one taking NeoForge's
     * ModContainer calls it with {@code FMLJavaModLoadingContext.of(container)} (runs after the remap: NeoForge names).
     */
    static final class ModCtorBridge extends ClassVisitor {
        String name; final boolean mod; final List<String> ctx = new ArrayList<>();
        ModCtorBridge(ClassVisitor cv, boolean mod) { super(Opcodes.ASM9, cv); this.mod = mod; }
        // Forge constructs @Mod classes reflectively with access checks off; NeoForge's FMLModContainer needs a public
        // class and constructor (Simple Harvesting's @Mod class is package-private)
        @Override public void visit(int v, int a, String n, String s, String sup, String[] itf) {
            name = n; if (mod) a = (a & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            super.visit(v, a, n, s, sup, itf);
        }
        @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) {
            if (mod && n.equals("<init>")) {
                if (d.equals("(L" + FMLJ_CTX + ";)V")) { ctx.add(d); a = (a & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE; }
                else if ((a & Opcodes.ACC_PRIVATE) == 0) a = (a & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
            }
            return super.visitMethod(a, n, d, s, x);
        }
        @Override public void visitEnd() {
            if (!ctx.isEmpty()) {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Lnet/neoforged/fml/ModContainer;)V", null, null);
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 1);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, FMLJ_CTX, "of", "(Lnet/neoforged/fml/ModContainer;)L" + FMLJ_CTX + ";", false);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, name, "<init>", "(L" + FMLJ_CTX + ";)V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(2, 2);
                mv.visitEnd();
            }
            super.visitEnd();
        }
    }

    /** Has a (Forge, pre-remap) @Mod annotation. */
    static boolean isMod(ClassReader cr) {
        boolean[] m = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) { if (d.equals("Lnet/minecraftforge/fml/common/Mod;")) m[0] = true; return null; }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return m[0];
    }

    static final Pattern DEP_HEADER = Pattern.compile("^\\s*\\[\\[\\s*dependencies\\..*\\]\\]\\s*$");

    /** Forge 52 mods.toml -> NeoForge 21.1 neoforge.mods.toml (line based; comments and layout kept). */
    public static String toml(String s, String mixinCfg) {
        String[] lines = s.split("\n", -1);
        Pattern kv = Pattern.compile("^(\\s*)([A-Za-z]+)(\\s*=\\s*)(.*?)(\r?)$");
        boolean inDep = false;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("[")) inDep = t.startsWith("[[dependencies");   // headers may carry a comment: [[dependencies."mod"]] #optional
            Matcher m = kv.matcher(line);
            if (m.matches()) {
                String k = m.group(2), v = m.group(4);
                if (!inDep && k.equals("loaderVersion")) line = m.group(1) + k + m.group(3) + "\"[1,)\"" + m.group(5);
                if (inDep && k.equals("modId") && v.matches("[\"']forge[\"'].*")) line = m.group(1) + k + m.group(3) + "\"neoforge\"" + v.substring(7) + m.group(5);
                if (inDep && k.equals("mandatory")) line = m.group(1) + "type" + m.group(3) + (v.startsWith("true") ? "\"required\"" : "\"optional\"") + v.replaceFirst("^(true|false)", "") + m.group(5);
            }
            out.add(line);
        }
        String r = String.join("\n", out);
        // the versionRange of the (now) neoforge dependency is Forge's (e.g. [52,)): any NeoForge 21.1 satisfies it
        r = Pattern.compile("(modId\\s*=\\s*\"neoforge\"[^\\[]*?versionRange\\s*=\\s*)([\"'])[^\"']*\\2").matcher(r).replaceAll("$1\"[21,)\"");
        if (mixinCfg != null && !r.contains("[[mixins]]")) {
            StringBuilder sb = new StringBuilder(r);
            if (!r.endsWith("\n")) sb.append('\n');
            for (String c : mixinCfg.split(",")) if (!c.isBlank()) sb.append("\n[[mixins]]\nconfig=\"").append(c.trim()).append("\"\n");
            r = sb.toString();
        }
        return r;
    }

    // ---- verification: every reference into NeoForge / the game / forgecompat must resolve ----

    /** Minimal class index: super, interfaces, members (name+desc). */
    public static final class Index {
        record C(String sup, String[] itf, Set<String> members) {}
        final Map<String, C> classes = new HashMap<>();
        public Index add(Path jar) throws IOException {
            try (JarFile j = new JarFile(jar.toFile())) {
                for (var en = j.entries(); en.hasMoreElements(); ) {
                    JarEntry e = en.nextElement(); String n = e.getName();
                    if (!n.endsWith(".class") || n.startsWith("META-INF/")) continue;
                    try (InputStream in = j.getInputStream(e)) { add(in.readAllBytes()); }
                }
            }
            return this;
        }
        void add(byte[] b) {
            ClassReader cr = new ClassReader(b);
            Set<String> m = new HashSet<>();
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) { m.add(n + ":" + d); return null; }
                @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) { m.add(n + d); return null; }
            }, ClassReader.SKIP_CODE);
            classes.putIfAbsent(cr.getClassName(), new C(cr.getSuperName(), cr.getInterfaces(), m));
        }
        boolean has(String cls) { return classes.containsKey(cls) || cls.startsWith("java/") || cls.startsWith("javax/") || cls.startsWith("jdk/") || cls.startsWith("["); }
        /** Walks supers and interfaces; JDK types count as resolved (they are never what changed). */
        boolean member(String owner, String key) {
            Deque<String> todo = new ArrayDeque<>(List.of(owner)); Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.pop();
                if (!seen.add(c)) continue;
                if (c.startsWith("[")) return true;
                if (c.startsWith("java/")) { if (jdk(c, key)) return true; continue; }
                C k = classes.get(c);
                if (k == null) return false;
                if (k.members.contains(key)) return true;
                if (k.sup != null) todo.add(k.sup);
                todo.addAll(Arrays.asList(k.itf));
            }
            return false;
        }
        /** A member inherited from a JDK class (java.lang.Object, Record, Enum ...), looked up on the running JDK. */
        static boolean jdk(String c, String key) {
            try {
                Class<?> k = Class.forName(c.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
                for (Class<?> x = k; x != null; x = x.getSuperclass()) {
                    for (var m : x.getDeclaredMethods()) if (key.equals(m.getName() + Type.getMethodDescriptor(m))) return true;
                    for (var f : x.getDeclaredFields()) if (key.equals(f.getName() + ":" + Type.getDescriptor(f.getType()))) return true;
                    for (var i : x.getInterfaces()) for (var m : i.getMethods()) if (key.equals(m.getName() + Type.getMethodDescriptor(m))) return true;
                }
                if (key.startsWith("<init>")) for (var ct : k.getDeclaredConstructors()) if (key.equals("<init>" + Type.getConstructorDescriptor(ct))) return true;
            } catch (Throwable ignored) {}
            return false;
        }
    }

    static boolean checked(String owner) { return owner.startsWith("net/neoforged/") || owner.startsWith(SHIM) || owner.startsWith("net/minecraftforge/"); }

    /** Unresolved references of a converted jar ("Owner.name desc" / "class Owner"), sorted. */
    public static List<String> verify(Path converted, Index platform) throws IOException {
        Index own = new Index().add(converted);
        TreeSet<String> bad = new TreeSet<>();
        try (JarFile j = new JarFile(converted.toFile())) {
            for (var en = j.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getName().startsWith("META-INF/")) continue;
                byte[] b; try (InputStream in = j.getInputStream(e)) { b = in.readAllBytes(); }
                new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
                    void cls(String c) { if (c != null && checked(c) && !platform.has(c) && !own.has(c)) bad.add("class " + c); }
                    void type(String d) { if (d == null) return; Matcher m = Pattern.compile("L([^;<]+)[;<]").matcher(d); while (m.find()) cls(m.group(1)); }
                    @Override public void visit(int v, int a, String n, String s, String sup, String[] itf) { cls(sup); if (itf != null) for (String i : itf) cls(i); }
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) { type(d); return null; }
                    @Override public FieldVisitor visitField(int a, String n, String d, String s, Object v) { type(d); return null; }
                    @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] x) {
                        type(d);
                        return new MethodVisitor(Opcodes.ASM9) {
                            void mem(String owner, String key) {
                                if (!checked(owner)) return;
                                if (!platform.has(owner) && !own.has(owner)) { bad.add("class " + owner); return; }
                                if (!platform.member(owner, key) && !own.member(owner, key)) bad.add(owner + "." + key);
                            }
                            @Override public void visitFieldInsn(int op, String o, String n2, String d2) { mem(o, n2 + ":" + d2); }
                            @Override public void visitMethodInsn(int op, String o, String n2, String d2, boolean itf) { mem(o, n2 + d2); }
                            @Override public void visitTypeInsn(int op, String t) { if (!t.startsWith("[")) cls(t); else type(t); }
                            @Override public void visitLdcInsn(Object c) { if (c instanceof Type t && t.getSort() == Type.OBJECT) cls(t.getInternalName()); }
                            @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) { type(d); return null; }
                        };
                    }
                }, ClassReader.SKIP_FRAMES);
            }
        }
        return new ArrayList<>(bad);
    }

    /** CLI: ForgeCompat <shim.jar|-> <outdir> <platform jar;...> <mod.jar>... : convert and print what does not resolve. */
    public static void main(String[] a) throws Exception {
        Set<String> shims = shimIndex(a[0].equals("-") ? null : Path.of(a[0]));
        Index platform = new Index();
        for (String p : a[2].split(";")) if (!p.isBlank()) platform.add(Path.of(p));
        if (!a[0].equals("-")) platform.add(Path.of(a[0]));
        for (int i = 3; i < a.length; i++) {
            Path in = Path.of(a[i]), out = Path.of(a[1]).resolve(in.getFileName().toString().replace(' ', '_'));
            int n = convert(in, out, shims);
            List<String> bad = verify(out, platform);
            System.out.println("== " + in.getFileName() + ": " + n + " classes changed, " + bad.size() + " unresolved");
            for (String s : bad) System.out.println("   " + s);
        }
    }
}
