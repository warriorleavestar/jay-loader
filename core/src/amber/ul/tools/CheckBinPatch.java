package amber.ul.tools;

import amber.ul.bake.BinPatcher;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** CheckBinPatch <client.lzma> <ART srg jar> <official patched jar>: our GDIFF output vs the official binarypatcher output. */
public final class CheckBinPatch {
    public static void main(String[] a) throws Exception {
        Map<String, BinPatcher.Patch> ps = BinPatcher.read(Path.of(a[0]));
        int same = 0, differ = 0, failed = 0, notInOfficial = 0, deleted = 0;
        try (JarFile srg = new JarFile(a[1]); JarFile off = new JarFile(a[2])) {
            for (BinPatcher.Patch p : ps.values()) {
                JarEntry be = srg.getJarEntry(p.obf() + ".class");
                byte[] mine;
                try { mine = BinPatcher.apply(p, be == null ? null : srg.getInputStream(be).readAllBytes()); }
                catch (Exception e) { failed++; System.out.println("FAILED " + p.obf() + " exists=" + p.exists() + " inSrg=" + (be != null) + ": " + e.getMessage()); continue; }
                JarEntry oe = off.getJarEntry(p.obf() + ".class");
                if (mine == null) { deleted++; if (oe != null) { differ++; System.out.println("deleted but present in official: " + p.obf()); } continue; }
                if (oe == null) { notInOfficial++; System.out.println("not in official output: " + p.obf()); continue; }
                if (Arrays.equals(mine, off.getInputStream(oe).readAllBytes())) same++; else { differ++; System.out.println("DIFFERS " + p.obf()); }
            }
        }
        System.out.printf("patches %d: byte-identical to official %d, deleted (absent in official too) %d, differ %d, failed %d, not in official %d%n", ps.size(), same, deleted, differ, failed, notInOfficial);
    }
}
