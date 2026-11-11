package amber.forgecompat.common.capabilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Forge's CapabilityManager: one Capability per type name, shared by {@link #get} and RegisterCapabilitiesEvent. */
public final class CapabilityManager {
    private CapabilityManager() {}
    private static final Map<String, Capability<?>> CAPS = new ConcurrentHashMap<>();
    @SuppressWarnings("unchecked")
    static <T> Capability<T> byName(String name) { return (Capability<T>) CAPS.computeIfAbsent(name, Capability::new); }
    public static <T> Capability<T> get(CapabilityToken<T> type) { return byName(type.getType()); }
    static void register(Class<?> type) { byName(type.getName()).registered = true; }
}
