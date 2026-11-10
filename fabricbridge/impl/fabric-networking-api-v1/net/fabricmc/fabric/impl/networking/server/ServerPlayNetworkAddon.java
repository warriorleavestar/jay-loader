/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.server;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.AmberAddon;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Amber re-base: see AmberAddon. */
public final class ServerPlayNetworkAddon extends AmberAddon<ServerPlayNetworking.PlayPayloadHandler<?>> {
    public ServerPlayNetworkAddon(ServerGamePacketListenerImpl handler) { super(handler, ServerNetworkingImpl.PLAY, PayloadTypeRegistryImpl.PLAY_S2C); }
    public void onClientReady() {}
    @Override public Packet<?> createPacket(CustomPacketPayload payload) { return ServerNetworkingImpl.createS2CPacket(payload); }
}
