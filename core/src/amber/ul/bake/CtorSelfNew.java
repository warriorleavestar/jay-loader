package amber.ul.bake;

import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Game bake: a constructor that creates instances of its own class (`new C(...)` inside C.<init>, e.g. NeoForge's
 * Pack(…, List<Pack> children) building child packs) confuses Mixin's search for the constructor's super() call. Mixin then
 * puts mixin field initialisers after the CHILD's <init> instead of after super(), so for ordinary instances they never run
 * (Fabric API's Pack.parentsPredicate stays null -> NPE). Each such `NEW C; DUP; args; INVOKESPECIAL C.<init>` is replaced by
 * `args; INVOKESTATIC C.amber$new$k(args)C`, a private static synthetic factory with the same body. Semantics are unchanged.
 * Sites with a stack-map frame between NEW and <init> (uninitialised value live across a branch) are left alone.
 */
public final class CtorSelfNew {
    private CtorSelfNew() {}

    /** null if nothing to do. */
    public static byte[] hoist(byte[] b) {
        ClassReader cr = new ClassReader(b);
        String self = cr.getClassName();
        boolean[] any = {false};
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!name.equals("<init>")) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitTypeInsn(int op, String type) { if (op == Opcodes.NEW && type.equals(self)) any[0] = true; }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (!any[0]) return null;
        ClassNode cn = new ClassNode(); cr.accept(cn, 0);
        boolean isInterface = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        if (isInterface) return null;
        Map<String, MethodNode> factories = new LinkedHashMap<>();
        int changed = 0;
        for (MethodNode m : new ArrayList<>(cn.methods)) {
            if (!m.name.equals("<init>")) continue;
            Deque<TypeInsnNode> pending = new ArrayDeque<>();
            for (AbstractInsnNode i : m.instructions.toArray()) {
                if (i instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW && t.desc.equals(self)) { pending.push(t); continue; }
                if (i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.name.equals("<init>") && mi.owner.equals(self) && !pending.isEmpty()) {
                    TypeInsnNode n = pending.pop();
                    AbstractInsnNode dup = n.getNext();
                    if (dup == null || dup.getOpcode() != Opcodes.DUP || framesBetween(n, mi)) continue;
                    MethodNode f = factories.computeIfAbsent(mi.desc, d -> factory(cn, d, factories.size()));
                    m.instructions.remove(n); m.instructions.remove(dup);
                    m.instructions.set(mi, new MethodInsnNode(Opcodes.INVOKESTATIC, self, f.name, f.desc, false));
                    changed++;
                }
            }
        }
        if (changed == 0) return null;
        cn.methods.addAll(factories.values());
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static boolean framesBetween(AbstractInsnNode a, AbstractInsnNode b) {
        for (AbstractInsnNode i = a; i != null && i != b; i = i.getNext()) if (i instanceof FrameNode) return true;
        return false;
    }

    static MethodNode factory(ClassNode cn, String ctorDesc, int k) {
        Type[] args = Type.getArgumentTypes(ctorDesc);
        MethodNode f = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, "amber$new$" + k,
                Type.getMethodDescriptor(Type.getObjectType(cn.name), args), null, null);
        f.instructions.add(new TypeInsnNode(Opcodes.NEW, cn.name));
        f.instructions.add(new InsnNode(Opcodes.DUP));
        int s = 0;
        for (Type t : args) { f.instructions.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), s)); s += t.getSize(); }
        f.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, cn.name, "<init>", ctorDesc, false));
        f.instructions.add(new InsnNode(Opcodes.ARETURN));
        return f;
    }
}
