package amber.ul.fml;

import amber.ul.runtime.ClassTransformer;
import java.util.*;
import org.objectweb.asm.*;

/**
 * FML 4 looks mod classes and services up through the modlauncher GAME module layer. On Amber's single class loader there
 * is no such layer, so the few call sites are redirected (bytecode, FML classes only) to the universal loader:
 *   ModuleLayer.findModule(name)          -> Optional.of(<universal loader>.getUnnamedModule())
 *   ServiceLoader.load(ModuleLayer, svc)  -> ServiceLoader.load(svc, <universal loader>)
 * Class.forName(Module, name) and Module.getClassLoader() then work unchanged for an unnamed module.
 */
public final class Jpms {
    public static volatile ClassLoader loader;
    public static int redirected;

    public static Optional<Module> findModule(ModuleLayer layer, String name) { return Optional.of(loader.getUnnamedModule()); }
    public static <S> ServiceLoader<S> load(ModuleLayer layer, Class<S> svc) { return ServiceLoader.load(svc, loader); }

    /** JarVersionLookupHandler.getVersion: modlauncher gives each jar an automatic module whose version comes from the file
     *  name (loader-4.0.45.jar -> 4.0.45). Same rule here, from the class's code source. */
    public static Optional<String> version(Class<?> c) {
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            if (cs == null) return Optional.empty();
            String n = java.nio.file.Path.of(cs.getLocation().toURI()).getFileName().toString().replaceFirst("[.]jar$", "");
            var m = java.util.regex.Pattern.compile("-([0-9]+([.]|$).*)").matcher(n);
            return m.find() ? Optional.of(m.group(1)) : Optional.ofNullable(c.getPackage() == null ? null : c.getPackage().getImplementationVersion());
        } catch (Exception e) { return Optional.empty(); }
    }

    /**
     * FML 47 JarVersionLookupHandler.getImplementationVersion(Class): the package's Implementation-Version, else - for
     * Forge's own jars (javafmllanguage-1.20.1-47.4.28.jar: no per-package manifest section) - the Forge major version,
     * which is what FML itself falls back to for its language providers (forgeVersion.split(".")[0]) and what
     * loaderVersion="[47,)" checks against.
     */
    public static Optional<String> forgeImplVersion(Class<?> c) {
        Package p = c.getPackage();
        if (p != null && p.getImplementationVersion() != null) return Optional.of(p.getImplementationVersion());
        String fv = System.getProperty("amber.ul.forgeVersion");
        try {
            var cs = c.getProtectionDomain().getCodeSource();
            if (fv != null && cs != null && java.nio.file.Path.of(cs.getLocation().toURI()).getFileName().toString().contains("-" + fv)) return Optional.of(fv.split("[.]")[0]);
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public static ClassTransformer redirect() {
        return new ClassTransformer() {
            public String fingerprint() { return "jpms-redirect-3"; }
            public byte[] transform(String name, byte[] b) {
                if (!name.startsWith("net/neoforged/fml/") && !name.startsWith("net/minecraftforge/fml/") && !name.startsWith("net/minecraftforge/logging/") && !name.startsWith("net/minecraftforge/eventbus/service/")) return b;
                boolean jvl = name.equals("net/neoforged/fml/loading/JarVersionLookupHandler") || name.equals("net/minecraftforge/fml/loading/JarVersionLookupHandler");
                if (!jvl && !contains(b, "java/lang/ModuleLayer")) return b;
                boolean[] changed = {false};
                ClassReader cr = new ClassReader(b); ClassWriter cw = new ClassWriter(cr, 0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                        if (jvl && n.equals("getImplementationVersion") && d.equals("(Ljava/lang/Class;)Ljava/util/Optional;")) {   // FML 47
                            MethodVisitor mv = super.visitMethod(acc, n, d, s, ex);
                            mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "amber/ul/fml/Jpms", "forgeImplVersion", d, false);
                            mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1, 1); mv.visitEnd();
                            changed[0] = true;
                            return null;
                        }
                        if (jvl && n.equals("getVersion") && d.equals("(Ljava/lang/Class;)Ljava/util/Optional;")) {
                            MethodVisitor mv = super.visitMethod(acc, n, d, s, ex);   // replace the body: return Jpms.version(clazz)
                            mv.visitCode(); mv.visitVarInsn(Opcodes.ALOAD, 0);
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "amber/ul/fml/Jpms", "version", d, false);
                            mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1, 1); mv.visitEnd();
                            changed[0] = true;
                            return null;   // drop the original code
                        }
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, n, d, s, ex)) {
                            @Override public void visitMethodInsn(int op, String owner, String mn, String md, boolean itf) {
                                if (owner.equals("java/lang/ModuleLayer") && mn.equals("findModule") && md.equals("(Ljava/lang/String;)Ljava/util/Optional;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "amber/ul/fml/Jpms", "findModule", "(Ljava/lang/ModuleLayer;Ljava/lang/String;)Ljava/util/Optional;", false); changed[0] = true; return;
                                }
                                if (owner.equals("java/util/ServiceLoader") && mn.equals("load") && md.equals("(Ljava/lang/ModuleLayer;Ljava/lang/Class;)Ljava/util/ServiceLoader;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "amber/ul/fml/Jpms", "load", md, false); changed[0] = true; return;
                                }
                                super.visitMethodInsn(op, owner, mn, md, itf);
                            }
                        };
                    }
                }, 0);
                if (!changed[0]) return b;
                redirected++;
                return cw.toByteArray();
            }
        };
    }

    /**
     * M4 (Forge 1.20.1): modlauncher never runs, but FML 47 asks its Launcher for the module layers and the "srg" name
     * function. Launcher.findLayerManager() / findNameMapping(String) get bodies that return the Forge bridge's answers
     * (amber.ul.fmlforge.ForgeShim, loaded by the same universal loader).
     */
    public static ClassTransformer forgeLauncher() {
        return new ClassTransformer() {
            public String fingerprint() { return "forge-launcher-1"; }
            public byte[] transform(String name, byte[] b) {
                if (!name.equals("cpw/mods/modlauncher/Launcher")) return b;
                ClassReader cr = new ClassReader(b); ClassWriter cw = new ClassWriter(cr, 0);
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] ex) {
                        boolean lm = n.equals("findLayerManager") && d.equals("()Ljava/util/Optional;");
                        boolean nm = n.equals("findNameMapping") && d.equals("(Ljava/lang/String;)Ljava/util/Optional;");
                        if (!lm && !nm) return super.visitMethod(acc, n, d, s, ex);
                        MethodVisitor mv = super.visitMethod(acc, n, d, s, ex);
                        mv.visitCode();
                        if (nm) mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "amber/ul/fmlforge/ForgeShim", n, nm ? "(Ljava/lang/String;)Ljava/util/Optional;" : "()Ljava/util/Optional;", false);
                        mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(1, 2); mv.visitEnd();
                        return null;
                    }
                }, 0);
                return cw.toByteArray();
            }
        };
    }

    static boolean contains(byte[] b, String s) {
        byte[] n = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer: for (int i = 0; i + n.length <= b.length; i++) { for (int k = 0; k < n.length; k++) if (b[i + k] != n[k]) continue outer; return true; }
        return false;
    }
}
