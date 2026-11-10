package amber.ul.fml;

import amber.ul.runtime.ClassTransformer;
import java.util.function.BiFunction;

/**
 * M4: modlauncher launch plugins (ILaunchPluginService) as a universal-loader transformer. Forge 1.20.1 relies on several
 * for class shapes at load time: eventbus (Event listener lists + public no-arg constructor), RuntimeEnumExtender
 * (IExtensibleEnum.create), CapabilityTokenSubclass, ObjectHolderDefinalize, RuntimeDistCleaner (@OnlyIn). They live in
 * the universal loader, so the Forge bridge installs the actual pass (amber.ul.fmlforge.ForgeLaunchPlugins) once FML is
 * prepared; classes loaded before that (loader internals) pass through, as modlauncher's own service classes do.
 */
public final class LaunchPluginHost implements ClassTransformer {
    public static volatile BiFunction<String, byte[], byte[]> impl;
    /** Classes no jar has that a plugin generates (modlauncher's "empty class" pass: eventbus's __EventDispatcher_* handlers). */
    public static volatile java.util.function.Function<String, byte[]> synth;
    public static void install(amber.ul.runtime.UniversalClassLoader cl) {
        java.util.function.Function<String, byte[]> prev = cl.generator;
        cl.generator = n -> { var f = synth; byte[] b = f == null ? null : f.apply(n.replace('.', '/')); return b != null ? b : prev.apply(n); };
    }
    private final String fp;
    public LaunchPluginHost(String fp) { this.fp = fp; }
    @Override public String fingerprint() { return "launch-plugins-2|" + fp; }
    @Override public byte[] transform(String internalName, byte[] bytes) {
        BiFunction<String, byte[], byte[]> f = impl;
        return f == null ? bytes : f.apply(internalName, bytes);
    }
}
