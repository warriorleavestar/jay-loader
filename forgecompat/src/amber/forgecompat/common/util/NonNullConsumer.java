package amber.forgecompat.common.util;

/** Forge's NonNullConsumer (removed in NeoForge). */
@FunctionalInterface
public interface NonNullConsumer<T> { void accept(T t); }
