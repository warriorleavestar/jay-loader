package amber.ul.fabricbridge.net;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.impl.networking.client.ClientConfigurationNetworkAddon;
import net.fabricmc.fabric.impl.networking.client.ClientNetworkingImpl;
import net.fabricmc.fabric.impl.networking.client.ClientPlayNetworkAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Client half of NetBridge (only loaded on the client dist). */
public final class ClientNetBridge {
    private ClientNetBridge() {}

    static void init() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            ClientPacketListener h = e.getPlayer().connection;
            Minecraft mc = Minecraft.getInstance();
            ClientPlayNetworkAddon a = ClientNetworkingImpl.getAddon(h);
            ClientNetworkingImpl.setClientPlayAddon(a);
            ClientNetworkingImpl.setClientConfigurationAddon(null);
            ClientPlayConnectionEvents.INIT.invoker().onPlayInit(h, mc);
            ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(h, a, mc);
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, e -> {
            ClientPacketListener h = Minecraft.getInstance().getConnection();
            if (h == null) return;
            ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(h, Minecraft.getInstance());
            ClientNetworkingImpl.amber$forget(h);
            ClientNetworkingImpl.setClientPlayAddon(null);
        });
    }

    static void dispatch(ConnectionProtocol protocol, ResourceLocation id, CustomPacketPayload payload, IPayloadContext ctx) {
        ClientNetworkingImpl.amber$dispatch(protocol, id, payload, ctx);
    }

    /** From NetworkRegistryClientMixin: TAIL of both client initialize*Connection (NeoForge and vanilla/other servers). */
    public static void configurationStart(Object listener) {
        if (!(listener instanceof ClientConfigurationPacketListenerImpl l)) return;
        Minecraft mc = Minecraft.getInstance();
        ClientConfigurationNetworkAddon a = ClientNetworkingImpl.getAddon(l);
        ClientNetworkingImpl.setClientConfigurationAddon(a);
        ClientConfigurationConnectionEvents.INIT.invoker().onConfigurationInit(l, mc);
        ClientConfigurationConnectionEvents.START.invoker().onConfigurationStart(l, mc);
    }
}
