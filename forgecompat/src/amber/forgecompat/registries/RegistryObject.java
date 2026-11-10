package amber.forgecompat.registries;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredHolder;

/** Forge's RegistryObject over NeoForge's DeferredHolder (a lazy reference by key). */
@SuppressWarnings({"unchecked", "rawtypes"})
public final class RegistryObject<T> implements Supplier<T> {
    private final DeferredHolder holder;
    RegistryObject(DeferredHolder h) { holder = h; }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, U extends T> RegistryObject<U> create(ResourceLocation name, IForgeRegistry<T> registry) {
        return new RegistryObject<>((DeferredHolder) DeferredHolder.create((ResourceKey) registry.getRegistryKey(), name));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, U extends T> RegistryObject<U> create(ResourceLocation name, ResourceKey<? extends Registry<T>> registryKey, String modid) {
        return new RegistryObject<>((DeferredHolder) DeferredHolder.create((ResourceKey) registryKey, name));
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T, U extends T> RegistryObject<U> create(ResourceLocation name, ResourceLocation registryName, String modid) {
        return new RegistryObject<>((DeferredHolder) DeferredHolder.create((ResourceKey) ResourceKey.createRegistryKey(registryName), name));
    }

    @Override public T get() { return (T) holder.get(); }
    public ResourceLocation getId() { return holder.getId(); }
    @SuppressWarnings("unchecked") public ResourceKey<T> getKey() { return (ResourceKey<T>) (ResourceKey<?>) holder.getKey(); }
    public boolean isPresent() { return holder.isBound(); }
    public Optional<T> asOptional() { return isPresent() ? Optional.of(get()) : Optional.empty(); }
    @SuppressWarnings("unchecked") public Optional<Holder<T>> getHolder() { return isPresent() ? Optional.of((Holder<T>) (Holder<?>) holder) : Optional.empty(); }
    @Override public String toString() { return "RegistryObject{" + getId() + "}"; }
    @Override public boolean equals(Object o) { return o instanceof RegistryObject<?> r && r.getId().equals(getId()); }
    @Override public int hashCode() { return getId().hashCode(); }
}
