package amber.ul.runtime;
/** Runtime transformer (Mixin, Forge coremods, ...). fingerprint() must change whenever its output could change. */
public interface ClassTransformer {
    String fingerprint();
    byte[] transform(String internalName, byte[] bytes);
}
