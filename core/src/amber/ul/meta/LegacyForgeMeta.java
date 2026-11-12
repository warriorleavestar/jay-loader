package amber.ul.meta;

import amber.ul.bake.SafeFiles;
import amber.ul.model.*;
import com.google.gson.*;
import org.objectweb.asm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.*;

/**
 * Forge 1.12.2 (FML 8, LaunchWrapper) mod metadata (docs/forge-1.12.2-plan.md). Not wired into {@link MetadataReaders#read}
 * yet: the forge-1.12.2 platform is planned, and its discovery only runs on that platform.
 *
 * 1.12 mods have no mods.toml. What FML itself trusts is the {@code @Mod} annotation on a class; {@code mcmod.info} is
 * optional, often stale, and only fills display fields (and the version when the annotation keeps a build placeholder).
 * The jar manifest carries the coremod side: {@code FMLCorePlugin} (an IFMLLoadingPlugin), {@code FMLCorePluginContainsFMLMod},
 * {@code TweakClass}, {@code FMLAT} (access transformer files under META-INF/) and {@code ContainedDeps} (nested jars).
 * Results are ModInfo with kind FORGE and {@link Facts} in {@code raw}.
 */
public final class LegacyForgeMeta {
    private LegacyForgeMeta() {}
    public static final String MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";
    /** Class files above this are not scanned for @Mod (no real mod class is near it). */
    static final long CLASS_MAX = 4L << 20;
    /** Stop scanning a jar after this many class entries (a zip bomb of tiny classes must not stall discovery). */
    static final int CLASS_LIMIT = 200_000;

    /** Facts a 1.12 jar declares beyond ModInfo. */
    public record Facts(String coremod, boolean coremodContainsMod, String tweakClass, List<String> accessTransformers,
                        List<String> containedDeps, String acceptedMinecraftVersions, boolean clientSideOnly, boolean serverSideOnly,
                        String modClass, boolean fromAnnotation) {}

    /** One {@code @Mod} annotation as found in bytecode. */
    record ModAnn(String className, Map<String, Object> values) {
        String s(String k) { Object v = values.get(k); return v instanceof String x ? x : null; }
        boolean b(String k) { return Boolean.TRUE.equals(values.get(k)); }
    }

    /**
     * The mods a jar ships through ContainedDeps (FML 1.12 extracts them to mods/memory_repo and loads them unless the
     * same file is already there): nested jars named in the manifest, at the jar root or under META-INF/libraries/.
     * One level only (FML does not recurse either); unreadable or missing entries are skipped.
     */
    public static List<ModInfo> readContained(Path jarPath) throws IOException {
        List<ModInfo> out = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest mf = jar.getManifest();
            if (mf == null) return out;
            for (String name : split(mf.getMainAttributes().getValue("ContainedDeps"), " ")) {
                if (name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                var e = jar.getJarEntry(name); if (e == null) e = jar.getJarEntry("META-INF/libraries/" + name);
                if (e == null || e.getSize() > 256L << 20) continue;
                Path tmp = Files.createTempFile("amber-contained", ".jar");
                try {
                    try (var in = jar.getInputStream(e)) { Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING); }
                    for (ModInfo m : read(tmp)) { ModInfo c = new ModInfo(m.id, m.version, m.kind, jarPath); c.deps.addAll(m.deps); c.raw = m.raw; c.parent = name; out.add(c); }   // reported against the outer jar
                } catch (IOException | RuntimeException ex) { /* FML would fail on it at launch; not our call here */ }
                finally { Files.deleteIfExists(tmp); }
            }
        }
        return out;
    }

    public static List<ModInfo> read(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) { return read(jar, jarPath); }
    }

    static List<ModInfo> read(JarFile jar, Path jarPath) throws IOException {
        Manifest mf = jar.getManifest();
        Attributes at = mf == null ? new Attributes() : mf.getMainAttributes();
        String coremod = blankToNull(at.getValue("FMLCorePlugin")), tweak = blankToNull(at.getValue("TweakClass"));
        // FML 1.12 (CoreModManager) scans a coremod jar for @Mod only when the manifest has this key at all (any value),
        // or ForceLoadAsMod; otherwise its @Mod classes are never loaded and must not count
        boolean cmMod = at.getValue("FMLCorePluginContainsFMLMod") != null || "true".equalsIgnoreCase(trim(at.getValue("ForceLoadAsMod")));
        List<String> ats = split(at.getValue("FMLAT"), " "), contained = split(at.getValue("ContainedDeps"), " ");

        Map<String, JsonObject> info = new LinkedHashMap<>();
        String s = MetadataReaders.text(jar, "mcmod.info");
        if (s != null) for (JsonObject o : mcmodInfo(s)) { String id = str(o, "modid"); if (id != null) info.putIfAbsent(id, o); }

        List<ModAnn> anns = coremod != null && !cmMod ? List.of() : scan(jar);
        List<ModInfo> out = new ArrayList<>();
        for (ModAnn a : anns) {
            String id = a.s("modid");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("@Mod without modid on " + a.className());
            JsonObject mi = info.get(id);
            String version = a.s("version");
            if (placeholder(version)) version = mi == null ? null : str(mi, "version");
            if (placeholder(version)) version = "0.0.0";   // FML itself shows such mods with an empty/placeholder version
            ModInfo m = new ModInfo(id, version, LoaderKind.FORGE, jarPath);
            m.name = a.s("name") != null && !a.s("name").isBlank() ? a.s("name") : mi != null && str(mi, "name") != null ? str(mi, "name") : id;
            if (mi != null && str(mi, "description") != null) m.description = str(mi, "description");
            m.deps.addAll(dependencies(a.s("dependencies")));
            m.accessFiles.addAll(ats.stream().map(x -> "META-INF/" + x).toList());
            m.entrypoints.put("fml", List.of(a.className()));
            m.raw = new Facts(coremod, cmMod, tweak, ats, contained, a.s("acceptedMinecraftVersions"), a.b("clientSideOnly"), a.b("serverSideOnly"), a.className(), true);
            out.add(m);
        }
        // a pure coremod / tweaker jar has no @Mod: FML still loads it (as a hidden mod container), identified by its class
        if (out.isEmpty() && (coremod != null || tweak != null)) {
            String id = info.size() == 1 ? info.keySet().iterator().next() : "coremod:" + (coremod != null ? coremod : tweak);
            JsonObject mi = info.get(id);
            ModInfo m = new ModInfo(id, mi != null && !placeholder(str(mi, "version")) ? str(mi, "version") : "0.0.0", LoaderKind.FORGE, jarPath);
            m.name = mi != null && str(mi, "name") != null ? str(mi, "name") : id;
            m.accessFiles.addAll(ats.stream().map(x -> "META-INF/" + x).toList());
            m.raw = new Facts(coremod, cmMod, tweak, ats, contained, null, false, false, null, false);
            out.add(m);
        }
        return out;
    }

    /** mcmod.info is either a bare array of mod objects or {"modListVersion": 2, "modList": [...]}; anything else is ignored. */
    static List<JsonObject> mcmodInfo(String text) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement root;
        try { root = JsonParser.parseString(text.charAt(0) == '﻿' ? text.substring(1) : text); } catch (RuntimeException e) { return out; }   // FML ignores a broken mcmod.info too
        JsonArray list = root.isJsonArray() ? root.getAsJsonArray()
                : root.isJsonObject() && root.getAsJsonObject().get("modList") instanceof JsonArray a ? a : null;
        if (list != null) for (JsonElement e : list) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    /**
     * FML 1.12 dependency string: {@code required-after:forge@[14.23.5.2768,);after:jei;before:*}. Each entry is
     * [required-](after|before|client|server):modid[@versionRange]. "client"/"server" only say on which side the dependency
     * applies; we keep them as AFTER ordering. "*" means all mods (ordering only) and is dropped. Unknown entries are ignored,
     * as FML does with a warning.
     */
    public static List<Dependency> dependencies(String spec) {
        List<Dependency> out = new ArrayList<>();
        if (spec == null) return out;
        for (String raw : spec.split(";")) {
            String e = raw.trim(); if (e.isEmpty()) continue;
            int c = e.indexOf(':'); if (c <= 0) continue;
            String type = e.substring(0, c).trim().toLowerCase(Locale.ROOT), target = e.substring(c + 1).trim();
            boolean required = type.startsWith("required-");
            String kind = required ? type.substring("required-".length()) : type;
            if (!Set.of("after", "before", "client", "server").contains(kind)) continue;
            int at = target.indexOf('@');
            String id = (at < 0 ? target : target.substring(0, at)).trim(), range = at < 0 ? "*" : target.substring(at + 1).trim();
            if (id.isEmpty() || id.equals("*")) continue;
            out.add(new Dependency(id, range.isEmpty() ? "*" : range, required, kind.equals("before") ? "BEFORE" : "AFTER"));
        }
        return out;
    }

    /** Every {@code @Mod} in the jar's class files (code skipped: annotations only). */
    static List<ModAnn> scan(JarFile jar) throws IOException {
        List<ModAnn> out = new ArrayList<>(); int n = 0;
        for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) {
            JarEntry e = en.nextElement(); String name = e.getName();
            if (!name.endsWith(".class") || name.startsWith("META-INF/") || e.isDirectory()) continue;
            if (++n > CLASS_LIMIT) throw new IOException("more than " + CLASS_LIMIT + " classes in " + jar.getName());
            if (e.getSize() > CLASS_MAX) continue;
            byte[] b;
            try (InputStream in = jar.getInputStream(e)) { b = SafeFiles.boundedRead(in, CLASS_MAX, name); }
            if (!mayHaveMod(b)) continue;
            ModAnn a;
            try { a = modAnnotation(b); } catch (RuntimeException bad) { continue; }   // not a class FML could load either
            if (a != null) out.add(a);
        }
        return out;
    }

    /** Cheap pre-filter: the annotation descriptor is a constant-pool UTF8, so it appears verbatim in the class bytes. */
    static boolean mayHaveMod(byte[] b) {
        byte[] d = MOD_ANNOTATION.getBytes(StandardCharsets.UTF_8);
        outer: for (int i = 0, end = b.length - d.length; i <= end; i++) {
            for (int j = 0; j < d.length; j++) if (b[i + j] != d[j]) continue outer;
            return true;
        }
        return false;
    }

    static ModAnn modAnnotation(byte[] b) {
        ModAnn[] found = { null };
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            String cls;
            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) { cls = name.replace('/', '.'); }
            @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                if (!MOD_ANNOTATION.equals(desc)) return null;
                Map<String, Object> vals = new LinkedHashMap<>(); found[0] = new ModAnn(cls, vals);
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override public void visit(String k, Object v) { vals.put(k, v); }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    static boolean placeholder(String v) { return v == null || v.isBlank() || v.contains("${") || v.startsWith("@") && v.endsWith("@"); }
    static String str(JsonObject o, String k) { JsonElement e = o.get(k); return e != null && e.isJsonPrimitive() ? e.getAsString() : null; }
    static String trim(String s) { return s == null ? null : s.trim(); }
    static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    static List<String> split(String s, String sep) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.trim().split(sep)).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
