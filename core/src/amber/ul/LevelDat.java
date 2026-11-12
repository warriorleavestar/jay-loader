package amber.ul;

import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

/**
 * Reads the one value the loader needs from a world's level.dat (gzip NBT): {@code Data.DataVersion}, the data version
 * of the game that last saved the world. Plain Java, no game classes, so it works at bake time and on every game version.
 * Worlds saved before 1.9 have no DataVersion: -1.
 */
public final class LevelDat {
    private LevelDat() {}
    /** Data version of the game itself, per supported Minecraft version (SharedConstants / the version json's world_version). */
    public static int gameDataVersion(String mc) {
        return switch (mc) { case "1.21.1" -> 3955; case "1.20.1" -> 3465; case "1.12.2" -> 1343; default -> -1; };
    }

    public static int dataVersion(Path levelDat) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(levelDat))))) {
            if (in.readByte() != 10) throw new IOException("level.dat: root is not a compound");
            in.readUTF();
            Integer v = find(in, 10, "Data", true);
            return v == null ? -1 : v;
        }
    }

    /** Walks a compound's entries. top: look for the "Data" compound; inside it, return DataVersion. */
    private static Integer find(DataInputStream in, int type, String want, boolean top) throws IOException {
        while (true) {
            int t = in.readByte();
            if (t == 0) return null;
            String name = in.readUTF();
            if (top && t == 10 && name.equals(want)) return find(in, 10, "DataVersion", false);
            if (!top && t == 3 && name.equals(want)) return in.readInt();
            skip(in, t);
        }
    }

    private static void skip(DataInputStream in, int t) throws IOException {
        switch (t) {
            case 1 -> in.skipNBytes(1);
            case 2 -> in.skipNBytes(2);
            case 3, 5 -> in.skipNBytes(4);
            case 4, 6 -> in.skipNBytes(8);
            case 7 -> in.skipNBytes(len(in));
            case 8 -> in.skipNBytes(in.readUnsignedShort());
            case 9 -> { int et = in.readByte(), n = len(in); for (int i = 0; i < n; i++) skip(in, et); }
            case 10 -> { int c; while ((c = in.readByte()) != 0) { in.readUTF(); skip(in, c); } }
            case 11 -> in.skipNBytes(4L * len(in));
            case 12 -> in.skipNBytes(8L * len(in));
            default -> throw new IOException("level.dat: bad NBT tag type " + t);
        }
    }

    private static int len(DataInputStream in) throws IOException {
        int n = in.readInt(); if (n < 0) throw new IOException("level.dat: negative length"); return n;
    }
}
