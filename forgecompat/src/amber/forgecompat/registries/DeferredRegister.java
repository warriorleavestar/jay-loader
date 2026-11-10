package amber.forgecompat.registries;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;

/** Forge's DeferredRegister over NeoForge's; entries are Forge RegistryObjects. */
public class DeferredRegister<T> {
    private final net.neoforged.neoforge.registries.DeferredRegister<T> neo;
    private final List<RegistryObject<T>> entries = new ArrayList<>();
    private DeferredRegister(net.neoforged.neoforge.registries.DeferredRegister<T> n) { neo = n; }

    public static <B> DeferredRegister<B> create(IForgeRegistry<B> reg, String modid) { return new DeferredRegister<>(net.neoforged.neoforge.registries.DeferredRegister.create(reg.getRegistryKey(), modid)); }
    public static <B> DeferredRegister<B> create(ResourceKey<? extends Registry<B>> key, String modid) { return new DeferredRegister<>(net.neoforged.neoforge.registries.DeferredRegister.create(key, modid)); }
    public static <B> DeferredRegister<B> create(ResourceLocation registryName, String modid) { return new DeferredRegister<>(net.neoforged.neoforge.registries.DeferredRegister.create(registryName, modid)); }

    @SuppressWarnings("unchecked")
    public <I extends T> RegistryObject<I> register(String name, Supplier<? extends I> sup) {
        RegistryObject<I> r = new RegistryObject<>(neo.register(name, sup));
        entries.add((RegistryObject<T>) (RegistryObject<?>) r);
        return r;
    }
    public void register(IEventBus bus) { neo.register(bus); }
    public Collection<RegistryObject<T>> getEntries() { return Collections.unmodifiableList(entries); }
    public ResourceKey<? extends Registry<T>> getRegistryKey() { return neo.getRegistryKey(); }
    public ResourceLocation getRegistryName() { return neo.getRegistryName(); }
    public Supplier<Registry<T>> getRegistry() { return neo.getRegistry(); }
    public net.minecraft.tags.TagKey<T> createTagKey(String path) { return neo.createTagKey(path); }
    public net.minecraft.tags.TagKey<T> createTagKey(ResourceLocation id) { return neo.createTagKey(id); }
}
