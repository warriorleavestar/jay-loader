package amber.ul.model;
/** ordering (mods.toml): AFTER = load after modId (the default for loaders without ordering), BEFORE = modId loads after this mod, NONE = no order. */
public record Dependency(String modId, String range, boolean required, String ordering) {
    public Dependency(String modId, String range, boolean required) { this(modId, range, required, "AFTER"); }
}
