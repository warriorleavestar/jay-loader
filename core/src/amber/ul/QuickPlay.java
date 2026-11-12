package amber.ul;

import com.google.gson.*;
import java.nio.file.*;
import java.util.Locale;

/**
 * Interface v1.3.3: bake-instance {@code quickPlay} — start the game straight into a singleplayer world or a server, with
 * no title-screen interaction. Exactly one of
 * <pre>
 *   "quickPlay": {"singleplayer": "&lt;world folder name under &lt;gameDir&gt;/saves&gt;"}
 *   "quickPlay": {"multiplayer": "host:port"}      (host, IPv4, or [IPv6]; ":port" optional, default 25565)
 * </pre>
 * The bake reads only: the world must already exist (level.dat) in the instance's saves folder. It is never created,
 * and nothing else is written. How each platform starts it:
 * <ul>
 *   <li>1.20.1 / 1.21.1 (vanilla, NeoForge, Forge): the game's own {@code --quickPlaySingleplayer} / {@code --quickPlayMultiplayer}
 *       (added to {@code launch.gameArgsPrefix}).</li>
 *   <li>1.12.2 multiplayer: the game's own {@code --server host --port port}.</li>
 *   <li>1.12.2 singleplayer: {@code -Damber.ul.legacy.quickPlay=<folder>}; core-legacy opens the world as soon as the main menu
 *       (vanilla's or a replacement) is up.</li>
 * </ul>
 */
final class QuickPlay {
    final String singleplayer, host; final int port;
    private QuickPlay(String sp, String host, int port) { this.singleplayer = sp; this.host = host; this.port = port; }

    /** Null when the request has no quickPlay; BadRequest when it is malformed or the world is not there. */
    static QuickPlay of(JsonObject q) throws Bake.BadRequest {
        if (!q.has("quickPlay") || q.get("quickPlay").isJsonNull()) return null;
        if (!q.get("quickPlay").isJsonObject()) throw new Bake.BadRequest("quickPlay must be an object: {\"singleplayer\": world} or {\"multiplayer\": \"host:port\"}");
        JsonObject o = q.getAsJsonObject("quickPlay");
        boolean sp = o.has("singleplayer"), mp = o.has("multiplayer");
        if (sp == mp || o.size() != 1) throw new Bake.BadRequest("quickPlay needs exactly one of singleplayer / multiplayer");
        if (sp) {
            String w = o.get("singleplayer").getAsString();
            worldName(w);
            Path gameDir = Path.of(q.has("gameDir") ? q.get("gameDir").getAsString() : Bake.str(q, "instanceDir"));
            Path dir = gameDir.resolve("saves").resolve(w);
            if (!Files.isRegularFile(dir.resolve("level.dat"))) throw new Bake.BadRequest("quickPlay world '" + w + "' not found: no level.dat in " + dir);
            return new QuickPlay(w, null, 0);
        }
        String a = o.get("multiplayer").getAsString().trim();
        String host; int port = 25565;
        if (a.startsWith("[")) {
            int e = a.indexOf(']'); if (e < 2) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': bad IPv6 address");
            host = a.substring(1, e); String rest = a.substring(e + 1);
            if (!rest.isEmpty()) { if (!rest.startsWith(":")) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': expected [address]:port"); port = port(a, rest.substring(1)); }
        } else {
            int c = a.lastIndexOf(':');
            if (c >= 0 && a.indexOf(':') != c) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': write IPv6 addresses as [address]:port");
            host = c < 0 ? a : a.substring(0, c); if (c >= 0) port = port(a, a.substring(c + 1));
        }
        if (host.isEmpty() || !host.matches("[A-Za-z0-9._:%-]+")) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': bad host");
        return new QuickPlay(null, host, port);
    }

    /** A save folder name: one path segment, as the game's world list would show it. */
    static void worldName(String w) throws Bake.BadRequest {
        if (w == null || w.isBlank() || w.equals(".") || w.equals("..") || w.contains("/") || w.contains("\\") || w.contains(":") || w.chars().anyMatch(ch -> ch < 0x20))
            throw new Bake.BadRequest("quickPlay singleplayer must be a world folder name under saves/ (got '" + w + "')");
    }

    private static int port(String a, String p) throws Bake.BadRequest {
        try { int n = Integer.parseInt(p); if (n >= 1 && n <= 65535) return n; } catch (NumberFormatException ignored) { }
        throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': port must be 1-65535");
    }

    String address() { return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port; }

    /** 1.20.1 / 1.21.1: the game's own quick play arguments. */
    void modernArgs(JsonArray gameArgs) {
        if (singleplayer != null) { gameArgs.add("--quickPlaySingleplayer"); gameArgs.add(singleplayer); }
        else { gameArgs.add("--quickPlayMultiplayer"); gameArgs.add(address()); }
    }

    /** 1.12.2: --server/--port for multiplayer; the core-legacy hook for singleplayer. */
    void legacyArgs(JsonArray jvm, JsonArray gameArgs) {
        if (singleplayer != null) jvm.add("-Damber.ul.legacy.quickPlay=" + singleplayer);
        else { gameArgs.add("--server"); gameArgs.add(host); gameArgs.add("--port"); gameArgs.add(Integer.toString(port)); }
    }

    /** launch.quickPlay: what the launch block does, for the launcher's UI. */
    JsonObject json(String how) {
        JsonObject o = new JsonObject();
        if (singleplayer != null) o.addProperty("singleplayer", singleplayer); else o.addProperty("multiplayer", address());
        o.addProperty("via", how);
        return o;
    }

    @Override public String toString() { return singleplayer != null ? "singleplayer " + singleplayer : "multiplayer " + address().toLowerCase(Locale.ROOT); }
}
