package amber.ul.runtime;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/**
 * Stand-in for the per-class work Mixin does on every class it targets: parse to a tree, walk it, write it back with
 * frame computation. Used only to measure what the class cache saves; it does not change behaviour.
 */
public final class FullPassTransformer implements ClassTransformer {
    private final String prefix;
    public FullPassTransformer(String prefix) { this.prefix = prefix; }
    public String fingerprint() { return "fullpass:" + prefix; }
    public byte[] transform(String name, byte[] b) {
        if (!name.startsWith(prefix)) return b;
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, ClassReader.EXPAND_FRAMES);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String a, String c) { return "java/lang/Object"; }
        };
        cn.accept(cw); return cw.toByteArray();
    }
}
