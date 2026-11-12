package amber.ul.legacy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Runtime problems for forge-1.12.2 in the bake interface's format (amber.ul.RuntimeProblems in core, which is Java 21
 * and Gson-based; this is the Java 8, dependency-free copy for the game JVM): one JSON object per line in
 * launch.problemsFile (-Damber.ul.problemsFile), truncated at launch start, and the same line on stdout behind
 * "@amber-ul/problem ".
 */
public final class LegacyProblems {
    private LegacyProblems() {}
    public static final String PREFIX = "@amber-ul/problem ", SCHEMA = "amber-ul/runtime-problem@1";
    private static File file;

    /** Launch start: truncates the problems file (if any) and writes the launch line. */
    public static synchronized void open(String loaderVersion) {
        String p = System.getProperty("amber.ul.problemsFile");
        file = p == null || p.isEmpty() ? null : new File(p);
        if (file == null) return;
        String l = "{\"type\":\"launch\",\"schema\":\"" + SCHEMA + "\",\"loaderVersion\":" + str(loaderVersion) + ",\"platform\":\"forge-1.12.2\",\"time\":" + System.currentTimeMillis() + "}";
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        if (!write(l, false)) file = null;
    }

    /** One problem; kv are extra string fields as key, value pairs (null values left out). */
    public static synchronized String report(String severity, String code, String message, String... kv) {
        StringBuilder sb = new StringBuilder("{\"type\":\"problem\",\"schema\":\"").append(SCHEMA).append("\",\"severity\":").append(str(severity))
                .append(",\"code\":").append(str(code));
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) sb.append(',').append(str(kv[i])).append(':').append(str(kv[i + 1]));
        sb.append(",\"message\":").append(str(message)).append('}');
        String line = sb.toString();
        System.out.println(PREFIX + line); System.out.flush();
        if (file != null) write(line, true);
        return line;
    }

    private static boolean write(String line, boolean append) {
        try (OutputStream o = new FileOutputStream(file, append)) { o.write((line + "\n").getBytes(StandardCharsets.UTF_8)); return true; }
        catch (IOException e) { System.err.println("[amber-ul legacy] problems file " + file + ": " + e); return false; }
    }

    /** A JSON string literal. */
    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                // pure ASCII: Java 8 stdout uses the platform code page (GBK, cp1252...), so anything else would reach the
                // reader as mojibake or "?"; JSON readers turn the escapes back into the same text
                default: if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
