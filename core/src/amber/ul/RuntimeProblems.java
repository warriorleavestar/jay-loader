package amber.ul;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Runtime problems as JSON lines (bake interface v1.3), for the launcher to read without parsing log text:
 * <ul>
 * <li>a file next to the state, {@code <instanceDir>/.amber-ul/runtime-problems.jsonl} (the bake-instance result names
 *     it as {@code launch.problemsFile}); truncated at the start of every launch, one object per line, flushed per line;</li>
 * <li>the same object on stdout behind the fixed prefix {@code @amber-ul/problem }, for live display.</li>
 * </ul>
 * Line: {@code {"type":"problem","schema":"amber-ul/runtime-problem@1","severity","code","modId"?,"message",...fields}}.
 * The first line of the file is {@code {"type":"launch",...}}. The human-readable Chinese lines stay as they were.
 */
public final class RuntimeProblems {
    private RuntimeProblems() {}
    public static final String FILE = "runtime-problems.jsonl", PREFIX = "@amber-ul/problem ";
    static final Gson G = new GsonBuilder().disableHtmlEscaping().create();
    private static Path file;

    /** Launch start: the problems file is the state's sibling (or -Damber.ul.problemsFile). */
    public static synchronized void open(Path stateFile) {
        String o = System.getProperty("amber.ul.problemsFile");
        file = o != null ? Path.of(o) : stateFile != null ? stateFile.resolveSibling(FILE) : null;
        if (file == null) return;
        JsonObject l = new JsonObject();
        l.addProperty("type", "launch"); l.addProperty("schema", "amber-ul/runtime-problem@1");
        l.addProperty("loaderVersion", Bake.LOADER_VERSION); l.addProperty("interface", Bake.INTERFACE); l.addProperty("time", System.currentTimeMillis());
        try { Files.createDirectories(file.getParent()); Files.writeString(file, G.toJson(l) + "\n", StandardCharsets.UTF_8); }
        catch (IOException e) { System.err.println("[amber-ul] problems file " + file + ": " + e); file = null; }
    }

    /** One problem. {@code kv} are extra string fields as key, value pairs (null values are left out). */
    public static synchronized void report(String severity, String code, String modId, String message, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "problem"); o.addProperty("schema", "amber-ul/runtime-problem@1");
        o.addProperty("severity", severity); o.addProperty("code", code);
        if (modId != null) o.addProperty("modId", modId);
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) o.addProperty(kv[i], kv[i + 1]);
        o.addProperty("message", message);
        String line = G.toJson(o);
        System.out.println(PREFIX + line);
        if (file != null) try { Files.writeString(file, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException e) { System.err.println("[amber-ul] problems file " + file + ": " + e); }
    }
}
