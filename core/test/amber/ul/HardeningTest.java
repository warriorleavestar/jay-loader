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

    /** A chunk's NBT, zlib-compressed as in a region file: xPos, zPos, Status (1.13+) or inside Level (legacy). */
    static byte[] chunk(int x, int z, String status, boolean legacy) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b);
        o.writeByte(10); o.writeUTF("");
        o.writeByte(3); o.writeUTF("DataVersion"); o.writeInt(legacy ? 1343 : 3465);
        if (legacy) { o.writeByte(10); o.writeUTF("Level"); }
        o.writeByte(3); o.writeUTF("xPos"); o.writeInt(x); o.writeByte(3); o.writeUTF("zPos"); o.writeInt(z);
        if (status != null) { o.writeByte(8); o.writeUTF("Status"); o.writeUTF(status); }
        o.writeByte(9); o.writeUTF("sections"); o.writeByte(10); o.writeInt(1); o.writeByte(12); o.writeUTF("data"); o.writeInt(3); o.writeLong(1); o.writeLong(2); o.writeLong(3); o.writeByte(0);
        if (legacy) o.writeByte(0);
        o.writeByte(0);
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        try (var d = new java.util.zip.DeflaterOutputStream(c)) { d.write(b.toByteArray()); }
        return c.toByteArray();
    }
    /** A region file with these chunks (key: (x & 31) + (z & 31) * 32), each in its own sectors, zlib. */
    static void region(Path f, Map<Integer, byte[]> chunks) throws IOException {
        int[] head = new int[1024]; ByteArrayOutputStream body = new ByteArrayOutputStream(); DataOutputStream bo = new DataOutputStream(body); int sector = 2;
        for (var e : chunks.entrySet()) {
            byte[] c = e.getValue(); int len = c.length + 1, sectors = (len + 4 + 4095) / 4096;
            head[e.getKey()] = (sector << 8) | sectors;
            bo.writeInt(len); bo.writeByte(2); bo.write(c); bo.write(new byte[sectors * 4096 - len - 4]);
            sector += sectors;
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(all);
        for (int v : head) o.writeInt(v);
        o.write(new byte[4096]); o.write(body.toByteArray());
        Files.write(f, all.toByteArray());
    }

    public static void main(String[] a) throws Exception {
        Path root = Files.createTempDirectory("amber-hardening");
        try { run(root); } finally {
            try (var s = Files.walk(root)) { s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
        System.out.printf("RESULT hardening %s: %d passed, %d failed%n", fail == 0 ? "PASS" : "FAIL", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run(Path root) throws Exception {
        System.out.println("maintenance: the save check reads the chunks back");
        {   // radius 1 around chunk (0,0): 9 chunks over the four regions r.-1.-1, r.0.-1, r.-1.0, r.0.0
            Path rd = Files.createDirectories(root.resolve("region"));
            check("no region files: the first chunk is not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            int[][] sp = { {0,0}, {-1,-1}, {0,-1}, {1,-1}, {1,0}, {1,1}, {0,1}, {-1,1}, {-1,0} };
            Map<String, Map<Integer, byte[]>> regions = new HashMap<>();
            for (int i = 0; i < 9; i++) if (i != 5) { int x = sp[i][0], z = sp[i][1]; regions.computeIfAbsent((x >> 5) + "." + (z >> 5), k -> new TreeMap<>()).put((x & 31) + (z & 31) * 32, chunk(x, z, "minecraft:full", false)); }
            for (var e : regions.entrySet()) region(rd.resolve("r." + e.getKey() + ".mca"), e.getValue());
            check("one chunk not in its region file: its spiral index", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 9) == 5);
            check("the range after it is all saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 6, 9) == -1);
            Path r00 = rd.resolve("r.0.0.mca"); byte[] good = Files.readAllBytes(r00);
            byte[] bad = good.clone(); Arrays.fill(bad, 8192, bad.length, (byte) 0); Files.write(r00, bad);   // header entry, no chunk data
            check("a header entry without readable chunk data counts as not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            region(r00, Map.of(0, chunk(5, 7, "minecraft:full", false)));
            check("a chunk with other coordinates counts as not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            region(r00, Map.of(0, chunk(0, 0, "minecraft:features", false)));
            check("a chunk that is not full counts as not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 1) == 0);
            region(r00, Map.of(0, chunk(0, 0, "minecraft:full", false)));
            check("the same chunk, full: saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 1) == -1);
            byte[] full = Files.readAllBytes(r00); int clen = java.nio.ByteBuffer.wrap(full, 8192, 4).getInt();
            Files.write(r00, Arrays.copyOf(full, 8192 + 4 + clen));   // the last chunk not yet padded to its sector (the game pads on close)
            check("the last chunk, not padded to its sector yet: saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 1) == -1);
            Files.write(r00, Arrays.copyOf(full, 8192 + 2 + clen));
            check("chunk data cut short: not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 1) == 0);
            Files.write(r00, new byte[10]);   // a region file cut short: no entries, no exception
            check("a region file cut short counts as not saved", amber.ul.runtime.MaintenanceRun.firstUnsaved(rd, 0, 0, 0, 9) == 0);
        }
        System.out.println("maintenance: progress file");
        {
            String ok = "{\"schema\":\"amber-ul/pregen@1\",\"dimension\":\"minecraft:overworld\",\"centerX\":3,\"centerZ\":-2,\"radius\":12,\"done\":100,\"total\":625,\"complete\":false}";
            check("a valid file resumes at done", amber.ul.runtime.MaintenanceFiles.resumeAt(ok, 3, -2, 12) == 100);
            check("another centre starts at 0", amber.ul.runtime.MaintenanceFiles.resumeAt(ok, 0, 0, 12) == 0);
            check("a smaller radius now: at most its total", amber.ul.runtime.MaintenanceFiles.resumeAt(ok, 3, -2, 1) == 9);
            check("done = Long.MAX_VALUE is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("\"done\":100", "\"done\":9223372036854775807"), 3, -2, 12) == -1);
            check("done larger than a long is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("\"done\":100", "\"done\":99999999999999999999"), 3, -2, 12) == -1);
            check("negative done is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("\"done\":100", "\"done\":-5"), 3, -2, 12) == -1);
            check("total that does not match the radius is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("\"total\":625", "\"total\":626"), 3, -2, 12) == -1);
            check("a huge radius is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("\"radius\":12", "\"radius\":3000000000").replace("\"total\":625", "\"total\":1"), 3, -2, 12) == -1);
            check("another schema is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok.replace("pregen@1", "pregen@9"), 3, -2, 12) == -1);
            check("an oversized file is not valid", amber.ul.runtime.MaintenanceFiles.resumeAt(ok + " ".repeat(5000), 3, -2, 12) == -1);
            Path dir = Files.createDirectories(root.resolve("progress")), pf = dir.resolve("amber-ul-pregen.json"), sentinel = root.resolve("sentinel.txt");
            Files.writeString(sentinel, "keep");
            boolean linked = true;
            try { Files.createLink(dir.resolve("amber-ul-pregen.json.tmp"), sentinel); } catch (Exception e) { linked = false; }
            amber.ul.runtime.MaintenanceFiles.writeAtomically(pf.toFile(), utf8(ok));
            amber.ul.runtime.MaintenanceFiles.writeAtomically(pf.toFile(), utf8(ok));
            check("progress written through a fresh temp file; a planted link is not followed", Files.readString(pf).equals(ok) && Files.readString(sentinel).equals("keep") && (!linked || Files.readString(dir.resolve("amber-ul-pregen.json.tmp")).equals("keep")));
            try (var l = Files.list(dir)) { check("no temp file left behind", l.filter(f -> f.getFileName().toString().endsWith(".tmp")).count() == (linked ? 1 : 0)); }
        }
        System.out.println("maintenance: error text");
        {
            String home0 = System.getProperty("user.home"), user0 = System.getProperty("user.name");
            try {
                System.setProperty("user.home", "C:\\Users\\fakeuser"); System.setProperty("user.name", "fakeuser");
                String d = amber.ul.runtime.MaintenanceFiles.describe(new java.nio.file.DirectoryNotEmptyException("C:\\Users\\fakeuser\\games\\inst\\saves\\w\\amber-ul-pregen.json"));
                check("home folder masked, stable reason and role", !d.toLowerCase().contains("c:\\users\\fakeuser") && d.contains("~") && d.startsWith("io_error (progress file)"));
                String e = amber.ul.runtime.MaintenanceFiles.mask("D:/data/FakeUser/saves and C:/Users/fakeuser/x and fakeuserX");
                check("user name masked as a folder name only", e.equals("D:/data/<user>/saves and ~/x and fakeuserX"));
            } finally { System.setProperty("user.home", home0); System.setProperty("user.name", user0); }
        }
        System.out.println("level.dat budgets");
        {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); DataOutputStream o = new DataOutputStream(b);
            o.writeByte(10); o.writeUTF(""); for (int i = 0; i < 2000; i++) { o.writeByte(10); o.writeUTF("a"); }
            throwsIo("NBT nested 2000 deep: IOException, no stack overflow", () -> amber.ul.LevelDat.values(new ByteArrayInputStream(b.toByteArray()), "Data", 1 << 20));
            ByteArrayOutputStream b2 = new ByteArrayOutputStream(); DataOutputStream o2 = new DataOutputStream(b2);
            o2.writeByte(10); o2.writeUTF(""); o2.writeByte(7); o2.writeUTF("big"); o2.writeInt(Integer.MAX_VALUE);
            throwsIo("a 2 GiB byte array: IOException before reading", () -> amber.ul.LevelDat.values(new ByteArrayInputStream(b2.toByteArray()), "Data", 64L << 20));
            ByteArrayOutputStream b3 = new ByteArrayOutputStream(); DataOutputStream o3 = new DataOutputStream(b3);
            o3.writeByte(10); o3.writeUTF(""); o3.writeByte(9); o3.writeUTF("l"); o3.writeByte(1); o3.writeInt(Integer.MAX_VALUE);
            throwsIo("a list of 2^31 elements: IOException", () -> amber.ul.LevelDat.values(new ByteArrayInputStream(b3.toByteArray()), "Data", 64L << 20));
            Path dat = root.resolve("level.dat");
            try (var g = new DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(dat)))) { g.writeByte(10); g.writeUTF(""); g.writeByte(10); g.writeUTF("Data"); g.writeByte(3); g.writeUTF("DataVersion"); g.writeInt(3465); g.writeByte(0); g.writeByte(0); }
            check("a normal level.dat still reads", amber.ul.LevelDat.dataVersion(dat) == 3465);
        }
        System.out.println("bake requests: a null field counts as missing");
        {
            var q = com.google.gson.JsonParser.parseString("{\"side\":null,\"arr\":[1]}").getAsJsonObject();
            try { Bake.str(q, "side"); check("side null: bad request, not internal", false); } catch (Bake.BadRequest e) { check("side null: bad request (" + e.getMessage() + ")", e.getMessage().equals("missing field side")); }
            try { Bake.str(q, "arr"); check("an array field: bad request", false); } catch (Bake.BadRequest e) { check("an array field: bad request", true); }
        }
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
