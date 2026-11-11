package amber.forgecompat.common.util;

/** Forge's NonNullSupplier (removed in NeoForge). */
@FunctionalInterface
public interface NonNullSupplier<T> { T get(); }
