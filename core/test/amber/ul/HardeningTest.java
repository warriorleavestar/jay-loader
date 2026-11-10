package amber.ul;

import amber.ul.bake.*;
import amber.ul.meta.Discovery;
import amber.ul.model.*;
import amber.ul.runtime.UniversalClassLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Small self-checking tests for the security-scan hardening (H13-H16). No framework: run
 *   java -cp "build/loader;build/test;<loader libs>" amber.ul.HardeningTest
 * Exit 0 = all pass. Works in a fresh temp dir it deletes afterwards.
 */
public final class HardeningTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + name); }
    static void throwsIo(String name, Runnable0 r) { try { r.run(); check(name, false); } catch (IOException e) { check(name, true); } catch (Exception e) { check(name + " (wrong exception " + e + ")", false); } }
    interface Runnable0 { void run() throws Exception; }

    static Path jar(Path p, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream o = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) { o.putNextEntry(new JarEntry(e.getKey())); o.write(e.getValue()); o.closeEntry(); }
        }
        return p;
    }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static String fmj(String id, String extra) { return "{\"schemaVersion\":1,\"id\":" + id + ",\"version\":\"1.0\"" + extra + "}"; }

    public static void main(String[] a) throws Exception {
        Path root = Files.createTempDirectory("amber-hardening");
        try { run(root); } finally {
            try (var s = Files.walk(root)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.printf("RESULT hardening %s: %d passed, %d failed%n", fail == 0 ? "PASS" : "FAIL", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run(Path root) throws Exception {
        Path cache = Files.createDirectories(root.resolve("cache"));
        System.out.println("H13 mod ids / cache paths");
        check("fabric id ok", SafeFiles.validModId(LoaderKind.FABRIC, "fabric-api-base"));
        check("fabric id ../ rejected", !SafeFiles.validModId(LoaderKind.FABRIC, "../../evil"));
        check("fabric id absolute rejected", !SafeFiles.validModId(LoaderKind.FABRIC, "C:/Windows/evil"));
        check("fabric id null rejected", !SafeFiles.validModId(LoaderKind.FABRIC, null));
        check("fabric id uppercase rejected", !SafeFiles.validModId(LoaderKind.FABRIC, "Evil"));
        check("neoforge id with '-' rejected (FML rule)", !SafeFiles.validModId(LoaderKind.NEOFORGE, "a-b"));
        check("neoforge id ok", SafeFiles.validModId(LoaderKind.NEOFORGE, "ironfurnaces"));
        throwsIo("under() rejects ../ escape", () -> SafeFiles.under(cache, cache.resolve("mods").resolve("../../x.jar")));
        throwsIo("under() rejects absolute", () -> SafeFiles.under(cache, cache.resolve("mods").resolve(root.resolve("x.jar").toAbsolutePath().toString())));
        check("under() accepts inside", SafeFiles.under(cache, cache.resolve("mods/abc.jar")) != null);
        Path evil = jar(root.resolve("evil.jar"), Map.of("fabric.mod.json", utf8(fmj("\"../../../escape\"", ""))));
        Path noId = jar(root.resolve("noid.jar"), Map.of("fabric.mod.json", utf8("{\"schemaVersion\":1,\"version\":\"1\"}")));
        Path good = jar(root.resolve("good.jar"), Map.of("fabric.mod.json", utf8(fmj("\"good_mod\"", ""))));
        Discovery d = new Discovery(cache, Map.of());
        List<ModInfo> found = d.discover(List.of(evil, noId, good));
        check("bad id not loaded, good one is", found.size() == 1 && found.get(0).id.equals("good_mod"));
        check("invalid_mod_id reported", d.problems.stream().anyMatch(p -> p.code().equals("invalid_mod_id")));
        check("missing id reported as bad_metadata (no crash)", d.problems.stream().anyMatch(p -> p.code().equals("bad_metadata")));
        check("nothing written outside the cache", !Files.exists(root.resolve("escape")) && !Files.exists(root.getParent().resolve("escape")));

        System.out.println("H14 bounded reads / nesting");
        throwsIo("boundedRead over limit", () -> SafeFiles.boundedRead(new ByteArrayInputStream(new byte[1001]), 1000, "x"));
        check("boundedRead at limit", SafeFiles.boundedRead(new ByteArrayInputStream(new byte[1000]), 1000, "x").length == 1000);
        byte[] big = new byte[(int) SafeFiles.METADATA_MAX + 10]; Arrays.fill(big, (byte) ' ');
        byte[] head = utf8(fmj("\"big_mod\"", "")); System.arraycopy(head, 0, big, 0, head.length);
        Path bigMeta = jar(root.resolve("bigmeta.jar"), Map.of("fabric.mod.json", big));
        Discovery d2 = new Discovery(cache, Map.of());
        List<ModInfo> f2 = d2.discover(List.of(bigMeta));
        check("oversized metadata -> resource_limit, no mod", f2.isEmpty() && d2.problems.stream().anyMatch(p -> p.code().equals("resource_limit")));
        byte[] child = Files.readAllBytes(jar(root.resolve("child.jar"), Map.of("fabric.mod.json", utf8(fmj("\"child_mod\"", "")))));
        Path parent = jar(root.resolve("parent.jar"), Map.of(
                "fabric.mod.json", utf8(fmj("\"parent_mod\"", ",\"jars\":[{\"file\":\"META-INF/jars/a.jar\"},{\"file\":\"META-INF/jars/b.jar\"}]")),
                "META-INF/jars/a.jar", child, "META-INF/jars/b.jar", child));
        Discovery d3 = new Discovery(cache, Map.of());
        List<ModInfo> f3 = d3.discover(List.of(parent));
        check("identical nested children deduped by sha256", f3.stream().filter(m -> m.id.equals("child_mod")).count() == 1 && d3.nestedJars == 2);
        try (var s = Files.list(cache.resolve("nested"))) { check("nested cache names are content hashes", s.allMatch(p -> p.getFileName().toString().matches("[0-9a-f]{32}\\.jar"))); }

        System.out.println("H15 class cache file");
        Path cf = cache.resolve("classes-test.bin");
        Map<String, byte[]> t = new HashMap<>(Map.of("a/B", new byte[]{1, 2, 3}));
        UniversalClassLoader.writeCache(cf, List.of("x/Y", "x/Z"), t);
        Set<String> u2 = new HashSet<>(); Map<String, byte[]> t2 = new HashMap<>();
        UniversalClassLoader.readCache(cf, u2, t2);
        check("round trip", u2.equals(Set.of("x/Y", "x/Z")) && Arrays.equals(t2.get("a/B"), new byte[]{1, 2, 3}));
        byte[] raw = Files.readAllBytes(cf); raw[raw.length / 2] ^= 1; Files.write(cf, raw);
        throwsIo("flipped byte -> checksum mismatch", () -> UniversalClassLoader.readCache(cf, new HashSet<>(), new HashMap<>()));
        Files.write(cf, Arrays.copyOf(raw, 20));
        throwsIo("truncated file rejected", () -> UniversalClassLoader.readCache(cf, new HashSet<>(), new HashMap<>()));
        try (var s = Files.list(cache)) { check("no temp files left behind", s.noneMatch(p -> p.toString().endsWith(".tmp"))); }
        Path win = cache.resolve("pub.bin"); Files.write(win, utf8("first"));
        Path tmp = SafeFiles.tempFor(win); Files.write(tmp, utf8("second"));
        SafeFiles.publish(tmp, win);
        check("publish replaces atomically and removes temp", Files.readString(win).equals("second") && !Files.exists(tmp));

        System.out.println("H16 bake inputs");
        Baker b = new Baker(cache.resolve("baked"));
        Path missing = root.resolve("no-such-game.jar");
        throwsIo("bakeGame with missing game jar fails", () -> b.bakeGame(missing, new AccessRules(), List.of()));
        check("... and writes nothing", !Files.exists(cache.resolve("baked/game")) || isEmpty(cache.resolve("baked/game")));
        throwsIo("context() needs the game jar", () -> Baker.context(missing, List.of()));
        Path g = jar(root.resolve("game.jar"), Map.of("a/A.class", new byte[]{0}));
        Path l1 = jar(root.resolve("lib-1.0.jar"), Map.of("x", new byte[]{1}));
        Path l2 = jar(root.resolve("lib-2.0.jar"), Map.of("x", new byte[]{1, 2}));
        check("context depends on libraries", !Baker.context(g, List.of(l1)).equals(Baker.context(g, List.of(l2))));
        check("context independent of library order", Baker.context(g, List.of(l1, l2)).equals(Baker.context(g, List.of(l2, l1))));
    }
    static boolean isEmpty(Path d) throws IOException { try (var s = Files.list(d)) { return s.findAny().isEmpty(); } }
}
