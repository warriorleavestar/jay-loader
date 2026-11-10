package amber.ul.fabricbridge;

import amber.ul.personality.FabricPersonality;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

/**
 * Runs the deferred Fabric main + client/server entrypoints inside NeoForge's registration window (brief §1.5).
 * The root registry is unfrozen for the duration, so FabricRegistryBuilder.buildAndRegister (a direct root add) works;
 * NeoForge's own registries are already unfrozen by GameData.unfreezeData. trackModdedRegistry is never called.
 * Registries created here that carry Fabric's SYNCED attribute are handed to NeoForge's sync (brief §3.4), again at
 * GameData.freezeData for registries created later in the window.
 */
public final class FabricWindow {
    private FabricWindow() {}
    private static boolean done;
    private static Set<ResourceLocation> before;
    static final boolean FAPI_REGISTRY = BridgeEnv.has("net/fabricmc/fabric/api/event/registry/RegistryAttributeHolder");

    public static void run() {
        if (done) return;
        done = true;
        if (!FabricPersonality.pending()) return;
        before = new HashSet<>(BuiltInRegistries.REGISTRY.keySet());
        MappedRegistry<?> root = (MappedRegistry<?>) BuiltInRegistries.REGISTRY;
        long t = System.nanoTime();
        startNetworking();
        startTransfer();
        startContent();   // before the entrypoints: snapshots the vanilla tables Fabric's registries write
        root.unfreeze();
        try { FabricPersonality.runDeferred(); }
        finally { root.freeze(); }
        System.out.printf("[amber-fabric-bridge] Fabric 入口点完成 (%.1f ms), 根注册表已重新冻结; Fabric 新建注册表 %d 个%n",
                (System.nanoTime() - t) / 1e6, BuiltInRegistries.REGISTRY.size() - before.size());
        syncAttributes("入口点之后");
    }

    /** Fabric networking on NeoForge (NetBridge), only when the bridge's impl.networking replacement is being served. */
    static void startNetworking() {
        // the official networking module itself (its api package); the bridge jar always carries the re-based impl classes
        if (!BridgeEnv.has("net/fabricmc/fabric/api/networking/v1/PacketSender") || !BridgeEnv.has("net/fabricmc/fabric/impl/networking/AmberAddon")) return;
        try { amber.ul.fabricbridge.net.NetBridge.init(); }
        catch (Throwable e) { NeoBus.problem("fabric_api_networking", "error", "Fabric 网络桥接启动失败: %s", e); }
    }

    /** Fabric Storage <-> NeoForge capabilities, only when the official transfer module is installed. */
    static void startTransfer() {
        if (!BridgeEnv.has("net/fabricmc/fabric/api/transfer/v1/item/ItemStorage") || Boolean.getBoolean("amber.fabricbridge.noTransfer")) return;   // test switch: negative control
        try { amber.ul.fabricbridge.fapi.transfer.TransferBridge.init(); }
        catch (Throwable e) { NeoBus.problem("fabric_api_transfer", "error", "Fabric transfer 桥接启动失败: %s", e); }
    }

    /** fabric-content-registries-v0 tables -> NeoForge data maps / events, only when that module is installed. */
    static void startContent() {
        if (!BridgeEnv.has("net/fabricmc/fabric/api/registry/FuelRegistry") || Boolean.getBoolean("amber.fabricbridge.noContent")) return;   // test switch: negative control
        try { amber.ul.fabricbridge.fapi.content.ContentBridge.init(); }
        catch (Throwable e) { NeoBus.problem("fabric_api_content_registries", "error", "Fabric content registries 桥接启动失败: %s", e); }
    }

    public static void syncAttributes(String when) {
        if (before == null || !FAPI_REGISTRY || Boolean.getBoolean("amber.fabricbridge.noSyncedAttr")) return;   // test switch: negative control
        try { amber.ul.fabricbridge.fapi.RegistryAttributes.apply(before, when); }
        catch (Exception e) { NeoBus.problem("fabric_api_registry_sync", "error", "SYNCED -> NeoForge 同步失败: %s", e); }
    }
}
