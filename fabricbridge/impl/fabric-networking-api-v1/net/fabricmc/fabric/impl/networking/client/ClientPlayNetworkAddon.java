/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.impl.networking.AmberAddon;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Amber re-base: see AmberAddon. */
public final class ClientPlayNetworkAddon extends AmberAddon<ClientPlayNetworking.PlayPayloadHandler<?>> {
    public ClientPlayNetworkAddon(ClientPacketListener handler) { super(handler, ClientNetworkingImpl.PLAY, PayloadTypeRegistryImpl.PLAY_C2S); }
    public void onServerReady() {}
    @Override public Packet<?> createPacket(CustomPacketPayload payload) { return ClientNetworkingImpl.createC2SPacket(payload); }
}
