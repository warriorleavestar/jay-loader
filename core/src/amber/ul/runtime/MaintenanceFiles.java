package amber.ul.runtime;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * File handling for maintenance (the same rules as amber.ul.legacy.LegacyFiles in core-legacy, the Java 8 copy):
 * reading a chunk back from its region file, NBT with budgets, writing the progress file through a fresh temp file,
 * and error text without the user's home folder or name.
 */
public final class MaintenanceFiles {
    private MaintenanceFiles() {}
    static final long CHUNK_MAX_BYTES = 64L << 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    // ---- region files ----

    /** One region file, opened once for a save check. A missing or short file has no chunks. */
    public static final class Region implements Closeable {
        final File file; RandomAccessFile f; final int[] header = new int[1024]; long length;
        public Region(File file) throws IOException {
            this.file = file;
            if (!file.isFile()) return;
            f = new RandomAccessFile(file, "r");
            length = f.length();
            if (length < 8192) return;   // a region file still being created: no complete header yet
            byte[] h = new byte[4096]; f.readFully(h);
            ByteBuffer b = ByteBuffer.wrap(h);
            for (int i = 0; i < 1024; i++) header[i] = b.getInt();
        }
        /**
         * Whether chunk (x, z) can be read back: a location inside the file, a length that fits its sectors, data that
         * decompresses and parses as NBT within the budgets, with the chunk's own coordinates (legacy: in "Level") and,
         * on 1.13+ worlds, status full. LZ4 chunks (an option since 1.20.5) count as present without being read.
         */
        public boolean saved(int x, int z, boolean legacy) {
            int e = header[(x & 31) + (z & 31) * 32];
            if (e == 0 || f == null) return false;
            long off = (e >>> 8) * 4096L; int sectors = e & 0xFF;
            if (off < 8192 || sectors == 0 || off + 5 > length) return false;   // the last chunk is padded to its sectors only when the game closes the file
            InputStream raw = null, in = null;
            try {
                f.seek(off);
                int len = f.readInt();
                if (len < 1 || len > sectors * 4096 - 4 || off + 4 + len > length) return false;
                int type = f.readUnsignedByte();
                if ((type & 0x80) != 0) {   // stored in its own file next to the region file
                    File ext = new File(file.getParentFile(), "c." + x + "." + z + ".mcc");
                    if (!ext.isFile() || ext.length() == 0) return false;
                    raw = new FileInputStream(ext); type &= 0x7F;
                } else { byte[] b = new byte[len - 1]; f.readFully(b); raw = new ByteArrayInputStream(b); }
                switch (type) {
                    case 1: in = new GZIPInputStream(raw); break;
                    case 2: in = new InflaterInputStream(raw); break;
                    case 3: in = raw; break;
                    case 4: return true;
                    default: return false;
                }
                Map<String, Object> v = amber.ul.LevelDat.values(in, legacy ? "Level" : null, CHUNK_MAX_BYTES);
                if (!Integer.valueOf(x).equals(v.get("xPos")) || !Integer.valueOf(z).equals(v.get("zPos"))) return false;
                if (legacy) return true;
                Object st = v.get("Status");
                return "minecraft:full".equals(st) || "full".equals(st);
            } catch (IOException | RuntimeException ex) {
                return false;
            } finally {
                try { if (in != null) in.close(); if (raw != null) raw.close(); } catch (IOException ignored) { }
            }
        }
        @Override public void close() throws IOException { if (f != null) f.close(); }
    }

    // ---- the progress file ----

    /**
     * Replaces target with data: written to a new temp file with a random name in the same folder (created exclusively,
     * never an existing path or link), forced to disk, then moved over target atomically while still open.
     */
    public static void writeAtomically(File target, byte[] data) throws IOException {
        Path dir = target.getAbsoluteFile().getParentFile().toPath();
        for (int attempt = 0; ; attempt++) {
            Path tmp = dir.resolve(target.getName() + "." + Long.toHexString(RANDOM.nextLong() & Long.MAX_VALUE) + ".tmp");
            FileChannel ch;
            try { ch = FileChannel.open(tmp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS); }
            catch (FileAlreadyExistsException e) { if (attempt < 16) continue; throw e; }
            boolean moved = false;
            try {
                ByteBuffer b = ByteBuffer.wrap(data);
                while (b.hasRemaining()) ch.write(b);
                ch.force(true);
                Files.move(tmp, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                moved = true;
            } finally {
                ch.close();
                if (!moved) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            return;
        }
    }

    /** The largest progress file read; anything bigger is not ours. */
    static final int PROGRESS_MAX_BYTES = 4096;
    static final long MAX_RADIUS = 4096;

    /**
     * The spiral index to continue at from the progress file's text, 0 when it is for another centre, or -1 when it is
     * not a valid progress file (wrong schema or dimension, a missing field, a radius, total or done out of range).
     * The result is at most side(radius) of the current run.
     */
    public static long resumeAt(String p, int cx, int cz, int radius) {
        if (p == null || p.length() > PROGRESS_MAX_BYTES) return -1;
        if (!p.contains("\"schema\":\"amber-ul/pregen@1\"") || !p.contains("\"dimension\":\"minecraft:overworld\"")) return -1;
        long x = num(p, "centerX"), z = num(p, "centerZ"), r = num(p, "radius"), done = num(p, "done"), total = num(p, "total");
        if (x == Long.MIN_VALUE || z == Long.MIN_VALUE || r < 0 || r > MAX_RADIUS || total != side(r) || done < 0 || done > total) return -1;
        if (x != cx || z != cz) return 0;
        return Math.min(done, side(radius));
    }
    static long side(long r) { long d = 2L * r + 1; return d * d; }
    /** A whole-number field, Long.MIN_VALUE when it is missing or not a long. */
    static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":(-?\\d{1,19})(?![\\d.eE])").matcher(json);
        if (!m.find()) return Long.MIN_VALUE;
        try { return Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return Long.MIN_VALUE; }
    }

    // ---- error text ----

    /** A stable reason code for a failure. */
    public static String reason(Throwable t) {
        if (t instanceof IOException || t instanceof UncheckedIOException) return "io_error";
        if (t instanceof IllegalStateException) return "maintenance_error";
        return "internal_error";
    }
    /** Which file a failure is about, from its text: progress file, region file, level.dat, cancel file, or world folder. */
    public static String role(String text) {
        if (text == null) return null;
        String t = text.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("amber-ul-pregen")) return "progress file";
        if (t.contains(".mca") || t.contains("region")) return "region file";
        if (t.contains("level.dat")) return "level.dat";
        if (t.contains("maintenance.cancel")) return "cancel file";
        return t.contains(File.separator) || t.contains("/") ? "world folder" : null;
    }
    /** Text with the user's home folder as ~ and the user name (as a folder name) as <user>. */
    public static String mask(String s) {
        if (s == null) return null;
        String home = System.getProperty("user.home"), user = System.getProperty("user.name");
        if (home != null && home.length() > 1) {
            String h = home.replaceAll("[\\\\/]+$", "");
            for (String v : new String[] { h, h.replace('\\', '/'), h.replace('/', '\\') })
                s = Pattern.compile(Pattern.quote(v) + "(?![^\\\\/\\s'\"),;])", Pattern.CASE_INSENSITIVE).matcher(s).replaceAll("~");
        }
        if (user != null && !user.isEmpty())
            s = Pattern.compile("(?<=[\\\\/])" + Pattern.quote(user) + "(?=[\\\\/]|$)", Pattern.CASE_INSENSITIVE).matcher(s).replaceAll("<user>");
        return s;
    }
    /** "reason (role): Kind: masked detail" for a failure. */
    public static String describe(Throwable t) {
        String msg = t.getMessage(), role = role(msg);
        return reason(t) + (role != null ? " (" + role + ")" : "") + ": " + t.getClass().getSimpleName() + (msg != null ? ": " + mask(msg) : "");
    }
    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
