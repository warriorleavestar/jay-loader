package amber.ul;

import com.google.gson.*;
import java.nio.file.*;
import java.util.Set;

/**
 * Interface v1.3.3: bake-instance {@code maintenance} — a launch of the normal game client that prepares one singleplayer
 * world inside its own integrated server, then quits by itself:
 * <pre>
 *   "maintenance": {"world": "&lt;folder under &lt;gameDir&gt;/saves&gt;", "upgrade": true, "pregenRadius": 32}
 * </pre>
 * <ol>
 *   <li>upgrade (1.20.1 / 1.21.1, default on): a world last saved by an older game version goes through the game's own
 *       "Optimize World" upgrader (OptimizeWorldScreen / WorldUpgrader) before it is opened;</li>
 *   <li>the world is opened in the client's integrated server (never a dedicated server; eula.txt is never touched);</li>
 *   <li>spawn: the chunks within {@link #SPAWN_RADIUS} of the world spawn are generated, always;</li>
 *   <li>pregen (optional, {@code pregenRadius} chunks around spawn): resumable (progress kept in the world folder) and
 *       cancellable (the cancel file, or closing the game);</li>
 *   <li>the world is saved and the game quits.</li>
 * </ol>
 * Progress: {@code @amber-ul/progress {...}} lines on stdout. The bake only reads the world.
 */
final class Maintenance {
    /** Chunks around the world spawn that are always generated: 17 x 17. */
    static final int SPAWN_RADIUS = 8;
    static final int MAX_RADIUS = 512;
    static final String PROGRESS_FILE = "amber-ul-pregen.json", CANCEL_FILE = "maintenance.cancel";
    private static final Set<String> KEYS = Set.of("world", "upgrade", "pregenRadius");

    final String world, mc; final boolean upgrade; final int radius, dataVersion; final Path worldDir, cancelFile;
    private Maintenance(String world, String mc, boolean upgrade, int radius, int dataVersion, Path worldDir, Path cancelFile) {
        this.world = world; this.mc = mc; this.upgrade = upgrade; this.radius = radius; this.dataVersion = dataVersion; this.worldDir = worldDir; this.cancelFile = cancelFile;
    }

    /** Null when the request has none; BadRequest when it is malformed, the world is missing or newer than the game. */
    static Maintenance of(JsonObject q, String mc) throws Bake.BadRequest {
        if (!q.has("maintenance") || q.get("maintenance").isJsonNull()) return null;
        if (!q.get("maintenance").isJsonObject()) throw new Bake.BadRequest("maintenance must be an object: {\"world\": folder, \"upgrade\"?: bool, \"pregenRadius\"?: chunks}");
        if (q.has("quickPlay") && !q.get("quickPlay").isJsonNull()) throw new Bake.BadRequest("maintenance and quickPlay cannot be combined");
        if (q.has("side") && !"client".equals(q.get("side").getAsString())) throw new Bake.BadRequest("maintenance runs in the game client's integrated server: side must be client");
        JsonObject o = q.getAsJsonObject("maintenance");
        for (String k : o.keySet()) if (!KEYS.contains(k)) throw new Bake.BadRequest("maintenance: unknown key '" + k + "'");
        if (!o.has("world")) throw new Bake.BadRequest("maintenance needs world");
        String w = o.get("world").getAsString();
        try { QuickPlay.worldName(w); } catch (Bake.BadRequest e) { throw new Bake.BadRequest("maintenance world must be a folder name under saves/ (got '" + w + "')"); }
        boolean up = true;
        if (o.has("upgrade")) { JsonElement u = o.get("upgrade"); if (!u.isJsonPrimitive() || !u.getAsJsonPrimitive().isBoolean()) throw new Bake.BadRequest("maintenance upgrade must be true or false"); up = u.getAsBoolean(); }
        int r = 0;
        if (o.has("pregenRadius")) {
            JsonElement e = o.get("pregenRadius");
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() || e.getAsDouble() != Math.rint(e.getAsDouble()) || e.getAsDouble() < 0 || e.getAsDouble() > MAX_RADIUS)
                throw new Bake.BadRequest("maintenance pregenRadius must be a whole number of chunks, 0-" + MAX_RADIUS);
            r = e.getAsInt();
        }
        Path gameDir = Path.of(q.has("gameDir") ? q.get("gameDir").getAsString() : Bake.str(q, "instanceDir"));
        Path dir = gameDir.resolve("saves").resolve(w), dat = dir.resolve("level.dat");
        if (!Files.isRegularFile(dat)) throw new Bake.BadRequest("maintenance world '" + w + "' not found: no level.dat in " + dir);
        int dv;
        try { dv = LevelDat.dataVersion(dat); } catch (Exception e) { throw new Bake.BadRequest("maintenance world '" + w + "': level.dat cannot be read (" + e.getMessage() + ")"); }
        int game = LevelDat.gameDataVersion(mc);
        if (game > 0 && dv > game) throw new Bake.BadRequest("maintenance world '" + w + "' was saved by a newer game (data version " + dv + ", Minecraft " + mc + " is " + game + "); the game cannot open it");
        return new Maintenance(w, mc, up, r, dv, dir, Path.of(Bake.str(q, "instanceDir")).resolve(".amber-ul").resolve(CANCEL_FILE));
    }

    /** "needed" / "not needed" (same version) / "off" (asked not to) / "game" (1.12.2: the game upgrades on load). */
    String upgradeState() {
        if (mc.equals("1.12.2")) return "game";
        if (!upgrade) return "off";
        return dataVersion < LevelDat.gameDataVersion(mc) ? "needed" : "not needed";
    }

    /** The system properties the runtime side reads (core: runtime.MaintenanceRun; forge-1.12.2: core-legacy). */
    void jvmArgs(JsonArray jvm) {
        jvm.add("-Damber.ul.maintenance=" + world);
        jvm.add("-Damber.ul.maintenance.upgrade=" + upgrade);
        jvm.add("-Damber.ul.maintenance.radius=" + radius);
        jvm.add("-Damber.ul.maintenance.spawnRadius=" + SPAWN_RADIUS);
        jvm.add("-Damber.ul.maintenance.cancelFile=" + cancelFile);
        jvm.add("-Damber.ul.maintenance.gameDataVersion=" + LevelDat.gameDataVersion(mc));
    }

    /** launch.maintenance: what this launch will do, for the launcher's UI. */
    JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("upgrade", upgradeState());
        o.addProperty("worldDataVersion", dataVersion);
        o.addProperty("gameDataVersion", LevelDat.gameDataVersion(mc));
        o.addProperty("spawnRadius", SPAWN_RADIUS);
        o.addProperty("pregenRadius", radius);
        o.addProperty("progressFile", worldDir.resolve(PROGRESS_FILE).toString());
        o.addProperty("cancelFile", cancelFile.toString());
        return o;
    }
}
