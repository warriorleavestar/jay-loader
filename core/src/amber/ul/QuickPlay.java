package amber.ul;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;

/**
 * Interface v1.3.3: bake-instance {@code quickPlay} — start the game straight into a singleplayer world or a server, with
 * no title-screen interaction. Exactly one of
 * <pre>
 *   "quickPlay": {"singleplayer": "&lt;world folder name under &lt;gameDir&gt;/saves&gt;"}
 *   "quickPlay": {"multiplayer": "host:port"}      (ASCII host name, IPv4, or [IPv6]; ":port" optional, default 25565)
 * </pre>
 * Both values must be JSON strings. The bake reads only: the world must already exist (level.dat) in the saves folder of
 * the request's {@code gameDir} (else {@code instanceDir}), which must be the --gameDir the game is started with. It is
 * never created, and nothing else is written. How each platform starts it:
 * <ul>
 *   <li>1.20.1 / 1.21.1 (vanilla, NeoForge, Forge): the game's own {@code --quickPlaySingleplayer} / {@code --quickPlayMultiplayer}
 *       (added to {@code launch.gameArgsPrefix}).</li>
 *   <li>1.12.2 multiplayer: the game's own {@code --server host --port port} (an IPv6 host in brackets).</li>
 *   <li>1.12.2 singleplayer: {@code -Damber.ul.legacy.quickPlay=<folder, percent-encoded UTF-8>}; core-legacy opens the world
 *       as the world list does once the main menu (vanilla's or a known replacement) is up.</li>
 * </ul>
 */
final class QuickPlay {
    final String singleplayer, host; final int port;
    private QuickPlay(String sp, String host, int port) { this.singleplayer = sp; this.host = host; this.port = port; }

    /** Null when the request has no quickPlay; BadRequest when it is malformed or the world is not there. */
    static QuickPlay of(JsonObject q, boolean legacy) throws Bake.BadRequest {
        if (!q.has("quickPlay") || q.get("quickPlay").isJsonNull()) return null;
        if (!q.get("quickPlay").isJsonObject()) throw new Bake.BadRequest("quickPlay must be an object: {\"singleplayer\": world} or {\"multiplayer\": \"host:port\"}");
        JsonObject o = q.getAsJsonObject("quickPlay");
        boolean sp = o.has("singleplayer"), mp = o.has("multiplayer");
        if (sp == mp || o.size() != 1) throw new Bake.BadRequest("quickPlay needs exactly one of singleplayer / multiplayer");
        if (q.has("side") && !(q.get("side").isJsonPrimitive() && "client".equals(q.get("side").getAsString()))) throw new Bake.BadRequest("quickPlay starts the game client: side must be client");
        if (sp) {
            String w = string(o, "singleplayer");
            worldName(w);
            worldDir(q, w, "quickPlay");
            nameWarnings(w, legacy);
            return new QuickPlay(w, null, 0);
        }
        String a = string(o, "multiplayer").trim();
        String host; int port = 25565;
        if (a.startsWith("[")) {
            int e = a.indexOf(']'); if (e < 2) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': bad IPv6 address");
            host = a.substring(1, e); String rest = a.substring(e + 1);
            if (!host.matches("[0-9A-Fa-f:.]+(%[A-Za-z0-9._-]+)?") || host.indexOf(':') < 0) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': bad IPv6 address");
            if (!rest.isEmpty()) { if (!rest.startsWith(":")) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': expected [address]:port"); port = port(a, rest.substring(1)); }
        } else {
            int c = a.lastIndexOf(':');
            if (c >= 0 && a.indexOf(':') != c) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': write IPv6 addresses as [address]:port");
            host = c < 0 ? a : a.substring(0, c); if (c >= 0) port = port(a, a.substring(c + 1));
            // an ASCII host name or IPv4 address; never starting with '-' (it would read as an option in the game's arguments)
            if (!host.matches("[A-Za-z0-9_](?:[A-Za-z0-9._-]*[A-Za-z0-9_])?")) throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': bad host (ASCII host names, IPv4, or [IPv6] only)");
        }
        return new QuickPlay(null, host, port);
    }

    /** A JSON string, nothing else (Gson would turn numbers, booleans and one-element arrays into strings). */
    static String string(JsonObject o, String key) throws Bake.BadRequest {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new Bake.BadRequest("quickPlay " + key + " must be a string");
        return e.getAsString();
    }

    /** The game directory the game will use: the request's gameDir, else instanceDir (the launch must pass the same --gameDir). */
    static Path gameDir(JsonObject q) throws Bake.BadRequest {
        String key = q.has("gameDir") && !q.get("gameDir").isJsonNull() ? "gameDir" : "instanceDir";
        JsonElement e = q.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new Bake.BadRequest(key + " must be a string");
        try { return Path.of(e.getAsString()); }
        catch (InvalidPathException x) { throw new Bake.BadRequest(key + " '" + e.getAsString() + "' is not a usable path: " + x.getMessage()); }
    }

    /**
     * The world folder &lt;gameDir&gt;/saves/&lt;w&gt;, which must hold a level.dat and be named exactly w on disk: the game
     * matches folder names exactly, while Windows (case) and macOS (Unicode normalization) would also find a near match.
     */
    static Path worldDir(JsonObject q, String w, String what) throws Bake.BadRequest {
        Path saves = gameDir(q).resolve("saves"), dir;
        try { dir = saves.resolve(w); }
        catch (InvalidPathException e) { throw new Bake.BadRequest(what + " world '" + w + "' is not a usable folder name on this system: " + e.getMessage()); }
        if (!Files.isRegularFile(dir.resolve("level.dat"))) throw new Bake.BadRequest(what + " world '" + w + "' not found: no level.dat in " + dir);
        String real;
        try { real = dir.toRealPath().getFileName().toString(); } catch (java.io.IOException e) { throw new Bake.BadRequest(what + " world '" + w + "': " + e); }
        if (!real.equals(w)) throw new Bake.BadRequest(what + " world '" + w + "' not found: the folder on disk is named '" + real + "' (names must match exactly, case and Unicode form included)");
        return dir;
    }

    /** A save folder name: one path segment, as the game's world list would show it. */
    static void worldName(String w) throws Bake.BadRequest {
        if (w == null || w.isBlank() || w.equals(".") || w.equals("..") || w.contains("/") || w.contains("\\") || w.contains(":") || w.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f) || unpairedSurrogate(w))
            throw new Bake.BadRequest("quickPlay singleplayer must be a world folder name under saves/ (got '" + w + "')");
    }

    static boolean unpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++; else return true; }
            else if (Character.isLowSurrogate(c)) return true;
        }
        return false;
    }

    /** Warnings only: names that work here but may not survive the trip to the game. */
    static void nameWarnings(String w, boolean legacy) {
        if (w.chars().anyMatch(ch -> ch > 0x7e))
            Bake.problem("warning", "quickplay_world_name", null, null, null, legacy
                    ? "quickPlay world '" + w + "' has non-ASCII characters: the loader passes it percent-encoded, but on systems whose file names are not Unicode (a non-UTF-8 locale on Linux) Java 8 may not open it"
                    : "quickPlay world '" + w + "' has non-ASCII characters: the game reads its arguments in the system code page on Windows, so a name outside it may not be found");
        if (w.startsWith("-")) Bake.problem("warning", "quickplay_world_name", null, null, null, "quickPlay world '" + w + "' starts with '-'; the game reads it as the option's value, but other tools may not");
    }

    private static int port(String a, String p) throws Bake.BadRequest {
        try { if (p.matches("[0-9]{1,5}")) { int n = Integer.parseInt(p); if (n >= 1 && n <= 65535) return n; } } catch (NumberFormatException ignored) { }
        throw new Bake.BadRequest("quickPlay multiplayer '" + a + "': port must be 1-65535");
    }

    /** host:port, an IPv6 host in brackets. */
    String address() { return hostArg() + ":" + port; }
    String hostArg() { return host.indexOf(':') >= 0 ? "[" + host + "]" : host; }

    /** 1.20.1 / 1.21.1: the game's own quick play arguments. */
    void modernArgs(JsonArray gameArgs) {
        if (singleplayer != null) { gameArgs.add("--quickPlaySingleplayer"); gameArgs.add(singleplayer); }
        else { gameArgs.add("--quickPlayMultiplayer"); gameArgs.add(address()); }
    }

    /** 1.12.2: --server/--port for multiplayer (FML joins host + ":" + port, so an IPv6 host goes in brackets); the core-legacy hook for singleplayer. */
    void legacyArgs(JsonArray jvm, JsonArray gameArgs) {
        if (singleplayer != null) jvm.add("-Damber.ul.legacy.quickPlay=" + encode(singleplayer));
        else { gameArgs.add("--server"); gameArgs.add(hostArg()); gameArgs.add("--port"); gameArgs.add(Integer.toString(port)); }
    }

    /** Percent-encoded UTF-8, everything but A-Z a-z 0-9 - . _ ~ (so the JVM argument is pure ASCII whatever the code page). */
    static String encode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~') sb.append((char) c);
            else sb.append('%').append(String.format("%02X", c));
        }
        return sb.toString();
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
