package amber.forgecompat.common.capabilities;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

/** Forge's RegisterCapabilitiesEvent (mod bus). Posted by amber_forgecompat to every mod bus during common setup. */
public class RegisterCapabilitiesEvent extends Event implements IModBusEvent {
    public <T> void register(Class<T> type) { CapabilityManager.register(type); }
}
