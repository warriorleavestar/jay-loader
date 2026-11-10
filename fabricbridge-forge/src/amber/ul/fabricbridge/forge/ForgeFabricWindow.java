package amber.ul.fabricbridge.forge;

import amber.ul.personality.FabricPersonality;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegistryManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the deferred Fabric main + client/server entrypoints inside Forge 47's registration window (design notes
 * docs/fabric-on-forge-1.20.1-design.md, section 1: D1 ported). Forge's GameData.unfreezeData has already unfrozen the
 * vanilla registries; the root registry is unfrozen for the duration (FabricRegistryBuilder adds registries there) and
 * refrozen in a finally. Forge 47 also locks each of its own registries (ForgeRegistry, which wraps the vanilla one and
 * refuses NamespacedWrapper.register while locked) except during that registry's RegisterEvent; the window unlocks every
 * Forge registry that is locked and locks exactly those again afterwards. The vanilla-facing wrapper of each
 * (NamespacedWrapper, package-private, "Modder should use Forge Register methods") has its own lock flag, which Forge
 * never clears; with it cleared, Registry.register goes straight to ForgeRegistry.add. Cleared here, set again (lock()) after. Forge then posts RegisterEvent per registry, freezes and snapshots, so Fabric registrations are
 * part of Forge's snapshot, id sync and remap like any Forge mod's.
 */
public final class ForgeFabricWindow {
    private ForgeFabricWindow() {}
    private static boolean done;
    /** NamespacedWrapper.locked (package-private class and field). */
    static final java.lang.reflect.Field LOCKED;
    static {
        java.lang.reflect.Field f = null;
        try { f = Class.forName("net.minecraftforge.registries.NamespacedWrapper").getDeclaredField("locked"); f.setAccessible(true); }
        catch (ReflectiveOperationException | RuntimeException e) { System.out.println("[amber-ul] 问题 fabric_forge_window (error): NamespacedWrapper.locked 不可用: " + e); }
        LOCKED = f;
    }

    public static void run() {
        if (done) return;
        done = true;
        if (!FabricPersonality.pending()) return;
        int before = BuiltInRegistries.REGISTRY.size();
        MappedRegistry<?> root = (MappedRegistry<?>) BuiltInRegistries.REGISTRY;
        long t = System.nanoTime();
        List<ForgeRegistry<?>> unlocked = new ArrayList<>();
        List<net.minecraftforge.registries.ILockableRegistry> wrappers = new ArrayList<>();
        for (ResourceLocation key : new ArrayList<>(BuiltInRegistries.REGISTRY.keySet())) {
            ForgeRegistry<?> fr = RegistryManager.ACTIVE.getRegistry(key);
            if (fr != null && fr.isLocked()) { fr.unfreeze(); unlocked.add(fr); }
            Object w = BuiltInRegistries.REGISTRY.get(key);
            if (w != null && LOCKED != null && LOCKED.getDeclaringClass().isInstance(w)) try {
                if (LOCKED.getBoolean(w)) { LOCKED.setBoolean(w, false); wrappers.add((net.minecraftforge.registries.ILockableRegistry) w); }
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        root.unfreeze();
        try { FabricPersonality.runDeferred(); }
        finally { root.freeze(); for (ForgeRegistry<?> fr : unlocked) fr.freeze(); for (var w : wrappers) w.lock(); }
        System.out.printf("[amber-fabric-bridge] Fabric 入口点完成 (%.1f ms, Forge 注册窗口), 根注册表、%d 个 Forge 注册表和 %d 个包装已重新锁定; Fabric 新建注册表 %d 个%n",
                (System.nanoTime() - t) / 1e6, unlocked.size(), wrappers.size(), BuiltInRegistries.REGISTRY.size() - before);
    }
}
