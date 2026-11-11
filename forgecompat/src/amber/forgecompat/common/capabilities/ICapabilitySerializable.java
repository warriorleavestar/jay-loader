package amber.forgecompat.common.capabilities;

import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/** Forge's ICapabilitySerializable (Forge's INBTSerializable is the same interface as NeoForge's, so it is renamed to it). */
public interface ICapabilitySerializable<T extends Tag> extends ICapabilityProvider, INBTSerializable<T> {}
