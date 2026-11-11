package amber.forgecompat.event;

import amber.forgecompat.common.capabilities.ICapabilityProvider;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

/**
 * Forge's AttachCapabilitiesEvent (game bus). Forge's was a GenericEvent filtered by its type argument; NeoForge's bus has no
 * generic events, so Amber's converter adds a {@link #getGenericType()} check to each {@code AttachCapabilitiesEvent<X>}
 * handler. Posted by amber_forgecompat the first time a capability of an object is asked for.
 */
public class AttachCapabilitiesEvent<T> extends Event {
    private final Class<T> type;
    private final T obj;
    private final Map<ResourceLocation, ICapabilityProvider> caps = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public AttachCapabilitiesEvent(Class<T> type, T obj) { this.type = type; this.obj = obj; }
    public Type getGenericType() { return type; }
    public T getObject() { return obj; }
    public void addCapability(ResourceLocation key, ICapabilityProvider cap) {
        if (caps.containsKey(key)) throw new IllegalStateException("Duplicate Capability Key: " + key + " " + cap);
        caps.put(key, cap);
    }
    public Map<ResourceLocation, ICapabilityProvider> getCapabilities() { return Collections.unmodifiableMap(caps); }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public List<Runnable> getListeners() { return Collections.unmodifiableList(listeners); }
}
