package amber.ul.fabricbridge.net;

import amber.ul.fabricbridge.NeoBus;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.impl.networking.AmberAddon;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Fabric networking on NeoForge's network stack (phase 5, brief §4). Started from the Fabric entrypoint window when
 * fabric-networking-api-v1 is present.
 * - Payload types Fabric mods registered (PayloadTypeRegistry, buffered in Fabric's own PayloadTypeRegistryImpl) are handed to
 *   NeoForge in RegisterPayloadHandlersEvent: NetworkRegistry.register(..., optional = true, one version), one dispatcher per id.
 * - Server: CONFIGURE from RegisterConfigurationTasksEvent; play INIT/JOIN/DISCONNECT from PlayerLoggedIn/Out (one producer each).
 * - Client: ClientNetBridge (play events from ClientPlayerNetworkEvent, configuration START from NetworkRegistry's initialize*).
 */
public final class NetBridge {
    private NetBridge() {}
    static final String VERSION = "fabric-amber-1";
    static volatile boolean flushed;
    /** protocol|id -> {C2S codec, S2C codec} for ids whose two directions use different codecs. */
    static final Map<String, StreamCodec<?, ?>[]> SPLIT = new java.util.concurrent.ConcurrentHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static StreamCodec<? super net.minecraft.network.FriendlyByteBuf, ? extends CustomPacketPayload> splitCodec(ResourceLocation id, ConnectionProtocol protocol, PacketFlow flow) {
        if (SPLIT.isEmpty()) return null;
        StreamCodec[] c = SPLIT.get(protocol.id() + "|" + id);
        return c == null ? null : c[flow == PacketFlow.SERVERBOUND ? 0 : 1];
    }

    public static void init() {
        NeoBus.mod().addListener(RegisterPayloadHandlersEvent.class, NetBridge::flush);
        NeoBus.mod().addListener(RegisterConfigurationTasksEvent.class, NetBridge::configure);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            ServerGamePacketListenerImpl h = p.connection;
            ServerPlayConnectionEvents.INIT.invoker().onPlayInit(h, p.server);
            ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(h, ServerNetworkingImpl.getAddon(h), p.server);
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(p.connection, p.server);
            ServerNetworkingImpl.amber$forget(p.connection);
        });
        if (FMLEnvironment.dist == Dist.CLIENT) ClientNetBridge.init();
        NeoBus.log("Fabric 网络桥接已启动 (NeoForge 网络栈)");
    }

    static void flush(RegisterPayloadHandlersEvent e) {
        int n = flush(ConnectionProtocol.PLAY, PayloadTypeRegistryImpl.PLAY_C2S, PayloadTypeRegistryImpl.PLAY_S2C)
              + flush(ConnectionProtocol.CONFIGURATION, PayloadTypeRegistryImpl.CONFIGURATION_C2S, PayloadTypeRegistryImpl.CONFIGURATION_S2C);
        flushed = true;
        NeoBus.log("RegisterPayloadHandlersEvent: 交给 NeoForge %d 个 Fabric payload 类型 (optional, version %s)", n, VERSION);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int flush(ConnectionProtocol protocol, PayloadTypeRegistryImpl<?> c2s, PayloadTypeRegistryImpl<?> s2c) {
        Map<ResourceLocation, ?> up = AmberAddon.types(c2s), down = AmberAddon.types(s2c);
        Set<ResourceLocation> ids = new LinkedHashSet<>(up.keySet()); ids.addAll(down.keySet());
        int n = 0;
        for (ResourceLocation id : ids) {
            String ns = id.getNamespace();
            if (ns.equals("minecraft") || ns.equals("c") || ns.equals("neoforge")) continue;   // reserved / common channels: NeoForge's
            CustomPacketPayload.TypeAndCodec tu = (CustomPacketPayload.TypeAndCodec) up.get(id), td = (CustomPacketPayload.TypeAndCodec) down.get(id);
            CustomPacketPayload.TypeAndCodec tc = tu != null ? tu : td;
            Optional<PacketFlow> flow = tu != null && td != null ? Optional.empty() : Optional.of(tu != null ? PacketFlow.SERVERBOUND : PacketFlow.CLIENTBOUND);
            if (tu != null && td != null && tu.codec() != td.codec()) {   // one registration, codec chosen per direction (NetworkRegistryMixin)
                SPLIT.put(protocol.id() + "|" + id, new StreamCodec[]{tu.codec(), td.codec()});
                NeoBus.log("payload %s (%s): 两个方向 codec 不同, 按方向选择", id, protocol.id());
            }
            NetworkRegistry.register(tc.type(), tc.codec(), dispatcher(protocol, id), List.of(protocol), flow, VERSION, true);
            n++;
        }
        return n;
    }

    static IPayloadHandler<CustomPacketPayload> dispatcher(ConnectionProtocol protocol, ResourceLocation id) {
        return (payload, ctx) -> {
            if (ctx.flow() == PacketFlow.SERVERBOUND) ServerNetworkingImpl.amber$dispatch(protocol, id, payload, ctx);
            else ClientNetBridge.dispatch(protocol, id, payload, ctx);
        };
    }

    static void configure(RegisterConfigurationTasksEvent e) {
        if (e.getListener() instanceof ServerConfigurationPacketListenerImpl l)
            ServerConfigurationConnectionEvents.CONFIGURE.invoker().onSendConfiguration(l, ServerLifecycleHooks.getCurrentServer());
    }

    /** From ServerConfigurationListenerMixin: HEAD of runConfiguration (after NeoForge's pong(0) query). */
    public static void beforeConfigure(ServerConfigurationPacketListenerImpl l) {
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.invoker().onSendConfiguration(l, ServerLifecycleHooks.getCurrentServer());
    }

    public static void configDisconnect(ServerConfigurationPacketListenerImpl l) {
        ServerConfigurationConnectionEvents.DISCONNECT.invoker().onConfigureDisconnect(l, ServerLifecycleHooks.getCurrentServer());
        ServerNetworkingImpl.amber$forget(l);
    }
}
