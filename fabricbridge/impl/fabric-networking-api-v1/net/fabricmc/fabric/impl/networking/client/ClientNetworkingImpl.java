/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.client;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Amber re-base (same binary surface as Fabric API 0.116.17's class): addons per listener, dispatch from NeoForge. */
public final class ClientNetworkingImpl {
    public static final GlobalReceiverRegistry<ClientLoginNetworking.LoginQueryRequestHandler> LOGIN = new GlobalReceiverRegistry<>(PacketFlow.CLIENTBOUND, ConnectionProtocol.LOGIN, null);
    public static final GlobalReceiverRegistry<ClientConfigurationNetworking.ConfigurationPayloadHandler<?>> CONFIGURATION = new GlobalReceiverRegistry<>(PacketFlow.CLIENTBOUND, ConnectionProtocol.CONFIGURATION, PayloadTypeRegistryImpl.CONFIGURATION_S2C);
    public static final GlobalReceiverRegistry<ClientPlayNetworking.PlayPayloadHandler<?>> PLAY = new GlobalReceiverRegistry<>(PacketFlow.CLIENTBOUND, ConnectionProtocol.PLAY, PayloadTypeRegistryImpl.PLAY_S2C);
    private static final Map<Object, Object> ADDONS = Collections.synchronizedMap(new WeakHashMap<>());
    private static ClientPlayNetworkAddon currentPlayAddon;
    private static ClientConfigurationNetworkAddon currentConfigurationAddon;

    public ClientNetworkingImpl() {}

    public static ClientPlayNetworkAddon getAddon(ClientPacketListener h) { return (ClientPlayNetworkAddon) ADDONS.computeIfAbsent(h, k -> new ClientPlayNetworkAddon(h)); }
    public static ClientConfigurationNetworkAddon getAddon(ClientConfigurationPacketListenerImpl h) { return (ClientConfigurationNetworkAddon) ADDONS.computeIfAbsent(h, k -> new ClientConfigurationNetworkAddon(h)); }
    public static ClientLoginNetworkAddon getAddon(ClientHandshakePacketListenerImpl h) {
        throw new UnsupportedOperationException("Fabric login networking is not supported on NeoForge by Amber's bridge (fabric_api_login_networking)");
    }
    public static Packet<ServerCommonPacketListener> createC2SPacket(CustomPacketPayload payload) { return new ServerboundCustomPayloadPacket(payload); }
    public static Connection getLoginConnection() { return null; }
    public static ClientConfigurationNetworkAddon getClientConfigurationAddon() { return currentConfigurationAddon; }
    public static ClientPlayNetworkAddon getClientPlayAddon() {
        ClientPacketListener c = Minecraft.getInstance().getConnection();
        return c != null ? getAddon(c) : currentPlayAddon;
    }
    public static void setClientPlayAddon(ClientPlayNetworkAddon a) { currentPlayAddon = a; }
    public static void setClientConfigurationAddon(ClientConfigurationNetworkAddon a) { currentConfigurationAddon = a; }
    /** Upstream registers c:version / c:register handling; NeoForge does that itself (skipped by the bridge manifest). */
    public static void clientInit() {}

    public static void amber$forget(Object listener) { ADDONS.remove(listener); }

    record PlayCtx(Minecraft client, LocalPlayer player, PacketSender responseSender) implements ClientPlayNetworking.Context {}
    record ConfigCtx(Minecraft client, ClientConfigurationPacketListenerImpl networkHandler, PacketSender responseSender) implements ClientConfigurationNetworking.Context {}

    /** One dispatcher per payload id (NetBridge): clientbound payload -> per-connection or global Fabric receiver. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void amber$dispatch(ConnectionProtocol protocol, ResourceLocation id, CustomPacketPayload payload, IPayloadContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (protocol == ConnectionProtocol.PLAY) {
            ClientPacketListener l = (ClientPacketListener) ctx.listener();
            ClientPlayNetworkAddon a = getAddon(l);
            ClientPlayNetworking.PlayPayloadHandler h = a.handler(id);
            if (h == null) return;
            ctx.enqueueWork(() -> h.receive(payload, new PlayCtx(mc, mc.player, a)));   // upstream: play receivers run on the client thread
        } else {
            ClientConfigurationPacketListenerImpl l = (ClientConfigurationPacketListenerImpl) ctx.listener();
            ClientConfigurationNetworkAddon a = getAddon(l);
            currentConfigurationAddon = a;
            ClientConfigurationNetworking.ConfigurationPayloadHandler h = a.handler(id);
            if (h == null) return;
            h.receive(payload, new ConfigCtx(mc, l, a));   // upstream: configuration receivers run on the network thread
        }
    }
}
