package amber.ul.meta;

import amber.ul.model.Dependency;
import amber.ul.model.LoaderKind;
import amber.ul.model.ModInfo;
import amber.ul.resolve.Resolver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Quilt mods (quilt.mod.json) on every platform (loader matrix item 3). Quilt mods ship in intermediary names like
 * Fabric mods, so they go through the Fabric pipeline (remap, Mixin, Fabric facade). What differs is metadata:
 * <ul>
 * <li>{@code quilt_loader}: satisfied by Amber itself (it is not advertised as a mod id: Fabric mods probe
 *     isModLoaded("quilt_loader") and would switch to Quilt APIs Amber does not provide);</li>
 * <li>QSL module dependencies ({@code quilt_resource_loader}, ...): the Fabric API module that does the same job, or
 *     nothing where the platform does it natively (Forge/NeoForge load every mod's resources themselves);</li>
 * <li>Amber provides a small Quilt API subset in the loader (shims: {@code ModContainer.metadata()/rootPath()},
 *     {@code ModMetadata.id()/name()/...}, QSL's {@code init}/{@code client_init}/{@code server_init} entrypoint
 *     interfaces, {@code @ClientOnly}). A Quilt jar that references any other {@code org/quiltmc/} class or member (the
 *     rest of the Quilt loader API, QSL, including QSL itself) is not loaded: error {@code quilt_api_missing} naming the
 *     first references. Compile-time annotations are not counted.</li>
 * </ul>
 * Test switch: -Damber.ul.noQuiltCompat=true (Quilt metadata as is: quilt_loader and QSL ids stay unresolved).
 */
public final class QuiltCompat {
    private QuiltCompat() {}

    /** QSL module -> the Fabric API module with the same job (vanilla/Fabric base, or Fabric API on a platform). */
    static final Map<String, String> QSL_AS_FABRIC = Map.of(
            "quilt_resource_loader", "fabric-resource-loader-v0",
            "quilt_lifecycle_events", "fabric-lifecycle-events-v1",
            "quilt_command", "fabric-command-api-v2",
            "quilt_item_group", "fabric-item-group-api-v1",
            "quilt_registry", "fabric-registry-sync-v0",
            "quilt_networking", "fabric-networking-api-v1",
            "quilted_fabric_api", "fabric-api");
    /** QSL modules whose job a Forge/NeoForge platform does for every mod. */
    static final Set<String> PLATFORM_NATIVE = Set.of("quilt_resource_loader");
    /** Satisfied by Amber on every platform. */
    static final Set<String> AMBER = Set.of("quilt_loader", "quilt_base");

    public static int aliased, refused;

    /** @param platform vanilla | neoforge | forge */
    public static List<ModInfo> apply(List<ModInfo> found, String platform, List<Resolver.Problem> problems) {
        aliased = 0; refused = 0;
        if (Boolean.getBoolean("amber.ul.noQuiltCompat")) return found;
        List<ModInfo> out = new ArrayList<>();
        Set<String> drop = new HashSet<>();
        for (ModInfo m : found) {
            if (m.kind != LoaderKind.QUILT) continue;
            List<String> refs = quiltRefs(m);
            if (!refs.isEmpty()) {
                refused++; drop.add(m.id);
                problems.add(new Resolver.Problem("quilt_api_missing", "error", m.id, m.jar.getFileName().toString(), "quilt_loader", "", "",
                        "Quilt 模组 " + m.id + " 用到了 Amber 还没提供的 Quilt API (" + refs.size() + " 处), 先不加载: "
                                + String.join(", ", refs.subList(0, Math.min(5, refs.size()))) + (refs.size() > 5 ? " ..." : "")));
            }
        }
        for (ModInfo m : found) {
            if (drop.contains(m.id) || m.parent != null && drop.contains(m.parent)) continue;
            if (m.kind == LoaderKind.QUILT) {
                List<Dependency> deps = new ArrayList<>();
                for (Dependency d : m.deps) {
                    String id = d.modId();
                    if (AMBER.contains(id) || !platform.equals("vanilla") && PLATFORM_NATIVE.contains(id)) { aliased++; continue; }
                    if (QSL_AS_FABRIC.containsKey(id)) { aliased++; deps.add(new Dependency(QSL_AS_FABRIC.get(id), "*", d.required(), d.ordering())); continue; }
                    deps.add(d);
                }
                m.deps.clear(); m.deps.addAll(deps);
            }
            out.add(m);
        }
        return out;
    }

    /** References into org/quiltmc/ that Amber's Quilt subset does not provide (sorted, unique; "owner" or "owner.name desc"). */
    static List<String> quiltRefs(ModInfo m) {
        Set<String> refs = new TreeSet<>();
        byte[] pat = "org/quiltmc/".getBytes(StandardCharsets.US_ASCII);
        try (JarFile jf = new JarFile(m.jar.toFile(), false)) {
            for (JarEntry e : Collections.list(jf.entries())) {
                if (!e.getName().endsWith(".class") || e.getSize() > 4 << 20) continue;
                byte[] b; try (InputStream in = jf.getInputStream(e)) { b = in.readNBytes(4 << 20); }
                if (indexOf(b, pat) < 0) continue;
                String self = e.getName().substring(0, e.getName().length() - 6);
                if (self.startsWith("org/quiltmc/")) { refs.add(self); continue; }   // the jar is (part of) a Quilt API itself: QSL
                for (String r : new Refs(b).missing()) refs.add(r);
            }
        } catch (IOException e) { refs.add("(unreadable: " + e.getMessage() + ")"); }
        return new ArrayList<>(refs);
    }

    /** Collects a class's references into org/quiltmc (types, methods, fields; not annotations) and checks them against the loader's shims. */
    static final class Refs extends org.objectweb.asm.ClassVisitor {
        final Set<String> types = new TreeSet<>(); final Set<String[]> members = new LinkedHashSet<>();
        Refs(byte[] b) { super(org.objectweb.asm.Opcodes.ASM9); new org.objectweb.asm.ClassReader(b).accept(this, org.objectweb.asm.ClassReader.SKIP_FRAMES); }
        void type(String internal) { if (internal != null && internal.startsWith("org/quiltmc/")) types.add(internal); }
        void desc(String d) {
            if (d == null || !d.contains("org/quiltmc/")) return;
            org.objectweb.asm.Type t = org.objectweb.asm.Type.getType(d);
            if (t.getSort() == org.objectweb.asm.Type.METHOD) { desc(t.getReturnType().getDescriptor()); for (var a : t.getArgumentTypes()) desc(a.getDescriptor()); }
            else if (t.getSort() == org.objectweb.asm.Type.ARRAY) desc(t.getElementType().getDescriptor());
            else if (t.getSort() == org.objectweb.asm.Type.OBJECT) type(t.getInternalName());
        }
        @Override public void visit(int v, int a, String n, String sig, String sup, String[] itf) { type(sup); if (itf != null) for (String i : itf) type(i); }
        @Override public org.objectweb.asm.FieldVisitor visitField(int a, String n, String d, String sig, Object val) { desc(d); return null; }
        @Override public org.objectweb.asm.MethodVisitor visitMethod(int a, String n, String d, String sig, String[] ex) {
            desc(d);
            return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) { if (owner.startsWith("org/quiltmc/")) members.add(new String[]{owner, name, desc}); Refs.this.desc(desc); type(owner); }
                @Override public void visitFieldInsn(int op, String owner, String name, String desc) { if (owner.startsWith("org/quiltmc/")) members.add(new String[]{owner, name, desc}); Refs.this.desc(desc); type(owner); }
                @Override public void visitTypeInsn(int op, String t) { if (t.startsWith("[")) Refs.this.desc(t); else type(t); }
                @Override public void visitLdcInsn(Object c) { if (c instanceof org.objectweb.asm.Type t) Refs.this.desc(t.getDescriptor()); }
                @Override public void visitLocalVariable(String n, String d, String sig, org.objectweb.asm.Label s, org.objectweb.asm.Label e, int i) { Refs.this.desc(d); }
            };
        }
        List<String> missing() {
            List<String> out = new ArrayList<>();
            ClassLoader cl = QuiltCompat.class.getClassLoader();
            for (String t : types) if (cls(cl, t) == null) out.add(t);
            for (String[] mm : members) {
                Class<?> c = cls(cl, mm[0]);
                if (c == null) continue;   // already listed
                if (!has(c, mm[1], mm[2])) out.add(mm[0] + "." + mm[1] + " " + mm[2]);
            }
            return out;
        }
        static Class<?> cls(ClassLoader cl, String internal) { try { return Class.forName(internal.replace('/', '.'), false, cl); } catch (ClassNotFoundException | LinkageError e) { return null; } }
        static boolean has(Class<?> c, String name, String desc) {
            if (desc.startsWith("(")) {
                for (Class<?> k = c; k != null; k = k.getSuperclass()) if (hasMethod(k, name, desc)) return true;
                Deque<Class<?>> q = new ArrayDeque<>(List.of(c.getInterfaces()));
                while (!q.isEmpty()) { Class<?> i = q.poll(); if (hasMethod(i, name, desc)) return true; q.addAll(List.of(i.getInterfaces())); }
                return false;
            }
            for (Class<?> k = c; k != null; k = k.getSuperclass()) for (var f : k.getDeclaredFields()) if (f.getName().equals(name) && org.objectweb.asm.Type.getDescriptor(f.getType()).equals(desc)) return true;
            return false;
        }
        static boolean hasMethod(Class<?> k, String name, String desc) {
            if (name.equals("<init>")) { for (var m : k.getDeclaredConstructors()) if (org.objectweb.asm.Type.getConstructorDescriptor(m).equals(desc)) return true; return false; }
            for (var m : k.getDeclaredMethods()) if (m.getName().equals(name) && org.objectweb.asm.Type.getMethodDescriptor(m).equals(desc)) return true;
            return false;
        }
    }

    static int indexOf(byte[] b, byte[] p) {
        outer: for (int i = 0; i + p.length <= b.length; i++) { for (int j = 0; j < p.length; j++) if (b[i + j] != p[j]) continue outer; return i; }
        return -1;
    }
}
