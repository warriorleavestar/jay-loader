package amber.ul.bake;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Bake-time fixer for Fabric mixins that fail on the NeoForge-patched game (phase 4). Our own design; no third-party code.
 *
 * Input: census failures (mixin class, target class, handler), the CLEAN game classes (AutoRenamingTool output = vanilla in
 * Mojmap) and the PATCHED game classes (binpatched + coremods), and the baked mod jars that contain the mixin classes.
 *
 * Analysis (per failing injector): resolve the target method in clean and patched, locate the @At point, and classify:
 *   method_missing        target method absent in the patched class
 *   descriptor_changed    same name, different descriptor in patched
 *   moved_to_new_method   @At target present in clean M, absent in patched M, present in a NEW patched method N called from M
 *   point_gone            @At target absent in patched M and not found in a new method
 *   ordinal_shifted       @At target count in M differs between clean and patched
 *   point_present         @At target still there (failure is about locals/captures/other)
 *   unsupported_injector  not an @Inject/@At(INVOKE|FIELD) shape this fixer understands
 *
 * Fix implemented: moved_to_new_method for @Inject. Value provenance: for every handler parameter the handler actually
 * uses, the value's source in clean M (a target argument, or a local identified by the instruction that produced it) is
 * matched with the arguments patched M passes to N. A bridge @Inject into N (same @At) rebuilds the original handler's
 * arguments (defaults for unused ones) and calls it; the original handler's injector annotation is removed.
 * Unfixable when a used value has no counterpart in N's arguments or the handler uses its CallbackInfo.
 */
public final class MixinFixer {
    public static final String VERSION = "mixin-fixer-3";
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
    static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

    public record Result(String mixin, String target, String handler, String category, boolean fixed, String detail) {}

    private final Map<String, byte[]> clean, patched, mixins;   // internal name -> bytes
    private final Map<String, JsonObject> refmaps;              // mixin internal name -> refmap table (named values)
    public Mappings intermediary;                                // optional: intermediary -> named, for strings no refmap covers
    public final List<Result> results = new ArrayList<>();
    public final Map<String, byte[]> fixedClasses = new LinkedHashMap<>();
    public final Set<String> noScan = new HashSet<>();

    public MixinFixer(Map<String, byte[]> clean, Map<String, byte[]> patched, Map<String, byte[]> mixins, Map<String, JsonObject> refmaps) {
        this.clean = clean; this.patched = patched; this.mixins = mixins; this.refmaps = refmaps;
    }

    /** One failing (mixin, target, handler) from the census. */
    public void handle(String mixinFqn, String targetFqn, String handler) {
        String mi = mixinFqn.replace('.', '/'), ti = targetFqn.replace('.', '/');
        byte[] mb = fixedClasses.containsKey(mi) ? fixedClasses.get(mi) : mixins.get(mi);
        if (mb == null || clean.get(ti) == null || patched.get(ti) == null) { results.add(new Result(mixinFqn, targetFqn, handler, "no_bytes", false, "mixin or target class bytes not found")); return; }
        ClassNode mixin = node(mb), cleanC = node(clean.get(ti)), patchedC = node(patched.get(ti));
        List<MethodNode> handlers = new ArrayList<>();
        for (MethodNode m : mixin.methods) if (injector(m) != null && (handler == null || m.name.equals(handler))) handlers.add(m);
        if (handlers.isEmpty()) { results.add(new Result(mixinFqn, targetFqn, handler, "no_injector_found", false, "")); return; }
        boolean changed = false;
        Set<MethodNode> moved = new HashSet<>();
        for (MethodNode h : handlers) {
            if (moved.contains(h)) continue;
            // DELEGATING OVERLOAD: NeoForge turned the target into "load args, push constants, call same-name overload with
            // the args appended, return". Every injector of this mixin on that method moves to the overload together
            // (@Share and LocalCapture need them in one method), not only the failing one
            MethodNode cm0 = targetOf(mixin, h, cleanC), dl = cm0 == null ? null : delegatingOverload(cm0, cleanC, patchedC);
            if (dl != null) {
                List<MethodNode> all = new ArrayList<>();
                for (MethodNode m : new ArrayList<>(mixin.methods)) if (injector(m) != null && targetOf(mixin, m, cleanC) == cm0) all.add(m);
                for (MethodNode m : all) { Result r = moveToOverload(mixin, m, cm0, dl, cleanC); results.add(r); changed |= r.fixed(); moved.add(m); }
                continue;
            }
            Result r = analyzeAndFix(mixin, h, cleanC, patchedC);
            results.add(r);
            changed |= r.fixed();
        }
        if (changed) { ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); mixin.accept(cw); fixedClasses.put(mi, cw.toByteArray()); }
    }

    /** Proactive pass over every single-target mixin: an injector that applies fine to a method NeoForge turned into a
     *  delegator (HEAD / RETURN / TAIL still match there) misses every caller NeoForge sends straight to the overload. */
    public void scanDelegators() {
        for (String mi : new ArrayList<>(mixins.keySet())) {
            if (noScan.contains(mi)) continue;   // a scan fix that failed verification
            byte[] mb = fixedClasses.containsKey(mi) ? fixedClasses.get(mi) : mixins.get(mi);
            if (!new String(mb, StandardCharsets.ISO_8859_1).contains("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            ClassNode mixin = node(mb);
            List<String> targets = mixinTargets(mixin);
            if (targets.size() != 1 || clean.get(targets.get(0)) == null || patched.get(targets.get(0)) == null) continue;
            ClassNode cleanC = node(clean.get(targets.get(0))), patchedC = node(patched.get(targets.get(0)));
            boolean changed = false;
            for (MethodNode h : new ArrayList<>(mixin.methods)) {
                if (injector(h) == null) continue;
                MethodNode cm = targetOf(mixin, h, cleanC), dl = cm == null ? null : delegatingOverload(cm, cleanC, patchedC);
                if (dl == null) continue;
                Result r = moveToOverload(mixin, h, cm, dl, cleanC);
                results.add(new Result(r.mixin(), r.target(), r.handler(), "delegating_overload(scan)", r.fixed(), r.detail()));
                changed |= r.fixed();
            }
            if (changed) { ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); mixin.accept(cw); fixedClasses.put(mi, cw.toByteArray()); }
        }
    }

    List<String> mixinTargets(ClassNode mixin) {
        List<String> r = new ArrayList<>();
        for (List<AnnotationNode> l : Arrays.asList(mixin.visibleAnnotations, mixin.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l) {
            if (!a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
            Object v = value(a, "value"), t = value(a, "targets");
            if (v instanceof List<?> vs) for (Object o : vs) if (o instanceof Type ty) r.add(ty.getInternalName());
            if (t instanceof List<?> ts) for (Object o : ts) if (o instanceof String s) r.add(resolve(mixin.name, s).replace('.', '/'));
        }
        return r;
    }

    /** The clean target method an injector selects (single method selector only), or null. */
    MethodNode targetOf(ClassNode mixin, MethodNode h, ClassNode cleanC) {
        AnnotationNode inj = injector(h);
        List<String> sel = strings(value(inj, "method"));
        return sel.size() == 1 ? find(cleanC, selector(resolve(mixin.name, sel.get(0)))) : null;
    }

    /** Patched cm is a pure delegator to a same-name overload (new in NeoForge) taking cm's args plus appended ones: that overload. */
    static MethodNode delegatingOverload(MethodNode cm, ClassNode cleanC, ClassNode patchedC) {
        MethodNode pm = find(patchedC, cm.name + cm.desc);
        if (pm == null) return null;
        MethodInsnNode call = delegateCall(pm, patchedC);
        if (call == null || delegateCall(cm, cleanC) != null || find(cleanC, call.name + call.desc) != null) return null;
        return find(patchedC, call.name + call.desc);
    }

    static MethodInsnNode delegateCall(MethodNode m, ClassNode c) {
        boolean st = (m.access & Opcodes.ACC_STATIC) != 0;
        Type[] a = Type.getArgumentTypes(m.desc);
        List<AbstractInsnNode> ins = new ArrayList<>();
        for (AbstractInsnNode i : m.instructions) if (i.getOpcode() >= 0) ins.add(i);
        int p = 0, slot = 0;
        if (!st) { if (ins.isEmpty() || !(ins.get(0) instanceof VarInsnNode v0) || v0.getOpcode() != Opcodes.ALOAD || v0.var != 0) return null; p = 1; slot = 1; }
        for (Type t : a) {
            if (p >= ins.size() || !(ins.get(p) instanceof VarInsnNode v) || v.getOpcode() != t.getOpcode(Opcodes.ILOAD) || v.var != slot) return null;
            p++; slot += t.getSize();
        }
        int extras = 0;
        while (p < ins.size() && !(ins.get(p) instanceof MethodInsnNode mi && mi.owner.equals(c.name) && mi.name.equals(m.name))) {
            AbstractInsnNode i = ins.get(p); int op = i.getOpcode();
            boolean konst = op >= Opcodes.ACONST_NULL && op <= Opcodes.LDC || op == Opcodes.GETSTATIC
                    || i instanceof MethodInsnNode mi && op == Opcodes.INVOKESTATIC && Type.getArgumentTypes(mi.desc).length == 0;
            if (!konst) return null;
            p++; extras++;
        }
        if (p + 2 != ins.size() || extras == 0) return null;
        MethodInsnNode call = (MethodInsnNode) ins.get(p);
        Type[] b = Type.getArgumentTypes(call.desc);
        if (b.length != a.length + extras || !Arrays.equals(Arrays.copyOf(b, a.length), a) || !Type.getReturnType(call.desc).equals(Type.getReturnType(m.desc))) return null;
        if ((call.getOpcode() == Opcodes.INVOKESTATIC) != st) return null;
        int ret = ins.get(p + 1).getOpcode();
        return ret >= Opcodes.IRETURN && ret <= Opcodes.RETURN ? call : null;
    }

    /** Move one injector from cm to its delegating overload dl. An @Inject handler that takes cm's args gets a bridge taking dl's
     *  args (extras dropped) with the CI, captured locals and sugar parameters passed through unchanged. */
    Result moveToOverload(ClassNode mixin, MethodNode h, MethodNode cm, MethodNode dl, ClassNode cleanC) {
        AnnotationNode inj = injector(h);
        String mixinName = mixin.name.replace('/', '.'), tgt = cleanC.name.replace('/', '.'), sel = dl.name + dl.desc;
        Type[] hArgs = Type.getArgumentTypes(h.desc), mArgs = Type.getArgumentTypes(cm.desc), dArgs = Type.getArgumentTypes(dl.desc);
        boolean takesArgs = inj.desc.equals(INJECT) && hArgs.length >= mArgs.length && Arrays.equals(Arrays.copyOf(hArgs, mArgs.length), mArgs);
        if (!takesArgs) {
            setValue(inj, "method", new ArrayList<>(List.of(sel)));
            return new Result(mixinName, tgt, h.name, "delegating_overload", true, simple(inj.desc) + " retargeted " + cm.name + cm.desc + " -> " + sel);
        }
        int extra = dArgs.length - mArgs.length;
        Type[] bArgs = new Type[hArgs.length + extra];
        System.arraycopy(dArgs, 0, bArgs, 0, dArgs.length);
        System.arraycopy(hArgs, mArgs.length, bArgs, dArgs.length, hArgs.length - mArgs.length);
        boolean hStatic = (h.access & Opcodes.ACC_STATIC) != 0;
        MethodNode b = new MethodNode(Opcodes.ACC_PRIVATE | (h.access & Opcodes.ACC_STATIC) | Opcodes.ACC_SYNTHETIC, "amber$overload$" + h.name,
                Type.getMethodDescriptor(Type.getReturnType(h.desc), bArgs), null, null);
        int s = hStatic ? 0 : 1;
        if (!hStatic) b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int i = 0; i < bArgs.length; i++) {
            if (i < mArgs.length || i >= dArgs.length) b.instructions.add(new VarInsnNode(bArgs[i].getOpcode(Opcodes.ILOAD), s));
            s += bArgs[i].getSize();
        }
        b.instructions.add(new MethodInsnNode(hStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, mixin.name, h.name, h.desc, false));
        b.instructions.add(new InsnNode(Type.getReturnType(h.desc).getOpcode(Opcodes.IRETURN)));
        // annotations move to the bridge; parameter annotations shift right by the appended args
        setValue(inj, "method", new ArrayList<>(List.of(sel)));
        b.visibleAnnotations = h.visibleAnnotations; b.invisibleAnnotations = h.invisibleAnnotations;
        h.visibleAnnotations = null; h.invisibleAnnotations = null;
        b.visibleParameterAnnotations = shiftParams(h.visibleParameterAnnotations, mArgs.length, extra, bArgs.length);
        b.invisibleParameterAnnotations = shiftParams(h.invisibleParameterAnnotations, mArgs.length, extra, bArgs.length);
        h.visibleParameterAnnotations = null; h.invisibleParameterAnnotations = null; h.visibleAnnotableParameterCount = 0; h.invisibleAnnotableParameterCount = 0;
        mixin.methods.add(b);
        return new Result(mixinName, tgt, h.name, "delegating_overload", true, "bridge " + b.name + b.desc + " -> " + sel + " (" + extra + " appended arg(s) dropped)");
    }

    @SuppressWarnings("unchecked")
    static List<AnnotationNode>[] shiftParams(List<AnnotationNode>[] p, int at, int by, int len) {
        if (p == null) return null;
        List<AnnotationNode>[] r = new List[len];
        for (int i = 0; i < p.length; i++) { int j = i < at ? i : i + by; if (j < len) r[j] = p[i]; }
        return r;
    }

    /** Where the injection point went in the patched game. kind: same | desc_changed | replaced | field | instance_to_static */
    record Reloc(MethodNode scope, String kind, String target, String atValue, String newDesc, boolean newStatic) {}

    Result analyzeAndFix(ClassNode mixin, MethodNode h, ClassNode cleanC, ClassNode patchedC) {
        AnnotationNode inj = injector(h);
        String mixinName = mixin.name.replace('/', '.'), tgt = cleanC.name.replace('/', '.');
        boolean isInject = inj.desc.equals(INJECT);
        String kind = simple(inj.desc);
        List<String> methodSel = strings(value(inj, "method"));
        List<AnnotationNode> ats = annos(value(inj, "at"));
        if (methodSel.size() != 1 || ats.size() != 1) return new Result(mixinName, tgt, h.name, "unsupported_shape", false, kind + " method=" + methodSel + " at=" + ats.size());
        String sel = selector(resolve(mixin.name, methodSel.get(0)));
        AnnotationNode at = ats.get(0);
        String atValue = (String) value(at, "value"), atTarget = value(at, "target") == null ? null : resolve(mixin.name, (String) value(at, "target"));
        MethodNode cm = find(cleanC, sel), pm = cm == null ? null : find(patchedC, cm.name + cm.desc);
        if (cm == null) return new Result(mixinName, tgt, h.name, "method_missing_in_clean", false, sel);
        List<String> notes = new ArrayList<>();
        // LAMBDA RENAMED: lambda$<outer>$<n> numbers shift when NeoForge adds or removes lambdas in the class; the same
        // lambda is the patched one of the same outer method and descriptor whose calls match best (unique, close match)
        boolean lambdaRenamed = false;
        if (cm.name.startsWith("lambda$")) {
            MethodNode best = sameLambda(cm, patchedC);
            if (best == null && pm == null) return new Result(mixinName, tgt, h.name, "lambda_renamed", false, sel + ": no unique match");
            if (best != null && best != pm) {
                notes.add("lambda " + cm.name + " -> " + best.name);
                pm = best; setValue(inj, "method", new ArrayList<>(List.of(pm.name + pm.desc))); lambdaRenamed = true;
            }
        }
        Conv[] conv = null;
        if (pm == null) {
            String nm = cm.name;
            List<MethodNode> same = patchedC.methods.stream().filter(m -> m.name.equals(nm)).toList();
            // ARG_TYPE_CHANGED: exactly one same-name method of the same arity whose changed argument types convert to the old ones
            int n = 0;
            for (MethodNode m : same) { Conv[] c = conversions(cm.desc, m.desc); if (c != null) { n++; conv = c; pm = m; } }
            if (n != 1) return new Result(mixinName, tgt, h.name, same.isEmpty() ? "method_missing" : "descriptor_changed", false, sel);
            notes.add("descriptor " + cm.desc + " -> " + pm.desc);
        }
        // OVERLOAD SELECTOR: a name-only selector binds the first method of that name; if NeoForge added an overload, pin the
        // descriptor so the patched game binds the method we analysed (later retargets start from it)
        if (!sel.contains("(") && patchedC.methods.stream().filter(m -> m.name.equals(sel)).count() > 1) {
            setValue(inj, "method", new ArrayList<>(List.of(pm.name + pm.desc))); notes.add("selector pinned " + pm.name + pm.desc);
        }
        Reloc rl = null;
        if (("INVOKE".equals(atValue) || "FIELD".equals(atValue)) && atTarget != null) {
            int cc = count(cm, atTarget), pc = count(pm, atTarget);
            if (pc == 0 && cc > 0) {
                rl = relocate(cleanC, patchedC, cm, pm, atTarget);
                if (rl == null) return new Result(mixinName, tgt, h.name, "point_gone", false, atTarget);
            } else if (cc != pc && conv == null) return new Result(mixinName, tgt, h.name, "ordinal_shifted", false, atTarget + " clean=" + cc + " patched=" + pc);
        } else if (!kind.equals("ModifyVariable") && conv == null) {
            // HEAD / TAIL / RETURN of a renamed lambda: the rename is the whole fix
            if (lambdaRenamed && ("HEAD".equals(atValue) || "TAIL".equals(atValue) || "RETURN".equals(atValue))) return new Result(mixinName, tgt, h.name, "lambda_renamed", true, String.join("; ", notes));
            return new Result(mixinName, tgt, h.name, "point_kind:" + atValue, false, "");
        }
        if (rl == null) rl = new Reloc(pm, "same", atTarget, atValue, null, false);
        String cat = rl.scope() != pm ? (lambdaRef(pm, patchedC.name, rl.scope()) ? "moved_into_lambda" : "moved_to_new_method") : null;
        String pointCat = switch (rl.kind()) { case "same" -> null; case "desc_changed" -> "call_site_changed"; case "replaced" -> "call_replaced";
            case "field" -> "call_replaced_by_field"; default -> "instance_to_static"; };
        String category = String.join("+", java.util.stream.Stream.of(cat, pointCat, conv != null ? "arg_type_changed" : null).filter(Objects::nonNull).toList());
        if (category.isEmpty()) category = kind.equals("ModifyVariable") ? "local_shifted" : "point_present";
        try {
            String d = apply(mixin, h, inj, at, kind, isInject, cleanC, patchedC, cm, pm, rl, conv, atTarget, notes);
            if (d == null) return new Result(mixinName, tgt, h.name, category, false, "nothing to change" + (notes.isEmpty() ? "" : ": " + String.join("; ", notes)));
            return new Result(mixinName, tgt, h.name, category, true, d);
        } catch (Unfixable u) { return new Result(mixinName, tgt, h.name, category, false, u.getMessage()); }
    }

    static final class Unfixable extends RuntimeException { Unfixable(String m) { super(m, null, false, false); } }

    /** Apply the relocation for one injector kind. Returns the log line, or null if nothing needed changing. */
    String apply(ClassNode mixin, MethodNode h, AnnotationNode inj, AnnotationNode at, String kind, boolean isInject, ClassNode cleanC, ClassNode patchedC,
                 MethodNode cm, MethodNode pm, Reloc rl, Conv[] conv, String atTarget, List<String> notes) {
        MethodNode S = rl.scope();
        boolean moved = S != pm, pointChanged = !rl.kind().equals("same");
        if (isInject) {
            if (moved) {
                if (conv != null) throw new Unfixable("@Inject moved and arg types changed");
                if (lambdaRef(pm, patchedC.name, S) && callTo(pm, patchedC.name, S) == null) {
                    // the method's body moved whole into a lambda of the same descriptor (NeoForge wraps it to post an event
                    // on the result, ServerPlayer.startSleepInBed): the lambda is the old method, so the @Inject - its
                    // CallbackInfo(Returnable), args and captured locals - moves with it unchanged; apply-time checks verify
                    if (S.desc.equals(cm.desc) && ((S.access ^ cm.access) & Opcodes.ACC_STATIC) == 0) {
                        setValue(inj, "method", new ArrayList<>(List.of(S.name + S.desc))); notes.add("@Inject method -> " + S.name + S.desc + " (body moved into a same-descriptor lambda)");
                        if (pointChanged) { retargetAt(at, rl); notes.add("@At " + atTarget + " -> " + rl.target()); }
                        return String.join("; ", notes);
                    }
                    throw new Unfixable("@Inject into a lambda: not bridged");
                }
                Result r = fixMoved(mixin, h, inj, at, atTarget, rl.target(), rl.atValue(), cleanC, cm, pm, S, callTo(pm, patchedC.name, S), mixin.name.replace('/', '.'), cleanC.name.replace('/', '.'));
                if (!r.fixed()) throw new Unfixable(r.detail());
                return r.detail();
            }
            if (pointChanged) { retargetAt(at, rl); notes.add("@At " + atTarget + " -> " + rl.target()); }
            if (conv != null) { setValue(inj, "method", new ArrayList<>(List.of(pm.name + pm.desc))); MethodNode b = capturedBridge(mixin, h, inj, 0, Type.getArgumentTypes(cm.desc).length, pm, conv); notes.add("bridge " + b.name + " converts " + convLog(conv)); }
            return notes.isEmpty() ? null : String.join("; ", notes);
        }
        // captures of the same enclosing method stay valid when only the @At changes; a new scope or a bridge method would lose them
        if (hasSugarParams(h) && (moved || conv != null)) throw new Unfixable(kind + " uses @Local/@Share captures");
        Type[] hArgs = Type.getArgumentTypes(h.desc);
        switch (kind) {
            case "ModifyVariable": {
                if (conv != null) {
                    setValue(inj, "method", new ArrayList<>(List.of(pm.name + pm.desc)));
                    MethodNode b = capturedBridge(mixin, h, inj, 1, hArgs.length - 1, pm, conv);
                    notes.add("bridge " + b.name + " converts " + convLog(conv));
                    h = b;
                }
                if (moved) { checkCaptured(h, 1, cm, S); setValue(inj, "method", new ArrayList<>(List.of(S.name + S.desc))); notes.add("method -> " + S.name + S.desc); }
                if (pointChanged) { retargetAt(at, rl); notes.add("@At " + atTarget + " -> " + rl.target()); }
                String lv = recomputeLocal(h, inj, at, cm, S, atTarget, rl.target());
                if (lv != null) notes.add(lv);
                return notes.isEmpty() ? null : String.join("; ", notes);
            }
            case "ModifyExpressionValue": case "ModifyReturnValue": case "ModifyArg": {
                if (conv != null) throw new Unfixable(kind + " with changed target arg types");
                if (pointChanged) {
                    String why = rl.kind().equals("desc_changed") ? callSiteCompatible(kind, inj, h, atTarget.substring(atTarget.indexOf('(')), rl.newDesc())
                            : rl.kind().equals("replaced") ? null
                            : kind.equals("ModifyExpressionValue") && (rl.kind().equals("field") || rl.kind().equals("instance_to_static")) ? null
                            : kind + ": point kind " + rl.kind() + " changes the handler signature";
                    if (why != null) throw new Unfixable(why);
                }
                if (moved) { checkCaptured(h, kind.equals("ModifyArg") ? hArgs.length : 1, cm, S); setValue(inj, "method", new ArrayList<>(List.of(S.name + S.desc))); notes.add(kind + " method -> " + S.name + S.desc); }
                if (pointChanged) { retargetAt(at, rl); notes.add("@At " + atTarget + " -> " + rl.target()); }
                return notes.isEmpty() ? null : String.join("; ", notes);
            }
            case "Redirect": case "WrapOperation": {
                if (conv != null) throw new Unfixable(kind + " with changed target arg types");
                if (atTarget == null || !atTarget.contains("(")) throw new Unfixable(kind + " on a field");
                boolean wrap = kind.equals("WrapOperation");
                String oldDesc = atTarget.substring(atTarget.indexOf('('));
                boolean oldStatic = isStaticCall(cm, atTarget);
                int callParams = Type.getArgumentTypes(oldDesc).length + (oldStatic ? 0 : 1) + (wrap ? 1 : 0);
                if (moved) { checkCaptured(h, callParams, cm, S); setValue(inj, "method", new ArrayList<>(List.of(S.name + S.desc))); notes.add(kind + " method -> " + S.name + S.desc); }
                if (!pointChanged) return notes.isEmpty() ? null : String.join("; ", notes);
                if (rl.kind().equals("replaced") && rl.newStatic() == oldStatic && (oldStatic || rl.target().startsWith(atTarget.substring(0, atTarget.indexOf(';') + 1)))) {
                    retargetAt(at, rl); notes.add("@At " + atTarget + " -> " + rl.target()); return String.join("; ", notes);
                }
                if (rl.kind().equals("field")) throw new Unfixable(kind + ": call became a field read");
                if (!Type.getReturnType(oldDesc).equals(Type.getReturnType(rl.newDesc()))) throw new Unfixable(kind + ": return type changed");
                Type[] oldP = callParamTypes(atTarget, oldStatic), newP = callParamTypes(rl.target(), rl.newStatic());
                int[] map = subsequence(oldP, newP);
                if (map == null) throw new Unfixable(kind + ": old call parameters are not an unambiguous subsequence of " + rl.target());
                if (hasSugarParams(h)) throw new Unfixable(kind + " uses @Local/@Share captures (a bridge would lose them)");
                MethodNode b = wrap ? operationBridge(mixin, h, inj, oldP, newP, map) : redirectBridge(mixin, h, inj, atTarget, rl, oldP, newP, map, oldStatic);
                retargetAt(at, rl);
                notes.add("bridge " + b.name + b.desc + " (" + atTarget + " -> " + rl.target() + ")");
                return String.join("; ", notes);
            }
            default: throw new Unfixable("injector kind " + kind + " not handled");
        }
    }

    static void retargetAt(AnnotationNode at, Reloc rl) { setValue(at, "value", rl.atValue()); setValue(at, "target", rl.target()); }

    /** Captured target-method args (handler params after `prefix`) must keep their types in the new scope. */
    static void checkCaptured(MethodNode h, int prefix, MethodNode cm, MethodNode S) {
        Type[] hp = Type.getArgumentTypes(h.desc), sp = Type.getArgumentTypes(S.desc);
        for (int i = prefix, k = 0; i < hp.length; i++, k++) {
            if (hp[i].getSort() == Type.OBJECT && (hp[i].getInternalName().equals(CI) || hp[i].getInternalName().equals(CIR))) break;
            if (k >= sp.length || !sp[k].equals(hp[i])) throw new Unfixable("captured target arg " + k + " (" + hp[i] + ") is not the same in " + S.name + S.desc);
        }
    }

    // ---- where did the point go ----------------------------------------------------------------------------------------

    /** Search patched M, then the new methods/lambdas/overloads M now calls, for the relocated point. */
    Reloc relocate(ClassNode cleanC, ClassNode patchedC, MethodNode cm, MethodNode pm, String atTarget) {
        Set<String> cleanMethods = new HashSet<>(); for (MethodNode m : cleanC.methods) cleanMethods.add(m.name + m.desc);
        List<MethodNode> scopes = new ArrayList<>(); scopes.add(pm);
        for (MethodNode n : patchedC.methods) {
            if (n == pm) continue;
            boolean call = callTo(pm, patchedC.name, n) != null, lambda = !call && lambdaRef(pm, patchedC.name, n);
            if (!call && !lambda) continue;
            if (call && cleanMethods.contains(n.name + n.desc)) continue;   // an existing method that already had its own code: not a move
            scopes.add(n);
        }
        int cc = count(cm, atTarget);
        for (MethodNode S : scopes) {
            if (S != pm && count(S, atTarget) > 0) {
                if (count(S, atTarget) != cc && count(S, atTarget) != cc + count(pm, atTarget)) continue;
                return new Reloc(S, "same", atTarget, atTarget.contains("(") ? "INVOKE" : "FIELD", null, false);
            }
            Reloc r = changedPoint(cm, S, atTarget);
            if (r != null) return r;
        }
        return null;
    }

    /** The point is gone from S: a same-name call with another descriptor, a replacement call/field, or an instance->static move. */
    Reloc changedPoint(MethodNode cm, MethodNode S, String atTarget) {
        if (!atTarget.contains("(")) return null;
        String owner = atTarget.substring(1, atTarget.indexOf(';')), name = atTarget.substring(atTarget.indexOf(';') + 1, atTarget.indexOf('('));
        String oldDesc = atTarget.substring(atTarget.indexOf('('));
        int cc = count(cm, atTarget);
        // 1. CALL_SITE_CHANGED: same owner + name, exactly one other descriptor
        List<MethodInsnNode> cands = new ArrayList<>();
        for (AbstractInsnNode i : S.instructions) if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name) && !mi.desc.equals(oldDesc)
                && cands.stream().noneMatch(c -> c.desc.equals(mi.desc))) cands.add(mi);
        if (cands.size() == 1) { MethodInsnNode c = cands.get(0);
            return new Reloc(S, "desc_changed", "L" + owner + ";" + name + c.desc, "INVOKE", c.desc, c.getOpcode() == Opcodes.INVOKESTATIC); }
        if (cands.size() > 1) return null;
        Set<String> cleanKeys = new HashSet<>(); for (AbstractInsnNode i : cm.instructions) { String k = key(i); if (k != null) cleanKeys.add(k); }
        Type ret = Type.getReturnType(oldDesc);
        boolean oldStatic = isStaticCall(cm, atTarget);
        // 2. REPLACED: the vanished call sites of this value type and the new call/field-read sites of this value type, mapped in order
        List<String> gone = new ArrayList<>(), fresh = new ArrayList<>();
        for (AbstractInsnNode i : cm.instructions) if (i instanceof MethodInsnNode mi && Type.getReturnType(mi.desc).equals(ret) && count(S, key(mi)) == 0) gone.add(key(mi));
        for (AbstractInsnNode i : S.instructions) {
            String k = key(i); if (k == null || cleanKeys.contains(k)) continue;
            if (i instanceof MethodInsnNode mi && Type.getReturnType(mi.desc).equals(ret)) fresh.add(k);
            else if (i instanceof FieldInsnNode fi && (fi.getOpcode() == Opcodes.GETFIELD || fi.getOpcode() == Opcodes.GETSTATIC) && Type.getType(fi.desc).equals(ret)) fresh.add(k);
        }
        if (ret.getSort() != Type.VOID && !gone.isEmpty() && gone.size() == fresh.size()) {
            Map<String, String> m = new HashMap<>(); boolean ok = true;
            for (int i = 0; i < gone.size(); i++) { String prev = m.putIfAbsent(gone.get(i), fresh.get(i)); if (prev != null && !prev.equals(fresh.get(i))) ok = false; }
            String nk = m.get(atTarget);
            if (ok && nk != null && new HashSet<>(m.values()).size() == m.size() && count(S, nk) == cc) {
                if (nk.contains("(")) {
                    String nd = nk.substring(nk.indexOf('('));
                    boolean ns = ((MethodInsnNode) firstKey(S, nk)).getOpcode() == Opcodes.INVOKESTATIC;
                    if (nd.equals(oldDesc)) return new Reloc(S, "replaced", nk, "INVOKE", nd, ns);
                    if (!oldStatic && ns && subsequence(callParamTypes(atTarget, false), Type.getArgumentTypes(nd)) != null) return new Reloc(S, "instance_to_static", nk, "INVOKE", nd, true);
                } else if (Type.getArgumentTypes(oldDesc).length == 0) return new Reloc(S, "field", nk, "FIELD", null, false);
            }
        }
        // 3. INSTANCE_TO_STATIC: one new static call returning the same type that takes the receiver and the old args in order,
        //    or has the old name with the receiver as its first parameter
        if (!oldStatic) {
            Type[] oldP = callParamTypes(atTarget, false);
            List<MethodInsnNode> st = new ArrayList<>();
            for (AbstractInsnNode i : S.instructions) if (i instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC && !cleanKeys.contains(key(mi))
                    && Type.getReturnType(mi.desc).equals(ret) && st.stream().noneMatch(x -> key(x).equals(key(mi)))) {
                Type[] np = Type.getArgumentTypes(mi.desc);
                boolean sameName = mi.name.equals(name) && np.length > 0 && np[0].equals(Type.getObjectType(owner));
                if (sameName || subsequence(oldP, np) != null) st.add(mi);
            }
            if (st.size() == 1 && count(S, key(st.get(0))) == cc) return new Reloc(S, "instance_to_static", key(st.get(0)), "INVOKE", st.get(0).desc, true);
        }
        return null;
    }

    static String key(AbstractInsnNode i) {
        if (i instanceof MethodInsnNode mi) return "L" + mi.owner + ";" + mi.name + mi.desc;
        if (i instanceof FieldInsnNode fi) return "L" + fi.owner + ";" + fi.name + ":" + fi.desc;
        return null;
    }
    static AbstractInsnNode firstKey(MethodNode m, String k) { for (AbstractInsnNode i : m.instructions) if (k.equals(key(i))) return i; return null; }
    static boolean isStaticCall(MethodNode m, String target) {
        for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode mi && target.equals(key(mi))) return mi.getOpcode() == Opcodes.INVOKESTATIC;
        return false;
    }
    /** Receiver (instance calls) + arguments. */
    static Type[] callParamTypes(String target, boolean isStatic) {
        String owner = target.substring(1, target.indexOf(';'));
        Type[] a = Type.getArgumentTypes(target.substring(target.indexOf('(')));
        if (isStatic) return a;
        Type[] r = new Type[a.length + 1]; r[0] = Type.getObjectType(owner); System.arraycopy(a, 0, r, 1, a.length); return r;
    }
    /** old[j] -> index in nw, in order; forced (each mapped type occurs equally often in both, so there is no choice). null if not. */
    static int[] subsequence(Type[] old, Type[] nw) {
        if (nw.length >= old.length && Arrays.equals(old, Arrays.copyOf(nw, old.length))) { int[] id = new int[old.length]; for (int j = 0; j < id.length; j++) id[j] = j; return id; }   // appended params
        int[] map = new int[old.length]; int k = 0;
        for (int j = 0; j < old.length; j++) {
            while (k < nw.length && !nw[k].equals(old[j])) k++;
            if (k == nw.length) return null;
            map[j] = k++;
        }
        for (Type t : old) if (Arrays.stream(old).filter(t::equals).count() != Arrays.stream(nw).filter(t::equals).count()) return null;
        return map;
    }

    // ---- argument type conversions (ARG_TYPE_CHANGED) ------------------------------------------------------------------

    /** How to get an old argument from a new one: a no-arg instance method on the new type returning the old type. */
    record Conv(String owner, String name, String desc) {}
    Conv[] conversions(String oldDesc, String newDesc) {
        Type[] o = Type.getArgumentTypes(oldDesc), n = Type.getArgumentTypes(newDesc);
        if (o.length != n.length || !Type.getReturnType(oldDesc).equals(Type.getReturnType(newDesc))) return null;
        Conv[] c = new Conv[o.length]; boolean any = false;
        for (int i = 0; i < o.length; i++) {
            if (o[i].equals(n[i])) continue;
            if (o[i].getSort() != Type.OBJECT || n[i].getSort() != Type.OBJECT) return null;
            Conv cv = accessor(n[i].getInternalName(), o[i]);
            if (cv == null) return null;
            c[i] = cv; any = true;
        }
        return any ? c : null;
    }
    /** The unique public no-arg instance method of `cls` (or a superclass) that returns `t`. */
    Conv accessor(String cls, Type t) {
        List<Conv> found = new ArrayList<>();
        for (String c = cls; c != null; ) {
            byte[] b = patched.get(c); if (b == null) break;
            ClassNode n = node(b);
            for (MethodNode m : n.methods) if ((m.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PUBLIC)) == Opcodes.ACC_PUBLIC && m.desc.equals("()" + t.getDescriptor())
                    && found.stream().noneMatch(f -> f.name().equals(m.name))) found.add(new Conv(cls, m.name, m.desc));
            c = n.superName;
        }
        return found.size() == 1 ? found.get(0) : null;
    }
    static String convLog(Conv[] c) { StringBuilder s = new StringBuilder(); for (int i = 0; i < c.length; i++) if (c[i] != null) s.append("arg").append(i).append(" via ").append(c[i].name()).append("() "); return s.toString().trim(); }

    // ---- bridges -------------------------------------------------------------------------------------------------------

    /** Move the injector from h to a new bridge method b (h stays as a plain method that b calls). */
    static MethodNode newBridge(ClassNode mixin, MethodNode h, AnnotationNode inj, String suffix, String desc) {
        String name = "amber$bridge$" + h.name + suffix;
        for (MethodNode m : mixin.methods) if (m.name.equals(name)) throw new Unfixable("bridge " + name + " exists");
        MethodNode b = new MethodNode(Opcodes.ACC_PRIVATE | (h.access & Opcodes.ACC_STATIC) | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
        if (h.visibleAnnotations != null) h.visibleAnnotations.removeIf(a -> a == inj);
        if (h.invisibleAnnotations != null) h.invisibleAnnotations.removeIf(a -> a == inj);
        b.visibleAnnotations = new ArrayList<>(List.of(inj));
        mixin.methods.add(b);
        return b;
    }
    static int[] slots(Type[] args, boolean isStatic) { int[] r = new int[args.length]; int s = isStatic ? 0 : 1; for (int i = 0; i < args.length; i++) { r[i] = s; s += args[i].getSize(); } return r; }
    static void invokeHandler(MethodNode b, ClassNode mixin, MethodNode h) {
        b.instructions.add(new MethodInsnNode((h.access & Opcodes.ACC_STATIC) != 0 ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, mixin.name, h.name, h.desc, false));
        b.instructions.add(new InsnNode(Type.getReturnType(h.desc).getOpcode(Opcodes.IRETURN)));
    }

    /** Handler params [0,prefix) unchanged, [prefix, prefix+captured) are target args that changed type (converted), the rest unchanged. */
    static MethodNode capturedBridge(ClassNode mixin, MethodNode h, AnnotationNode inj, int prefix, int captured, MethodNode newTarget, Conv[] conv) {
        Type[] hp = Type.getArgumentTypes(h.desc), np = Type.getArgumentTypes(newTarget.desc);
        int capCount = 0;
        for (int i = prefix; i < hp.length && i - prefix < captured && i - prefix < np.length; i++) {
            if (hp[i].getSort() == Type.OBJECT && (hp[i].getInternalName().equals(CI) || hp[i].getInternalName().equals(CIR))) break;
            capCount++;
        }
        Type[] bp = hp.clone();
        for (int k = 0; k < capCount; k++) if (conv[k] != null) bp[prefix + k] = np[k];
        boolean st = (h.access & Opcodes.ACC_STATIC) != 0;
        MethodNode b = newBridge(mixin, h, inj, "", Type.getMethodDescriptor(Type.getReturnType(h.desc), bp));
        int[] sl = slots(bp, st);
        if (!st) b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int i = 0; i < bp.length; i++) {
            b.instructions.add(new VarInsnNode(bp[i].getOpcode(Opcodes.ILOAD), sl[i]));
            int k = i - prefix;
            if (k >= 0 && k < capCount && conv[k] != null) b.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, conv[k].owner(), conv[k].name(), conv[k].desc(), false));
        }
        invokeHandler(b, mixin, h);
        return b;
    }

    static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    /**
     * WrapOperation whose call changed shape (extra params, or instance->static with the receiver as a param): the bridge takes the
     * new call's params + Operation, hands the original handler the old params + an Operation proxy that puts the old params
     * back into the new call's param array (the extra ones keep the values this call site passed) and calls the real Operation.
     */
    static MethodNode operationBridge(ClassNode mixin, MethodNode h, AnnotationNode inj, Type[] oldP, Type[] newP, int[] map) {
        Type[] hp = Type.getArgumentTypes(h.desc);
        if (hp.length < oldP.length + 1 || !hp[oldP.length].getInternalName().equals(OPERATION)) throw new Unfixable("WrapOperation handler shape");
        Type[] captured = Arrays.copyOfRange(hp, oldP.length + 1, hp.length);
        Type[] bp = new Type[newP.length + 1 + captured.length];
        System.arraycopy(newP, 0, bp, 0, newP.length); bp[newP.length] = Type.getObjectType(OPERATION); System.arraycopy(captured, 0, bp, newP.length + 1, captured.length);
        boolean st = (h.access & Opcodes.ACC_STATIC) != 0;
        MethodNode b = newBridge(mixin, h, inj, "", Type.getMethodDescriptor(Type.getReturnType(h.desc), bp));
        // proxy implementation: static Object lambda$amber$op$<h>(Operation op, Object[] base, Object[] a) { n = base.clone(); n[map[j]] = a[j]; return op.call(n); }
        String implName = "lambda$amber$op$" + h.name + "$0";
        String implDesc = "(L" + OPERATION + ";[Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
        MethodNode impl = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, implName, implDesc, null, null);
        impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        impl.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "[Ljava/lang/Object;", "clone", "()Ljava/lang/Object;", false));
        impl.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
        impl.instructions.add(new VarInsnNode(Opcodes.ASTORE, 3));
        for (int j = 0; j < map.length; j++) {
            impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3)); impl.instructions.add(new LdcInsnNode(map[j]));
            impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2)); impl.instructions.add(new LdcInsnNode(j)); impl.instructions.add(new InsnNode(Opcodes.AALOAD));
            impl.instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); impl.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
        impl.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
        impl.instructions.add(new InsnNode(Opcodes.ARETURN));
        mixin.methods.add(impl);
        // bridge body
        int[] sl = slots(bp, st);
        int baseSlot = st ? 0 : 1; for (Type t : bp) baseSlot += t.getSize();
        b.instructions.add(new LdcInsnNode(newP.length)); b.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int k = 0; k < newP.length; k++) {
            b.instructions.add(new InsnNode(Opcodes.DUP)); b.instructions.add(new LdcInsnNode(k));
            b.instructions.add(new VarInsnNode(newP[k].getOpcode(Opcodes.ILOAD), sl[k])); box(b.instructions, newP[k]);
            b.instructions.add(new InsnNode(Opcodes.AASTORE));
        }
        b.instructions.add(new VarInsnNode(Opcodes.ASTORE, baseSlot));
        if (!st) b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        for (int j = 0; j < oldP.length; j++) b.instructions.add(new VarInsnNode(oldP[j].getOpcode(Opcodes.ILOAD), sl[map[j]]));
        b.instructions.add(new VarInsnNode(Opcodes.ALOAD, sl[newP.length])); b.instructions.add(new VarInsnNode(Opcodes.ALOAD, baseSlot));
        Handle mf = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
        Type sam = Type.getMethodType("([Ljava/lang/Object;)Ljava/lang/Object;");
        b.instructions.add(new InvokeDynamicInsnNode("call", "(L" + OPERATION + ";[Ljava/lang/Object;)L" + OPERATION + ";", mf,
                sam, new Handle(Opcodes.H_INVOKESTATIC, mixin.name, implName, implDesc, false), sam));
        for (int c = 0; c < captured.length; c++) b.instructions.add(new VarInsnNode(captured[c].getOpcode(Opcodes.ILOAD), sl[newP.length + 1 + c]));
        invokeHandler(b, mixin, h);
        return b;
    }
    static void box(InsnList l, Type t) {
        String w = switch (t.getSort()) { case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte"; case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short"; case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long"; case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double"; default -> null; };
        if (w != null) l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false));
    }

    /**
     * Redirect whose call gained parameters at the end (same owner, name, static-ness, return type): a copy of the handler that takes
     * the extra params right after the call params and forwards them wherever the handler body makes the original call.
     */
    static MethodNode redirectBridge(ClassNode mixin, MethodNode h, AnnotationNode inj, String atTarget, Reloc rl, Type[] oldP, Type[] newP, int[] map, boolean oldStatic) {
        for (int j = 0; j < map.length; j++) if (map[j] != j) throw new Unfixable("Redirect: new parameters are not appended at the end");
        if (rl.newStatic() != oldStatic || !rl.kind().equals("desc_changed")) throw new Unfixable("Redirect: only appended parameters are rewritten (" + rl.kind() + ")");
        Type[] hp = Type.getArgumentTypes(h.desc), extra = Arrays.copyOfRange(newP, oldP.length, newP.length);
        Type[] bp = new Type[hp.length + extra.length];
        System.arraycopy(hp, 0, bp, 0, oldP.length); System.arraycopy(extra, 0, bp, oldP.length, extra.length); System.arraycopy(hp, oldP.length, bp, oldP.length + extra.length, hp.length - oldP.length);
        boolean st = (h.access & Opcodes.ACC_STATIC) != 0;
        int insertSlot = st ? 0 : 1; for (int j = 0; j < oldP.length; j++) insertSlot += hp[j].getSize();
        int extraSize = 0; for (Type t : extra) extraSize += t.getSize();
        // copy h
        MethodNode b = new MethodNode(h.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED) | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, "amber$bridge$" + h.name,
                Type.getMethodDescriptor(Type.getReturnType(h.desc), bp), null, h.exceptions.toArray(new String[0]));
        h.accept(b);
        b.visibleAnnotations = null; b.invisibleAnnotations = null; b.visibleParameterAnnotations = null; b.invisibleParameterAnnotations = null;
        b.name = "amber$bridge$" + h.name; b.desc = Type.getMethodDescriptor(Type.getReturnType(h.desc), bp);
        for (MethodNode m : mixin.methods) if (m.name.equals(b.name)) throw new Unfixable("bridge " + b.name + " exists");
        final int is = insertSlot, es = extraSize;
        for (AbstractInsnNode i : b.instructions) {
            if (i instanceof VarInsnNode v && v.var >= is) v.var += es;
            else if (i instanceof IincInsnNode v && v.var >= is) v.var += es;
            else if (i instanceof FrameNode f && (f.type == Opcodes.F_FULL || f.type == Opcodes.F_NEW) && f.local != null) {
                int pos = (st ? 0 : 1) + oldP.length;
                if (f.local.size() >= pos) for (int k = extra.length - 1; k >= 0; k--) f.local.add(pos, frameType(extra[k]));
            }
        }
        if (b.localVariables != null) for (LocalVariableNode lv : b.localVariables) if (lv.index >= is) lv.index += es;
        b.maxLocals += es;
        // forward the extra params where the handler makes the original call
        String owner = atTarget.substring(1, atTarget.indexOf(';')), name = atTarget.substring(atTarget.indexOf(';') + 1, atTarget.indexOf('('));
        String oldDesc = atTarget.substring(atTarget.indexOf('(')); int calls = 0;
        for (AbstractInsnNode i : b.instructions.toArray()) if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(oldDesc)) {
            int s = is; InsnList pre = new InsnList();
            for (Type t : extra) { pre.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), s)); s += t.getSize(); }
            b.instructions.insertBefore(mi, pre); mi.desc = rl.newDesc(); calls++;
        }
        if (h.visibleAnnotations != null) h.visibleAnnotations.removeIf(a -> a == inj);
        if (h.invisibleAnnotations != null) h.invisibleAnnotations.removeIf(a -> a == inj);
        b.visibleAnnotations = new ArrayList<>(List.of(inj));
        mixin.methods.add(b);
        return b;
    }
    static Object frameType(Type t) {
        return switch (t.getSort()) { case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER; case Type.LONG -> Opcodes.LONG;
            case Type.FLOAT -> Opcodes.FLOAT; case Type.DOUBLE -> Opcodes.DOUBLE; default -> t.getInternalName(); };
    }

    // ---- @ModifyVariable local recompute -------------------------------------------------------------------------------

    /**
     * Identify the variable in the clean method (explicit index, or ordinal among locals of the handler's type in scope at the
     * point), take the instruction that produced its value there, and find the one local of that type in the patched scope with
     * the same producer at the corresponding point. The result is pinned as an explicit index (ordinal/name dropped).
     */
    String recomputeLocal(MethodNode h, AnnotationNode inj, AnnotationNode at, MethodNode cm, MethodNode S, String cleanTarget, String newTarget) {
        Type vt = Type.getReturnType(h.desc);
        if (value(inj, "name") != null) throw new Unfixable("ModifyVariable by name");
        Integer idx = (Integer) value(inj, "index"), ord = (Integer) value(inj, "ordinal");
        boolean argsOnly = Boolean.TRUE.equals(value(inj, "argsOnly"));
        if (argsOnly) return null;   // arguments: handled by the descriptor checks
        String av = (String) value(at, "value");
        Integer atOrd = (Integer) value(at, "ordinal");
        boolean mStatic = (cm.access & Opcodes.ACC_STATIC) != 0;
        int firstLocalC = argEnd(cm), firstLocalS = argEnd(S);
        String sig; int cleanSlot;
        if ("LOAD".equals(av) || "STORE".equals(av)) {
            boolean load = "LOAD".equals(av);
            Set<Integer> cand = new TreeSet<>(); Map<Integer, String> sigs = new HashMap<>();
            for (AbstractInsnNode i : cm.instructions) if (i instanceof VarInsnNode v && isVarOp(v, vt, load)) {
                int s = idx != null ? idx : ordinalLocal(cm, i, vt, ord == null ? -1 : ord, firstLocalC, load);
                if (s == v.var) { cand.add(s); }
            }
            if (cand.size() != 1) throw new Unfixable("ModifyVariable: clean variable not unique (" + cand + ")");
            cleanSlot = cand.iterator().next();
            // producer: for STORE the stored value's producer at the matched store (ordinal of the @At), for LOAD the last store before the load
            AbstractInsnNode p = nthVar(cm, cleanSlot, vt, load, atOrd == null ? 0 : atOrd);
            if (p == null) throw new Unfixable("ModifyVariable: clean point not found");
            sig = load ? slotProducers(cm, p).get(cleanSlot) : producer(prev(p));
            // patched: the slot whose matching var op (same @At ordinal) has the same producer
            Set<Integer> pc = new TreeSet<>();
            for (int s = firstLocalS; s < S.maxLocals; s++) {
                AbstractInsnNode q = nthVar(S, s, vt, load, atOrd == null ? 0 : atOrd);
                if (q == null) continue;
                String qs = load ? slotProducers(S, q).get(s) : producer(prev(q));
                if (sig != null && sig.equals(qs)) pc.add(s);
            }
            if (pc.size() != 1) throw new Unfixable("ModifyVariable: producer " + sig + " of clean slot " + cleanSlot + " matches " + pc + " in " + S.name);
            int ns = pc.iterator().next();
            if (ns == cleanSlot && idx != null) return null;
            pinIndex(inj, ns);
            return "ModifyVariable " + av + " local slot " + cleanSlot + " -> index " + ns + " (producer " + sig + ")";
        }
        // INVOKE/FIELD/other point: locals in scope at the point
        AbstractInsnNode cp = cleanTarget == null ? null : nth(cm, cleanTarget, atOrd == null ? 0 : atOrd);
        AbstractInsnNode pp = newTarget == null ? null : nth(S, newTarget, atOrd == null ? 0 : atOrd);
        if (cp == null || pp == null) throw new Unfixable("ModifyVariable: point " + av + " not resolvable");
        cleanSlot = idx != null ? idx : ordinalLocal(cm, cp, vt, ord == null ? -1 : ord, firstLocalC, true);
        if (cleanSlot < 0) throw new Unfixable("ModifyVariable: clean local not resolvable");
        sig = slotProducers(cm, cp).get(cleanSlot);
        Set<Integer> pc = new TreeSet<>();
        for (var e : slotProducers(S, pp).entrySet()) if (e.getKey() >= firstLocalS && e.getValue().equals(sig) && localType(S, e.getKey(), pp, vt)) pc.add(e.getKey());
        if (pc.size() != 1) throw new Unfixable("ModifyVariable: producer " + sig + " of clean slot " + cleanSlot + " matches " + pc + " in " + S.name);
        int ns = pc.iterator().next();
        if (ns == cleanSlot && idx != null) return null;
        pinIndex(inj, ns);
        return "ModifyVariable local slot " + cleanSlot + " -> index " + ns + " (producer " + sig + ")";
    }
    static void pinIndex(AnnotationNode inj, int slot) {
        for (int i = 0; i < inj.values.size(); i += 2) if (inj.values.get(i).equals("ordinal") || inj.values.get(i).equals("name")) { inj.values.remove(i); inj.values.remove(i); i -= 2; }
        setValue(inj, "index", slot);
    }
    static int argEnd(MethodNode m) { int s = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1; for (Type t : Type.getArgumentTypes(m.desc)) s += t.getSize(); return s; }
    static boolean isVarOp(VarInsnNode v, Type t, boolean load) { return v.getOpcode() == t.getOpcode(load ? Opcodes.ILOAD : Opcodes.ISTORE); }
    static AbstractInsnNode nthVar(MethodNode m, int slot, Type t, boolean load, int n) {
        for (AbstractInsnNode i : m.instructions) if (i instanceof VarInsnNode v && v.var == slot && isVarOp(v, t, load) && n-- == 0) return i;
        return null;
    }
    static AbstractInsnNode nth(MethodNode m, String target, int n) { for (AbstractInsnNode i : m.instructions) if (matches(i, target) && n-- == 0) return i; return null; }
    /** Ordinal-th local (not an argument) of type t in scope at insn, by LVT, in slot order; the only one if ordinal < 0. */
    static int ordinalLocal(MethodNode m, AbstractInsnNode insn, Type t, int ordinal, int firstLocal, boolean atInsn) {
        if (m.localVariables == null || m.localVariables.isEmpty()) return -1;
        int pos = m.instructions.indexOf(insn);
        TreeSet<Integer> in = new TreeSet<>();
        for (LocalVariableNode lv : m.localVariables) {
            if (lv.index < firstLocal || !Type.getType(lv.desc).equals(t)) continue;
            int s = m.instructions.indexOf(lv.start), e = m.instructions.indexOf(lv.end);
            // a STORE starts the variable's range just after itself
            if ((s <= pos || (!atInsn && s <= pos + 2)) && e > pos) in.add(lv.index);
        }
        List<Integer> l = new ArrayList<>(in);
        if (ordinal < 0) return l.size() == 1 ? l.get(0) : -1;
        return ordinal < l.size() ? l.get(ordinal) : -1;
    }
    static boolean localType(MethodNode m, int slot, AbstractInsnNode at, Type t) {
        if (m.localVariables == null) return true;
        int pos = m.instructions.indexOf(at);
        for (LocalVariableNode lv : m.localVariables) if (lv.index == slot && m.instructions.indexOf(lv.start) <= pos && m.instructions.indexOf(lv.end) > pos) return Type.getType(lv.desc).equals(t);
        return false;
    }

    /** Bridge manifest: point a handler's injector at another method of the target (selector as written in the manifest). */
    public static byte[] retargetInjector(byte[] cls, String handler, String method) {
        ClassNode n = node(cls); boolean done = false;
        for (MethodNode m : n.methods) if (handler.contains("(") ? (m.name + m.desc).equals(handler) : m.name.equals(handler)) {
            AnnotationNode a = injector(m); if (a != null) { setValue(a, "method", new ArrayList<>(List.of(method.split(",")))); done = true; }   // several targets: comma-separated
        }
        if (!done) return null;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); n.accept(cw); return cw.toByteArray();
    }

    /** Bridge manifest strip with invoker: an abstract @Accessor becomes @Invoker(invoker) (a method the bridge adds to the target). */
    public static byte[] accessorToInvoker(byte[] cls, String handler, String invoker) {
        ClassNode n = node(cls); boolean done = false;
        for (MethodNode m : n.methods) if ((handler.contains("(") ? (m.name + m.desc).equals(handler) : m.name.equals(handler)) && (m.access & Opcodes.ACC_ABSTRACT) != 0) {
            for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
                if (a.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) { a.desc = "Lorg/spongepowered/asm/mixin/gen/Invoker;"; a.values = new ArrayList<>(List.of("value", invoker)); done = true; }
        }
        if (!done) return null;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); n.accept(cw); return cw.toByteArray();
    }

    /** Compat rule "drop_injector": the handler keeps existing as a plain method, Mixin no longer injects it. null if not found. */
    public static byte[] dropInjector(byte[] cls, String handler) {
        ClassNode n = node(cls); boolean done = false;
        // an abstract @Accessor / @Invoker whose target NeoForge removed: drop the method itself (callers are re-based)
        // handler "name" or "name(desc)" (several handlers can share a name)
        java.util.function.Predicate<MethodNode> is = m -> handler.contains("(") ? (m.name + m.desc).equals(handler) : m.name.equals(handler);
        if (n.methods.removeIf(m -> is.test(m) && (m.access & Opcodes.ACC_ABSTRACT) != 0 && genAnnotation(m))) done = true;
        for (MethodNode m : n.methods) if (is.test(m)) { AnnotationNode a = injector(m); if (a != null) {
            if (m.visibleAnnotations != null) m.visibleAnnotations.removeIf(x -> x == a);
            if (m.invisibleAnnotations != null) m.invisibleAnnotations.removeIf(x -> x == a);
            done = true; } }
        if (!done) return null;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS); n.accept(cw); return cw.toByteArray();
    }

    Result fixMoved(ClassNode mixin, MethodNode h, AnnotationNode inj, AnnotationNode at, String atTarget, String newTarget, String newAtValue, ClassNode cleanC,
                    MethodNode cm, MethodNode pm, MethodNode n, MethodInsnNode call, String mixinName, String tgt) {
        String cat = "moved_to_new_method";
        Type[] hArgs = Type.getArgumentTypes(h.desc), mArgs = Type.getArgumentTypes(cm.desc);
        boolean hStatic = (h.access & Opcodes.ACC_STATIC) != 0, mStatic = (cm.access & Opcodes.ACC_STATIC) != 0;
        boolean[] used = usedParams(h, hArgs, hStatic);
        // provenance of each handler parameter in CLEAN M at the injection point
        AbstractInsnNode cleanPoint = first(cm, atTarget);
        Map<Integer, String> cleanSlotSig = slotProducers(cm, cleanPoint);
        String[] want = new String[hArgs.length];
        List<AnnotationNode>[] pAnn = h.invisibleParameterAnnotations;
        int ciIndex = -1;
        for (int i = 0; i < hArgs.length; i++) {
            if (i < mArgs.length) { want[i] = "arg:" + i; continue; }
            String d = hArgs[i].getInternalName();
            if (hArgs[i].getSort() == Type.OBJECT && (d.equals(CI) || d.equals(CIR))) { ciIndex = i; want[i] = "ci"; continue; }
            AnnotationNode loc = pAnn != null && i < pAnn.length && pAnn[i] != null ? pAnn[i].stream().filter(a -> a.desc.equals(LOCAL)).findFirst().orElse(null) : null;
            if (loc == null) return new Result(mixinName, tgt, h.name, cat, false, "handler param " + i + " is not a @Local (LocalCapture not handled yet)");
            Integer ord = (Integer) value(loc, "ordinal"); Integer idx = (Integer) value(loc, "index");
            int slot = idx != null ? idx : localSlot(cm, cleanPoint, hArgs[i], ord == null ? -1 : ord, mStatic, mArgs);
            int as = mStatic ? 0 : 1, argIdx = -1;
            for (int k = 0; k < mArgs.length; k++) { if (as == slot) argIdx = k; as += mArgs[k].getSize(); }
            want[i] = slot < 0 ? null : argIdx >= 0 ? "arg:" + argIdx : cleanSlotSig.get(slot);
        }
        if (ciIndex >= 0 && used[ciIndex]) return new Result(mixinName, tgt, h.name, cat, false, "handler uses its CallbackInfo (cancel/return value): not bridgeable");
        // what patched M passes to N
        Type[] nArgs = Type.getArgumentTypes(n.desc);
        String[] nSig = argSources(pm, call, nArgs.length, mStatic, mArgs);
        int[] map = new int[hArgs.length]; Arrays.fill(map, -1);
        boolean[] taken = new boolean[nArgs.length];
        StringBuilder log = new StringBuilder(); boolean approx = false;
        for (int i = 0; i < hArgs.length; i++) {   // pass 1: exact provenance
            if (!used[i] || i == ciIndex || want[i] == null) continue;
            for (int k = 0; k < nSig.length; k++) if (!taken[k] && want[i].equals(nSig[k]) && nArgs[k].getDescriptor().equals(hArgs[i].getDescriptor())) { map[i] = k; taken[k] = true; break; }
        }
        for (int i = 0; i < hArgs.length; i++) {   // pass 2: the only remaining parameter of the same type (approximate; logged)
            if (!used[i] || i == ciIndex || map[i] >= 0) continue;
            int only = -1, cnt = 0;
            for (int k = 0; k < nArgs.length; k++) if (!taken[k] && nArgs[k].getDescriptor().equals(hArgs[i].getDescriptor())) { only = k; cnt++; }
            if (cnt != 1) return new Result(mixinName, tgt, h.name, cat, false, "value '" + want[i] + "' of handler param " + i + " is not passed to " + n.name + " (want=" + Arrays.toString(want) + " passed=" + Arrays.toString(nSig) + " used=" + Arrays.toString(used) + ")");
            map[i] = only; taken[only] = true; approx = true;
            log.append("[approx: ").append(i).append(" was ").append(want[i]).append("] ");
        }
        for (int i = 0; i < hArgs.length; i++) if (map[i] >= 0) log.append(i).append("<-").append(n.name).append(".arg").append(map[i]).append(" ");
        // bridge handler injected into N
        boolean nVoid = Type.getReturnType(n.desc).getSort() == Type.VOID;
        String bDesc = Type.getMethodDescriptor(Type.VOID_TYPE, concat(nArgs, Type.getObjectType(nVoid ? CI : CIR)));
        MethodNode b = new MethodNode(Opcodes.ACC_PRIVATE | (h.access & Opcodes.ACC_STATIC) | Opcodes.ACC_SYNTHETIC, "amber$bridge$" + h.name, bDesc, null, null);
        boolean nStatic = (n.access & Opcodes.ACC_STATIC) != 0;
        if (!hStatic) b.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        int[] nSlots = new int[nArgs.length]; int s = nStatic ? 0 : 1;
        if (hStatic != nStatic) return new Result(mixinName, tgt, h.name, cat, false, "static-ness differs between handler and " + n.name);
        for (int k = 0; k < nArgs.length; k++) { nSlots[k] = s; s += nArgs[k].getSize(); }
        for (int i = 0; i < hArgs.length; i++) {
            if (map[i] >= 0) b.instructions.add(new VarInsnNode(nArgs[map[i]].getOpcode(Opcodes.ILOAD), nSlots[map[i]]));
            else b.instructions.add(defaultValue(hArgs[i]));
        }
        b.instructions.add(new MethodInsnNode(hStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, mixin.name, h.name, h.desc, false));
        b.instructions.add(new InsnNode(Opcodes.RETURN));
        AnnotationNode bi = new AnnotationNode(INJECT);
        bi.values = new ArrayList<>(List.of("method", List.of(n.name + n.desc)));
        AnnotationNode bAt = new AnnotationNode(at.desc);
        bAt.values = new ArrayList<>(List.of("value", newAtValue, "target", newTarget));
        for (String k : List.of("shift", "by")) if (value(at, k) != null) bAt.values.addAll(List.of(k, value(at, k)));
        bi.values.add("at"); bi.values.add(List.of(bAt));
        b.visibleAnnotations = new ArrayList<>(List.of(bi));
        mixin.methods.add(b);
        // original handler: no longer an injector
        if (h.visibleAnnotations != null) h.visibleAnnotations.removeIf(a -> a == inj);
        if (h.invisibleAnnotations != null) h.invisibleAnnotations.removeIf(a -> a == inj);
        if (h.invisibleParameterAnnotations != null) for (List<AnnotationNode> l : h.invisibleParameterAnnotations) if (l != null) l.removeIf(a -> a.desc.equals(LOCAL));
        return new Result(mixinName, tgt, h.name, approx ? cat + "(approx)" : cat, true, "bridge " + b.name + " -> " + n.name + n.desc + " @ " + newTarget + "; " + log.toString().trim());
    }

    /** Can the @At target change from oldDesc to newDesc without touching the handler? null = yes, else the reason. */
    static String callSiteCompatible(String kind, AnnotationNode inj, MethodNode h, String oldDesc, String newDesc) {
        switch (kind) {
            case "Inject": return null;   // handler sees the target method's args, not the call's
            case "ModifyExpressionValue": case "ModifyReturnValue":
                return Type.getReturnType(oldDesc).equals(Type.getReturnType(newDesc)) ? null : "return type changed";
            case "ModifyArg": {
                Integer idx = (Integer) value(inj, "index");
                Type[] o = Type.getArgumentTypes(oldDesc), n = Type.getArgumentTypes(newDesc);
                if (idx == null) { Type ht = Type.getReturnType(h.desc); long cnt = Arrays.stream(o).filter(ht::equals).count(), cn = Arrays.stream(n).filter(ht::equals).count();
                    return cnt == 1 && cn == 1 ? null : "implicit index no longer unique"; }
                return idx < n.length && idx < o.length && o[idx].equals(n[idx]) && !hasSugarParams(h) && Type.getArgumentTypes(h.desc).length == 1 ? null : "argument at index " + idx + " changed or handler captures the full arg list";
            }
            default: return "handler signature depends on the call descriptor (" + kind + "): not rewritten yet";
        }
    }
    static boolean hasSugarParams(MethodNode h) {
        if (h.invisibleParameterAnnotations != null) for (List<AnnotationNode> l : h.invisibleParameterAnnotations) if (l != null) for (AnnotationNode a : l) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) return true;
        if (h.visibleParameterAnnotations != null) for (List<AnnotationNode> l : h.visibleParameterAnnotations) if (l != null) for (AnnotationNode a : l) if (a.desc.startsWith("Lcom/llamalad7/mixinextras/sugar/")) return true;
        return false;
    }
    static boolean lambdaRef(MethodNode m, String owner, MethodNode n) {
        for (AbstractInsnNode i : m.instructions) if (i instanceof InvokeDynamicInsnNode d) for (Object a : d.bsmArgs)
            if (a instanceof Handle hd && hd.getOwner().equals(owner) && hd.getName().equals(n.name) && hd.getDesc().equals(n.desc)) return true;
        return false;
    }
    static void setValue(AnnotationNode a, String k, Object v) {
        if (a.values == null) a.values = new ArrayList<>();
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(k)) { a.values.set(i + 1, v); return; }
        a.values.add(k); a.values.add(v);
    }

    // ---- analysis helpers ---------------------------------------------------------------------------------------------

    /** Which handler parameters the handler body actually reads. */
    static boolean[] usedParams(MethodNode h, Type[] args, boolean isStatic) {
        boolean[] used = new boolean[args.length];
        int[] slot = new int[args.length]; int s = isStatic ? 0 : 1;
        for (int i = 0; i < args.length; i++) { slot[i] = s; s += args[i].getSize(); }
        for (AbstractInsnNode i : h.instructions) if (i instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ILOAD && v.getOpcode() <= Opcodes.ALOAD)
            for (int k = 0; k < args.length; k++) if (slot[k] == v.var) used[k] = true;
        return used;
    }

    /** slot -> signature of the instruction that last produced the value stored there, scanning up to (not including) point. */
    static Map<Integer, String> slotProducers(MethodNode m, AbstractInsnNode point) {
        Map<Integer, String> sig = new HashMap<>();
        for (AbstractInsnNode i = m.instructions.getFirst(); i != null && i != point; i = i.getNext()) {
            if (i instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ISTORE && v.getOpcode() <= Opcodes.ASTORE) sig.put(v.var, producer(prev(i)));
        }
        return sig;
    }
    static String producer(AbstractInsnNode p) {
        if (p instanceof MethodInsnNode mi) return "call:" + mi.owner + "." + mi.name + mi.desc;
        if (p instanceof FieldInsnNode fi) return "field:" + fi.owner + "." + fi.name;
        if (p instanceof TypeInsnNode ti) return "type:" + ti.getOpcode() + ":" + ti.desc;
        if (p instanceof VarInsnNode vi) return "var:" + vi.var;
        return "insn:" + (p == null ? -1 : p.getOpcode());
    }

    /** Signature of each argument patched M passes to the call (args of M stay "arg:i" even when re-stored, e.g. pos = pos.immutable()). */
    static String[] argSources(MethodNode m, MethodInsnNode call, int n, boolean mStatic, Type[] mArgs) {
        Map<Integer, String> producers = slotProducers(m, call);
        Map<Integer, Integer> argOfSlot = new HashMap<>(); int s = mStatic ? 0 : 1;
        for (int i = 0; i < mArgs.length; i++) { argOfSlot.put(s, i); s += mArgs[i].getSize(); }
        String[] out = new String[n];
        AbstractInsnNode p = call.getPrevious();
        for (int k = n - 1; k >= 0 && p != null; k--, p = prev(p)) {
            while (p != null && (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode)) p = p.getPrevious();
            if (p instanceof VarInsnNode v && v.getOpcode() >= Opcodes.ILOAD && v.getOpcode() <= Opcodes.ALOAD) out[k] = argOfSlot.containsKey(v.var) ? "arg:" + argOfSlot.get(v.var) : producers.get(v.var);
            else return out;   // not a simple local/arg load: stop (remaining stay null)
        }
        return out;
    }

    /** MixinExtras @Local slot: locals (not args) of the given type live at the point, by ordinal (or the only one). */
    static int localSlot(MethodNode m, AbstractInsnNode point, Type t, int ordinal, boolean isStatic, Type[] mArgs) {
        // MixinExtras @Local ordinals count every local of the type in slot order, method arguments included
        TreeMap<Integer, Boolean> cand = new TreeMap<>();
        int s = isStatic ? 0 : 1;
        for (Type a : mArgs) { if (a.equals(t)) cand.put(s, true); s += a.getSize(); }
        int firstLocal = s;
        for (var e : slotProducers(m, point).entrySet()) if (e.getKey() >= firstLocal && typeOf(e.getValue(), t)) cand.put(e.getKey(), true);
        List<Integer> cands = new ArrayList<>(cand.keySet());
        if (ordinal < 0) return cands.size() == 1 ? cands.get(0) : -1;
        return ordinal < cands.size() ? cands.get(ordinal) : -1;
    }
    static boolean typeOf(String producer, Type t) {
        if (producer.startsWith("call:")) return Type.getReturnType(producer.substring(producer.indexOf('('))).equals(t);
        if (producer.startsWith("type:")) return producer.endsWith(":" + t.getInternalName());
        return false;
    }

    static AbstractInsnNode prev(AbstractInsnNode i) {
        AbstractInsnNode p = i.getPrevious();
        while (p != null && (p instanceof LabelNode || p instanceof LineNumberNode || p instanceof FrameNode)) p = p.getPrevious();
        return p;
    }
    /** The patched lambda that is clean lambda cm: same outer method and descriptor, best Jaccard over the call/field
     *  targets it uses (>= 0.6 and a clear winner), else null. */
    static MethodNode sameLambda(MethodNode cm, ClassNode patchedC) {
        String outer = cm.name.substring(0, cm.name.lastIndexOf('$') + 1);
        Set<String> want = refs(cm);
        MethodNode best = null; double bs = -1, second = -1;
        for (MethodNode m : patchedC.methods) {
            if (!m.name.startsWith(outer) || !m.desc.equals(cm.desc) || (m.access & Opcodes.ACC_STATIC) != (cm.access & Opcodes.ACC_STATIC)) continue;
            Set<String> got = refs(m), inter = new HashSet<>(want); inter.retainAll(got);
            Set<String> uni = new HashSet<>(want); uni.addAll(got);
            double s = uni.isEmpty() ? 1 : (double) inter.size() / uni.size();
            if (s > bs) { second = bs; bs = s; best = m; } else if (s > second) second = s;
        }
        return best != null && bs >= 0.6 && bs - second >= 0.2 ? best : null;
    }

    static Set<String> refs(MethodNode m) {
        Set<String> r = new HashSet<>();
        for (AbstractInsnNode i : m.instructions) {
            if (i instanceof MethodInsnNode mi) r.add(mi.owner + "." + mi.name + mi.desc);
            else if (i instanceof FieldInsnNode fi) r.add(fi.owner + "." + fi.name);
        }
        return r;
    }

    static int count(MethodNode m, String target) { int c = 0; for (AbstractInsnNode i : m.instructions) if (matches(i, target)) c++; return c; }
    static AbstractInsnNode first(MethodNode m, String target) { for (AbstractInsnNode i : m.instructions) if (matches(i, target)) return i; return null; }
    static boolean matches(AbstractInsnNode i, String target) {
        // target: Lowner;name(desc) | Lowner;name:desc
        if (i instanceof MethodInsnNode mi) return target.equals("L" + mi.owner + ";" + mi.name + mi.desc);
        if (i instanceof FieldInsnNode fi) return target.equals("L" + fi.owner + ";" + fi.name + ":" + fi.desc);
        return false;
    }
    static MethodInsnNode callTo(MethodNode m, String owner, MethodNode n) {
        for (AbstractInsnNode i : m.instructions) if (i instanceof MethodInsnNode mi && mi.owner.equals(owner) && mi.name.equals(n.name) && mi.desc.equals(n.desc)) return mi;
        return null;
    }
    static MethodNode find(ClassNode c, String sel) {
        int p = sel.indexOf('(');
        if (p >= 0) { for (MethodNode m : c.methods) if ((m.name + m.desc).equals(sel)) return m; return null; }
        for (MethodNode m : c.methods) if (m.name.equals(sel)) return m;   // name-only selector: Mixin binds the first in class order
        return null;
    }
    String resolve(String mixinInternal, String s) {
        JsonObject t = refmaps.get(mixinInternal);
        if (t != null && t.has(s)) s = t.get(s).getAsString();
        if (intermediary != null && (s.contains("class_") || s.contains("method_") || s.contains("field_"))) s = Baker.remapRef(s, intermediary);
        return s;
    }
    /** Method selector -> "name(desc)" or "name": drop an owner prefix "Lowner;" and wildcard/quantifier suffixes. */
    static String selector(String s) {
        if (s.startsWith("L") && s.indexOf(';') > 0 && (s.indexOf('(') < 0 || s.indexOf(';') < s.indexOf('('))) s = s.substring(s.indexOf(';') + 1);
        int q = s.indexOf('{'); if (q >= 0) s = s.substring(0, q);
        if (s.endsWith("*")) s = s.substring(0, s.length() - 1);
        return s;
    }
    static boolean genAnnotation(MethodNode m) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/gen/")) return true;
        return false;
    }
    static AnnotationNode injector(MethodNode m) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l)
            if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !a.desc.contains("/At;") || a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) return a;
        return null;
    }
    static Object value(AnnotationNode a, String k) { if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(k)) return a.values.get(i + 1); return null; }
    @SuppressWarnings("unchecked") static List<String> strings(Object o) { return o == null ? List.of() : (List<String>) o; }
    @SuppressWarnings("unchecked") static List<AnnotationNode> annos(Object o) { return o == null ? List.of() : o instanceof AnnotationNode a ? List.of(a) : (List<AnnotationNode>) o; }
    static String simple(String desc) { return desc.substring(desc.lastIndexOf('/') + 1, desc.length() - 1); }
    static Type[] concat(Type[] a, Type b) { Type[] r = Arrays.copyOf(a, a.length + 1); r[a.length] = b; return r; }
    static AbstractInsnNode defaultValue(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> new InsnNode(Opcodes.ICONST_0);
            case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
            case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
            case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
            default -> new InsnNode(Opcodes.ACONST_NULL);
        };
    }
    static ClassNode node(byte[] b) { ClassNode n = new ClassNode(); new ClassReader(b).accept(n, 0); return n; }

    // ---- CLI: java amber.ul.bake.MixinFixer <census.json> <clean jar> <patched jar> <out fixes.jar> <baked mod jars...> ----
    public static void main(String[] a) throws Exception {
        JsonObject census = JsonParser.parseString(Files.readString(Path.of(a[0]))).getAsJsonObject();
        Map<String, byte[]> clean = classes(Path.of(a[1])), patched = classes(Path.of(a[2]));
        Map<String, byte[]> mixins = new HashMap<>(); Map<String, JsonObject> refmaps = new HashMap<>();
        for (int i = 4; i < a.length; i++) loadMod(Path.of(a[i]), mixins, refmaps);
        MixinFixer f = new MixinFixer(clean, patched, mixins, refmaps);
        String mp = System.getProperty("amber.ul.mappings");
        if (mp != null) f.intermediary = new Mappings.Set3(Path.of(mp)).get("intermediary", "named");
        for (JsonElement e : census.getAsJsonArray("failures")) {
            JsonObject o = e.getAsJsonObject();
            f.handle(o.get("mixin").getAsString(), o.get("target").getAsString(), o.has("handler") && !o.get("handler").isJsonNull() ? o.get("handler").getAsString() : null);
        }
        if (census.has("noScan")) for (JsonElement e : census.getAsJsonArray("noScan")) f.noScan.add(e.getAsString().replace('.', '/'));
        if (!"false".equals(System.getProperty("amber.ul.fixer.scan"))) f.scanDelegators();
        Path out = Path.of(a[3]); Files.createDirectories(out.toAbsolutePath().getParent());
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + "." + System.nanoTime() + ".tmp");
        try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (var e : f.fixedClasses.entrySet()) { o.putNextEntry(SafeFiles.entry(e.getKey() + ".class")); o.write(e.getValue()); o.closeEntry(); }
            o.putNextEntry(SafeFiles.entry("amber-fixes.json"));
            o.write(new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(f.results).getBytes(StandardCharsets.UTF_8)); o.closeEntry();
        }
        Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Map<String, Integer> cats = new TreeMap<>(); int fixed = 0;
        for (Result r : f.results) { cats.merge(r.category(), 1, Integer::sum); if (r.fixed()) fixed++; }
        System.out.println("fixer: " + f.results.size() + " injectors analysed, fixed " + fixed + ", categories " + cats);
        for (Result r : f.results) System.out.println((r.fixed() ? "  FIXED " : "  -     ") + r.category() + " " + r.mixin().substring(r.mixin().lastIndexOf('.') + 1) + "#" + r.handler() + " -> " + r.target().substring(r.target().lastIndexOf('.') + 1) + " : " + r.detail());
    }
    static Map<String, byte[]> classes(Path jar) throws IOException {
        Map<String, byte[]> m = new HashMap<>();
        try (JarFile jf = new JarFile(jar.toFile(), false)) { for (JarEntry e : Collections.list(jf.entries())) if (e.getName().endsWith(".class")) m.put(e.getName().substring(0, e.getName().length() - 6), jf.getInputStream(e).readAllBytes()); }
        return m;
    }
    static void loadMod(Path jar, Map<String, byte[]> mixins, Map<String, JsonObject> refmaps) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile(), false)) {
            for (JarEntry e : Collections.list(jf.entries())) {
                String n = e.getName();
                if (n.endsWith(".class")) mixins.put(n.substring(0, n.length() - 6), jf.getInputStream(e).readAllBytes());
                else if (n.endsWith(".json") && !n.contains("/")) try {
                    JsonObject o = JsonParser.parseString(new String(jf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    if (o.has("mappings") && o.get("mappings").isJsonObject()) for (var x : o.getAsJsonObject("mappings").entrySet()) refmaps.put(x.getKey(), x.getValue().getAsJsonObject());
                } catch (Exception ignored) {}
            }
        }
    }
}
