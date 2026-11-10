package amber.fabricforgeprobe;

import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.client.networking.v1.C2SPlayChannelEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.fabricmc.fabric.api.networking.v1.S2CPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * fabric-networking-api-v1 (0.92) on Forge 47 over the integrated server: raw-buffer channels both ways (ping C2S ->
 * pong S2C), the FabricPacket/PacketType variant both ways, canSend on both sides (needs the minecraft:register exchange),
 * the play JOIN events, the channel REGISTER events on both sides (S2CPlayChannelEvents on the server, C2S on the client), and the handler threads (server thread for the
 * global receiver, client thread for the client receiver).
 */
final class NetProbe {
    private NetProbe() {}
    static final ResourceLocation PING = new ResourceLocation("amber_ffprobe", "ping"), PONG = new ResourceLocation("amber_ffprobe", "pong");
    static volatile int serverPing = -1, clientPong = -1, serverPkt = -1, clientPkt = -1;
    static volatile boolean serverOnServerThread, clientOnClientThread, serverJoin, clientJoin, s2cRegister, c2sRegister, serverCanSend;
    static int ok, total;

    record PingPkt(int n) implements FabricPacket {
        static final PacketType<PingPkt> TYPE = PacketType.create(new ResourceLocation("amber_ffprobe", "ping_pkt"), b -> new PingPkt(b.readVarInt()));
        @Override public void write(FriendlyByteBuf b) { b.writeVarInt(n); }
        @Override public PacketType<?> getType() { return TYPE; }
    }
    record PongPkt(int n) implements FabricPacket {
        static final PacketType<PongPkt> TYPE = PacketType.create(new ResourceLocation("amber_ffprobe", "pong_pkt"), b -> new PongPkt(b.readVarInt()));
        @Override public void write(FriendlyByteBuf b) { b.writeVarInt(n); }
        @Override public PacketType<?> getType() { return TYPE; }
    }

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] net: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        ServerPlayNetworking.registerGlobalReceiver(PING, (server, player, handler, buf, sender) -> {
            int n = buf.readVarInt();
            server.execute(() -> {
                serverPing = n;
                serverOnServerThread = server.isSameThread();
                FriendlyByteBuf out = PacketByteBufs.create();
                out.writeVarInt(n + 1);
                sender.sendPacket(PONG, out);
            });
        });
        ServerPlayNetworking.registerGlobalReceiver(PingPkt.TYPE, (pkt, player, sender) -> {
            serverPkt = pkt.n();
            sender.sendPacket(new PongPkt(pkt.n() * 2));
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> serverJoin = true);
        S2CPlayChannelEvents.REGISTER.register((handler, sender, server, channels) -> { if (channels.contains(PONG)) s2cRegister = true; });
    }

    static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(PONG, (client, handler, buf, sender) -> {
            int n = buf.readVarInt();
            client.execute(() -> { clientPong = n; clientOnClientThread = client.isSameThread(); });
        });
        ClientPlayNetworking.registerGlobalReceiver(PongPkt.TYPE, (pkt, player, sender) -> clientPkt = pkt.n());
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clientJoin = true);
        C2SPlayChannelEvents.REGISTER.register((handler, sender, client, channels) -> { if (channels.contains(PING)) c2sRegister = true; });
    }

    /** tick 100 (client thread): send both pings; the server checks canSend(pong) for this player on its own thread. */
    static void run(Minecraft mc, MinecraftServer server, UUID me) {
        try {
            check("client canSend(ping)", true, ClientPlayNetworking.canSend(PING));
            check("client canSend(ping_pkt)", true, ClientPlayNetworking.canSend(PingPkt.TYPE));
            FriendlyByteBuf b = PacketByteBufs.create();
            b.writeVarInt(41);
            ClientPlayNetworking.send(PING, b);
            ClientPlayNetworking.send(new PingPkt(21));
            server.execute(() -> { ServerPlayer p = server.getPlayerList().getPlayer(me); serverCanSend = p != null && ServerPlayNetworking.canSend(p, PONG) && ServerPlayNetworking.canSend(p, PongPkt.TYPE); });
        } catch (Throwable e) { System.out.println("[amber-ffprobe] net: crashed " + e); e.printStackTrace(System.out); }
    }

    static void report() {
        check("server play JOIN fired", true, serverJoin);
        check("client play JOIN fired", true, clientJoin);
        check("server saw the client register amber_ffprobe:pong (S2CPlayChannelEvents)", true, s2cRegister);
        check("client saw the server register amber_ffprobe:ping (C2SPlayChannelEvents)", true, c2sRegister);
        check("server canSend(pong, pong_pkt) to the player", true, serverCanSend);
        check("server got raw ping", 41, serverPing);
        check("server ping handled on the server thread", true, serverOnServerThread);
        check("client got raw pong", 42, clientPong);
        check("client pong handled on the client thread", true, clientOnClientThread);
        check("server got FabricPacket ping", 21, serverPkt);
        check("client got FabricPacket pong", 42, clientPkt);
        System.out.println("[amber-ffprobe] net total: " + ok + "/" + total + " OK");
    }
}
