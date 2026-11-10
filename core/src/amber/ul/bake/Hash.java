package amber.ul.bake;
import java.io.*; import java.nio.file.*; import java.security.*; import java.util.HexFormat;
public final class Hash {
    public static String sha256(byte[]... parts) {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256"); for (byte[] p : parts) md.update(p); return HexFormat.of().formatHex(md.digest()); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String file(Path p) throws IOException {
        try { MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(p)) { byte[] b = new byte[1 << 16]; int n; while ((n = in.read(b)) > 0) md.update(b, 0, n); }
            return HexFormat.of().formatHex(md.digest()); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String str(String s) { return sha256(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}
