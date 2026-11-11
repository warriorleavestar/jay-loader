package amber.forgecompat.amber_internal;

import amber.forgecompat.common.capabilities.Capability;
import amber.forgecompat.common.capabilities.ICapabilityProvider;
import amber.forgecompat.common.util.LazyOptional;
import amber.forgecompat.event.AttachCapabilitiesEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Forge capabilities on NeoForge objects. Forge patched {@code getCapability}/{@code reviveCaps}/{@code invalidateCaps}
 * into Entity, BlockEntity, Level and LevelChunk; Amber's converter turns those calls into calls to the static methods
 * here. The providers attached by AttachCapabilitiesEvent live in a NeoForge data attachment on the object, which
 * NeoForge saves and loads with it (Forge kept them under "ForgeCaps"; here they are under the attachment's key).
 * The event is posted the first time a capability is asked for, not when the object is constructed.
 */
public final class Caps {
    private Caps() {}

    /** Per-object capability state; {@code providers} is null until the attach event has run. */
    public static final class Store {
        Map<ResourceLocation, ICapabilityProvider> providers;
        final List<Runnable> listeners = new ArrayList<>();
        boolean valid = true;
        CompoundTag pending; HolderLookup.Provider pendingLookup;
    }

    static Supplier<AttachmentType<Store>> TYPE;
    static final boolean LOG = Boolean.getBoolean("amber.forgecompat.log");
    static final Set<String> logged = ConcurrentHashMap.newKeySet();

    static final IAttachmentSerializer<CompoundTag, Store> SERIALIZER = new IAttachmentSerializer<>() {
        @Override public Store read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider lookup) {
            Store s = new Store(); s.pending = tag; s.pendingLookup = lookup; return s;
        }
        @Override public CompoundTag write(Store s, HolderLookup.Provider lookup) {
            if (s.providers == null) return s.pending;   // never asked for since it was loaded: keep the saved data as is
            CompoundTag t = new CompoundTag();
            s.providers.forEach((k, p) -> { if (p instanceof INBTSerializable<?> ser) { Tag v = ser.serializeNBT(lookup); if (v != null) t.put(k.toString(), v); } });
            return t.isEmpty() ? null : t;
        }
    };

    public static void register(IEventBus modBus) {
        var reg = net.neoforged.neoforge.registries.DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, "amber_forgecompat");
        TYPE = reg.register("forge_caps", () -> AttachmentType.builder(() -> new Store()).serialize(SERIALIZER).build());
        reg.register(modBus);
    }

    static Class<?> attachType(Object o) {
        if (o instanceof Entity) return Entity.class;
        if (o instanceof BlockEntity) return BlockEntity.class;
        if (o instanceof LevelChunk) return LevelChunk.class;
        if (o instanceof Level) return Level.class;
        return o.getClass();
    }

    static Store store(Object o) {
        if (!(o instanceof IAttachmentHolder h) || TYPE == null) {
            if (o != null && logged.add("unsupported " + o.getClass().getName()))
                System.out.println("[amber-forgecompat] Forge capabilities on " + o.getClass().getName() + " are not supported; returning empty");
            return null;
        }
        Store s = h.getData(TYPE.get());
        if (s.providers == null) attach(o, s);
        return s;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void attach(Object o, Store s) {
        s.providers = new LinkedHashMap<>();   // set first: a handler asking for a capability of the same object gets none, as on Forge
        AttachCapabilitiesEvent ev = new AttachCapabilitiesEvent(attachType(o), o);
        NeoForge.EVENT_BUS.post(ev);
        s.providers.putAll(ev.getCapabilities());
        s.listeners.addAll(ev.getListeners());
        if (s.pending != null) {
            for (String k : s.pending.getAllKeys()) {
                ICapabilityProvider p = s.providers.get(ResourceLocation.tryParse(k));
                if (p instanceof INBTSerializable ser) ser.deserializeNBT(s.pendingLookup, s.pending.get(k));
            }
            s.pending = null; s.pendingLookup = null;
        }
        if (LOG && !s.providers.isEmpty() && logged.add("attach " + o.getClass().getName() + " " + s.providers.keySet()))
            System.out.println("[amber-forgecompat] attached capabilities " + s.providers.keySet() + " to " + o.getClass().getName());
    }

    // ---- targets of the converter's call redirects (receiver first) ----

    public static <T> LazyOptional<T> getCapability(Object o, Capability<T> cap) { return getCapability(o, cap, null); }
    /** {@code obj.getCapability(...)}: a converted mod class that overrides getCapability is an ICapabilityProvider. */
    public static <T> LazyOptional<T> getCapability(Object o, Capability<T> cap, Direction side) {
        if (o instanceof ICapabilityProvider p) return p.getCapability(cap, side);
        return superGetCapability(o, cap, side);
    }
    /** {@code super.getCapability(...)} in such an override: the attached providers (Forge's CapabilityProvider). */
    public static <T> LazyOptional<T> superGetCapability(Object o, Capability<T> cap) { return superGetCapability(o, cap, null); }
    public static <T> LazyOptional<T> superGetCapability(Object o, Capability<T> cap, Direction side) {
        Store s = store(o);
        if (s == null || !s.valid) return LazyOptional.empty();
        for (ICapabilityProvider p : s.providers.values()) {
            LazyOptional<T> r = p.getCapability(cap, side);
            if (r.isPresent()) return r;
        }
        return LazyOptional.empty();
    }
    public static void reviveCaps(Object o) { Store s = store(o); if (s != null) s.valid = true; }
    public static void invalidateCaps(Object o) {
        Store s = store(o);
        if (s != null && s.valid) { s.valid = false; s.listeners.forEach(Runnable::run); }
    }
}
