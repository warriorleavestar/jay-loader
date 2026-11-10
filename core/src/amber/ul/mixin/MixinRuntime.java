package amber.ul.mixin;

import amber.ul.model.ModInfo;
import amber.ul.runtime.*;
import java.lang.reflect.Method;
import java.util.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigSource;
import org.spongepowered.asm.mixin.transformer.Config;

/** Boots real Mixin (Fabric's sponge-mixin fork) on top of the UniversalClassLoader. */
public final class MixinRuntime {
    private MixinRuntime() {}

    public static final List<Config> CONFIGS = new ArrayList<>();

    /** Fabric Loader 0.20.1's table (FabricMixinVersions): lower bound of the mod's fabricloader dependency -> Mixin compat level. */
    static final String[][] COMPAT = {{"0.20.0", "17005"}, {"0.19.4", "17004"}, {"0.19.0", "17001"}, {"0.18.4", "17000"}, {"0.17.3", "16005"}, {"0.16.0", "14000"}, {"0.12.0-", "10000"}};
    static int compat(ModInfo m) {
        if (m.kind == amber.ul.model.LoaderKind.NEOFORGE || m.kind == amber.ul.model.LoaderKind.FORGE) return 14000;   // FML never decorates; NeoForge 21.1 ran Mixin 0.16.4
        String min = null;
        for (amber.ul.model.Dependency d : m.deps) if (d.required() && (d.modId().equals("fabricloader") || d.modId().equals("fabric-loader"))) min = lowerBound(d.range());
        if (min == null) return 9002;
        for (String[] e : COMPAT) if (amber.ul.resolve.Versions.compare(min, e[0]) >= 0) return Integer.parseInt(e[1]);
        return 9002;
    }
    static String lowerBound(String range) {
        for (String part : range.trim().split("\\s+")) {
            String p = part.replaceFirst("^(>=|\\^|~|=)", "");
            if (part.startsWith("<") || part.equals("*") || p.isEmpty()) continue;
            if (part.startsWith(">") && !part.startsWith(">=")) continue;
            return p.replace(".x", ".0").replace(".*", ".0");
        }
        return null;
    }

    /** dedicated server: Mixin side SERVER, so "client" mixin lists are skipped (as on Fabric). */
    public static boolean server;
    /**
     * M4 Forge platform: Forge mods target Forge's own patched game, so Mixin's own checks (require, @Group min/max) decide
     * exactly what real Forge decides. countInjections (lenient/census) would also report handlers real Forge accepts:
     * require=0 handlers (ModernFix) and the unused branch of a @Group pair (Create/Quark OptiFine variants), and isolation
     * then removed the whole mixin class. Off on Forge; NeoForge/Fabric keep it (the fixer needs silent misses).
     */
    public static boolean forge;

    public static void boot(UniversalClassLoader cl, List<ModInfo> mods) throws Exception {
        AmberMixinService.setLoader(cl);
        if (AmberMixinErrorHandler.lenient() && !forge) System.setProperty("mixin.debug.countInjections", "true");
        MixinBootstrap.init();
        Mixins.registerErrorHandlerClass(AmberMixinErrorHandler.class.getName());
        MixinEnvironment.getDefaultEnvironment().setSide(server ? MixinEnvironment.Side.SERVER : MixinEnvironment.Side.CLIENT); // as FabricMixinBootstrap does; else "client" mixins are skipped
        Map<String, String> configToMod = new HashMap<>();
        for (ModInfo m : mods) for (String c : m.mixinConfigs) {
            configToMod.put(c, m.id);
            // Fabric/Quilt mods on Forge: no countInjections there (see forge), so in lenient mode an injector whose target
            // Forge's patch replaced passes silently (Fabric API's sleep-time redirect). -Damber.ul.strictFabricRequire=true
            // (census.py --strict-require) keeps their own require so such misses are reported as a real launch would hit them
            if (forge && Boolean.getBoolean("amber.ul.strictFabricRequire") && (m.kind == amber.ul.model.LoaderKind.FABRIC || m.kind == amber.ul.model.LoaderKind.QUILT)) AmberMixinService.KEEP_REQUIRE.add(c);
            Mixins.addConfiguration(c, new IMixinConfigSource() {
                public String getId() { return m.id; }
                public String getDescription() { return m.id + " " + m.version; }
            });
        }
        for (Config c : Mixins.getConfigs()) {
            String id = configToMod.get(c.getName());
            if (id != null && !c.getConfig().hasDecoration(FabricUtil.KEY_MOD_ID)) c.getConfig().decorate(FabricUtil.KEY_MOD_ID, id);
            ModInfo owner = id == null ? null : mods.stream().filter(m -> m.id.equals(id)).findFirst().orElse(null);
            if (owner != null && !c.getConfig().hasDecoration(FabricUtil.KEY_COMPATIBILITY)) c.getConfig().decorate(FabricUtil.KEY_COMPATIBILITY, compat(owner));
        }
        AmberMixinService.transformer(); // creating it registers the active transformer MixinExtras hooks into
        CONFIGS.addAll(Mixins.getConfigs());   // our own handles: select() removes them from Mixins.getConfigs() (brief 5.2)
        com.llamalad7.mixinextras.MixinExtrasBootstrap.init();
        // same as Fabric's FabricLauncherBase.finishMixinBootstrapping
        Method go = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        go.setAccessible(true);
        go.invoke(null, MixinEnvironment.Phase.INIT);
        go.invoke(null, MixinEnvironment.Phase.DEFAULT);
        // Make Mixin select/prepare configs now (plugin onLoad, shouldApplyMixin, refmaps), before any class can be defined
        // from the class cache without passing through the transformer (phase-2 brief 5.1).
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC, "amber/ul/mixin/SelectTrigger", null, "java/lang/Object", null);
        cw.visitEnd();
        AmberMixinService.transformer().transformClassBytes("amber.ul.mixin.SelectTrigger", "amber.ul.mixin.SelectTrigger", cw.toByteArray());
        cl.generator = name -> AmberMixinService.transformer().generateClass(MixinEnvironment.getDefaultEnvironment(), name);
    }

    /** Mixin's own audit: pushes every not-yet-loaded mixin target through the class loader (and so through Mixin). */
    public static void audit() { AmberMixinService.transformer().audit(MixinEnvironment.getCurrentEnvironment()); }

    /**
     * Census audit: like Mixin's audit, but one target class at a time and fault-tolerant. Errors Mixin throws past the
     * error handler (attach/pre-processor errors are fatal MixinTransformerErrors) are attributed to the mixin named in the
     * message and recorded, and the census continues with the next target.
     */
    public static int censusAudit(ClassLoader cl) {
        Set<String> targets = new TreeSet<>();
        Map<String, String> pkgByConfig = new HashMap<>();
        for (Config c : CONFIGS) { targets.addAll(c.getConfig().getTargets()); pkgByConfig.put(c.getName(), c.getConfig().getMixinPackage()); }
        int fatal = 0;
        for (String t : targets) {
            try { Class.forName(t.replace('/', '.'), false, cl); }
            catch (Throwable e) {
                StringBuilder all = new StringBuilder();
                for (Throwable x = e; x != null; x = x.getCause() == x ? null : x.getCause()) all.append(x.getClass().getSimpleName()).append(": ").append(x.getMessage()).append(" | ");
                String msg = all.toString();
                var m = java.util.regex.Pattern.compile("([A-Za-z0-9_.-]+[.]json):([A-Za-z0-9_.$]+)").matcher(msg);
                String mixin = "?", cfg = "?";
                if (m.find()) { cfg = m.group(1); String pkg = pkgByConfig.getOrDefault(cfg, ""); mixin = pkg.isEmpty() ? m.group(2) : (pkg.endsWith(".") ? pkg : pkg + ".") + m.group(2); }
                String fm = mixin;
                if (AmberMixinErrorHandler.RECORDS.stream().anyMatch(r -> r.mixin().equals(fm) && r.target().equals(t))) continue;
                AmberMixinErrorHandler.RECORDS.add(new AmberMixinErrorHandler.Rec("fatal", "?", cfg, mixin, t, null,
                        msg.contains("Attach") ? "attach_error" : AmberMixinErrorHandler.classify(msg), msg.length() > 1500 ? msg.substring(0, 1500) : msg));
                System.out.println("[amber-ul] Mixin 失败 (fatal) mixin=" + mixin + " 目标=" + t);
                fatal++;
            }
        }
        return fatal;
    }

    /** Classes loaded before Mixin booted (M4: FML 47 + Amber's Forge bridge); never mixin targets in practice. */
    static boolean gameClass(String n) { return n.startsWith("net/minecraft/") || n.startsWith("com/mojang/blaze3d/") || n.startsWith("com/mojang/math/") || n.startsWith("com/mojang/realmsclient/"); }
    public static final java.util.Set<String> preBoot = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public static ClassTransformer transformer(String fingerprint) {
        return new ClassTransformer() {
            public String fingerprint() { return "mixin:" + fingerprint; }
            public byte[] transform(String internalName, byte[] bytes) {
                String n = internalName.replace('/', '.');
                // M4: the Forge FML bridge runs before Mixin boots (mixin plugins read FML). Its own and FML's loading
                // classes pass untransformed; a game class this early would miss its mixins, so that stays an error.
                if (AmberMixinService.factory == null && !gameClass(internalName)) { preBoot.add(internalName); return bytes; }
                return AmberMixinService.transformer().transformClassBytes(n, n, bytes);
            }
        };
    }
}
