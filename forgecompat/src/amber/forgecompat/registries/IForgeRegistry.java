package amber.forgecompat.registries;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Forge's IForgeRegistry, as a view of the vanilla registry (NeoForge has no separate Forge registries). */
public interface IForgeRegistry<V> extends Iterable<V> {
    Registry<V> vanilla();
    @SuppressWarnings("unchecked") default ResourceKey<Registry<V>> getRegistryKey() { return (ResourceKey<Registry<V>>) vanilla().key(); }
    default ResourceLocation getRegistryName() { return vanilla().key().location(); }
    default boolean containsKey(ResourceLocation key) { return vanilla().containsKey(key); }
    default boolean containsValue(V value) { return vanilla().getKey(value) != null; }
    default boolean isEmpty() { return vanilla().size() == 0; }
    default V getValue(ResourceLocation key) { return vanilla().getOptional(key).orElse(null); }
    default ResourceLocation getKey(V value) { return vanilla().getKey(value); }
    default Optional<ResourceKey<V>> getResourceKey(V value) { return vanilla().getResourceKey(value); }
    default Set<ResourceLocation> getKeys() { return vanilla().keySet(); }
    default Collection<V> getValues() { return vanilla().stream().toList(); }
    default Set<Map.Entry<ResourceKey<V>, V>> getEntries() { return vanilla().entrySet(); }
    @SuppressWarnings("unchecked") default Optional<Holder<V>> getHolder(ResourceLocation key) { return vanilla().getHolder(key).map(h -> (Holder<V>) h); }
    @SuppressWarnings("unchecked") default Optional<Holder<V>> getHolder(ResourceKey<V> key) { return vanilla().getHolder(key).map(h -> (Holder<V>) h); }
    default Optional<Holder<V>> getHolder(V value) { return getResourceKey(value).flatMap(this::getHolder); }
    @Override default java.util.Iterator<V> iterator() { return vanilla().iterator(); }

    static <V> IForgeRegistry<V> of(Registry<V> r) { return () -> r; }
}
