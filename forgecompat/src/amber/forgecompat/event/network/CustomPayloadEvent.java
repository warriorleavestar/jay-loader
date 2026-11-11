package amber.forgecompat.event.network;

import java.util.concurrent.CompletableFuture;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Forge's CustomPayloadEvent; only its {@link Context} is used, by SimpleChannel handlers (backed by NeoForge's payload context). */
public class CustomPayloadEvent extends Event {
    public static class Context {
        private final IPayloadContext ctx;
        private boolean handled;
        public Context(IPayloadContext ctx) { this.ctx = ctx; }
        public CompletableFuture<Void> enqueueWork(Runnable r) { return ctx.enqueueWork(r); }
        /** The sending player, for messages received on the server. */
        public ServerPlayer getSender() { return ctx.flow() == PacketFlow.SERVERBOUND && ctx.player() instanceof ServerPlayer p ? p : null; }
        public void setPacketHandled(boolean handled) { this.handled = handled; }
        public boolean getPacketHandled() { return handled; }
        public Connection getConnection() { return ctx.connection(); }
        public boolean isServerSide() { return ctx.flow() == PacketFlow.SERVERBOUND; }
        public boolean isClientSide() { return ctx.flow() == PacketFlow.CLIENTBOUND; }
    }
}
