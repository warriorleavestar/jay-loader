package amber.forgecompat.common.capabilities;

import amber.forgecompat.common.util.LazyOptional;

/** Forge's Capability: an identity token; providers compare with {@code ==} (see {@link #orEmpty}). */
public final class Capability<T> {
    private final String name;
    volatile boolean registered;
    Capability(String name) { this.name = name; }
    public String getName() { return name; }
    public boolean isRegistered() { return registered; }
    public <R> LazyOptional<R> orEmpty(Capability<R> toCheck, LazyOptional<T> inst) { return this == toCheck ? inst.cast() : LazyOptional.empty(); }
    @Override public String toString() { return "Capability[" + name + "]"; }
}
