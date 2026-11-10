package amber.ul.fmlforge;

import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService.ComputeFlags;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService.Phase;
import java.io.InputStream;
import java.util.*;
import java.util.function.BiFunction;
import net.minecraftforge.api.distmarker.Dist;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;

/**
 * The launch plugins a Forge launch has besides Mixin (which Amber runs itself) and the access transformer service
 * (Amber applies ATs at bake time), run in modlauncher's order: all BEFORE passes, then all AFTER passes, one ClassNode.
 */
final class ForgeLaunchPlugins implements BiFunction<String, byte[], byte[]> {
    static final List<String> PLUGINS = List.of(
            "net.minecraftforge.fml.loading.RuntimeDistCleaner", "net.minecraftforge.fml.common.asm.RuntimeEnumExtender",
            "net.minecraftforge.fml.common.asm.ObjectHolderDefinalize", "net.minecraftforge.fml.common.asm.CapabilityTokenSubclass",
            "net.minecraftforge.eventbus.service.ModLauncherService");
    final List<ILaunchPluginService> plugins = new ArrayList<>();
    final ClassLoader cl;
    final Map<String, Integer> stats = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    ForgeLaunchPlugins(ClassLoader cl, Dist dist) throws ReflectiveOperationException {
        this.cl = cl;
        for (String n : PLUGINS) {
            ILaunchPluginService p = (ILaunchPluginService) Class.forName(n, true, cl).getDeclaredConstructor().newInstance();
            if (p.name().equals("runtimedistcleaner")) ((java.util.function.Consumer<Dist>) p.getExtension()).accept(dist);   // as FMLServiceProvider does
            plugins.add(p); names.add(p.name());
        }
        // warm-up: each plugin loads its own engine classes on first use; do that now, not inside some class's define
        ClassNode probe = new ClassNode(); new ClassReader(EMPTY).accept(probe, 0);
        for (ILaunchPluginService p : plugins) { p.handlesClass(Type.getObjectType("amber/ul/Probe"), false, "classloading"); p.handlesClass(Type.getObjectType("net/minecraftforge/eventbus/api/Event"), false, "classloading"); }
    }
    /** Plugins mods put into Launcher.launchPlugins.plugins at runtime (ModernFix), in map order after ours; re-read when the map changes. */
    private volatile Map<String, ILaunchPluginService> seenMap;
    private volatile List<ILaunchPluginService> active;
    @SuppressWarnings("unchecked") List<ILaunchPluginService> active() {
        Map<String, ILaunchPluginService> m = null;
        try {
            Object lph = LPH.get(cpw.mods.modlauncher.Launcher.INSTANCE);
            if (lph != null) m = (Map<String, ILaunchPluginService>) PLUGIN_MAP.get(lph);
        } catch (ReflectiveOperationException | NullPointerException e) { m = null; }
        List<ILaunchPluginService> a = active;
        if (a != null && m == seenMap && (m == null || a.size() == plugins.size() + countExtra(m))) return a;
        List<ILaunchPluginService> l = new ArrayList<>(plugins);
        if (m != null) for (ILaunchPluginService p : new ArrayList<>(m.values())) if (!names.contains(p.name())) {
            l.add(p);
            if (announced.add(p.name())) System.out.println("[amber-ul] Forge: 模组加入的 launch plugin " + p.name() + " (" + p.getClass().getName() + ")");
        }
        seenMap = m; active = l;
        return l;
    }
    private int countExtra(Map<String, ILaunchPluginService> m) { int n = 0; for (ILaunchPluginService p : m.values()) if (!names.contains(p.name())) n++; return n; }
    final Set<String> names = new HashSet<>();
    final Set<String> announced = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final java.lang.reflect.Field LPH, PLUGIN_MAP;
    static {
        try {
            LPH = cpw.mods.modlauncher.Launcher.class.getDeclaredField("launchPlugins"); LPH.setAccessible(true);
            PLUGIN_MAP = cpw.mods.modlauncher.LaunchPluginHandler.class.getDeclaredField("plugins"); PLUGIN_MAP.setAccessible(true);
        } catch (NoSuchFieldException e) { throw new ExceptionInInitializerError(e); }
    }

    static final byte[] EMPTY; static { ClassWriter w = new ClassWriter(0); w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "amber/ul/Probe", null, "java/lang/Object", null); w.visitEnd(); EMPTY = w.toByteArray(); }


    /** The plugins' own machinery: never theirs to transform (and would re-enter a plugin that is still initialising). */
    static final List<String> SKIP = List.of("amber/", "org/objectweb/asm/", "cpw/mods/", "net/minecraftforge/eventbus/", "net/minecraftforge/fml/loading/", "net/minecraftforge/fml/common/asm/");

    @Override public byte[] apply(String internal, byte[] bytes) {
        for (String p : SKIP) if (internal.startsWith(p)) return bytes;
        return pass(internal, bytes);
    }

    /** modlauncher's empty-class pass: a plugin may answer for a class that exists nowhere (isEmpty = true). */
    byte[] synthesize(String internal) {
        Type t = Type.getObjectType(internal);
        ClassNode cn = new ClassNode(); int flags = ComputeFlags.NO_REWRITE;
        for (Phase ph : List.of(Phase.BEFORE, Phase.AFTER)) for (ILaunchPluginService p : active())
            if (p.handlesClass(t, true, "classloading").contains(ph)) { int f = p.processClassWithFlags(ph, cn, t, "classloading"); if (f != 0) stats.merge(p.name() + "+gen", 1, Integer::sum); flags |= f; }
        if (flags == ComputeFlags.NO_REWRITE || cn.name == null) return null;
        int w = (flags & ComputeFlags.COMPUTE_FRAMES) != 0 ? ClassWriter.COMPUTE_FRAMES : (flags & ComputeFlags.COMPUTE_MAXS) != 0 ? ClassWriter.COMPUTE_MAXS : 0;
        ClassWriter cw = new ClassWriter(w) { @Override protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); } };
        cn.accept(cw);
        return cw.toByteArray();
    }

    private byte[] pass(String internal, byte[] bytes) {
        Type t = Type.getObjectType(internal);
        List<ILaunchPluginService> before = new ArrayList<>(), after = new ArrayList<>();
        for (ILaunchPluginService p : active()) {
            EnumSet<Phase> ph = p.handlesClass(t, false, "classloading");
            if (ph.contains(Phase.BEFORE)) before.add(p);
            if (ph.contains(Phase.AFTER)) after.add(p);
        }
        if (before.isEmpty() && after.isEmpty()) return bytes;
        ClassNode cn = new ClassNode(); new ClassReader(bytes).accept(cn, 0);
        int flags = ComputeFlags.NO_REWRITE;
        for (ILaunchPluginService p : before) { int f = p.processClassWithFlags(Phase.BEFORE, cn, t, "classloading"); if (f != 0) stats.merge(p.name(), 1, Integer::sum); flags |= f; }
        for (ILaunchPluginService p : after) { int f = p.processClassWithFlags(Phase.AFTER, cn, t, "classloading"); if (f != 0) stats.merge(p.name(), 1, Integer::sum); flags |= f; }
        if (flags == ComputeFlags.NO_REWRITE) return bytes;
        int w = (flags & ComputeFlags.COMPUTE_FRAMES) != 0 ? ClassWriter.COMPUTE_FRAMES : (flags & ComputeFlags.COMPUTE_MAXS) != 0 ? ClassWriter.COMPUTE_MAXS : 0;
        ClassWriter cw = new ClassWriter(w) {
            @Override protected String getCommonSuperClass(String a, String b) { return commonSuper(a, b); }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Without loading classes (we are inside the loader's define): walk super names from the class bytes. */
    String commonSuper(String a, String b) {
        List<String> sa = supers(a);
        if (sa.contains(b)) return b;
        for (String s : supers(b)) if (sa.contains(s)) return s;
        return "java/lang/Object";
    }
    List<String> supers(String n) {
        List<String> l = new ArrayList<>();
        while (n != null) {
            l.add(n);
            try (InputStream in = cl.getResourceAsStream(n + ".class")) {
                if (in == null) break;
                ClassReader r = new ClassReader(in);
                if ((r.getAccess() & Opcodes.ACC_INTERFACE) != 0) { l.add("java/lang/Object"); break; }
                n = r.getSuperName();
            } catch (Exception e) { break; }
        }
        if (!l.contains("java/lang/Object")) l.add("java/lang/Object");
        return l;
    }
}
