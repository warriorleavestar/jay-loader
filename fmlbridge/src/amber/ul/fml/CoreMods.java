package amber.ul.fml;

import cpw.mods.modlauncher.api.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import net.neoforged.neoforgespi.coremod.ICoreMod;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Applies Java coremods (ICoreMod services, e.g. NeoForge's own neoforge-coremods: finalizeSpawn MethodRedirector,
 * ReplaceFieldWithGetterAccess, ReplaceFieldComparisonWithInstanceOf) to the game jar AT BAKE TIME. Their targets are
 * fixed vanilla classes, so no runtime transformer is needed. Order per class, as modlauncher's ClassTransformer:
 * PRE_CLASS, then fields, then methods, then CLASS; each transformer is asked castVote first. Output is re-framed with
 * COMPUTE_FRAMES using a flat class-path lookup (the game jar itself + the JDK).
 * Runs in an isolated class loader (coremods jar + modlauncher API + FML SPI + this bridge), called by Instance.
 */
public final class CoreMods {
    private CoreMods() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String apply(Path in, Path out, ClassLoader serviceLoader) throws Exception {
        List<ITransformer<?>> all = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (ICoreMod cm : ServiceLoader.load(ICoreMod.class, serviceLoader)) { names.add(cm.getClass().getName()); for (ITransformer<?> t : cm.getTransformers()) all.add(t); }
        Map<String, List<ITransformer<?>>> byClass = new HashMap<>();
        for (ITransformer<?> t : all) for (ITransformer.Target<?> tg : t.targets()) byClass.computeIfAbsent(tg.className().replace('.', '/'), k -> new ArrayList<>()).add(t);
        int changed = 0;
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jf = new JarFile(in.toFile())) {
            for (JarEntry e : Collections.list(jf.entries())) if (e.getName().endsWith(".class")) classes.put(e.getName().substring(0, e.getName().length() - 6), jf.getInputStream(e).readAllBytes());
            Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(tmp))) {
                for (JarEntry e : Collections.list(jf.entries())) {
                    if (e.isDirectory()) continue;
                    byte[] b = jf.getInputStream(e).readAllBytes();
                    String n = e.getName();
                    String internal = n.endsWith(".class") ? n.substring(0, n.length() - 6) : null;
                    List<ITransformer<?>> ts = internal == null ? null : byClass.get(internal);
                    if (ts != null) { byte[] nb = transform(internal, b, ts, classes); if (nb != b) { b = nb; changed++; } }
                    o.putNextEntry(new JarEntry(n)); o.write(b); o.closeEntry();
                }
            }
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        return String.format("coremods %s: %d transformers, %d target classes, %d classes changed", names, all.size(), byClass.size(), changed);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static byte[] transform(String internal, byte[] b, List<ITransformer<?>> ts, Map<String, byte[]> classes) {
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        ITransformerVotingContext ctx = context(internal.replace('/', '.'));
        boolean touched = false;
        for (TargetType<?> phase : List.of(TargetType.PRE_CLASS, TargetType.FIELD, TargetType.METHOD, TargetType.CLASS)) {
            for (ITransformer t : ts) {
                if (t.getTargetType() != phase) continue;
                for (Object o : t.targets()) {
                    ITransformer.Target tg = (ITransformer.Target) o;
                    if (!tg.className().replace('.', '/').equals(internal) || tg.targetType() != phase) continue;
                    if (t.castVote(ctx) != TransformerVoteResult.YES) continue;
                    if (phase == TargetType.CLASS || phase == TargetType.PRE_CLASS) { cn = (ClassNode) t.transform(cn, ctx); touched = true; }
                    else if (phase == TargetType.METHOD) {
                        for (ListIterator<MethodNode> it = cn.methods.listIterator(); it.hasNext(); ) {
                            MethodNode mn = it.next();
                            if (mn.name.equals(tg.elementName()) && mn.desc.equals(tg.elementDescriptor())) { it.set((MethodNode) t.transform(mn, ctx)); touched = true; }
                        }
                    } else {
                        for (ListIterator<FieldNode> it = cn.fields.listIterator(); it.hasNext(); ) {
                            FieldNode fn = it.next();
                            if (fn.name.equals(tg.elementName())) { it.set((FieldNode) t.transform(fn, ctx)); touched = true; }
                        }
                    }
                }
            }
        }
        if (!touched) return b;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String c) { return commonSuper(a, c, classes); }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    static ITransformerVotingContext context(String className) {
        return (ITransformerVotingContext) java.lang.reflect.Proxy.newProxyInstance(CoreMods.class.getClassLoader(), new Class<?>[]{ITransformerVotingContext.class}, (p, m, a) -> switch (m.getName()) {
            case "getClassName" -> className;
            case "doesClassExist" -> true;
            case "getInitialClassSha256" -> new byte[0];
            case "getAuditActivities" -> List.of();
            case "getReason" -> "classloading";
            case "applyFieldPredicate", "applyMethodPredicate", "applyClassPredicate", "applyInstructionPredicate" -> true;
            case "hashCode" -> 0; case "equals" -> p == a[0]; case "toString" -> "AmberVotingContext[" + className + "]";
            default -> null;
        });
    }

    /** Flat class-path supertype lookup over the game jar + JDK (bootstrap resources). */
    static String commonSuper(String a, String b, Map<String, byte[]> classes) {
        List<String> chainA = chain(a, classes);
        if (isInterface(b, classes) || isInterface(a, classes)) return "java/lang/Object";
        for (String s : chain(b, classes)) if (chainA.contains(s)) return s;
        return "java/lang/Object";
    }
    static List<String> chain(String n, Map<String, byte[]> classes) {
        List<String> l = new ArrayList<>();
        for (String c = n; c != null; c = superOf(c, classes)) l.add(c);
        return l;
    }
    static String superOf(String n, Map<String, byte[]> classes) {
        byte[] b = bytes(n, classes);
        return b == null ? (n.equals("java/lang/Object") ? null : "java/lang/Object") : new ClassReader(b).getSuperName();
    }
    static boolean isInterface(String n, Map<String, byte[]> classes) {
        byte[] b = bytes(n, classes);
        return b != null && (new ClassReader(b).getAccess() & Opcodes.ACC_INTERFACE) != 0;
    }
    static byte[] bytes(String n, Map<String, byte[]> classes) {
        byte[] b = classes.get(n);
        if (b != null) return b;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(n + ".class")) { return in == null ? null : in.readAllBytes(); } catch (IOException e) { return null; }
    }
}
