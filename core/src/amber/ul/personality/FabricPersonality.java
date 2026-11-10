package amber.ul.personality;

import amber.ul.model.*;
import amber.ul.runtime.UniversalClassLoader;
import java.util.List;
import net.fabricmc.api.*;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Fabric/Quilt lifecycle, same order as Fabric Loader: preLaunch; then all "main", then all "client" (or "server").
 * On the NeoForge platform the main/client run is deferred to NeoForge's registration window (HEAD of
 * GameData.postRegisterEvents, Amber's Fabric bridge): registries are unfrozen there and NeoForge has not yet captured its
 * registration order (phase 5, brief §1.5).
 */
public final class FabricPersonality implements Personality {
    public boolean accepts(LoaderKind k) { return k == LoaderKind.FABRIC || k == LoaderKind.QUILT; }

    private static volatile Runnable deferred;
    private static volatile boolean ran;
    /** Defer start() until runDeferred() (NeoForge registration window). */
    public static void defer(Runnable r) { deferred = r; }
    public static boolean pending() { return deferred != null; }
    public static boolean ran() { return ran; }
    /** Called by the bridge at HEAD of GameData.postRegisterEvents. No-op if nothing is deferred. */
    public static void runDeferred() { Runnable r = deferred; deferred = null; if (r != null) r.run(); }

    public void preLaunch(List<ModInfo> mods, UniversalClassLoader cl) {
        for (PreLaunchEntrypoint e : FabricFacade.entrypoints("preLaunch", PreLaunchEntrypoint.class)) e.onPreLaunch();
    }
    public void start(Side side, List<ModInfo> mods, UniversalClassLoader cl) {
        for (EntrypointContainer<ModInitializer> c : FabricFacade.entrypointContainers("main", ModInitializer.class)) run(c, "main", () -> c.getEntrypoint().onInitialize());
        // Quilt (item 3): "init" with QSL's ModInitializer(ModContainer), in the same phase as Fabric's "main" (as Quilt Loader does)
        for (EntrypointContainer<org.quiltmc.qsl.base.api.entrypoint.ModInitializer> c : FabricFacade.entrypointContainers("init", org.quiltmc.qsl.base.api.entrypoint.ModInitializer.class))
            run(c, "init", () -> c.getEntrypoint().onInitialize(QuiltContainer.of(c.getProvider().getMetadata().getId())));
        if (side == Side.CLIENT) {
            for (EntrypointContainer<ClientModInitializer> c : FabricFacade.entrypointContainers("client", ClientModInitializer.class)) run(c, "client", () -> c.getEntrypoint().onInitializeClient());
            for (EntrypointContainer<org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer> c : FabricFacade.entrypointContainers("client_init", org.quiltmc.qsl.base.api.entrypoint.client.ClientModInitializer.class))
                run(c, "client_init", () -> c.getEntrypoint().onInitializeClient(QuiltContainer.of(c.getProvider().getMetadata().getId())));
        } else {
            for (EntrypointContainer<DedicatedServerModInitializer> c : FabricFacade.entrypointContainers("server", DedicatedServerModInitializer.class)) run(c, "server", () -> c.getEntrypoint().onInitializeServer());
            for (EntrypointContainer<org.quiltmc.qsl.base.api.entrypoint.server.DedicatedServerModInitializer> c : FabricFacade.entrypointContainers("server_init", org.quiltmc.qsl.base.api.entrypoint.server.DedicatedServerModInitializer.class))
                run(c, "server_init", () -> c.getEntrypoint().onInitializeServer(QuiltContainer.of(c.getProvider().getMetadata().getId())));
        }
        ran = true;
    }
    static void run(EntrypointContainer<?> c, String key, Runnable r) {
        try { r.run(); }
        catch (RuntimeException | Error e) {
            throw new RuntimeException("Fabric 模组 " + c.getProvider().getMetadata().getId() + " 的 '" + key + "' 入口点失败: " + c.getDefinition(), e);
        }
    }
}
