package amber.forgecompat.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/** Forge's LazyOptional: a lazily resolved, invalidatable capability instance (NeoForge's capability API has no such type). */
public final class LazyOptional<T> {
    private static final LazyOptional<Void> EMPTY = new LazyOptional<>(null);
    private final NonNullSupplier<T> supplier;
    private final Object lock = new Object();
    private volatile T resolved;
    private volatile boolean isValid = true;
    private List<NonNullConsumer<LazyOptional<T>>> listeners;

    private LazyOptional(NonNullSupplier<T> supplier) { this.supplier = supplier; }

    public static <T> LazyOptional<T> of(NonNullSupplier<T> supplier) { return supplier == null ? empty() : new LazyOptional<>(supplier); }
    @SuppressWarnings("unchecked") public static <T> LazyOptional<T> empty() { return (LazyOptional<T>) EMPTY; }
    @SuppressWarnings("unchecked") public <X> LazyOptional<X> cast() { return (LazyOptional<X>) this; }

    private T getValue() {
        if (!isValid || supplier == null) return null;
        if (resolved == null) synchronized (lock) {
            if (resolved == null) resolved = Objects.requireNonNull(supplier.get(), "LazyOptional supplier returned null");
        }
        return resolved;
    }
    private T getValueUnsafe() { T v = getValue(); if (v == null) throw new IllegalStateException("LazyOptional is empty or invalidated"); return v; }

    public boolean isPresent() { return supplier != null && isValid; }
    public void ifPresent(NonNullConsumer<? super T> consumer) { T v = getValue(); if (isValid && v != null) consumer.accept(v); }
    public <U> LazyOptional<U> lazyMap(NonNullFunction<? super T, ? extends U> f) { return isPresent() ? of(() -> f.apply(getValueUnsafe())) : empty(); }
    public <U> Optional<U> map(NonNullFunction<? super T, ? extends U> f) { return isPresent() ? Optional.of(f.apply(getValueUnsafe())) : Optional.empty(); }
    public Optional<T> filter(NonNullPredicate<? super T> p) { T v = getValue(); return v != null && p.test(v) ? Optional.of(v) : Optional.empty(); }
    public Optional<T> resolve() { return isPresent() ? Optional.of(getValueUnsafe()) : Optional.empty(); }
    public T orElse(T other) { T v = getValue(); return v != null ? v : other; }
    public T orElseGet(NonNullSupplier<? extends T> other) { T v = getValue(); return v != null ? v : other.get(); }
    public T orElseThrow() { T v = getValue(); if (v == null) throw new NoSuchElementException("No value present"); return v; }
    public <X extends Throwable> T orElseThrow(NonNullSupplier<? extends X> ex) throws X { T v = getValue(); if (v == null) throw ex.get(); return v; }

    public void addListener(NonNullConsumer<LazyOptional<T>> listener) {
        if (isPresent()) synchronized (lock) { if (listeners == null) listeners = new ArrayList<>(); listeners.add(listener); }
        else listener.accept(this);
    }
    public void removeListener(NonNullConsumer<LazyOptional<T>> listener) { synchronized (lock) { if (listeners != null) listeners.remove(listener); } }

    public void invalidate() {
        List<NonNullConsumer<LazyOptional<T>>> ls;
        synchronized (lock) { if (!isValid) return; isValid = false; ls = listeners; listeners = null; }
        if (ls != null) ls.forEach(l -> l.accept(this));
    }
}
