package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-dimensions-v1 on Forge: FabricDimensions.teleport puts an entity in another dimension at the exact target.
 * Fabric's EntityMixin answers findDimensionEntryPoint with the custom target (Forge's default ITeleporter still asks it);
 * its END redirect is retargeted to Forge's changeDimension(ServerLevel, ITeleporter); under strict require the whole
 * mixin had been excluded.
 */
final class DimensionProbe {
    private DimensionProbe() {}
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] dim: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ServerLevel overworld = server.overworld(), nether = server.getLevel(Level.NETHER);
                Pig pig = EntityType.PIG.create(overworld);
                pig.moveTo(0.5, 100, 0.5);
                overworld.addFreshEntity(pig);
                Entity moved = FabricDimensions.teleport(pig, nether, new PortalInfo(new Vec3(5.5, 80, 7.5), Vec3.ZERO, 30f, 0f));
                check("teleported entity exists", true, moved != null);
                check("in the Nether", "minecraft:the_nether", moved == null ? "null" : moved.level().dimension().location());
                check("at the custom target", "5.5,80.0,7.5", moved == null ? "null" : moved.getX() + "," + moved.getY() + "," + moved.getZ());
                check("with the custom yaw", 30.0f, moved == null ? "null" : moved.getYRot());
                if (moved != null) moved.discard();
            } catch (Throwable e) { System.out.println("[amber-ffprobe] dim: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] dim total: " + ok + "/" + total + " OK");
        });
    }

    /** dimplayer, tick 100: the player to the End at a custom target. */
    static void playerToEnd(MinecraftServer server, java.util.UUID id) {
        server.execute(() -> {
            try {
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(id);
                FabricDimensions.teleport(p, server.getLevel(Level.END), new PortalInfo(new Vec3(100.5, 70, 0.5), Vec3.ZERO, 0f, 0f));
                check("player in the End", "minecraft:the_end", server.getPlayerList().getPlayer(id).level().dimension().location());
            } catch (Throwable e) { System.out.println("[amber-ffprobe] dim: crashed (to End) " + e); e.printStackTrace(System.out); }
        });
    }

    /** dimplayer, tick 140: back to the Overworld at a custom target; no End credits. */
    static void playerBack(MinecraftServer server, java.util.UUID id) {
        server.execute(() -> {
            try {
                net.minecraft.server.level.ServerPlayer p = server.getPlayerList().getPlayer(id);
                FabricDimensions.teleport(p, server.overworld(), new PortalInfo(new Vec3(3.5, 120, 4.5), Vec3.ZERO, 0f, 0f));
                net.minecraft.server.level.ServerPlayer q = server.getPlayerList().getPlayer(id);
                check("End -> Overworld: no credits (wonGame)", false, q.wonGame);
                check("player in the Overworld", "minecraft:overworld", q.level().dimension().location());
                check("at the custom target", "3.5,120.0,4.5", q.getX() + "," + q.getY() + "," + q.getZ());
            } catch (Throwable e) { System.out.println("[amber-ffprobe] dim: crashed (back) " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] dim total: " + ok + "/" + total + " OK");
        });
    }
}
