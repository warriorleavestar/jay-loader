package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-entity-events-v1 sleep probe (-Damber.sleepprobe=true). On LivingEntity (an armor stand): ALLOW_BED accepting a
 * stone block (vanilla result false), SET_BED_OCCUPATION_STATE seeing the real state (handled, and not handled with no
 * OCCUPIED to set), MODIFY_SLEEPING_DIRECTION off a bed (null in, EAST out) and on a real bed (its FACING), a real bed's
 * OCCUPIED set and cleared, MODIFY_WAKE_UP_POSITION. On ServerPlayer.startSleepInBed in daytime: ALLOW_SLEEP_TIME and
 * ALLOW_NEARBY_MONSTERS allow it, ALLOW_SETTING_SPAWN keeps the old spawn.
 */
final class SleepProbe {
    private SleepProbe() {}
    static BlockPos stonePos, bedPos; static boolean handleOccupation = true, active;
    static int allowBed, occupation, direction, wake, sleepTime, monsters, spawnAsked; static String allowBedVanilla, occState, dirIn;
    static Vec3 wakeTo; static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] sleep probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        EntitySleepEvents.ALLOW_BED.register((e, pos, state, vanilla) -> {
            if (!pos.equals(stonePos)) return InteractionResult.PASS;
            allowBed++; allowBedVanilla = String.valueOf(vanilla); return InteractionResult.SUCCESS;
        });
        EntitySleepEvents.SET_BED_OCCUPATION_STATE.register((e, pos, state, occupied) -> {
            if (!pos.equals(stonePos)) return false;
            occupation++; occState = state.getBlock() + "/" + occupied; return handleOccupation;
        });
        EntitySleepEvents.MODIFY_SLEEPING_DIRECTION.register((e, pos, dir) -> {
            if (!active) return dir;
            direction++; dirIn = String.valueOf(dir); return pos.equals(stonePos) ? Direction.EAST : dir;
        });
        EntitySleepEvents.MODIFY_WAKE_UP_POSITION.register((e, pos, state, at) -> { if (pos.equals(bedPos) && e instanceof ArmorStand) { wake++; return wakeTo; } return at; });
        EntitySleepEvents.ALLOW_SLEEP_TIME.register((p, pos, vanilla) -> { if (!pos.equals(bedPos)) return InteractionResult.PASS; sleepTime++; return InteractionResult.SUCCESS; });
        EntitySleepEvents.ALLOW_NEARBY_MONSTERS.register((p, pos, vanilla) -> { if (!pos.equals(bedPos)) return InteractionResult.PASS; monsters++; return InteractionResult.SUCCESS; });
        EntitySleepEvents.ALLOW_SETTING_SPAWN.register((p, pos) -> { if (!pos.equals(bedPos)) return true; spawnAsked++; return false; });
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                BlockPos base = p.blockPosition();
                stonePos = base.offset(-3, 0, 3); bedPos = base.east();
                for (int dx = -1; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -1; dz <= 1; dz++) { BlockPos c = base.offset(dx, dy, dz); if (!c.equals(base) && !c.equals(base.above())) level.removeBlock(c, false); }
                level.setBlockAndUpdate(stonePos, Blocks.STONE.defaultBlockState());
                BlockState foot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
                level.setBlock(bedPos, foot, 3); level.setBlock(bedPos.east(), foot.setValue(BedBlock.PART, BedPart.HEAD), 3);
                active = true;

                // LivingEntity on a stone block accepted by ALLOW_BED
                ArmorStand a = new ArmorStand(level, stonePos.getX() + 0.5, stonePos.getY() + 1, stonePos.getZ() + 0.5); level.addFreshEntity(a);
                a.startSleeping(stonePos);
                check("ALLOW_BED asked for the stone", 1, allowBed);
                check("ALLOW_BED vanilla result for stone", false, allowBedVanilla);
                check("occupation event saw the real state, occupied", "Block{minecraft:stone}/true", occState);
                check("stand sleeps on the stone", true, a.isSleeping());
                check("stone untouched", "Block{minecraft:stone}", level.getBlockState(stonePos).getBlock());
                Direction d = a.getBedOrientation();
                check("MODIFY_SLEEPING_DIRECTION off a bed gets null", "null", dirIn);
                check("MODIFY_SLEEPING_DIRECTION result used", Direction.EAST, d);
                handleOccupation = false;
                a.stopSleeping();
                check("wake-up ALLOW_BED asked again", 2, allowBed);
                check("wake-up occupation event, not occupied", "Block{minecraft:stone}/false", occState);
                check("unhandled occupation leaves a non-bed alone", "Block{minecraft:stone}", level.getBlockState(stonePos).getBlock());
                check("stand awake", false, a.isSleeping());
                a.discard();

                // LivingEntity on a real bed
                ArmorStand b = new ArmorStand(level, bedPos.getX() + 0.5, bedPos.getY() + 1, bedPos.getZ() + 0.5); level.addFreshEntity(b);
                b.startSleeping(bedPos);
                check("real bed occupied", true, level.getBlockState(bedPos).getValue(BedBlock.OCCUPIED));
                dirIn = null; Direction bd = b.getBedOrientation();
                check("MODIFY_SLEEPING_DIRECTION on a bed gets its facing", Direction.EAST, dirIn);
                check("bed direction", Direction.EAST, bd);
                wakeTo = new Vec3(bedPos.getX() + 0.5, bedPos.getY() + 3, bedPos.getZ() + 0.5);
                b.stopSleeping();
                check("real bed freed", false, level.getBlockState(bedPos).getValue(BedBlock.OCCUPIED));
                check("MODIFY_WAKE_UP_POSITION used", 1, wake);
                check("woke at the modified position", wakeTo, b.position());
                b.discard();

                // ServerPlayer.startSleepInBed in daytime
                level.setDayTime(1000);
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);   // vanilla skips the monster check for creative players
                BlockPos spawnBefore = p.getRespawnPosition();
                var r = p.startSleepInBed(bedPos);
                check("player sleep result", "Right", r.map(l -> "Left " + l, u -> "Right"));
                check("ALLOW_SLEEP_TIME asked", true, sleepTime > 0);
                check("ALLOW_NEARBY_MONSTERS asked", true, monsters > 0);
                check("ALLOW_SETTING_SPAWN asked", true, spawnAsked > 0);
                check("spawn kept", spawnBefore, p.getRespawnPosition());
                check("player sleeping", true, p.isSleeping());
                p.stopSleepInBed(false, true);
                p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                active = false;
                level.removeBlock(bedPos, false); level.removeBlock(bedPos.east(), false); level.removeBlock(stonePos, false);
            } catch (Throwable e) { System.out.println("[amber-worldtest] sleep probe: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-worldtest] sleep probe total: " + ok + "/" + total + " OK");
        });
    }
}
