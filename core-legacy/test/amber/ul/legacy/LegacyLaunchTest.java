package amber.ul.legacy;

import java.util.Arrays;

/**
 * Plain-main tests for core-legacy (no framework, Java 8). Run on the Java 8 JDK:
 *   mc/1.12.2/jdk8/bin/java -cp "build/core-legacy.jar;build/test-legacy" amber.ul.legacy.LegacyLaunchTest
 */
public final class LegacyLaunchTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + name); }

    /** A 1.12.2 chunk's NBT (root{DataVersion, Level{xPos, zPos, ...}}), zlib-compressed as in a region file. */
    static byte[] chunk(int x, int z) throws java.io.IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); java.io.DataOutputStream o = new java.io.DataOutputStream(b);
        o.writeByte(10); o.writeUTF(""); o.writeByte(3); o.writeUTF("DataVersion"); o.writeInt(1343);
        o.writeByte(10); o.writeUTF("Level");
        o.writeByte(3); o.writeUTF("xPos"); o.writeInt(x); o.writeByte(3); o.writeUTF("zPos"); o.writeInt(z); o.writeByte(1); o.writeUTF("TerrainPopulated"); o.writeByte(1);
        o.writeByte(9); o.writeUTF("Sections"); o.writeByte(10); o.writeInt(1); o.writeByte(7); o.writeUTF("Blocks"); o.writeInt(4096); o.write(new byte[4096]); o.writeByte(0);
        o.writeByte(0); o.writeByte(0);
        java.io.ByteArrayOutputStream c = new java.io.ByteArrayOutputStream();
        java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(c); d.write(b.toByteArray()); d.close();
        return c.toByteArray();
    }
    /** A region file with these chunks (key: (x & 31) + (z & 31) * 32), each in its own sectors, zlib. */
    static void region(java.io.File f, java.util.Map<Integer, byte[]> chunks) throws java.io.IOException {
        int[] head = new int[1024]; java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream(); java.io.DataOutputStream bo = new java.io.DataOutputStream(body); int sector = 2;
        for (java.util.Map.Entry<Integer, byte[]> e : chunks.entrySet()) {
            byte[] c = e.getValue(); int len = c.length + 1, sectors = (len + 4 + 4095) / 4096;
            head[e.getKey()] = (sector << 8) | sectors;
            bo.writeInt(len); bo.writeByte(2); bo.write(c); bo.write(new byte[sectors * 4096 - len - 4]);
            sector += sectors;
        }
        java.io.DataOutputStream o = new java.io.DataOutputStream(new java.io.FileOutputStream(f));
        for (int v : head) o.writeInt(v);
        o.write(new byte[4096]); o.write(body.toByteArray()); o.close();
    }

    public static void main(String[] a) {
        check("running on Java 8", "1.8".equals(System.getProperty("java.specification.version")));
        { // maintenance sliding window: index() is the inverse of the spiral, ring() its ring, release() after every neighbour
            LegacyMaintenance.Spiral sp = new LegacyMaintenance.Spiral(); boolean inv = true, rings = true;
            for (long i = 0; i < 20000; i++) { int[] d = sp.next(); inv &= LegacyMaintenance.Job.index(d[0], d[1]) == i; rings &= LegacyMaintenance.Job.ring(i) == Math.max(Math.abs(d[0]), Math.abs(d[1])); }
            check("maintenance: spiral index() inverts Spiral.next() and ring() matches (20000 positions)", inv && rings);
            boolean rel = true;
            for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) {
                long r = LegacyMaintenance.Job.release(x, z);
                for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) rel &= LegacyMaintenance.Job.index(x + i, z + j) < r;
                rel &= r > LegacyMaintenance.Job.index(x, z);
            }
            check("maintenance: release() comes after every neighbour", rel);
        }
        try {   // the save check reads the chunks back: radius 1 around chunk (0,0), 9 chunks over four region files
            java.io.File rd = java.nio.file.Files.createTempDirectory("amber-region").toFile();
            check("maintenance: no region files: the first chunk is not saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            java.util.Map<String, java.util.Map<Integer, byte[]>> regions = new java.util.HashMap<String, java.util.Map<Integer, byte[]>>(); int[][] sp = { {0,0}, {-1,-1}, {0,-1}, {1,-1}, {1,0}, {1,1}, {0,1}, {-1,1}, {-1,0} };
            for (int i = 0; i < 9; i++) if (i != 5) {
                int x = sp[i][0], z = sp[i][1]; String k = (x >> 5) + "." + (z >> 5);
                if (!regions.containsKey(k)) regions.put(k, new java.util.TreeMap<Integer, byte[]>());
                regions.get(k).put((x & 31) + (z & 31) * 32, chunk(x, z));
            }
            for (java.util.Map.Entry<String, java.util.Map<Integer, byte[]>> e : regions.entrySet()) region(new java.io.File(rd, "r." + e.getKey() + ".mca"), e.getValue());
            check("maintenance: one chunk not in its region file: its spiral index", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 9) == 5);
            check("maintenance: the range after it is all saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 6, 9) == -1);
            java.io.File r00 = new java.io.File(rd, "r.0.0.mca");
            byte[] bad = java.nio.file.Files.readAllBytes(r00.toPath()); Arrays.fill(bad, 8192, bad.length, (byte) 0); java.nio.file.Files.write(r00.toPath(), bad);
            check("maintenance: a header entry without readable chunk data counts as not saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            region(r00, java.util.Collections.singletonMap(0, chunk(5, 7)));
            check("maintenance: a chunk with other coordinates counts as not saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 1) == 0);
            region(r00, java.util.Collections.singletonMap(0, chunk(0, 0)));
            check("maintenance: the same chunk with its own coordinates: saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 1) == -1);
            byte[] full = java.nio.file.Files.readAllBytes(r00.toPath()); int clen = java.nio.ByteBuffer.wrap(full, 8192, 4).getInt();
            java.nio.file.Files.write(r00.toPath(), Arrays.copyOf(full, 8192 + 4 + clen));   // the last chunk not yet padded to its sector
            check("maintenance: the last chunk, not padded to its sector yet: saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 1) == -1);
            java.nio.file.Files.write(r00.toPath(), Arrays.copyOf(full, 8192 + 2 + clen));
            check("maintenance: chunk data cut short: not saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 1) == 0);
            java.io.FileOutputStream cut = new java.io.FileOutputStream(r00); cut.write(new byte[10]); cut.close();
            check("maintenance: a region file cut short counts as not saved", LegacyMaintenance.firstUnsaved(rd, 0, 0, 0, 9) == 0);
            // progress file: validated, written through a fresh temp file
            String ok = "{\"schema\":\"amber-ul/pregen@1\",\"dimension\":\"minecraft:overworld\",\"centerX\":3,\"centerZ\":-2,\"radius\":12,\"done\":100,\"total\":625,\"complete\":false}";
            check("maintenance: progress file resumes at done", LegacyFiles.resumeAt(ok, 3, -2, 12) == 100);
            check("maintenance: progress file for another centre: 0", LegacyFiles.resumeAt(ok, 0, 0, 12) == 0);
            check("maintenance: progress done = Long.MAX_VALUE is not valid", LegacyFiles.resumeAt(ok.replace("\"done\":100", "\"done\":9223372036854775807"), 3, -2, 12) == -1);
            check("maintenance: progress done larger than a long is not valid", LegacyFiles.resumeAt(ok.replace("\"done\":100", "\"done\":99999999999999999999"), 3, -2, 12) == -1);
            check("maintenance: progress total not matching the radius is not valid", LegacyFiles.resumeAt(ok.replace("\"total\":625", "\"total\":626"), 3, -2, 12) == -1);
            java.io.File pf = new java.io.File(rd, "amber-ul-pregen.json"), sentinel = java.nio.file.Files.createTempFile("amber-sentinel", ".txt").toFile();
            java.nio.file.Files.write(sentinel.toPath(), "keep".getBytes("UTF-8"));
            boolean linked = true;
            try { java.nio.file.Files.createLink(new java.io.File(rd, "amber-ul-pregen.json.tmp").toPath(), sentinel.toPath()); } catch (Exception e) { linked = false; }
            LegacyFiles.writeAtomically(pf, ok.getBytes("UTF-8")); LegacyFiles.writeAtomically(pf, ok.getBytes("UTF-8"));
            check("maintenance: progress written through a fresh temp file; a planted link is not followed",
                    new String(java.nio.file.Files.readAllBytes(pf.toPath()), "UTF-8").equals(ok) && new String(java.nio.file.Files.readAllBytes(sentinel.toPath()), "UTF-8").equals("keep"));
            int tmps = 0; for (String n : rd.list()) if (n.endsWith(".tmp")) tmps++;
            check("maintenance: no temp file left behind", tmps == (linked ? 1 : 0));
            for (java.io.File f : rd.listFiles()) f.delete(); rd.delete(); sentinel.delete();
        } catch (java.io.IOException e) { check("maintenance: region / progress test: " + e, false); }
        {   // error text: home folder and user name masked
            String home0 = System.getProperty("user.home"), user0 = System.getProperty("user.name");
            try {
                System.setProperty("user.home", "C:\\Users\\fakeuser"); System.setProperty("user.name", "fakeuser");
                String d = LegacyFiles.describe(new java.nio.file.DirectoryNotEmptyException("C:\\Users\\fakeuser\\games\\inst\\saves\\w\\amber-ul-pregen.json"));
                check("maintenance: error text masks the home folder, stable reason and role", !d.toLowerCase().contains("c:\\users\\fakeuser") && d.startsWith("io_error (progress file)"));
                check("maintenance: user name masked as a folder name only", LegacyFiles.mask("D:/data/FakeUser/saves and fakeuserX").equals("D:/data/<user>/saves and fakeuserX"));
            } finally { System.setProperty("user.home", home0); System.setProperty("user.name", user0); }
        }
        check("javaMajor 1.8", LegacyLaunch.javaMajor("1.8") == 8);
        check("javaMajor 17", LegacyLaunch.javaMajor("17") == 17);
        check("javaMajor 21.0.2", LegacyLaunch.javaMajor("21.0.2") == 21);
        check("javaMajor garbage", LegacyLaunch.javaMajor("x") == -1 && LegacyLaunch.javaMajor(null) == -1);
        check("Java 8 ok", LegacyLaunch.javaProblem("1.8", false) == null);
        check("Java 7 refused", LegacyLaunch.javaProblem("1.7", false) != null && LegacyLaunch.javaProblem("1.7", true) != null);
        check("Java 21 refused by default", LegacyLaunch.javaProblem("21", false) != null);
        check("Java 21 allowed on request", LegacyLaunch.javaProblem("21", true) == null);
        String[] in = { "--version", "1.12.2", "--gameDir", "g" };
        String[] out = LegacyLaunch.launchArgs(in);
        check("FML tweaker appended", Arrays.asList(out).equals(Arrays.asList("--version", "1.12.2", "--gameDir", "g", "--tweakClass", LegacyLaunch.FML_TWEAKER)));
        check("caller args untouched", in.length == 4);
        check("FML tweaker not doubled", LegacyLaunch.launchArgs(out).length == out.length);
        String[] other = LegacyLaunch.launchArgs(new String[] { "--tweakClass", "optifine.OptiFineTweaker" });
        check("other tweakers kept, FML added", other.length == 4 && other[1].equals("optifine.OptiFineTweaker") && other[3].equals(LegacyLaunch.FML_TWEAKER));
        // v1.3.3: the bake percent-encodes the quick play world folder (UTF-8) so the JVM argument is ASCII
        String w = "My World caf" + (char) 0xe9 + " " + (char) 0x4e16 + "%+";
        check("quick play folder: percent-encoded UTF-8 decodes back", LegacyQuickPlay.decode("My%20World%20caf%C3%A9%20%E4%B8%96%25%2B").equals(w));
        check("quick play folder: plain ASCII and a stray % pass through", LegacyQuickPlay.decode("amber-worldtest").equals("amber-worldtest") && LegacyQuickPlay.decode("a%zz%4").equals("a%zz%4"));
        check("quick play: main menu = GuiMainMenu (or subclass) / GuiCustom only", !LegacyQuickPlay.mainMenu(new Object()));
        check("json string escaping", LegacyProblems.str("a\"b\\c\nd\u0001").equals("\"a\\\"b\\\\c\\nd\\u0001\"") && LegacyProblems.str(null).equals("null"));
        try {
            java.io.File f = java.io.File.createTempFile("amber-problems", ".jsonl");
            System.setProperty("amber.ul.problemsFile", f.getPath());
            LegacyProblems.open("9.9.9");
            String cn = "" + (char) 0x4e16 + (char) 0x754c;
            String line = LegacyProblems.report("error", "legacy_launch_failed", "boom \"x\" " + cn, "exception", "E", "rootCause", null);
            boolean ascii = true; for (char ch : line.toCharArray()) if (ch > 0x7e) ascii = false;
            check("problem line is pure ASCII, non-ASCII as \\uXXXX", ascii && line.contains("boom \\\"x\\\" \\" + "u4e16\\" + "u754c"));
            check("non-ASCII escapes, including surrogates and DEL", LegacyProblems.str(cn + (char) 0xe9 + (char) 0x7f + (char) 0xd83d + (char) 0xde00)
                    .equals("\"\\" + "u4e16\\" + "u754c\\" + "u00e9\\" + "u007f\\" + "ud83d\\" + "ude00\""));
            java.util.List<String> lines = java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
            check("problems file: launch line then the problem", lines.size() == 2 && lines.get(0).startsWith("{\"type\":\"launch\"") && lines.get(0).contains("\"loaderVersion\":\"9.9.9\"")
                    && lines.get(1).equals(line) && line.contains("\"code\":\"legacy_launch_failed\"") && line.contains("\"exception\":\"E\"") && !line.contains("rootCause"));
            LegacyProblems.open("9.9.9");
            check("problems file truncated at launch start", java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8).size() == 1);
            System.clearProperty("amber.ul.problemsFile"); f.delete();
        } catch (java.io.IOException e) { check("problems file: " + e, false); }
        try {
            java.lang.reflect.Method norm = Class.forName("amber.ul.legacy.lw.CachingLaunchClassLoader").getDeclaredMethod("mixinNormal", String.class);
            norm.setAccessible(true);
            String c = "x 0abdae9e-4eb5-4ef4-bdea-cb10f7c13c84 mdc13c84$m A$Anonymous$bc04c2c565324eea95100403c2ecbcd3";
            String l = "x 55f935d9-9b86-40ac-ba25-f42ed2656676 md656676$m A$Anonymous$d07541081a3f4c80b0d35faef837aeb8";
            check("mixin session id, md prefix and anonymous names normalised", norm.invoke(null, c).equals(norm.invoke(null, l)) && !norm.invoke(null, c).equals(norm.invoke(null, l + "!")));
        } catch (ClassNotFoundException | NoClassDefFoundError e) { System.out.println("  skip mixin normalisation (LaunchWrapper not on the test class path)"); }
        catch (ReflectiveOperationException e) { check("mixinNormal: " + e, false); }
        System.out.printf("RESULT legacy %s: %d passed, %d failed%n", fail == 0 ? "PASS" : "FAIL", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
