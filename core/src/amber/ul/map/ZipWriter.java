package amber.ul.map;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/**
 * Writes a zip with the same bytes java.util.zip.ZipOutputStream produces for DEFLATED entries whose sizes are not set
 * up front (flag bit 3 + data descriptor, UTF-8 flag, extended-timestamp extra for times outside 1980..2099), but takes
 * entries that were already compressed (in parallel, by {@link #deflate}). Layout written from the zip spec (APPNOTE)
 * and checked against ZipOutputStream output; no zip64 (fails if it would be needed).
 */
public final class ZipWriter implements Closeable {
    public record Packed(String name, long time, byte[] data, int crc, int size) {}

    /** Raw deflate at the default level, as ZipOutputStream does. */
    public static Packed deflate(String name, long time, byte[] raw, Deflater d) {
        d.reset(); d.setInput(raw); d.finish();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
        byte[] buf = new byte[65536];
        while (!d.finished()) { int n = d.deflate(buf); bo.write(buf, 0, n); }
        CRC32 c = new CRC32(); c.update(raw);
        return new Packed(name, time, bo.toByteArray(), (int) c.getValue(), raw.length);
    }
    static final byte[] EMPTY_DEFLATE = {3, 0};

    private final OutputStream out; private long written;
    private final List<long[]> cenMeta = new ArrayList<>(); private final List<Packed> cen = new ArrayList<>();

    public ZipWriter(OutputStream out) { this.out = out; }

    public void directory(String name, long time) throws IOException { add(new Packed(name, time, EMPTY_DEFLATE, 0, 0)); }

    public void add(Packed p) throws IOException {
        byte[] nm = p.name.getBytes(StandardCharsets.UTF_8);
        long dos = dosTime(p.time); boolean ext = needsExt(p.time);
        long off = written;
        int flag = 8 | 0x800;
        i32(0x04034b50); i16(20); i16(flag); i16(8); i32(dos); i32(0); i32(0); i32(0);
        i16(nm.length); i16(ext ? 9 : 0); bytes(nm);
        if (ext) { i16(0x5455); i16(5); i8(1); i32(p.time / 1000); }
        bytes(p.data);
        i32(0x08074b50); i32(p.crc); i32(p.data.length); i32(p.size);
        cen.add(p); cenMeta.add(new long[]{off, dos, ext ? 1 : 0});
    }

    @Override public void close() throws IOException {
        long cenOff = written;
        for (int i = 0; i < cen.size(); i++) {
            Packed p = cen.get(i); long[] m = cenMeta.get(i);
            byte[] nm = p.name.getBytes(StandardCharsets.UTF_8); boolean ext = m[2] == 1;
            i32(0x02014b50); i16(20); i16(20); i16(8 | 0x800); i16(8); i32(m[1]); i32(p.crc); i32(p.data.length); i32(p.size);
            i16(nm.length); i16(ext ? 9 : 0); i16(0); i16(0); i16(0); i32(0); i32(m[0]); bytes(nm);
            if (ext) { i16(0x5455); i16(5); i8(1); i32(p.time / 1000); }
        }
        long cenLen = written - cenOff;
        if (cen.size() > 0xFFFF || cenOff > 0xFFFFFFFFL) throw new IOException("zip64 not supported");
        i32(0x06054b50); i16(0); i16(0); i16(cen.size()); i16(cen.size()); i32(cenLen); i32(cenOff); i16(0);
        out.close();
    }

    /** MS-DOS date/time in the local zone; 1980-01-01 00:00 marker for earlier times (as java.util.zip does). */
    static long dosTime(long t) {
        LocalDateTime d = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault());
        int y = d.getYear();
        if (y < 1980) return (1 << 21) | (1 << 16);
        return ((long) (y - 1980) << 25 | d.getMonthValue() << 21 | d.getDayOfMonth() << 16 | d.getHour() << 11 | d.getMinute() << 5 | d.getSecond() >> 1) & 0xFFFFFFFFL;
    }
    static boolean needsExt(long t) {
        int y = LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).getYear();
        return y < 1980 || y > 2099;
    }

    private void i8(int v) throws IOException { out.write(v); written++; }
    private void i16(int v) throws IOException { out.write(v & 0xFF); out.write((v >>> 8) & 0xFF); written += 2; }
    private void i32(long v) throws IOException { i16((int) (v & 0xFFFF)); i16((int) ((v >>> 16) & 0xFFFF)); }
    private void bytes(byte[] b) throws IOException { out.write(b); written += b.length; }
}
