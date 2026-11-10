package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-events-interaction-v0 probe (-Damber.interactprobe=true). PlayerBlockBreakEvents through
 * ServerPlayerGameMode.destroyBlock (BEFORE / AFTER with the pre-break state, a BEFORE veto -> CANCELED, block kept,
 * false returned); UseEntityCallback on the server through ServerGamePacketListenerImpl.handleInteract (interact and
 * interact-at packets, a SUCCESS result skips vanilla) and on the client through a real use-key press aimed at the
 * probe armor stand.
 */
final class InteractProbe {
    private InteractProbe() {}
    static final String TAG = "amber-interact-probe";
    static BlockPos breakPos, vetoPos; static int before, after, canceled, serverUse, serverUseAt, clientUse; static String afterState, beforeState;
    static ArmorStand stand; static String aimedHit; static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] interact probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean ours(Entity e) { return e != null && e.getCustomName() != null && TAG.equals(e.getCustomName().getString()); }

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (pos.equals(vetoPos)) return false;
            if (pos.equals(breakPos)) { before++; beforeState = state.getBlock().toString(); }
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, be) -> { if (pos.equals(breakPos)) { after++; afterState = state.getBlock().toString(); } });
        PlayerBlockBreakEvents.CANCELED.register((level, player, pos, state, be) -> { if (pos.equals(vetoPos)) canceled++; });
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!ours(entity)) return InteractionResult.PASS;
            if (level.isClientSide()) clientUse++; else if (hit != null) serverUseAt++; else serverUse++;
            return InteractionResult.SUCCESS;
        });
    }

    /** Server side: break events and the server interact handler; spawns the stand the client aims at next. */
    static void server(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                breakPos = p.blockPosition().offset(2, 0, -2); vetoPos = breakPos.east();
                level.setBlockAndUpdate(breakPos, Blocks.GOLD_BLOCK.defaultBlockState()); level.setBlockAndUpdate(vetoPos, Blocks.IRON_BLOCK.defaultBlockState());
                boolean broke = p.gameMode.destroyBlock(breakPos);
                check("destroyBlock returned true", true, broke);
                check("BEFORE fired", 1, before);
                check("AFTER fired", 1, after);
                check("AFTER saw the pre-break state", Blocks.GOLD_BLOCK.toString(), afterState);
                check("block gone", true, level.getBlockState(breakPos).isAir());
                boolean vetoed = p.gameMode.destroyBlock(vetoPos);
                check("vetoed destroyBlock returned false", false, vetoed);
                check("CANCELED fired", 1, canceled);
                check("vetoed block kept", Blocks.IRON_BLOCK.toString(), level.getBlockState(vetoPos).getBlock().toString());
                level.removeBlock(vetoPos, false);

                Vec3 look = p.getLookAngle(); Vec3 dir = new Vec3(look.x, 0, look.z).normalize(); if (dir.lengthSqr() < 0.5) dir = new Vec3(1, 0, 0);
                Vec3 at = p.position().add(dir.scale(2.5));
                for (double d = 0.5; d <= 3.5; d += 0.5) for (int y = 0; y <= 2; y++) {   // clear the line of sight (terrain differs per test world)
                    BlockPos c = BlockPos.containing(p.position().add(dir.scale(d))).above(y);
                    if (!c.equals(p.blockPosition()) && !c.equals(p.blockPosition().above())) level.removeBlock(c, false);
                }
                stand = new ArmorStand(level, at.x, at.y, at.z); stand.setCustomName(Component.literal(TAG)); stand.setNoGravity(true);
                level.addFreshEntity(stand);
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(stand, false, InteractionHand.MAIN_HAND));
                check("server UseEntityCallback (interact)", 1, serverUse);
                p.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(stand, false, InteractionHand.MAIN_HAND, new Vec3(0, 1, 0)));
                check("server UseEntityCallback (interact at)", 1, serverUseAt);
            } catch (Throwable e) { System.out.println("[amber-worldtest] interact probe: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    /** Client side: aim at the stand and press use (startUseItem runs on the next tick). */
    static void aim(Minecraft client) {
        Entity target = null;
        for (Entity e : client.level.entitiesForRendering()) if (ours(e)) target = e;
        if (target == null) { System.out.println("[amber-worldtest] interact probe: stand not on the client"); return; }
        client.options.pauseOnLostFocus = false; client.setScreen(null);   // an unfocused test window pauses, and screens swallow the use key
        client.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.position().add(0, 1, 0));
        aimedHit = String.valueOf(client.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh ? eh.getEntity() : client.hitResult == null ? null : client.hitResult.getType());
        KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(client.options.keyUse.saveString()));   // getKey() is NeoForge-only
    }

    static void report(Minecraft client) {
        System.out.println("[amber-worldtest] interact probe: client state hit=" + client.hitResult + (client.hitResult instanceof net.minecraft.world.phys.EntityHitResult eh ? " entity=" + eh.getEntity() : "") + " screen=" + client.screen + " aimedHit=" + aimedHit);
        check("client UseEntityCallback (use key on the stand)", true, clientUse > 0);
        if (stand != null) client.getSingleplayerServer().execute(() -> stand.discard());
        System.out.println("[amber-worldtest] interact probe total: " + ok + "/" + total + " OK");
    }
}
