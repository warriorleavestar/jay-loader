package amber.ul.mixin;

import amber.ul.runtime.UniversalClassLoader;
import java.io.*;
import java.net.URL;
import java.util.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** Mixin service for Amber's UniversalClassLoader (the equivalent of Fabric's MixinServiceKnot). */
public final class AmberMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
    static volatile UniversalClassLoader loader;
    static volatile IMixinTransformerFactory factory;
    static volatile IMixinTransformer transformer;

    public static void setLoader(UniversalClassLoader cl) { loader = cl; }
    public static IMixinTransformer transformer() {
        if (transformer == null) synchronized (AmberMixinService.class) {
            if (transformer == null) {
                if (factory == null) throw new IllegalStateException("Mixin transformer factory was never offered");
                transformer = factory.createTransformer();
            }
        }
        return transformer;
    }

    @Override public String getName() { return "Amber"; }
    @Override public boolean isValid() { return true; }
    @Override public void offer(IMixinInternal internal) { if (internal instanceof IMixinTransformerFactory f) factory = f; super.offer(internal); }
    @Override public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() { return MixinEnvironment.CompatibilityLevel.JAVA_8; }
    @Override public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() { return MixinEnvironment.CompatibilityLevel.JAVA_22; }
    @Override public IClassProvider getClassProvider() { return this; }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return this; }
    @Override public ITransformerProvider getTransformerProvider() { return this; }
    @Override public IClassTracker getClassTracker() { return this; }
    @Override public IMixinAuditTrail getAuditTrail() { return null; }
    @Override public IAdviceProvider getAdviceProvider() { return IAdviceProvider.GENERIC; }   // Mixin 0.17+
    @Override public IFeatureValidator getFeatureValidator() { return IFeatureValidator.ALLOW_ALL; }   // Mixin 0.17+ (as Knot: enum extension allowed)
    @Override public Collection<String> getPlatformAgents() { return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault"); }
    @Override public Collection<IContainerHandle> getMixinContainers() { return List.of(); }
    @Override public IContainerHandle getPrimaryContainer() {
        try { return new ContainerHandleURI(AmberMixinService.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    /** Mixin configs whose own injector require stays in lenient mode (Fabric/Quilt mods on the Forge platform). */
    public static final Set<String> KEEP_REQUIRE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Override public InputStream getResourceAsStream(String name) {
        InputStream in = loader.getResourceAsStream(name);
        boolean lenient = AmberMixinErrorHandler.lenient(), exclude = !AmberMixinErrorHandler.excluded().isEmpty() || !AmberMixinErrorHandler.VETO.isEmpty();
        if (in == null || !(lenient || exclude) || !name.endsWith(".json") || name.contains("/")) return in;
        try (in) {
            String txt = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(txt).getAsJsonObject();
            if (!o.has("package")) return new java.io.ByteArrayInputStream(txt.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (lenient && !KEEP_REQUIRE.contains(name)) {
                // injector "require" failures are fatal InjectionErrors no error handler sees; with defaultRequire 0 +
                // mixin.debug.countInjections they become InvalidInjectionExceptions -> AmberMixinErrorHandler
                com.google.gson.JsonObject inj = o.has("injectors") ? o.getAsJsonObject("injectors") : new com.google.gson.JsonObject();
                inj.addProperty("defaultRequire", 0); o.add("injectors", inj);
            }
            if (exclude) {   // isolation: drop mixins listed in -Damber.ul.mixinExclude so they don't take their target class down
                String pkg = o.get("package").getAsString();
                for (String k : new String[]{"mixins", "client", "server"}) if (o.has(k) && o.get(k).isJsonArray()) {
                    com.google.gson.JsonArray kept = new com.google.gson.JsonArray();
                    for (com.google.gson.JsonElement e : o.getAsJsonArray(k)) {
                        String fqn = pkg + "." + e.getAsString();
                        String veto = AmberMixinErrorHandler.VETO.get(fqn);
                        if (veto != null) { AmberMixinErrorHandler.REMOVED.add(name + ": " + fqn + " (bridged)"); System.out.println("[amber-ul] 桥接: 从 " + name + " 停用 " + fqn + " (" + veto + ")"); }
                        else if (AmberMixinErrorHandler.excluded().contains(fqn)) { AmberMixinErrorHandler.REMOVED.add(name + ": " + fqn); System.out.println("[amber-ul] 隔离: 从 " + name + " 移除 " + fqn); }
                        else kept.add(e);
                    }
                    o.add(k, kept);
                }
            }
            return new java.io.ByteArrayInputStream(o.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) { return loader.getResourceAsStream(name); }
    }

    // IClassProvider
    @Override public URL[] getClassPath() { return new URL[0]; }
    @Override public Class<?> findClass(String name) throws ClassNotFoundException { return loader.loadClass(name); }
    /** Census: classes are only defined (transformed), never initialised: no game state exists. */
    public static volatile boolean noInit;
    @Override public Class<?> findClass(String name, boolean init) throws ClassNotFoundException { return Class.forName(name, init && !noInit, loader); }
    @Override public Class<?> findAgentClass(String name, boolean init) throws ClassNotFoundException { return Class.forName(name, init, AmberMixinService.class.getClassLoader()); }

    // IClassBytecodeProvider: bytes as baked (remapped + access rules), before Mixin
    @Override public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, true, 0); }
    @Override public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException { return getClassNode(name, runTransformers, 0); }
    @Override public ClassNode getClassNode(String name, boolean runTransformers, int flags) throws ClassNotFoundException, IOException {
        byte[] b = loader.classBytes(name.replace('.', '/'));
        if (b == null) throw new ClassNotFoundException(name);
        ClassNode n = new ClassNode(); new ClassReader(b).accept(n, flags); return n;
    }

    // ITransformerProvider: there are no legacy transformers
    @Override public Collection<ITransformer> getTransformers() { return List.of(); }
    @Override public Collection<ITransformer> getDelegatedTransformers() { return List.of(); }
    @Override public void addTransformerExclusion(String name) {}

    // IClassTracker
    @Override public void registerInvalidClass(String name) {}
    @Override public boolean isClassLoaded(String name) { return loader.isClassLoaded(name); }
    @Override public String getClassRestrictions(String name) { return ""; }
}
