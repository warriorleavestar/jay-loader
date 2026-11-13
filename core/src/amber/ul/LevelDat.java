package amber.ul;

import java.io.*;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads the one value the loader needs from a world's level.dat (gzip NBT): {@code Data.DataVersion}, the data version
 * of the game that last saved the world. Plain Java, no game classes, so it works at bake time and on every game version.
 * Worlds saved before 1.9 have no DataVersion: -1.
 * <p>
 * The NBT is read with limits (nesting depth, decompressed bytes, number of elements), so a damaged or hostile file ends
 * in an IOException instead of a stack overflow or a long run.
 */
public final class LevelDat {
    private LevelDat() {}
    /** Nesting depth (as the game's own NbtAccounter), and the budgets for a level.dat. */
    public static final int MAX_DEPTH = 512;
    public static final long LEVEL_DAT_MAX_BYTES = 64L << 20, MAX_ELEMENTS = 10_000_000;

    /** Data version of the game itself, per supported Minecraft version (SharedConstants / the version json's world_version). */
    public static int gameDataVersion(String mc) {
        return switch (mc) { case "1.21.1" -> 3955; case "1.20.1" -> 3465; case "1.12.2" -> 1343; default -> -1; };
    }

    public static int dataVersion(Path levelDat) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(levelDat))) {
            Object v = values(in, "Data", LEVEL_DAT_MAX_BYTES).get("DataVersion");
            return v instanceof Integer i ? i : -1;
        }
    }

    /**
     * The int and string tags directly in the root compound, or (inside != null) directly in the root's compound of that
     * name; reading stops at the end of that compound. Everything else is skipped within the budgets.
     */
    public static Map<String, Object> values(InputStream raw, String inside, long maxBytes) throws IOException {
        Budget b = new Budget(maxBytes);
        DataInputStream in = new DataInputStream(new BufferedInputStream(new Counting(raw, b)));
        Map<String, Object> out = new HashMap<>();
        if (in.readByte() != 10) throw new IOException("NBT: the root is not a compound");
        in.readUTF();
        if (inside == null) { collect(in, out, b, 1); return out; }
        while (true) {
            int t = in.readByte();
            if (t == 0) return out;
            String name = in.readUTF();
            if (t == 10 && name.equals(inside)) { collect(in, out, b, 2); return out; }
            skip(in, t, b, 1);
        }
    }

    private static void collect(DataInputStream in, Map<String, Object> out, Budget b, int depth) throws IOException {
        while (true) {
            int t = in.readByte();
            if (t == 0) return;
            String name = in.readUTF();
            b.element();
            if (t == 3) out.put(name, in.readInt());
            else if (t == 8) out.put(name, in.readUTF());
            else skip(in, t, b, depth);
        }
    }

    private static void skip(DataInputStream in, int t, Budget b, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT: nested deeper than " + MAX_DEPTH);
        b.element();
        switch (t) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(b.array(len(in), 1));
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> { int et = in.readByte(), n = len(in); b.elements(n); for (int i = 0; i < n; i++) skip(in, et, b, depth + 1); }
            case 10 -> { int c; while ((c = in.readByte()) != 0) { in.readUTF(); skip(in, c, b, depth + 1); } }
            case 11 -> in.skipNBytes(b.array(len(in), 4));
            case 12 -> in.skipNBytes(b.array(len(in), 8));
            case 0 -> { if (depth == 1) throw new IOException("NBT: bad tag type 0"); }   // an empty list's element type
            default -> throw new IOException("NBT: bad tag type " + t);
        }
    }

    private static int len(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0) throw new IOException("NBT: negative length"); return n;
    }

    /** What one read may still use: decompressed bytes (counted by the stream) and elements. */
    static final class Budget {
        final long maxBytes; long bytes, elements;
        Budget(long maxBytes) { this.maxBytes = maxBytes; }
        void element() throws IOException { elements(1); }
        void elements(long n) throws IOException { if ((elements += n) > MAX_ELEMENTS) throw new IOException("NBT: more than " + MAX_ELEMENTS + " elements"); }
        long array(int n, int size) throws IOException {
            long len = (long) n * size;
            if (bytes + len > maxBytes) throw new IOException("NBT: larger than " + (maxBytes >> 20) + " MiB");
            return len;
        }
        void read(long n) throws IOException { if ((bytes += n) > maxBytes) throw new IOException("NBT: larger than " + (maxBytes >> 20) + " MiB"); }
    }

    /** Counts what is read or skipped from the decompressed stream against the budget. */
    static final class Counting extends FilterInputStream {
        final Budget b;
        Counting(InputStream in, Budget b) { super(in); this.b = b; }
        @Override public int read() throws IOException { int c = super.read(); if (c >= 0) b.read(1); return c; }
        @Override public int read(byte[] buf, int off, int len) throws IOException { int n = super.read(buf, off, len); if (n > 0) b.read(n); return n; }
        @Override public long skip(long n) throws IOException { long k = super.skip(n); if (k > 0) b.read(k); return k; }
    }
}
