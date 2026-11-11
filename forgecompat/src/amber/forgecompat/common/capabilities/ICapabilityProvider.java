package amber.forgecompat.common.capabilities;

import amber.forgecompat.common.util.LazyOptional;
import net.minecraft.core.Direction;

/** Forge's ICapabilityProvider. */
public interface ICapabilityProvider {
    <T> LazyOptional<T> getCapability(Capability<T> cap, Direction side);
    default <T> LazyOptional<T> getCapability(Capability<T> cap) { return getCapability(cap, null); }
}
