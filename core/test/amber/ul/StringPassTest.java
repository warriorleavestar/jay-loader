package amber.ul;

import amber.ul.map.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;
import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

/**
 * M2 string passes on a synthetic intermediary mod class (the golden corpus has no such sites): reflection strings,
 * ObjectMethods component lists, enumSwitch labels, EnumDesc labels, and the cases that must be KEPT.
 * Real names come from the 1.21.1 graph (picked at run time), so nothing here hard-codes Mojang names.
 *   java -cp "build/loader;build/test;<loader-cp>" amber.ul.StringPassTest mc/mappings-cache/1.21.1 mc/versions/1.21.1/1.21.1.jar mc/libraries <out dir>
 */
public final class StringPassTest {
    static int fails;
    static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) fails++; }

    record Pick(String clsA, String clsB, String memA, String memB, String desc) {}

    public static void main(String[] a) throws Exception {
        Path mapDir = Path.of(a[0]), obfJar = Path.of(a[1]), libsDir = Path.of(a[2]), out = Path.of(a[3]);
        List<Path> libs; try (Stream<Path> s = Files.walk(libsDir)) { libs = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        MappingTree t = new MappingTree();
        Readers.proguard(t, mapDir.resolve("mojang-client.txt"), "mojmap");
        Path inter; try (Stream<Path> s = Files.list(mapDir)) { inter = s.filter(p -> p.getFileName().toString().startsWith("intermediary-")).findFirst().orElseThrow(); }
        Readers.tinyJar(t, inter, Map.of("official", "obf"));
        JarIndex obf = JarIndex.of(obfJar, libs);
        new Completer(t, obf).run();
        int A = t.nsIndex("intermediary"), B = t.nsIndex("mojmap");
        ModNames names = new ModNames(t, A, B, obf, libs, List.of(), true);

        Pick en = pick(t, obf, A, B, "java/lang/Enum", true, true);       // enum constant
        Pick rec = pick(t, obf, A, B, "java/lang/Record", false, true);   // record component field
        Pick fld = pick(t, obf, A, B, null, false, true);                  // any game field
        Pick mth = pick(t, obf, A, B, null, false, false);                 // a game method, unique name in its class
        System.out.println("picked (A names): " + en.clsA + "." + en.memA + ", " + rec.clsA + "." + rec.memA + ", " + fld.clsA + "." + fld.memA + ", " + mth.clsA + "." + mth.memA);

        String H = "java/lang/invoke/MethodHandles$Lookup";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "test/Probe", null, "java/lang/Object", null);
        MethodVisitor mv;
        // 1. Class.forName(dotted A name) -> mapped
        mv = m(cw, "forName", "()V");
        mv.visitLdcInsn(fld.clsA.replace('/', '.')); mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false); end(mv);
        // 2. getDeclaredField on LDC type -> mapped
        mv = m(cw, "field", "()V");
        mv.visitLdcInsn(Type.getObjectType(fld.clsA)); mv.visitLdcInsn(fld.memA); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false); end(mv);
        // 3. getDeclaredMethod(name, Class[0]) on LDC type -> mapped
        mv = m(cw, "method", "()V");
        mv.visitLdcInsn(Type.getObjectType(mth.clsA)); mv.visitLdcInsn(mth.memA); mv.visitInsn(Opcodes.ICONST_0); mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false); end(mv);
        // 4. owner through Class.forName(LDC) -> field mapped
        mv = m(cw, "viaForName", "()V");
        mv.visitLdcInsn(rec.clsA.replace('/', '.')); mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        mv.visitLdcInsn(rec.memA); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false); end(mv);
        // 5. KEPT: a mapped-looking string that is not a reflection argument (e.g. MappingResolver input)
        mv = m(cw, "resolverInput", "()V");
        String keep = "net.minecraft." + en.clsA.substring(en.clsA.lastIndexOf('/') + 1);
        mv.visitLdcInsn(keep); mv.visitInsn(Opcodes.POP); end(mv);
        // 6. KEPT: name is not a constant (comes from a parameter)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dynamic", "(Ljava/lang/String;)V", null, null); mv.visitCode();
        mv.visitLdcInsn(Type.getObjectType(fld.clsA)); mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false); end(mv);
        // 7. KEPT: plain (non-mapped) member name on a game class with no such member: a mod/JDK member
        mv = m(cw, "plain", "()V");
        mv.visitLdcInsn(Type.getObjectType(fld.clsA)); mv.visitLdcInsn("notAGameField"); mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false); end(mv);
        // 8. enumSwitch labels
        Handle enumSwitch = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/SwitchBootstraps", "enumSwitch", "(L" + H + ";Ljava/lang/String;Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;", false);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "enumSw", "(L" + en.clsA + ";)I", null, null); mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInvokeDynamicInsn("enumSwitch", "(L" + en.clsA + ";I)I", enumSwitch, en.memA); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        // 9. typeSwitch with an EnumDesc label and a String label (the String label must stay)
        Handle typeSwitch = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/SwitchBootstraps", "typeSwitch", enumSwitch.getDesc(), false);
        Handle invoke = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/ConstantBootstraps", "invoke", "(L" + H + ";Ljava/lang/String;Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;[Ljava/lang/Object;)Ljava/lang/Object;", false);
        Handle classDescOf = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/constant/ClassDesc", "of", "(Ljava/lang/String;)Ljava/lang/constant/ClassDesc;", true);
        Handle enumDescOf = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/Enum$EnumDesc", "of", "(Ljava/lang/constant/ClassDesc;Ljava/lang/String;)Ljava/lang/Enum$EnumDesc;", false);
        ConstantDynamic cd = new ConstantDynamic("invoke", "Ljava/lang/Enum$EnumDesc;", invoke, enumDescOf,
                new ConstantDynamic("invoke", "Ljava/lang/constant/ClassDesc;", invoke, classDescOf, en.clsA.replace('/', '.')), en.memA);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "typeSw", "(Ljava/lang/Object;)I", null, null); mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInvokeDynamicInsn("typeSwitch", "(Ljava/lang/Object;I)I", typeSwitch, cd, en.memA); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        // 10. ObjectMethods over a game record's component
        Handle om = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/runtime/ObjectMethods", "bootstrap", "(L" + H + ";Ljava/lang/String;Ljava/lang/invoke/TypeDescriptor;Ljava/lang/Class;Ljava/lang/String;[Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;", false);
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "recToString", "(L" + rec.clsA + ";)Ljava/lang/String;", null, null); mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInvokeDynamicInsn("toString", "(L" + rec.clsA + ";)Ljava/lang/String;", om, Type.getObjectType(rec.clsA), rec.memA, new Handle(Opcodes.H_GETFIELD, rec.clsA, rec.memA, rec.desc, false));
        mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        cw.visitEnd();

        Files.createDirectories(out);
        Path jar = out.resolve("stringpass.jar");
        StringPass sp = new StringPass(names);
        new JarRemapper(names, JarRemapper.Options.mod(), 1).strings(sp).run(List.of(new JarRemapper.Entry("test/Probe.class", 0, cw.toByteArray())), jar);
        byte[] res; try (ZipFile z = new ZipFile(jar.toFile())) { res = z.getInputStream(z.getEntry("test/Probe.class")).readAllBytes(); }
        StringWriter sw = new StringWriter();
        new ClassReader(res).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(sw)), 0);
        String txt = sw.toString();
        Files.writeString(out.resolve("stringpass.txt"), txt);
        Map<String, String> methods = new LinkedHashMap<>();
        String cur = null;
        for (String l : txt.split("\n")) {
            if (l.startsWith("  public static ")) { cur = l.substring(16, l.indexOf('(')); cur = cur.substring(cur.lastIndexOf(' ') + 1); methods.put(cur, ""); }
            else if (cur != null) methods.merge(cur, l + "\n", String::concat);
        }

        check(methods.get("forName").contains("\"" + fld.clsB.replace('/', '.') + "\""), "forName: class string mapped");
        check(methods.get("field").contains("\"" + fld.memB + "\""), "getDeclaredField: field name mapped");
        check(methods.get("method").contains("\"" + mth.memB + "\""), "getDeclaredMethod: method name mapped");
        check(methods.get("viaForName").contains("\"" + rec.memB + "\"") && methods.get("viaForName").contains("\"" + rec.clsB.replace('/', '.') + "\""), "owner from Class.forName: both mapped");
        check(methods.get("resolverInput").contains("\"" + keep + "\""), "non-reflection mapped-looking string kept");
        check(methods.get("plain").contains("\"notAGameField\""), "plain name kept");
        check(methods.get("enumSw").contains(en.memB) && !methods.get("enumSw").contains(en.memA), "enumSwitch label mapped");
        String ts = methods.get("typeSw");
        check(ts.contains(en.clsB.replace('/', '.')) && ts.contains("\"" + en.memB + "\""), "EnumDesc class + constant mapped");
        check(ts.contains("\"" + en.memA + "\""), "typeSwitch String label kept");
        check(methods.get("recToString").contains("\"" + rec.memB + "\""), "ObjectMethods component list mapped");
        System.out.println("stats " + new TreeMap<>(sp.stats));
        System.out.println("log   " + sp.log.keySet());
        check(sp.stats.getOrDefault("kept: mapped-looking string", 0) >= 1, "kept string logged");
        check(sp.stats.getOrDefault("site: name not a constant", 0) == 1, "dynamic name counted");
        System.out.println(fails == 0 ? "StringPassTest: all passed" : "StringPassTest: " + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static MethodVisitor m(ClassWriter cw, String name, String desc) { MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null); mv.visitCode(); return mv; }
    static void end(MethodVisitor mv) { mv.visitInsn(Opcodes.POP); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); }

    /**
     * A game class (optionally with the given super) with a member whose A and B names differ, and A/B class names differ.
     * enumConst: a static field of the class's own type. Methods: the A name must be the only one of that name in the class.
     */
    static Pick pick(MappingTree t, JarIndex obf, int A, int B, String sup, boolean enumConst, boolean field) {
        for (JarIndex.Info i : obf.classes.values()) {
            if (!i.inGame || sup != null && !sup.equals(i.superName)) continue;
            MappingTree.ClassRow c = t.classes.get(i.name);
            if (c == null || c.get(A) == null || c.get(B) == null || c.get(A).equals(c.get(B)) || i.name.contains("$")) continue;
            for (var e : (field ? c.fields : c.methods).entrySet()) {
                MappingTree.MemberRow r = e.getValue();
                if (r.get(A) == null || r.get(B) == null || r.get(A).equals(r.get(B)) || r.desc == null || r.obf.startsWith("<")) continue;
                if (field) {
                    Integer acc = i.fields.get(e.getKey());
                    if (acc == null) continue;
                    boolean st = (acc & Opcodes.ACC_STATIC) != 0;
                    if (enumConst != (st && r.desc.equals("L" + i.name + ";"))) continue;
                    if (sup != null && sup.equals("java/lang/Record") && st) continue;
                } else {
                    long same = c.methods.values().stream().filter(o -> r.get(A).equals(o.get(A))).count();
                    if (same != 1) continue;
                }
                String desc = Descriptors.mapClasses(r.desc, x -> { MappingTree.ClassRow k = t.classes.get(x); return k != null && k.get(A) != null ? k.get(A) : x; });
                return new Pick(c.get(A), c.get(B), r.get(A), r.get(B), desc);
            }
        }
        throw new IllegalStateException("nothing to pick for " + sup);
    }
}
