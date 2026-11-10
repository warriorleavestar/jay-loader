package amber.ul.map;

/** What {@link JarRemapper} asks while rewriting a class: every answer defaults to the input name. */
public interface NameSource {
    String cls(String name);
    String method(String owner, String name, String desc);
    String field(String owner, String name, String desc);
    /** Local variable / parameter name for a slot of a method declared in {@code owner}; {@code def} = keep. */
    default String param(String owner, String name, String desc, int slot, String def) { return def; }
}
