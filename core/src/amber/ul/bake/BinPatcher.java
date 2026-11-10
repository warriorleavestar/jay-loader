package amber.ul.bake;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.tukaani.xz.LZMAInputStream;

/**
 * Applies NeoForge/Forge binpatches (data/client.lzma) in our own code, without running the installer's binarypatcher.
 * Format: LZMA("alone") of a jar with *.binpatch entries; each entry = version(1) obfName(UTF) srgName(UTF) exists(byte)
 * [adler32 of base class (int)] patchLength(int) GDIFF-v4 patch. The base must be byte-identical to what the patch was
 * made against (AutoRenamingTool output), which the checksum enforces.
 */
public final class BinPatcher {
    public record Patch(String obf, String srg, boolean exists, int checksum, byte[] gdiff) {}

    public static Map<String, Patch> read(Path lzma) throws IOException {
        Map<String, Patch> out = new LinkedHashMap<>();
        try (InputStream in = new LZMAInputStream(new BufferedInputStream(Files.newInputStream(lzma)));
             JarInputStream jar = new JarInputStream(in)) {
            for (JarEntry e; (e = jar.getNextJarEntry()) != null; ) {
                if (!e.getName().endsWith(".binpatch")) continue;
                DataInputStream d = new DataInputStream(new ByteArrayInputStream(jar.readAllBytes()));
                int ver = d.readByte();
                if (ver != 1) throw new IOException("binpatch version " + ver + " in " + e.getName());
                String obf = d.readUTF(), srg = d.readUTF();
                boolean exists = d.readBoolean();
                int ck = exists ? d.readInt() : 0;
                byte[] p = new byte[d.readInt()]; d.readFully(p);
                out.put(obf, new Patch(obf, srg, exists, ck, p));
            }
        }
        return out;
    }

    /** Apply one patch. base == null for classes the patch creates. Returns null when the patch DELETES the class (empty patch). */
    public static byte[] apply(Patch p, byte[] base) throws IOException {
        if (p.exists()) {
            if (base == null) throw new IOException("patch for missing class " + p.obf());
            Adler32 a = new Adler32(); a.update(base);
            if ((int) a.getValue() != p.checksum()) throw new IOException("base checksum mismatch for " + p.obf() + " (input is not the AutoRenamingTool output this patch was made against)");
        }
        if (p.gdiff().length == 0) return null;   // e.g. Util$10/Util$11: NeoForge renumbers anonymous classes, the old ones go away
        return gdiff(base == null ? new byte[0] : base, p.gdiff());
    }

    /** GDIFF v4 (the format javaxdelta writes): DATA 1..246 inline, 247 DATA ushort, 248 DATA int, 249..255 COPY variants, 0 EOF. */
    public static byte[] gdiff(byte[] base, byte[] patch) throws IOException {
        DataInputStream d = new DataInputStream(new ByteArrayInputStream(patch));
        if (d.readInt() != 0xD1FFD1FF || d.readUnsignedByte() != 4) throw new IOException("not a GDIFF v4 patch");
        ByteArrayOutputStream out = new ByteArrayOutputStream(base.length + 1024);
        while (true) {
            int c = d.readUnsignedByte();
            if (c == 0) break;
            if (c <= 246) { out.write(d.readNBytes(c)); continue; }
            switch (c) {
                case 247 -> out.write(d.readNBytes(d.readUnsignedShort()));
                case 248 -> out.write(d.readNBytes(d.readInt()));
                case 249 -> copy(out, base, d.readUnsignedShort(), d.readUnsignedByte());
                case 250 -> copy(out, base, d.readUnsignedShort(), d.readUnsignedShort());
                case 251 -> copy(out, base, d.readUnsignedShort(), d.readInt());
                case 252 -> copy(out, base, d.readInt(), d.readUnsignedByte());
                case 253 -> copy(out, base, d.readInt(), d.readUnsignedShort());
                case 254 -> copy(out, base, d.readInt(), d.readInt());
                case 255 -> copy(out, base, (int) d.readLong(), d.readInt());
                default -> throw new IOException("bad GDIFF command " + c);
            }
        }
        return out.toByteArray();
    }
    private static void copy(ByteArrayOutputStream out, byte[] base, int off, int len) throws IOException {
        if (off < 0 || off + len > base.length) throw new IOException("GDIFF copy out of range");
        out.write(base, off, len);
    }

    /** Self-test: apply every patch that creates a new class and check the result parses as a class file. */
    public static void main(String[] a) throws Exception {
        Map<String, Patch> ps = read(Path.of(a[0]));
        int created = 0;
        for (Patch p : ps.values()) if (!p.exists()) {
            byte[] b = apply(p, null);
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(b);
            System.out.println("new class " + cr.getClassName() + " (" + b.length + " bytes, super " + cr.getSuperName() + ")");
            created++;
        }
        System.out.println(ps.size() + " patches read, " + created + " new classes built OK");
        if (a.length > 1) {   // optional: base jar to check checksums against
            int ok = 0, bad = 0;
            try (JarFile jf = new JarFile(a[1])) {
                for (Patch p : ps.values()) if (p.exists()) {
                    JarEntry e = jf.getJarEntry(p.obf() + ".class"); if (e == null) { bad++; continue; }
                    try { apply(p, jf.getInputStream(e).readAllBytes()); ok++; } catch (IOException ex) { bad++; }
                }
            }
            System.out.println("against " + a[1] + ": applied " + ok + ", failed " + bad);
        }
    }
}
