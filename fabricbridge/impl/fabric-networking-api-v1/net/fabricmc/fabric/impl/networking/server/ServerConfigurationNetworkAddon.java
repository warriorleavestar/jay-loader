/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.server;

import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.impl.networking.AmberAddon;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;

/** Amber re-base: see AmberAddon. */
public final class ServerConfigurationNetworkAddon extends AmberAddon<ServerConfigurationNetworking.ConfigurationPacketHandler<?>> {
    public ServerConfigurationNetworkAddon(ServerConfigurationPacketListenerImpl handler) { super(handler, ServerNetworkingImpl.CONFIGURATION, PayloadTypeRegistryImpl.CONFIGURATION_S2C); }
    @Override public Packet<?> createPacket(CustomPacketPayload payload) { return ServerNetworkingImpl.createS2CPacket(payload); }
}
