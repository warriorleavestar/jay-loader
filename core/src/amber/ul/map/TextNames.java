package amber.ul.map;

/**
 * Lookups for the text formats next to the bytecode (access wideners, mixin refmaps, MixinExtras @Definition strings):
 * names in the source namespace in, target names out; anything unknown comes back unchanged.
 * Implemented by the tiny-remapper path's table (amber.ul.bake.Mappings) and by {@link ModNames#text()}.
 */
public interface TextNames {
    String mapClass(String internal);
    /** Field or method descriptor. */
    String mapDesc(String desc);
    /** owner and desc may be null (name-only lookup: refmaps, wildcard targets). */
    String mapMethod(String owner, String name, String desc);
    String mapField(String owner, String name, String desc);
}
