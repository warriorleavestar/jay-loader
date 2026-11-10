/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.networking;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.extensions.ICommonPacketListener;

/**
 * Amber re-base of Fabric's per-connection network addon (phase 5, brief §4). Upstream's addons live on the vanilla packet
 * listeners through mixins (vetoed: NeoForge owns minecraft:register, the custom payload codecs and the handshake). Here an
 * addon is a plain object per listener: per-connection receivers, receivable channels (global + local), sendable channels
 * (the Fabric-registered ids the remote side negotiated with NeoForge: ICommonPacketListener.hasChannel), and PacketSender.
 * Inbound payloads arrive through NeoForge's registry (amber.ul.fabricbridge.net.NetBridge) and are dispatched at receive time.
 */
public abstract class AmberAddon<H> implements PacketSender {
    protected final ICommonPacketListener listener;
    private final GlobalReceiverRegistry<H> global;
    private final PayloadTypeRegistryImpl<?> sendTypes;
    private final Map<ResourceLocation, H> local = new ConcurrentHashMap<>();

    protected AmberAddon(ICommonPacketListener listener, GlobalReceiverRegistry<H> global, PayloadTypeRegistryImpl<?> sendTypes) {
        this.listener = listener; this.global = global; this.sendTypes = sendTypes;
    }

    /** Receiver for an inbound payload: per-connection first, then global (looked up at receive time). */
    public H handler(ResourceLocation id) { H h = local.get(id); return h != null ? h : global.getHandler(id); }

    public Set<ResourceLocation> getReceivableChannels() {
        Set<ResourceLocation> s = new HashSet<>(global.getChannels()); s.addAll(local.keySet()); return s;
    }

    public Set<ResourceLocation> getSendableChannels() {
        Set<ResourceLocation> s = new HashSet<>();
        for (ResourceLocation id : ids(sendTypes)) if (listener.hasChannel(id)) s.add(id);
        return s;
    }

    public boolean registerChannel(ResourceLocation id, H handler) {
        global.assertPayloadType(id);
        return local.putIfAbsent(id, handler) == null;
    }

    public H unregisterChannel(ResourceLocation id) { return local.remove(id); }

    @Override
    public void sendPacket(Packet<?> packet, PacketSendListener callback) {
        if (callback == null) listener.send(packet); else listener.getConnection().send(packet, callback);
    }

    @Override
    public void disconnect(Component reason) { listener.disconnect(reason); }

    private static final Field TYPES;
    static {
        try { TYPES = PayloadTypeRegistryImpl.class.getDeclaredField("packetTypes"); TYPES.setAccessible(true); }
        catch (NoSuchFieldException e) { throw new ExceptionInInitializerError(e); }
    }

    /** Ids registered in one of Fabric's four payload type registries. */
    @SuppressWarnings("unchecked")
    public static Set<ResourceLocation> ids(PayloadTypeRegistryImpl<?> reg) {
        return Collections.unmodifiableSet(types(reg).keySet());
    }

    @SuppressWarnings("unchecked")
    public static Map<ResourceLocation, ?> types(PayloadTypeRegistryImpl<?> reg) {
        try { return (Map<ResourceLocation, ?>) TYPES.get(reg); } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
}
