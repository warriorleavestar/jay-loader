package amber.regtest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Client half of the networking probe (see NetTest). */
public class RegTestClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientConfigurationConnectionEvents.START.register((h, mc) -> NetTest.log("client configuration START"));
        ClientConfigurationNetworking.registerGlobalReceiver(NetTest.CfgHello.TYPE, (p, ctx) -> {
            NetTest.log("client got config hello '" + p.msg() + "', sending ack");
            ctx.responseSender().sendPacket(new NetTest.CfgAck("ack from client"));
        });
        ClientPlayNetworking.registerGlobalReceiver(NetTest.Pong.TYPE, (p, ctx) -> NetTest.log("client got pong n=" + p.n() + " '" + p.msg() + "'"));
        ClientPlayConnectionEvents.JOIN.register((h, sender, mc) -> {
            boolean can = ClientPlayNetworking.canSend(NetTest.Ping.TYPE);
            NetTest.log("client JOIN canSend(ping)=" + can);
            if (can) sender.sendPacket(new NetTest.Ping(41, "ping from client"));
        });
        System.out.println("[amber-regtest] client init: networking receivers registered");
    }
}
