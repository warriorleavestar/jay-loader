package amber.ul.fabricbridge.fapi;

import java.lang.reflect.Method;
import java.util.Set;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryAttributeHolder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.BaseMappedRegistry;

/** RegistryAttribute.SYNCED on a registry a Fabric mod created -> NeoForge sync (BaseMappedRegistry.setSync, package-private). */
public final class RegistryAttributes {
    private RegistryAttributes() {}
    public static int apply(Set<ResourceLocation> before, String when) throws Exception {
        Method setSync = BaseMappedRegistry.class.getDeclaredMethod("setSync", boolean.class);
        setSync.setAccessible(true);
        int n = 0;
        for (Registry<?> r : BuiltInRegistries.REGISTRY) {
            ResourceLocation id = r.key().location();
            if (before.contains(id) || r.doesSync() || !(r instanceof BaseMappedRegistry<?>)) continue;
            if (!RegistryAttributeHolder.get(r.key()).hasAttribute(RegistryAttribute.SYNCED)) continue;
            setSync.invoke(r, true);
            n++;
            System.out.println("[amber-fabric-bridge] 注册表 " + id + " 标记 SYNCED -> NeoForge 同步 (" + when + ")");
        }
        return n;
    }
}
