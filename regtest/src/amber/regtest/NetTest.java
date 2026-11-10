package amber.regtest;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.FabricServerConfigurationNetworkHandler;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;

/**
 * Fabric networking probe (phase 5): play payloads both ways (ping C2S -> pong S2C) and a configuration task (hello S2C,
 * ack C2S, completeTask). Every step logs "[amber-regtest] NET ..." for tools/nettest checks.
 */
public final class NetTest {
    private NetTest() {}
    static ResourceLocation rl(String p) { return ResourceLocation.fromNamespaceAndPath(RegTest.ID, p); }
    static void log(String s) { System.out.println("[amber-regtest] NET " + s + " [" + Thread.currentThread().getName() + "]"); }

    public record Ping(int n, String msg) implements CustomPacketPayload {
        public static final Type<Ping> TYPE = new Type<>(rl("ping"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Ping> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Ping::n, ByteBufCodecs.STRING_UTF8, Ping::msg, Ping::new);
        @Override public Type<Ping> type() { return TYPE; }
    }
    public record Pong(int n, String msg) implements CustomPacketPayload {
        public static final Type<Pong> TYPE = new Type<>(rl("pong"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Pong> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Pong::n, ByteBufCodecs.STRING_UTF8, Pong::msg, Pong::new);
        @Override public Type<Pong> type() { return TYPE; }
    }
    public record CfgHello(String msg) implements CustomPacketPayload {
        public static final Type<CfgHello> TYPE = new Type<>(rl("cfg_hello"));
        public static final StreamCodec<FriendlyByteBuf, CfgHello> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CfgHello::msg, CfgHello::new);
        @Override public Type<CfgHello> type() { return TYPE; }
    }
    public record CfgAck(String msg) implements CustomPacketPayload {
        public static final Type<CfgAck> TYPE = new Type<>(rl("cfg_ack"));
        public static final StreamCodec<FriendlyByteBuf, CfgAck> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, CfgAck::msg, CfgAck::new);
        @Override public Type<CfgAck> type() { return TYPE; }
    }

    /** Server-side configuration task: sends hello, completes when the client's ack arrives. */
    public record HelloTask() implements ConfigurationTask {
        public static final Type TYPE = new Type("amber_regtest:hello");
        @Override public void start(Consumer<Packet<?>> send) { log("config task start: sending hello"); send.accept(new ClientboundCustomPayloadPacket(new CfgHello("hello from server"))); }
        @Override public Type type() { return TYPE; }
    }

    static void init() {
        PayloadTypeRegistry.playC2S().register(Ping.TYPE, Ping.CODEC);
        PayloadTypeRegistry.playS2C().register(Pong.TYPE, Pong.CODEC);
        PayloadTypeRegistry.configurationS2C().register(CfgHello.TYPE, CfgHello.CODEC);
        PayloadTypeRegistry.configurationC2S().register(CfgAck.TYPE, CfgAck.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Ping.TYPE, (p, ctx) -> {
            log("server got ping n=" + p.n() + " from " + ctx.player().getName().getString());
            ctx.responseSender().sendPacket(new Pong(p.n() + 1, "pong from server"));
        });
        ServerConfigurationNetworking.registerGlobalReceiver(CfgAck.TYPE, (p, ctx) -> {
            log("server got config ack '" + p.msg() + "', completing task");
            ((FabricServerConfigurationNetworkHandler) ctx.networkHandler()).completeTask(HelloTask.TYPE);
        });
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register((h, server) -> log("server BEFORE_CONFIGURE"));
        ServerConfigurationConnectionEvents.CONFIGURE.register((h, server) -> {
            boolean can = ServerConfigurationNetworking.canSend(h, CfgHello.TYPE);
            log("server CONFIGURE canSend(cfg_hello)=" + can);
            if (can) ((FabricServerConfigurationNetworkHandler) h).addTask(new HelloTask());
        });
        ServerPlayConnectionEvents.JOIN.register((h, sender, server) ->
                log("server JOIN " + h.player.getName().getString() + " canSend(pong)=" + ServerPlayNetworking.canSend(h.player, Pong.TYPE)));
    }
}
