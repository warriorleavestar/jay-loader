package amber.ul.tools;

import amber.ul.bake.*;
import java.nio.file.*;
import java.util.*;

/** RemapJar <in> <out> <mappings.tiny> <from> <to> <classpath jars...>: builds dev-namespace test mods into intermediary. */
public final class RemapJar {
    public static void main(String[] a) throws Exception {
        List<Path> cp = new ArrayList<>(); for (int i = 5; i < a.length; i++) cp.add(Path.of(a[i]));
        Baker.remapJar(Path.of(a[0]), Path.of(a[1]), cp, new Mappings.Set3(Path.of(a[2])).get(a[3], a[4]), false);
        System.out.println("remapped " + a[0] + " " + a[3] + " -> " + a[4]);
    }
}
