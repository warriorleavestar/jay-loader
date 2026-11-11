package amber.forgecompat.amber_internal;

import amber.forgecompat.event.network.CustomPayloadEvent;
import amber.forgecompat.network.SimpleChannel;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** The one NeoForge payload that carries every Forge SimpleChannel message (see SimpleChannel). */
public final class Net {
    private Net() {}
    static final Set<String> logged = ConcurrentHashMap.newKeySet();

    public record Pkt(SimpleChannel channel, int id, Object msg) implements CustomPacketPayload {
        public static final Type<Pkt> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("amber_forgecompat", "simplechannel"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pkt> CODEC = StreamCodec.of(
            (buf, p) -> { buf.writeResourceLocation(p.channel().getName()); buf.writeVarInt(p.id()); p.channel().encode(p.id(), p.msg(), buf); },
            buf -> {
                ResourceLocation n = buf.readResourceLocation();
                SimpleChannel c = SimpleChannel.byName(n);
                if (c == null) throw new IllegalArgumentException("Unknown Forge channel " + n);
                int id = buf.readVarInt();
                return new Pkt(c, id, c.decode(id, buf));
            });
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(IEventBus modBus) {
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1").optional().playBidirectional(Pkt.TYPE, Pkt.CODEC, Net::handle));
    }

    static void handle(Pkt p, IPayloadContext ctx) {
        if (Caps.LOG && logged.add("recv " + p.channel().getName() + "#" + p.id()))
            System.out.println("[amber-forgecompat] received " + p.msg().getClass().getName() + " on " + p.channel().getName() + " (" + ctx.flow() + ")");
        p.channel().handle(p.id(), p.msg(), new CustomPayloadEvent.Context(ctx));
    }

    public static void send(Pkt p, Connection c) {
        if (Caps.LOG && logged.add("send " + p.channel().getName() + "#" + p.id()))
            System.out.println("[amber-forgecompat] sending " + p.msg().getClass().getName() + " on " + p.channel().getName() + " (" + c.getSending() + ")");
        c.send(c.getSending() == PacketFlow.SERVERBOUND ? new ServerboundCustomPayloadPacket(p) : new ClientboundCustomPayloadPacket(p));
    }
}
