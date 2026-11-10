package amber.ul.model;

/** Which ecosystem a mod was written for. The core never branches on this except to pick a personality. */
public enum LoaderKind { FABRIC, QUILT, FORGE, NEOFORGE, BUILTIN, /** nested jar with no mod metadata */ LIBRARY;
    /** Mapping namespace the mod's bytecode is compiled against (for 1.21.x). */
    public String sourceNamespace() {
        return switch (this) { case FABRIC, QUILT -> "intermediary"; case FORGE -> "srg"; default -> "named"; };
    }
}
