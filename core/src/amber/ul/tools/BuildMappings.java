package amber.ul.tools;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.ZipFile;
import net.fabricmc.mappingio.*;
import net.fabricmc.mappingio.adapter.*;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.format.proguard.ProGuardFileReader;
import net.fabricmc.mappingio.tree.MemoryMappingTree;

/** intermediary-v2.jar + client_mappings.txt -> one tiny v2 file: official / intermediary / named (Mojmap). */
public final class BuildMappings {
    public static void main(String[] a) throws IOException {
        Path interJar = Path.of(a[0]), proguard = Path.of(a[1]), out = Path.of(a[2]);
        MemoryMappingTree tree = new MemoryMappingTree();
        try (ZipFile z = new ZipFile(interJar.toFile()); Reader r = new InputStreamReader(z.getInputStream(z.getEntry("mappings/mappings.tiny")))) {
            MappingReader.read(r, MappingFormat.TINY_2_FILE, tree);                       // official -> intermediary
        }
        try (Reader r = Files.newBufferedReader(proguard)) {
            ProGuardFileReader.read(r, "named", "official", new MappingSourceNsSwitch(tree, "official")); // named -> official, flipped
        }
        // Classes intermediary doesn't name (unobfuscated ones like client.main.Main) keep their official name.
        // Members are NOT completed: intermediary lists a method only on its root declaration; an override in a subclass
        // must stay unnamed so tiny-remapper propagates the root's name. Filling it with the official name (phase 1 did)
        // produced bogus "intermediary" names such as ClientChunkCache.j and broke inheritance propagation.
        int inter = tree.getNamespaceId("intermediary");
        for (var c : tree.getClasses()) if (c.getDstName(inter) == null) c.setDstName(c.getSrcName(), inter);
        Files.createDirectories(out.getParent());
        try (MappingWriter w = MappingWriter.create(out, MappingFormat.TINY_2_FILE)) {
            tree.accept(new MappingNsCompleter(w, Map.of("named", "official")));
        }
        System.out.printf("mappings: %d classes -> %s%n", tree.getClasses().size(), out);
    }
}
