package amber.ul;

/** Versions of the libraries the runtime provides (aligned with Fabric Loader 0.20.1 and NeoForge 21.1.265). Part of every cache key. */
public final class RuntimeVersions {
    private RuntimeVersions() {}
    public static final String MIXIN = "0.17.5+mixin.0.8.7", MIXINEXTRAS = "0.5.5", ASM = "9.10.1";
    /** Fabric Loader version Amber emulates for Fabric mods (dependency checks, mixin compat). */
    public static final String FABRIC_LOADER = "0.20.1";
    public static String fingerprint() { return "sponge-mixin-" + MIXIN + "|mixinextras-" + MIXINEXTRAS + "|asm-" + ASM + "|fabricloader-" + FABRIC_LOADER; }
}
