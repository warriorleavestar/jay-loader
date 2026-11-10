/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.impl.networking.AmberAddon;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Amber re-base: see AmberAddon. sendPacket(CustomPacketPayload) is PacketSender's default (createPacket + send). */
public final class ClientConfigurationNetworkAddon extends AmberAddon<ClientConfigurationNetworking.ConfigurationPayloadHandler<?>> {
    public ClientConfigurationNetworkAddon(ClientConfigurationPacketListenerImpl handler) { super(handler, ClientNetworkingImpl.CONFIGURATION, PayloadTypeRegistryImpl.CONFIGURATION_C2S); }
    public void onServerReady() {}
    public void handleComplete() {}
    @Override public Packet<?> createPacket(CustomPacketPayload payload) { return ClientNetworkingImpl.createC2SPacket(payload); }
}
