package amber.forgecompat.common.util;

/** Forge's NonNullFunction (removed in NeoForge). */
@FunctionalInterface
public interface NonNullFunction<T, R> { R apply(T t); }
