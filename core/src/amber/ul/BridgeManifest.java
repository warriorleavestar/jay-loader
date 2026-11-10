package amber.ul;

import amber.ul.model.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Phase 5: the Fabric API bridge manifest, amber-ul/bridge/fabric-api-<platform>-<mc>.json (data, not code); platform is
 * neoforge or forge (Fabric on Forge 47: docs/fabric-on-forge-1.20.1-design.md).
 * Per Fabric API module id + exact version: mixins to veto (their job is done by a NeoForge system or Amber's bridge),
 * single injectors to strip, entrypoints to skip. Applied only on the NeoForge and Forge platforms. Its fingerprint is part of the
 * isolation key, so census results are always taken with the manifest applied.
 */
public final class BridgeManifest {
    /** invoker: an abstract @Accessor becomes an @Invoker of that method, which a bridge mixin adds to the target; retarget == null: drop the injector; else move it to that method selector (the target method NeoForge moved the code into). */
    public record Strip(String mod, String mixin, String handler, String reason, String retarget, String invoker) {}
    public final Map<String, String> veto = new LinkedHashMap<>();       // mixin FQN -> module
    public final Set<String> skipEntrypoints = new LinkedHashSet<>();
    public final List<Strip> strips = new ArrayList<>();
    public final Map<String, Integer> bridgedModules = new TreeMap<>();  // module -> vetoed mixin count
    public final List<String> versionMismatch = new ArrayList<>();
    public final Set<String> replaceImpl = new LinkedHashSet<>();          // modules whose impl.* classes the bridge jar replaces

    static String raw(String platform, String mc) throws Exception {
        if (platform == null) return null;
        var in = BridgeManifest.class.getResourceAsStream("/amber-ul/bridge/fabric-api-" + platform + "-" + mc + ".json");
        return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    public static String fingerprint(String platform, String mc) {
        try { String r = raw(platform, mc); return r == null ? "-" : amber.ul.bake.Hash.str(r).substring(0, 12); } catch (Exception e) { return "-"; }
    }

    public static BridgeManifest load(String platform, String mc, List<ModInfo> mods) throws Exception {
        BridgeManifest bm = new BridgeManifest();
        String r = raw(platform, mc);
        if (r == null) return bm;
        JsonObject root = JsonParser.parseString(r).getAsJsonObject();
        Map<String, ModInfo> byId = new HashMap<>();
        for (ModInfo m : mods) if (m.kind == LoaderKind.FABRIC || m.kind == LoaderKind.QUILT) byId.put(m.id, m);
        for (var e : root.getAsJsonObject("modules").entrySet()) {
            ModInfo m = byId.get(e.getKey());
            if (m == null) continue;
            JsonObject o = e.getValue().getAsJsonObject();
            if (o.has("version") && !o.get("version").getAsString().equals(m.version.split("[+]")[0])) { bm.versionMismatch.add(m.id + " " + m.version + " (manifest " + o.get("version").getAsString() + ")"); continue; }
            String pkg = o.has("package") ? o.get("package").getAsString() + "." : "";
            int n = 0;
            if (o.has("veto")) for (JsonElement v : o.getAsJsonArray("veto")) { bm.veto.put(pkg + v.getAsString(), m.id); n++; }
            if (o.has("skipEntrypoints")) for (JsonElement v : o.getAsJsonArray("skipEntrypoints")) bm.skipEntrypoints.add(v.getAsString());
            if (o.has("strip")) for (JsonElement v : o.getAsJsonArray("strip")) {
                JsonObject s = v.getAsJsonObject();
                bm.strips.add(new Strip(m.id, pkg + s.get("mixin").getAsString(), s.get("handler").getAsString(), s.has("reason") ? s.get("reason").getAsString() : "", s.has("method") ? s.get("method").getAsString() : null, s.has("invoker") ? s.get("invoker").getAsString() : null));
            }
            if (o.has("replaceImpl") && o.get("replaceImpl").getAsBoolean()) bm.replaceImpl.add(m.id);
            bm.bridgedModules.put(m.id, n);
        }
        return bm;
    }
}
