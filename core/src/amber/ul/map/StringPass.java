package amber.ul.map;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * String constants that name game classes/members (M2, brief §4.3), conservative on purpose: a string is rewritten only
 * when it is provably the argument of a reflection call (data flow from ASM's SourceInterpreter: the argument has exactly
 * one source, an LDC) and the lookup has one answer. Everything else is kept and, when it looks like a mapped name,
 * logged. Strings that reach Fabric's MappingResolver must stay in the source namespace: they are never touched here.
 *
 * Runs on the INPUT bytes (namespace A), before the renamer, so owners and names are A names.
 *
 * Recognised sites:
 *  - class:  Class.forName(String[, boolean, ClassLoader]), ClassLoader.loadClass(String)
 *  - method: Class.getDeclaredMethod/getMethod(String, Class[]), Lookup.findVirtual/findStatic/findSpecial,
 *            (Neo)Forge ObfuscationReflectionHelper.findMethod
 *  - field:  Class.getDeclaredField/getField(String), Lookup.find(Static)Getter/Setter/VarHandle,
 *            ObfuscationReflectionHelper.findField/getPrivateValue/setPrivateValue
 * The owner is known when the Class argument is an LDC type or a Class.forName of an LDC string.
 */
public final class StringPass {
    final ModNames n;
    public final Map<String, Integer> stats = new ConcurrentHashMap<>();
    /** Category -> samples ("Owner.method: detail"), at most 30 per category. */
    public final Map<String, Set<String>> log = new ConcurrentHashMap<>();
    public StringPass(ModNames n) { this.n = n; }

    /** Member names that only exist in a mapping namespace (intermediary, SRG): globally unique, safe without an owner. */
    static final Pattern MAPPED_MEMBER = Pattern.compile("(?:method|field|comp)_\\d+|[mf]_\\d+_");
    static final Pattern MAPPED_TOKEN = Pattern.compile("class_\\d+|(?:method|field|comp)_\\d+|\\b[mfp]_\\d+_\\b|\\bC_\\d+_\\b");
    static final String[] TRIGGERS = {"forName", "loadClass", "getDeclaredMethod", "getMethod", "getDeclaredField", "getField",
            "findVirtual", "findStatic", "findSpecial", "findGetter", "findSetter", "findStaticGetter", "findStaticSetter", "findVarHandle",
            "findStaticVarHandle", "ObfuscationReflectionHelper"};

    void count(String k) { stats.merge(k, 1, Integer::sum); }
    void note(String cat, String s) { count(cat); Set<String> l = log.computeIfAbsent(cat, k -> ConcurrentHashMap.newKeySet()); if (l.size() < 30) l.add(s); }

    public byte[] apply(byte[] b) {
        if (!any(b)) { keptStrings(b); return b; }
        ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, 0);
        boolean changed = false;
        for (MethodNode mn : cn.methods) changed |= method(cn, mn);
        if (!changed) return b;
        ClassWriter w = new ClassWriter(0); cn.accept(w); return w.toByteArray();
    }

    /** Classes without a reflection site: only the constant pool's strings are looked at (no parse), for the log. */
    void keptStrings(byte[] b) {
        ClassReader r = new ClassReader(b);
        char[] buf = null;
        for (int i = 1; i < r.getItemCount(); i++) {
            int off = r.getItem(i);
            if (off == 0 || b[off - 1] != 8) continue;   // CONSTANT_String
            if (buf == null) buf = new char[r.getMaxStringLength()];
            String str = r.readUTF8(off, buf);
            if (str != null && MAPPED_TOKEN.matcher(str).find())
                note("kept: mapped-looking string", r.getClassName() + ": \"" + (str.length() > 80 ? str.substring(0, 80) + "..." : str) + "\"");
        }
    }

    static boolean any(byte[] b) {
        String s = new String(b, StandardCharsets.ISO_8859_1);
        for (String t : TRIGGERS) if (s.contains(t)) return true;
        return false;
    }

    enum Kind { CLASS, METHOD, FIELD }
    /** Which argument (index into the call's stack values: 0 = receiver for virtual calls) is the Class owner and which the name. */
    record Site(Kind kind, int owner, int name) {}

    static Site site(MethodInsnNode mi) {
        String o = mi.owner, nm = mi.name, d = mi.desc;
        boolean virt = mi.getOpcode() != Opcodes.INVOKESTATIC;
        if (o.equals("java/lang/Class") && nm.equals("forName") && (d.equals("(Ljava/lang/String;)Ljava/lang/Class;") || d.equals("(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"))) return new Site(Kind.CLASS, -1, 0);
        if (virt && nm.equals("loadClass") && d.equals("(Ljava/lang/String;)Ljava/lang/Class;")) return new Site(Kind.CLASS, -1, 1);
        if (o.equals("java/lang/Class") && (nm.equals("getDeclaredMethod") || nm.equals("getMethod")) && d.equals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;")) return new Site(Kind.METHOD, 0, 1);
        if (o.equals("java/lang/Class") && (nm.equals("getDeclaredField") || nm.equals("getField")) && d.equals("(Ljava/lang/String;)Ljava/lang/reflect/Field;")) return new Site(Kind.FIELD, 0, 1);
        if (o.equals("java/lang/invoke/MethodHandles$Lookup") && d.startsWith("(Ljava/lang/Class;Ljava/lang/String;")) {
            if (nm.equals("findVirtual") || nm.equals("findStatic") || nm.equals("findSpecial")) return new Site(Kind.METHOD, 1, 2);
            if (nm.equals("findGetter") || nm.equals("findSetter") || nm.equals("findStaticGetter") || nm.equals("findStaticSetter") || nm.equals("findVarHandle") || nm.equals("findStaticVarHandle")) return new Site(Kind.FIELD, 1, 2);
        }
        if (o.endsWith("/fml/util/ObfuscationReflectionHelper") && !virt) {
            Type[] a = Type.getArgumentTypes(d);
            if (a.length < 2 || !a[0].getDescriptor().equals("Ljava/lang/Class;")) return null;
            int last = a.length - 1;
            if (nm.equals("findMethod") && a[1].getDescriptor().equals("Ljava/lang/String;")) return new Site(Kind.METHOD, 0, 1);
            if (nm.equals("findField") && a[1].getDescriptor().equals("Ljava/lang/String;")) return new Site(Kind.FIELD, 0, 1);
            if ((nm.equals("getPrivateValue") || nm.equals("setPrivateValue")) && a[last].getDescriptor().equals("Ljava/lang/String;")) return new Site(Kind.FIELD, 0, last);
        }
        return null;
    }

    boolean method(ClassNode cn, MethodNode mn) {
        if (mn.instructions.size() == 0) return false;
        List<MethodInsnNode> sites = new ArrayList<>();
        for (AbstractInsnNode i : mn.instructions) if (i instanceof MethodInsnNode mi && site(mi) != null) sites.add(mi);
        Map<LdcInsnNode, String> done = new IdentityHashMap<>();
        if (!sites.isEmpty()) {
            Frame<SourceValue>[] frames;
            try { frames = new Analyzer<>(new SourceInterpreter()).analyze(cn.name, mn); }
            catch (AnalyzerException e) { note("analyzer failed", cn.name + "." + mn.name + mn.desc + ": " + e.getMessage()); frames = null; }
            if (frames != null) for (MethodInsnNode mi : sites) {
                Frame<SourceValue> f = frames[mn.instructions.indexOf(mi)];
                if (f == null) continue;   // dead code
                Site s = site(mi);
                int argc = Type.getArgumentTypes(mi.desc).length + (mi.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1);
                int base = f.getStackSize() - argc;
                LdcInsnNode nameLdc = ldc(f.getStack(base + s.name()), String.class);
                String where = cn.name + "." + mn.name + mn.desc + " -> " + mi.owner + "." + mi.name;
                if (nameLdc == null) { count("site: name not a constant"); continue; }
                String str = (String) nameLdc.cst;
                if (s.kind() == Kind.CLASS) { String r = mapClassString(str, where); if (r != null) done.put(nameLdc, r); continue; }
                String owner = s.owner() < 0 ? null : ownerOf(f.getStack(base + s.owner()), frames, mn);
                String r = mapMember(s.kind() == Kind.FIELD, owner, str, where);
                if (r != null) done.put(nameLdc, r);
            }
        }
        // anything else that looks like a mapped name is left alone (MappingResolver input, log text ...) and logged
        for (AbstractInsnNode i : mn.instructions)
            if (i instanceof LdcInsnNode l && l.cst instanceof String str && !done.containsKey(l) && MAPPED_TOKEN.matcher(str).find())
                note("kept: mapped-looking string", cn.name + "." + mn.name + ": \"" + (str.length() > 80 ? str.substring(0, 80) + "..." : str) + "\"");
        done.forEach((l, r) -> l.cst = r);
        return !done.isEmpty();
    }

    /** The one LDC constant of type c that produced this value, or null. */
    static LdcInsnNode ldc(SourceValue v, Class<?> c) {
        if (v == null || v.insns.size() != 1) return null;
        AbstractInsnNode i = v.insns.iterator().next();
        return i instanceof LdcInsnNode l && c.isInstance(l.cst) ? l : null;
    }

    /** A name of the Class value: LDC type, or Class.forName(LDC string). Null = unknown. */
    String ownerOf(SourceValue v, Frame<SourceValue>[] frames, MethodNode mn) {
        LdcInsnNode t = ldc(v, Type.class);
        if (t != null) { Type ty = (Type) t.cst; return ty.getSort() == Type.OBJECT ? ty.getInternalName() : null; }
        if (v != null && v.insns.size() == 1 && v.insns.iterator().next() instanceof MethodInsnNode mi && mi.owner.equals("java/lang/Class") && mi.name.equals("forName")) {
            Frame<SourceValue> f = frames[mn.instructions.indexOf(mi)];
            if (f == null) return null;
            int argc = Type.getArgumentTypes(mi.desc).length;
            LdcInsnNode s = ldc(f.getStack(f.getStackSize() - argc), String.class);
            return s == null ? null : ((String) s.cst).replace('.', '/');
        }
        return null;
    }

    String mapClassString(String s, String where) {
        boolean dotted = s.indexOf('/') < 0;
        String a = s.replace('.', '/'), b = n.cls(a);
        if (!b.equals(a)) { count("class"); return dotted ? b.replace('/', '.') : b; }
        if (MAPPED_TOKEN.matcher(s).find()) note("class: no mapping", where + " \"" + s + "\"");
        return null;
    }

    String mapMember(boolean field, String owner, String name, String where) {
        String kind = field ? "field" : "method";
        if (owner != null) {
            Set<String> got = field ? n.gameFieldsNamed(owner, name) : n.gameMethodsNamed(owner, name);
            if (got.size() == 1) { String r = got.iterator().next(); if (r.equals(name)) return null; count(kind + " (owner)"); return r; }
            if (got.size() > 1) { note(kind + ": ambiguous", where + " " + owner + "." + name + " -> " + new TreeSet<>(got)); return null; }
        }
        if (!MAPPED_MEMBER.matcher(name).matches()) { if (owner == null) count(kind + ": owner unknown, plain name kept"); return null; }
        String r = field ? n.text().mapField(null, name, null) : n.text().mapMethod(null, name, null);
        if (!r.equals(name)) { count(kind + " (unique name)"); return r; }
        note(kind + ": mapped-looking name not resolved", where + " " + owner + "." + name);
        return null;
    }
}
