package amber.ul;

import amber.ul.meta.LegacyForgeMeta;
import amber.ul.model.*;
import com.google.gson.*;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * forge-1.12.2 phase 0 (docs/forge-1.12.2-plan.md): legacy metadata (@Mod, mcmod.info, coremod manifest) and the planned
 * platform entry in `accepts`. Plain main like HardeningTest:
 *   java -cp "build/loader;build/test;<loader libs>" amber.ul.LegacyForgeTest
 */
public final class LegacyForgeTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + name); }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    /** A class carrying @Mod with the given annotation values (strings/booleans). */
    static byte[] modClass(String internalName, Map<String, Object> values) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationVisitor av = cw.visitAnnotation(LegacyForgeMeta.MOD_ANNOTATION, true);
        values.forEach(av::visit); av.visitEnd();
        cw.visitEnd(); return cw.toByteArray();
    }
    static byte[] plainClass(String internalName) {
        ClassWriter cw = new ClassWriter(0); cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null); cw.visitEnd(); return cw.toByteArray();
    }
    static Path jar(Path p, Map<String, String> manifest, Map<String, byte[]> entries) throws IOException {
        Manifest mf = new Manifest(); mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(p), mf)) {
            for (var e : entries.entrySet()) { o.putNextEntry(new JarEntry(e.getKey())); o.write(e.getValue()); o.closeEntry(); }
        }
        return p;
    }
    static Map<String, Object> m(Object... kv) { Map<String, Object> r = new LinkedHashMap<>(); for (int i = 0; i < kv.length; i += 2) r.put((String) kv[i], kv[i + 1]); return r; }

    public static void main(String[] a) throws Exception {
        Path root = Files.createTempDirectory("amber-legacy");
        try { run(root); } finally {
            try (var s = Files.walk(root)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.printf("RESULT legacy-forge %s: %d passed, %d failed%n", fail == 0 ? "PASS" : "FAIL", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run(Path root) throws Exception {
        System.out.println("dependency strings");
        var d = LegacyForgeMeta.dependencies("required-after:forge@[14.23.5.2768,);after:jei; before:*;required-before:baubles@[1.5,);client:jeresources;bogus;weird:x");
        check("4 deps kept (* and unknown types dropped)", d.size() == 4);
        check("required-after forge with range", d.get(0).equals(new Dependency("forge", "[14.23.5.2768,)", true, "AFTER")));
        check("optional after jei, any version", d.get(1).equals(new Dependency("jei", "*", false, "AFTER")));
        check("required-before baubles", d.get(2).equals(new Dependency("baubles", "[1.5,)", true, "BEFORE")));
        check("client: optional, side only", d.get(3).equals(new Dependency("jeresources", "*", false, "AFTER")));
        check("null / empty spec", LegacyForgeMeta.dependencies(null).isEmpty() && LegacyForgeMeta.dependencies(" ; ").isEmpty());

        System.out.println("@Mod + mcmod.info");
        Path j1 = jar(root.resolve("examplemod.jar"), Map.of("FMLAT", "example_at.cfg"), Map.of(
                "com/example/ExampleMod.class", modClass("com/example/ExampleMod", m("modid", "examplemod", "name", "Example Mod", "version", "${version}",
                        "dependencies", "required-after:forge@[14.23.5.2847,)", "acceptedMinecraftVersions", "[1.12.2]")),
                "com/example/Other.class", plainClass("com/example/Other"),
                "mcmod.info", utf8("[{\"modid\":\"examplemod\",\"name\":\"Ignored Name\",\"version\":\"1.4.2\",\"description\":\"An example\"}]")));
        List<ModInfo> r1 = LegacyForgeMeta.read(j1);
        check("one mod", r1.size() == 1);
        ModInfo e = r1.get(0);
        check("id from @Mod", e.id.equals("examplemod") && e.kind == LoaderKind.FORGE);
        check("placeholder version -> mcmod.info", e.version.equals("1.4.2"));
        check("name from @Mod wins over mcmod.info", e.name.equals("Example Mod"));
        check("description from mcmod.info", e.description.equals("An example"));
        check("forge dep parsed", e.deps.size() == 1 && e.deps.get(0).modId().equals("forge") && e.deps.get(0).required());
        check("access transformer under META-INF", e.accessFiles.equals(List.of("META-INF/example_at.cfg")));
        check("entrypoint is the @Mod class", e.entrypoints.get("fml").equals(List.of("com.example.ExampleMod")));
        var f = (LegacyForgeMeta.Facts) e.raw;
        check("facts: accepted MC versions, no coremod", "[1.12.2]".equals(f.acceptedMinecraftVersions()) && f.coremod() == null && f.fromAnnotation());

        System.out.println("modList v2 format, two mods in one jar, BOM");
        Path j2 = jar(root.resolve("two.jar"), Map.of(), Map.of(
                "a/A.class", modClass("a/A", m("modid", "alpha", "version", "2.0")),
                "b/B.class", modClass("b/B", m("modid", "beta", "version", "", "clientSideOnly", true)),
                "mcmod.info", utf8("﻿{\"modListVersion\":2,\"modList\":[{\"modid\":\"beta\",\"name\":\"Beta\",\"version\":\"0.9\"}]}")));
        List<ModInfo> r2 = LegacyForgeMeta.read(j2);
        r2.sort(Comparator.comparing(x -> x.id));
        check("two mods", r2.size() == 2 && r2.get(0).id.equals("alpha") && r2.get(1).id.equals("beta"));
        check("alpha: version from @Mod, name falls back to id", r2.get(0).version.equals("2.0") && r2.get(0).name.equals("alpha"));
        check("beta: empty version -> mcmod.info, name from mcmod.info", r2.get(1).version.equals("0.9") && r2.get(1).name.equals("Beta"));
        check("beta: clientSideOnly", ((LegacyForgeMeta.Facts) r2.get(1).raw).clientSideOnly());

        System.out.println("coremods and broken files");
        Path j3 = jar(root.resolve("core.jar"), Map.of("FMLCorePlugin", "com.x.LoadingPlugin", "FMLCorePluginContainsFMLMod", "true",
                        "ContainedDeps", "mixinbooter-8.9.jar  other.jar", "TweakClass", " "),
                Map.of("com/x/Mod.class", modClass("com/x/Mod", m("modid", "xmod", "version", "@VERSION@")),
                        "mcmod.info", utf8("not json")));
        ModInfo x = LegacyForgeMeta.read(j3).get(0);
        var fx = (LegacyForgeMeta.Facts) x.raw;
        check("coremod + mod in one jar", "com.x.LoadingPlugin".equals(fx.coremod()) && fx.coremodContainsMod());
        check("blank TweakClass ignored", fx.tweakClass() == null);
        check("ContainedDeps split on spaces", fx.containedDeps().equals(List.of("mixinbooter-8.9.jar", "other.jar")));
        check("@VERSION@ placeholder and broken mcmod.info -> 0.0.0", x.version.equals("0.0.0"));
        Path j4 = jar(root.resolve("pure.jar"), Map.of("FMLCorePlugin", "org.pure.Plugin"), Map.of("org/pure/Plugin.class", plainClass("org/pure/Plugin")));
        List<ModInfo> r4 = LegacyForgeMeta.read(j4);
        check("pure coremod (no @Mod) still listed", r4.size() == 1 && r4.get(0).id.equals("coremod:org.pure.Plugin") && !((LegacyForgeMeta.Facts) r4.get(0).raw).fromAnnotation());
        Path j5 = jar(root.resolve("lib.jar"), Map.of(), Map.of("l/L.class", plainClass("l/L"), "l/Bad.class", utf8("\u0000\u0001 not a class " + LegacyForgeMeta.MOD_ANNOTATION)));
        check("plain library and garbage class: no mods, no exception", LegacyForgeMeta.read(j5).isEmpty());
        Path j6 = jar(root.resolve("noid.jar"), Map.of(), Map.of("n/N.class", modClass("n/N", m("name", "No id"))));
        try { LegacyForgeMeta.read(j6); check("@Mod without modid rejected", false); }
        catch (IllegalArgumentException ok) { check("@Mod without modid rejected", true); }

        System.out.println("accepts / javaLaunch: forge-1.12.2 (interface 1.3.2)");
        JsonArray acc = Bake.accepts();
        JsonObject legacy = null;
        for (JsonElement el : acc) if (el.getAsJsonObject().get("platform").getAsString().equals("forge-1.12.2")) legacy = el.getAsJsonObject();
        boolean bridge = Files.isRegularFile(LoaderHome.bridge("fmlbridge-1.12.2.jar"));
        check("forge-1.12.2 listed for 1.12.2", legacy != null && legacy.get("mc").getAsString().equals("1.12.2"));
        check("available iff its bridge is present (" + bridge + "), accepts forge", legacy != null && legacy.get("available").getAsBoolean() == bridge
                && legacy.getAsJsonArray("accepts").toString().equals(bridge ? "[\"forge\"]" : "[]")
                && legacy.getAsJsonArray("missingBridges").toString().equals(bridge ? "[]" : "[\"fmlbridge-1.12.2.jar\"]"));
        check("a real platform now: in PLATFORMS, bakes on Java 21, launches on Java 8 exactly", Bake.PLATFORMS.get("forge-1.12.2").equals(List.of("1.12.2"))
                && Bake.JAVA_MIN.get("forge-1.12.2") == 21 && Arrays.equals(Bake.JAVA_LAUNCH.get("forge-1.12.2"), new int[] { 8, 8 }) && Bake.PLANNED.isEmpty());
        check("maven path / group-artifact helpers", LegacyPlatform.mavenPath("net.minecraft:launchwrapper:1.12").equals("net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar")
                && LegacyPlatform.mavenPath("org.lwjgl.lwjgl:lwjgl-platform:2.9.4:natives-windows").endsWith("lwjgl-platform-2.9.4-natives-windows.jar")
                && "org/ow2/asm/asm-debug-all".equals(LegacyPlatform.ga(root, root.resolve("org/ow2/asm/asm-debug-all/5.2/asm-debug-all-5.2.jar")))
                && LegacyPlatform.ga(root.resolve("a"), root.resolve("b/c/d/e.jar")) == null);
    }
}
