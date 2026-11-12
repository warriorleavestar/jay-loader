package amber.ul.legacy;

import java.util.Arrays;

/**
 * Plain-main tests for core-legacy (no framework, Java 8). Run on the Java 8 JDK:
 *   mc/1.12.2/jdk8/bin/java -cp "build/core-legacy.jar;build/test-legacy" amber.ul.legacy.LegacyLaunchTest
 */
public final class LegacyLaunchTest {
    static int pass, fail;
    static void check(String name, boolean ok) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + name); }

    public static void main(String[] a) {
        check("running on Java 8", "1.8".equals(System.getProperty("java.specification.version")));
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
