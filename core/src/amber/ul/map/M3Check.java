package amber.ul.map;

import amber.ul.bake.Mappings;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

/**
 * M3 oracle that needs nothing new: the vanilla jar remapped obf -> intermediary by Amber (graph + JarRemapper) and by
 * tiny-remapper (configured like Baker.remapJar, Fabric's own tables), compared class by class like ModCheck.
 *   java ... amber.ul.map.M3Check <mappingsDir> <obf client.jar> <libsDir> <workDir> <report.json>
 * Output jars contain only intermediary names, but stay local anyway (mc/).
 */
public final class M3Check {
    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a[0]), obfJar = Path.of(a[1]), libsDir = Path.of(a[2]), work = Path.of(a[3]), report = Path.of(a[4]);
        List<Path> libs; try (Stream<Path> s = Files.walk(libsDir)) { libs = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        Path outA = Files.createDirectories(work.resolve("amber")), outT = Files.createDirectories(work.resolve("tiny"));
        Path inter; try (Stream<Path> s = Files.list(dir)) { inter = s.filter(p -> p.getFileName().toString().startsWith("intermediary-")).findFirst().orElseThrow(); }
        JsonObject r = new JsonObject();

        long a0 = System.nanoTime();
        MappingTree t = new MappingTree();
        Readers.proguard(t, dir.resolve("mojang-client.txt"), "mojmap");
        Readers.tinyJar(t, inter, Map.of("official", "obf"));
        JarIndex idx = JarIndex.of(obfJar, libs);
        new Completer(t, idx).run();
        long a1 = System.nanoTime();
        RemapNames names = new RemapNames(t, t.nsIndex("intermediary"), idx, new MappingTree(), 0);
        JarRemapper.Options plain = new JarRemapper.Options(false, false, false, false, false, false, false);
        new JarRemapper(names, plain, Runtime.getRuntime().availableProcessors()).run(obfJar, outA.resolve(obfJar.getFileName()));
        long a2 = System.nanoTime();

        // tiny-remapper with Fabric's intermediary file (official -> intermediary)
        Path tiny = work.resolve("intermediary.tiny");
        try (ZipFile z = new ZipFile(inter.toFile())) { try (InputStream is = z.getInputStream(z.getEntry("mappings/mappings.tiny"))) { Files.copy(is, tiny, StandardCopyOption.REPLACE_EXISTING); } }
        long b0 = System.nanoTime();
        Mappings m = new Mappings.Set3(tiny).get("official", "intermediary");
        amber.ul.bake.Baker.remapJar(obfJar, outT.resolve(obfJar.getFileName()), libs, m, false);
        long b1 = System.nanoTime();

        JsonObject ms = new JsonObject();
        ms.addProperty("amberGraph", (a1 - a0) / 1_000_000); ms.addProperty("amberRemap", (a2 - a1) / 1_000_000); ms.addProperty("tiny", (b1 - b0) / 1_000_000);
        r.add("ms", ms);
        r.add("compare", ModCheck.compare(List.of(obfJar), outA, outT, work.resolve("diffs.txt")));
        String js = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(r);
        Files.writeString(report, js, StandardCharsets.UTF_8);
        System.out.println(js);
        idx.close();
    }
}
