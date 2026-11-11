package amber.forgecompat.network;

import amber.forgecompat.amber_internal.Net;
import amber.forgecompat.event.network.CustomPayloadEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Forge's SimpleChannel over NeoForge payloads. Every channel's messages travel in one optional NeoForge payload type
 * registered by amber_forgecompat ({@link Net}): channel name, discriminator, then the message as the mod encodes it.
 * So channels and messages can be made at any time, as on Forge, not only during NeoForge's payload registration.
 */
public class SimpleChannel extends Channel {
    static final Map<ResourceLocation, SimpleChannel> CHANNELS = new ConcurrentHashMap<>();

    record Msg<M>(int id, Class<M> type, BiConsumer<M, FriendlyByteBuf> encoder, Function<FriendlyByteBuf, M> decoder,
                  BiConsumer<M, CustomPayloadEvent.Context> handler) {}

    private final ResourceLocation name;
    private final Map<Integer, Msg<?>> byId = new ConcurrentHashMap<>();
    private final Map<Class<?>, Msg<?>> byType = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger();

    SimpleChannel(ResourceLocation name) { this.name = name; }
    static SimpleChannel create(ResourceLocation name) {
        SimpleChannel c = new SimpleChannel(name);
        if (CHANNELS.putIfAbsent(name, c) != null) throw new IllegalArgumentException("Channel " + name + " already exists");
        return c;
    }
    public static SimpleChannel byName(ResourceLocation name) { return CHANNELS.get(name); }
    public ResourceLocation getName() { return name; }

    public <M> MessageBuilder<M> messageBuilder(Class<M> type) { return new MessageBuilder<>(this, type, nextId.getAndIncrement()); }
    public <M> MessageBuilder<M> messageBuilder(Class<M> type, int id) { return new MessageBuilder<>(this, type, id); }

    /** Sends over this connection (to the server when called on the client's connection, else to that client). */
    public void send(Object msg, Connection connection) {
        Msg<?> m = byType.get(msg.getClass());
        if (m == null) throw new IllegalArgumentException("Message " + msg.getClass().getName() + " is not registered on channel " + name);
        Net.send(new Net.Pkt(this, m.id(), msg), connection);
    }

    // ---- used by Net ----
    @SuppressWarnings("unchecked")
    public void encode(int id, Object msg, FriendlyByteBuf buf) { ((Msg<Object>) byId.get(id)).encoder().accept(msg, buf); }
    public Object decode(int id, FriendlyByteBuf buf) {
        Msg<?> m = byId.get(id);
        if (m == null) throw new IllegalArgumentException("Unknown message " + id + " on channel " + name);
        return m.decoder().apply(buf);
    }
    @SuppressWarnings("unchecked")
    public void handle(int id, Object msg, CustomPayloadEvent.Context ctx) {
        Msg<Object> m = (Msg<Object>) byId.get(id);
        if (m != null && m.handler() != null) m.handler().accept(msg, ctx);
    }

    public static class MessageBuilder<M> {
        private final SimpleChannel channel; private final Class<M> type; private final int id;
        private BiConsumer<M, FriendlyByteBuf> encoder = (m, b) -> {};
        private Function<FriendlyByteBuf, M> decoder;
        private BiConsumer<M, CustomPayloadEvent.Context> handler;
        MessageBuilder(SimpleChannel channel, Class<M> type, int id) { this.channel = channel; this.type = type; this.id = id; }
        public MessageBuilder<M> encoder(BiConsumer<M, FriendlyByteBuf> e) { encoder = e; return this; }
        public MessageBuilder<M> decoder(Function<FriendlyByteBuf, M> d) { decoder = d; return this; }
        /** NeoForge runs payload handlers on the main thread already; enqueueWork in the handler still works. */
        public MessageBuilder<M> consumerMainThread(BiConsumer<M, CustomPayloadEvent.Context> h) {
            handler = (m, c) -> { h.accept(m, c); c.setPacketHandled(true); }; return this;
        }
        public MessageBuilder<M> consumerNetworkThread(BiConsumer<M, CustomPayloadEvent.Context> h) { handler = h; return this; }
        public MessageBuilder<M> consumer(BiConsumer<M, CustomPayloadEvent.Context> h) { return consumerNetworkThread(h); }
        public SimpleChannel add() {
            Msg<M> m = new Msg<>(id, type, encoder, decoder, handler);
            if (channel.byId.putIfAbsent(id, m) != null) throw new IllegalArgumentException("Duplicate message id " + id + " on channel " + channel.name);
            channel.byType.put(type, m);
            return channel;
        }
    }
}
