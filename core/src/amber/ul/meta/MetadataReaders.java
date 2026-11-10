package amber.ul.meta;

import amber.ul.model.*;
import com.google.gson.*;
import org.tomlj.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.*;

/** Reads fabric.mod.json, quilt.mod.json, META-INF/mods.toml and META-INF/neoforge.mods.toml into ModInfo. */
public final class MetadataReaders {
    private MetadataReaders() {}
    /** Version range a NeoForge 1.20.1 mod's "neoforge" dependency becomes on Forge 47 (every NeoForge 1.20.1 build is 47.1.x). */
    public static final String NEOFORGE_1201_AS_FORGE = "[47,)";

    public static List<ModInfo> read(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            String s;
            // M4: on the Forge platform a jar that ships both metadata files (Collective: fabric.mod.json + mods.toml) is the Forge mod
            if (System.getProperty("amber.ul.forgeVersion") != null && (s = text(jar, "META-INF/mods.toml")) != null) return toml(s, jarPath, LoaderKind.FORGE, jar);
            if ((s = text(jar, "fabric.mod.json")) != null) return List.of(fabric(JsonParser.parseString(s).getAsJsonObject(), jarPath));
            if ((s = text(jar, "quilt.mod.json")) != null) return List.of(quilt(JsonParser.parseString(s).getAsJsonObject(), jarPath));
            if ((s = text(jar, "META-INF/neoforge.mods.toml")) != null) return toml(s, jarPath, LoaderKind.NEOFORGE, jar);
            if ((s = text(jar, "META-INF/mods.toml")) != null) return toml(s, jarPath, LoaderKind.FORGE, jar);
            return List.of();
        }
    }

    static String text(JarFile jar, String name) throws IOException {
        JarEntry e = jar.getJarEntry(name);
        if (e == null) return null;
        try (InputStream in = jar.getInputStream(e)) { return new String(amber.ul.bake.SafeFiles.boundedRead(in, amber.ul.bake.SafeFiles.METADATA_MAX, name), StandardCharsets.UTF_8); }
    }

    static ModInfo fabric(JsonObject o, Path jar) {
        ModInfo m = new ModInfo(req(o, "id"), req(o, "version"), LoaderKind.FABRIC, jar);
        if (o.has("entrypoints")) for (var e : o.getAsJsonObject("entrypoints").entrySet()) {
            List<String> l = new ArrayList<>();
            for (JsonElement el : e.getValue().getAsJsonArray()) l.add(el.isJsonObject() ? el.getAsJsonObject().get("value").getAsString() : el.getAsString());
            m.entrypoints.put(e.getKey(), l);
        }
        deps(o, "depends", true, m); deps(o, "recommends", false, m);
        m.raw = o;
        m.name = o.has("name") ? o.get("name").getAsString() : m.id;
        if (o.has("description")) m.description = o.get("description").getAsString();
        if (o.has("mixins")) for (JsonElement el : o.getAsJsonArray("mixins")) {
            if (el.isJsonObject()) { JsonObject x = el.getAsJsonObject(); if (x.has("environment") && x.get("environment").getAsString().equals("server")) continue; m.mixinConfigs.add(x.get("config").getAsString()); }
            else m.mixinConfigs.add(el.getAsString());
        }
        if (o.has("accessWidener")) m.accessFiles.add(o.get("accessWidener").getAsString());
        if (o.has("provides")) for (JsonElement el : o.getAsJsonArray("provides")) m.provides.add(el.getAsString());
        return m;
    }

    /** A required string field; missing / non-string -> IllegalArgumentException (reported as bad_metadata by Discovery). */
    static String req(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive()) throw new IllegalArgumentException("missing or non-string \"" + k + "\"");
        return e.getAsString();
    }

    private static void deps(JsonObject o, String key, boolean req, ModInfo m) {
        if (!o.has(key)) return;
        for (var e : o.getAsJsonObject(key).entrySet()) {
            String r = e.getValue().isJsonArray() ? String.join(" || ", e.getValue().getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList()) : e.getValue().getAsString();
            m.deps.add(new Dependency(e.getKey(), r, req));
        }
    }

    static ModInfo quilt(JsonObject root, Path jar) {
        JsonObject q = root.getAsJsonObject("quilt_loader");
        if (q == null) throw new IllegalArgumentException("quilt.mod.json without quilt_loader");
        ModInfo m = new ModInfo(req(q, "id"), req(q, "version"), LoaderKind.QUILT, jar);
        m.raw = root;
        if (q.has("entrypoints")) for (var e : q.getAsJsonObject("entrypoints").entrySet()) {
            JsonElement v = e.getValue();
            m.entrypoints.put(e.getKey(), v.isJsonArray() ? v.getAsJsonArray().asList().stream().map(JsonElement::getAsString).toList() : List.of(v.getAsString()));
        }
        if (q.has("depends")) for (JsonElement el : q.getAsJsonArray("depends")) {
            if (el.isJsonPrimitive()) m.deps.add(new Dependency(el.getAsString(), "*", true));
            else { JsonObject d = el.getAsJsonObject(); m.deps.add(new Dependency(d.get("id").getAsString(), d.has("versions") ? quiltVersions(d.get("versions")) : "*", !d.has("optional") || !d.get("optional").getAsBoolean())); }
        }
        // Quilt provides: "id" or {"id", "version"?}
        if (q.has("provides")) for (JsonElement el : q.getAsJsonArray("provides")) m.provides.add(el.isJsonPrimitive() ? el.getAsString() : el.getAsJsonObject().get("id").getAsString());
        if (root.has("mixin")) { JsonElement x = root.get("mixin"); if (x.isJsonArray()) x.getAsJsonArray().forEach(e -> m.mixinConfigs.add(e.getAsString())); else m.mixinConfigs.add(x.getAsString()); }
        if (root.has("access_widener")) m.accessFiles.add(root.get("access_widener").getAsString());
        return m;
    }

    /**
     * Quilt version constraint: a string, an array (any of), or {"any": [...]} / {"all": [...]} (nested). Returned in the
     * form the resolver reads: alternatives joined with " || ", conjunctions with a space.
     */
    static String quiltVersions(JsonElement v) {
        if (v.isJsonPrimitive()) return v.getAsString();
        if (v.isJsonArray()) return String.join(" || ", v.getAsJsonArray().asList().stream().map(MetadataReaders::quiltVersions).toList());
        JsonObject o = v.getAsJsonObject();
        if (o.has("all")) return String.join(" ", o.getAsJsonArray("all").asList().stream().map(MetadataReaders::quiltVersions).toList());
        if (o.has("any")) return String.join(" || ", o.getAsJsonArray("any").asList().stream().map(MetadataReaders::quiltVersions).toList());
        return "*";
    }

    /** MANIFEST.MF MixinConfigs: comma separated config names (Forge/ModLauncher convention). */
    public static List<String> manifestMixins(JarFile jar) throws IOException {
        var mf = jar.getManifest(); if (mf == null) return List.of();
        String v = mf.getMainAttributes().getValue("MixinConfigs"); if (v == null || v.isBlank()) return List.of();
        List<String> l = new ArrayList<>(); for (String x : v.split(",")) if (!x.isBlank()) l.add(x.trim());
        return l;
    }

    static List<ModInfo> toml(String s, Path jarPath, LoaderKind kind, JarFile jar) throws IOException {
        TomlParseResult t = Toml.parse(s);
        if (t.hasErrors()) throw new IOException("bad toml in " + jarPath + ": " + t.errors());
        String implVersion = Optional.ofNullable(jar.getManifest()).map(mf -> mf.getMainAttributes().getValue("Implementation-Version")).orElse("0");
        List<ModInfo> out = new ArrayList<>();
        TomlArray mods = t.getArray("mods");
        for (int i = 0; mods != null && i < mods.size(); i++) {
            TomlTable mt = mods.getTable(i);
            String ver = mt.getString("version", () -> "0");
            if (ver.equals("${file.jarVersion}")) ver = implVersion;
            // Forge's own mods.toml: the launcher substitutes these (ForgePlatform publishes the installed versions)
            ver = ver.replace("${global.forgeVersion}", System.getProperty("amber.ul.forgeVersion", "0")).replace("${global.mcVersion}", System.getProperty("amber.ul.forgeMcVersion", "0"));
            ModInfo m = new ModInfo(mt.getString("modId"), ver, kind, jarPath);
            TomlArray ds = t.getArray("dependencies." + m.id);
            for (int j = 0; ds != null && j < ds.size(); j++) {
                TomlTable d = ds.getTable(j);
                boolean req = d.contains("type") ? "required".equalsIgnoreCase(d.getString("type")) : Boolean.TRUE.equals(d.getBoolean("mandatory"));
                String dep = d.getString("modId"), range = d.getString("versionRange", () -> "*");
                // NeoForge 1.20.1 is Forge 47.1 forked: on the Forge platform its mods' "neoforge" dependency means Forge
                // (ForgeCheck rewrites the jar's mods.toml the same way for FML)
                if (kind == LoaderKind.FORGE && "neoforge".equals(dep) && System.getProperty("amber.ul.forgeVersion") != null && !Boolean.getBoolean("amber.ul.noNeoForgeAlias")) { dep = "forge"; range = NEOFORGE_1201_AS_FORGE; }
                m.deps.add(new Dependency(dep, range, req, d.getString("ordering", () -> "NONE").toUpperCase(Locale.ROOT)));
            }
            TomlArray mx = t.getArray("mixins");
            for (int j = 0; mx != null && j < mx.size(); j++) m.mixinConfigs.add(mx.getTable(j).getString("config"));
            if (jar.getJarEntry("META-INF/accesstransformer.cfg") != null) m.accessFiles.add("META-INF/accesstransformer.cfg");
            // Forge 1.20.1 (and older NeoForge): mixin configs come from the manifest's MixinConfigs (once per file: first mod)
            if (kind == LoaderKind.FORGE && i == 0) for (String c : manifestMixins(jar)) if (!m.mixinConfigs.contains(c)) m.mixinConfigs.add(c);
            out.add(m);
        }
        return out;
    }
}
