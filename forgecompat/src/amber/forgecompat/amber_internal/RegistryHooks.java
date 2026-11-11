package amber.forgecompat.amber_internal;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forge registries allow overrides: registering a name that already exists replaces the entry (Skunk, for example,
 * re-registers copies of the vanilla armor materials). NeoForge's registries throw "Adding duplicate key". Amber's
 * converter sends a Forge mod's static {@code Registry.register*} calls here. A new key registers normally. A key that
 * already exists keeps its current entry, which is returned, and a warning is logged. The override itself is NOT applied:
 * NeoForge has no way to replace a registry entry.
 */
public final class RegistryHooks {
    private static final Logger LOG = LoggerFactory.getLogger("amber-forgecompat");
    private RegistryHooks() {}

    private static <T> ResourceKey<T> key(Registry<T> reg, ResourceLocation id) { return ResourceKey.create(reg.key(), id); }

    private static boolean exists(Registry<?> reg, ResourceLocation id) {
        if (!reg.containsKey(id)) return false;
        LOG.warn("Forge registry override not supported on NeoForge; keeping the existing {} {}", reg.key().location(), id);
        return true;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object register(Registry reg, String id, Object value) {
        return register(reg, ResourceLocation.parse(id), value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object register(Registry reg, ResourceLocation id, Object value) {
        if (exists(reg, id)) return reg.get(id);
        return Registry.register(reg, id, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object register(Registry reg, ResourceKey key, Object value) {
        if (exists(reg, key.location())) return reg.get(key.location());
        return Registry.register(reg, key, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Holder.Reference registerForHolder(Registry reg, ResourceKey key, Object value) {
        if (exists(reg, key.location())) return reg.getHolderOrThrow(key);
        return Registry.registerForHolder(reg, key, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Holder.Reference registerForHolder(Registry reg, ResourceLocation id, Object value) {
        if (exists(reg, id)) return reg.getHolderOrThrow(key(reg, id));
        return Registry.registerForHolder(reg, id, value);
    }
}
