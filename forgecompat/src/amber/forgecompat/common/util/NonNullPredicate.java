package amber.forgecompat.common.util;

/** Forge's NonNullPredicate (removed in NeoForge). */
@FunctionalInterface
public interface NonNullPredicate<T> { boolean test(T t); }
