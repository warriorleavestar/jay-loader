package amber.ul.bake;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * The "neoforge platform" layer of the game bake. Only active when the resolved set needs NeoForge.
 *
 * Inputs (from the NeoForge installer, produced once per NeoForge version by its official processors; see
 * tools/neoforge_processors.py and mc/neoforge/platform-cache/*.json):
 *   srg   = client.jar renamed to Mojmap by AutoRenamingTool (the exact base the binpatches were made against)
 *   extra = client.jar resources
 *   client.lzma binpatches, the neoforge universal jar, and the FML/NeoForge runtime libraries.
 * Output: one game jar = srg overlaid with the patched classes (applied by our BinPatcher) + extra resources,
 * cached under its own key (srg + patches + extra + pipeline), separate from the vanilla game bake.
 */
public final class NeoForgePlatform {
    public final Path dir, srg, extra, lzma, universal; public final String neoForge, fml, mc, neoForm;
    public final List<Path> libraries = new ArrayList<>();

    /** NeoForge libraries that must NOT go into the universal loader: the loader brings its own ASM/Mixin, logging stays single, no modlauncher boot. */
    static final List<String> EXCLUDE = List.of("/org/ow2/asm/", "/net/fabricmc/sponge-mixin/", "/org/apache/logging/", "/org/slf4j/",
            "/cpw/mods/bootstraplauncher/", "/net/minecrell/terminalconsoleappender/", "/org/jline/",
            // java.nio FileSystemProviders are only discovered on the system class loader: these go on the JVM class path
            "/cpw/mods/securejarhandler/", "/net/neoforged/JarJarFileSystems/");
    /** Dedicated server: its console (DedicatedServer console handler, NeoForge's TerminalConsoleAppender) needs jline. */
    static final List<String> SERVER_KEEP = List.of("/org/jline/", "/net/minecrell/terminalconsoleappender/");
    /** Libraries that must be on the JVM class path (parent loader) when the NeoForge platform is active. */
    public static final List<String> PARENT = List.of("cpw/mods/securejarhandler/", "net/neoforged/JarJarFileSystems/");

    public final boolean server;

    public NeoForgePlatform(Path dir) throws IOException { this(dir, false); }
    /** server: the dedicated-server base (server srg/extra from the server processors run, server.lzma). */
    public NeoForgePlatform(Path dir, boolean server) throws IOException {
        this.dir = dir; this.server = server;
        String side = server ? "-server-" : "-client-";
        Path cacheDir = dir.resolve("platform-cache");
        Path marker;
        try (var s = Files.list(cacheDir)) { marker = s.filter(p -> p.getFileName().toString().contains(side) && p.toString().endsWith(".json")).findFirst()
                .orElseThrow(() -> new IOException("no NeoForge platform base in " + cacheDir + " (run tools/neoforge_processors.py " + (server ? "server" : "client") + ")")); }
        JsonObject m = JsonParser.parseString(Files.readString(marker)).getAsJsonObject();
        srg = Path.of(m.get("srg").getAsString()); extra = Path.of(m.get("extra").getAsString());
        lzma = dir.resolve(server ? "inst/data/server.lzma" : "inst/data/client.lzma");
        JsonObject vj = JsonParser.parseString(Files.readString(dir.resolve("inst/version.json"))).getAsJsonObject();
        Map<String, String> ga = new HashMap<>();
        JsonArray game = vj.getAsJsonObject("arguments").getAsJsonArray("game");
        for (int i = 0; i + 1 < game.size(); i++) if (game.get(i).getAsString().startsWith("--")) ga.put(game.get(i).getAsString(), game.get(i + 1).getAsString());
        neoForge = ga.get("--fml.neoForgeVersion"); fml = ga.get("--fml.fmlVersion"); mc = ga.get("--fml.mcVersion"); neoForm = ga.get("--fml.neoFormVersion");
        universal = dir.resolve("libraries/net/neoforged/neoforge/" + neoForge + "/neoforge-" + neoForge + "-universal.jar");
        for (JsonElement e : vj.getAsJsonArray("libraries")) {
            String path = e.getAsJsonObject().getAsJsonObject("downloads").getAsJsonObject("artifact").get("path").getAsString();
            if (EXCLUDE.stream().anyMatch(x -> ("/" + path).contains(x)) && !(server && SERVER_KEEP.stream().anyMatch(x -> ("/" + path).contains(x)))) continue;
            libraries.add(dir.resolve("libraries").resolve(path));
        }
    }

    /** srg + binpatches + extra -> one Mojmap game jar for NeoForge. Cached by content. */
    public Path bakeGame(Path cacheRoot) throws IOException {
        String key = Hash.str(Hash.file(srg) + "|" + Hash.file(lzma) + "|" + Hash.file(extra) + "|" + Baker.PIPELINE);
        Path out = CacheUse.mark(cacheRoot.resolve("game-neoforge").resolve("neoforge-" + neoForge + (server ? "-server-" : "-") + key.substring(0, 16) + ".jar"));
        if (Files.exists(out)) return out;
        long t = System.nanoTime();
        Files.createDirectories(out.getParent());
        Map<String, BinPatcher.Patch> patches = BinPatcher.read(lzma);
        int patched = 0, created = 0, deleted = 0;
        Path tmp = out.resolveSibling(out.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
        Set<String> written = new HashSet<>();
        try (JarFile s = new JarFile(srg.toFile()); JarFile x = new JarFile(extra.toFile()); JarOutputStream o = new JarOutputStream(Files.newOutputStream(tmp))) {
            for (JarEntry e : Collections.list(s.entries())) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) continue;
                byte[] b = s.getInputStream(e).readAllBytes();
                String n = e.getName();
                if (n.endsWith(".class")) {
                    BinPatcher.Patch p = patches.get(n.substring(0, n.length() - 6));
                    if (p != null) { b = BinPatcher.apply(p, b); if (b == null) { deleted++; continue; } patched++; }
                }
                put(o, written, n, b);
            }
            for (BinPatcher.Patch p : patches.values()) if (!p.exists()) { put(o, written, p.obf() + ".class", BinPatcher.apply(p, null)); created++; }
            for (JarEntry e : Collections.list(x.entries())) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/") && !e.getName().equals("META-INF/MANIFEST.MF")) continue;
                if (written.contains(e.getName())) continue;
                put(o, written, e.getName(), x.getInputStream(e).readAllBytes());
            }
        }
        Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("[amber-ul] NeoForge 平台游戏 jar: 打补丁 %d, 新增 %d, 删除 %d (%.1f s)%n", patched, created, deleted, (System.nanoTime() - t) / 1e9);
        return out;
    }
    private static void put(JarOutputStream o, Set<String> written, String n, byte[] b) throws IOException {
        if (!written.add(n)) return;
        o.putNextEntry(new JarEntry(n)); o.write(b); o.closeEntry();
    }
}
