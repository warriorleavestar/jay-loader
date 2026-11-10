package amber.ul.fabricbridge.mixin.fapi;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.fabricmc.fabric.api.event.registry.RegistryIdRemapCallback;
import net.fabricmc.fabric.impl.registry.sync.ListenableRegistry;
import net.minecraft.core.MappedRegistry;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.callback.AddCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Re-base of fabric-registry-sync's SimpleRegistryMixin (vetoed: its addAlias override and get() alias redirects would hijack
 * NeoForge's aliases, and its remap/unmap belong to Fabric's own id sync). Keeps the ListenableRegistry surface that
 * RegistryEntryAddedCallback.event(registry) casts to; the add event is fed by a NeoForge AddCallback on the same registry.
 * The remap event exists (callers may register) but is not fired yet: NeoForge's clear/bake pairing is a later step.
 */
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryListenable<T> implements ListenableRegistry<T> {
    @Unique private volatile Event<RegistryEntryAddedCallback<T>> amber$add;
    @Unique private volatile Event<RegistryIdRemapCallback<T>> amber$remap;

    @Override
    public Event<RegistryEntryAddedCallback<T>> fabric_getAddObjectEvent() {
        Event<RegistryEntryAddedCallback<T>> e = amber$add;
        if (e == null) synchronized (this) {
            if ((e = amber$add) == null) {
                @SuppressWarnings("unchecked")
                Event<RegistryEntryAddedCallback<T>> n = (Event<RegistryEntryAddedCallback<T>>) (Event<?>) EventFactory.createArrayBacked(RegistryEntryAddedCallback.class,
                        cbs -> (raw, id, obj) -> { for (RegistryEntryAddedCallback cb : cbs) cb.onEntryAdded(raw, id, obj); });
                AddCallback<T> fwd = (reg, raw, key, value) -> n.invoker().onEntryAdded(raw, key.location(), value);
                ((BaseMappedRegistry<T>) (Object) this).addCallback(fwd);
                amber$add = e = n;
            }
        }
        return e;
    }

    @Override
    public Event<RegistryIdRemapCallback<T>> fabric_getRemapEvent() {
        Event<RegistryIdRemapCallback<T>> e = amber$remap;
        if (e == null) synchronized (this) {
            if ((e = amber$remap) == null) {
                @SuppressWarnings("unchecked")
                Event<RegistryIdRemapCallback<T>> n = (Event<RegistryIdRemapCallback<T>>) (Event<?>) EventFactory.createArrayBacked(RegistryIdRemapCallback.class,
                        cbs -> state -> { for (RegistryIdRemapCallback cb : cbs) cb.onRemap(state); });
                amber$remap = e = n;
            }
        }
        return e;
    }
}
