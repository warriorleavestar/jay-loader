package amber.forgecompat.network;

/**
 * Forge's Channel (base of SimpleChannel); here only its nested {@link VersionTest}. Amber's channels are optional NeoForge
 * payloads, so version tests are kept but not used for negotiation.
 */
public abstract class Channel {
    @FunctionalInterface
    public interface VersionTest {
        enum Status { MISSING, PRESENT, VANILLA }
        boolean accepts(Status status, int version);
        static VersionTest exact(int version) { return (s, v) -> s == Status.PRESENT && v == version; }
        VersionTest ACCEPT_MISSING = (s, v) -> s == Status.MISSING;
        VersionTest ACCEPT_VANILLA = (s, v) -> s == Status.VANILLA;
        default VersionTest or(VersionTest o) { return (s, v) -> accepts(s, v) || o.accepts(s, v); }
    }
}
