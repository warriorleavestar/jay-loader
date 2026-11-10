package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-events-interaction-v0 on Forge (bridge adapters ServerInteractEntityFabricMixin / MinecraftUseEntityFabricMixin):
 * UseEntityCallback on the server through ServerGamePacketListenerImpl.handleInteract (interact and interact-at packets)
 * and on the client through a real use-key press aimed at the probe armor stand.
 */
final class InteractProbe {
    private InteractProbe() {}
    static final String TAG = "amber-ff-interact-probe";
    static int serverUse, serverUseAt, clientUse, ok, total; static ArmorStand stand;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] interact: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean ours(Entity e) { return e != null && e.getCustomName() != null && TAG.equals(e.getCustomName().getString()); }

    static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!ours(entity)) return InteractionResult.PASS;
            if (level.isClientSide()) clientUse++; else if (hit != null) serverUseAt++; else serverUse++;
            return InteractionResult.SUCCESS;
        });
    }

    static void server(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                Vec3 look = p.getLookAngle(); Vec3 dir = new Vec3(look.x, 0, look.z).normalize(); if (dir.lengthSqr() < 0.5) dir = new Vec3(1, 0, 0);
                Vec3 at = p.position().add(dir.scale(2.5));
                for (double d = 0.5; d <= 3.5; d += 0.5) for (int y = 0; y <= 2; y++) {   // clear the line of sight
                    BlockPos c = BlockPos.containing(p.position().add(dir.scale(d))).above(y);
                    if (!c.equals(p.blockPosition()) && !c.equals(p.blockPosition().above())) level.removeBlock(c, false);
                }
                stand = new ArmorStand(level, at.x, at.y, at.z); stand.setCustomName(Component.literal(TAG)); stand.setNoGravity(true);
                level.addFreshEntity(stand);
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(stand, false, InteractionHand.MAIN_HAND));
                check("server UseEntityCallback (interact)", 1, serverUse);
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(stand, false, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0)));
                check("server UseEntityCallback (interact at)", 1, serverUseAt);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] interact: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    static void aim(Minecraft client) {
        Entity target = null;
        for (Entity e : client.level.entitiesForRendering()) if (ours(e)) target = e;
        if (target == null) { System.out.println("[amber-ffprobe] interact: stand not on the client"); return; }
        client.options.pauseOnLostFocus = false; client.setScreen(null);
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.position().add(0, 1, 0));
        KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(client.options.keyUse.saveString()));
    }

    static void report(Minecraft client) {
        System.out.println("[amber-ffprobe] interact: client hit=" + client.hitResult + (client.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh ? " entity=" + eh.getEntity() : ""));
        check("client UseEntityCallback (use key on the stand)", true, clientUse > 0);
        if (stand != null) client.getSingleplayerServer().execute(() -> stand.discard());
        System.out.println("[amber-ffprobe] interact total: " + ok + "/" + total + " OK");
    }
}
