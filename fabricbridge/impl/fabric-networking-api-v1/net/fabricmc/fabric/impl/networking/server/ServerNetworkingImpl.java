/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.server;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.GlobalReceiverRegistry;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Amber re-base (same binary surface as Fabric API 0.116.17's class): addons per listener, dispatch from NeoForge. */
public final class ServerNetworkingImpl {
    public static final GlobalReceiverRegistry<ServerLoginNetworking.LoginQueryResponseHandler> LOGIN = new GlobalReceiverRegistry<>(PacketFlow.SERVERBOUND, ConnectionProtocol.LOGIN, null);
    public static final GlobalReceiverRegistry<ServerConfigurationNetworking.ConfigurationPacketHandler<?>> CONFIGURATION = new GlobalReceiverRegistry<>(PacketFlow.SERVERBOUND, ConnectionProtocol.CONFIGURATION, PayloadTypeRegistryImpl.CONFIGURATION_C2S);
    public static final GlobalReceiverRegistry<ServerPlayNetworking.PlayPayloadHandler<?>> PLAY = new GlobalReceiverRegistry<>(PacketFlow.SERVERBOUND, ConnectionProtocol.PLAY, PayloadTypeRegistryImpl.PLAY_C2S);
    private static final Map<Object, Object> ADDONS = Collections.synchronizedMap(new WeakHashMap<>());

    public ServerNetworkingImpl() {}

    public static ServerPlayNetworkAddon getAddon(ServerGamePacketListenerImpl h) { return (ServerPlayNetworkAddon) ADDONS.computeIfAbsent(h, k -> new ServerPlayNetworkAddon(h)); }
    public static ServerConfigurationNetworkAddon getAddon(ServerConfigurationPacketListenerImpl h) { return (ServerConfigurationNetworkAddon) ADDONS.computeIfAbsent(h, k -> new ServerConfigurationNetworkAddon(h)); }
    public static ServerLoginNetworkAddon getAddon(ServerLoginPacketListenerImpl h) {
        throw new UnsupportedOperationException("Fabric login networking is not supported on NeoForge by Amber's bridge (fabric_api_login_networking)");
    }
    public static Packet<ClientCommonPacketListener> createS2CPacket(CustomPacketPayload payload) { return new ClientboundCustomPayloadPacket(payload); }

    public static void amber$forget(Object listener) { ADDONS.remove(listener); }

    record PlayCtx(MinecraftServer server, ServerPlayer player, PacketSender responseSender) implements ServerPlayNetworking.Context {}
    record ConfigCtx(MinecraftServer server, ServerConfigurationPacketListenerImpl networkHandler, PacketSender responseSender) implements ServerConfigurationNetworking.Context {}

    /** One dispatcher per payload id (NetBridge): serverbound payload -> per-connection or global Fabric receiver. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void amber$dispatch(ConnectionProtocol protocol, ResourceLocation id, CustomPacketPayload payload, IPayloadContext ctx) {
        if (protocol == ConnectionProtocol.PLAY) {
            ServerGamePacketListenerImpl l = (ServerGamePacketListenerImpl) ctx.listener();
            ServerPlayNetworkAddon a = getAddon(l);
            ServerPlayNetworking.PlayPayloadHandler h = a.handler(id);
            if (h == null) return;
            ServerPlayer p = l.player;
            ctx.enqueueWork(() -> h.receive(payload, new PlayCtx(p.server, p, a)));   // upstream: play receivers run on the server thread
        } else {
            ServerConfigurationPacketListenerImpl l = (ServerConfigurationPacketListenerImpl) ctx.listener();
            ServerConfigurationNetworkAddon a = getAddon(l);
            ServerConfigurationNetworking.ConfigurationPacketHandler h = a.handler(id);
            if (h == null) return;
            h.receive(payload, new ConfigCtx(ServerLifecycleHooks.getCurrentServer(), l, a));   // upstream: configuration receivers run on the network thread
        }
    }
}
