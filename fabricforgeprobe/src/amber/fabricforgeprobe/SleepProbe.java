package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-entity-events-v1 on Forge (run at tick 100, later at 110): ServerPlayer.startSleepInBed with Fabric's ServerPlayerEntityMixin applied (its
 * direction check re-based by ServerPlayerSleepDirectionFabricMixin). In daytime ALLOW_SLEEP_TIME and
 * ALLOW_NEARBY_MONSTERS allow sleep and ALLOW_SETTING_SPAWN keeps the old spawn; MODIFY_SLEEPING_DIRECTION returning
 * null on the second bed makes the attempt fail with NOT_POSSIBLE_HERE (the re-based check).
 */
final class SleepProbe {
    private SleepProbe() {}
    static BlockPos bedPos, nullBed; static int sleepTime, monsters, spawnAsked, dirAsked, bedAsked, sleepTimeAtStart, occAsked, wakeAsked, ok, total;
    static final net.minecraft.world.phys.Vec3 WAKE = new net.minecraft.world.phys.Vec3(0.5, 0, 0.5);   // offset from the bed foot

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] sleep: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean ours(BlockPos pos) { return pos.equals(bedPos) || pos.equals(nullBed); }

    static void register() {
        EntitySleepEvents.ALLOW_SLEEP_TIME.register((p, pos, vanilla) -> { if (!ours(pos)) return InteractionResult.PASS; sleepTime++; return InteractionResult.SUCCESS; });
        EntitySleepEvents.ALLOW_NEARBY_MONSTERS.register((p, pos, vanilla) -> { if (!ours(pos)) return InteractionResult.PASS; monsters++; return InteractionResult.SUCCESS; });
        EntitySleepEvents.ALLOW_SETTING_SPAWN.register((p, pos) -> { if (!ours(pos)) return true; spawnAsked++; return false; });
        EntitySleepEvents.ALLOW_BED.register((e, pos, state, vanilla) -> { if (!ours(pos)) return InteractionResult.PASS; bedAsked++; return InteractionResult.SUCCESS; });
        EntitySleepEvents.SET_BED_OCCUPATION_STATE.register((e, pos, state, occ) -> { if (ours(pos)) occAsked++; return false; });
        EntitySleepEvents.MODIFY_WAKE_UP_POSITION.register((e, pos, state, v) -> { if (!ours(pos)) return v; wakeAsked++; return Vec3.atLowerCornerOf(pos).add(WAKE).add(0, 1, 0); });
        EntitySleepEvents.MODIFY_SLEEPING_DIRECTION.register((e, pos, dir) -> { if (nullBed == null || !pos.equals(nullBed)) return dir; dirAsked++; return null; });
    }

    static void bed(ServerLevel level, BlockPos foot) {
        BlockState s = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT);
        level.setBlock(foot, s, 3); level.setBlock(foot.east(), s.setValue(BedBlock.PART, BedPart.HEAD), 3);
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                BlockPos base = p.blockPosition();
                for (int dx = -1; dx <= 3; dx++) for (int dy = 0; dy <= 2; dy++) for (int dz = -3; dz <= 1; dz++) { BlockPos c = base.offset(dx, dy, dz); if (!c.equals(base) && !c.equals(base.above())) level.removeBlock(c, false); }
                bedPos = base.east(); bed(level, bedPos);
                level.setDayTime(1000);
                p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);   // vanilla skips the monster check for creative players
                BlockPos spawnBefore = p.getRespawnPosition();
                var r = p.startSleepInBed(bedPos);
                check("player sleep result (daytime, Fabric allows)", "Right", r.map(l -> "Left " + l, u -> "Right"));
                check("ALLOW_SLEEP_TIME asked", true, sleepTime > 0);
                check("ALLOW_NEARBY_MONSTERS asked", true, monsters > 0);
                check("ALLOW_SETTING_SPAWN asked", true, spawnAsked > 0);
                check("spawn kept", spawnBefore, p.getRespawnPosition());
                check("player sleeping", true, p.isSleeping());
                check("SET_BED_OCCUPATION_STATE asked", true, occAsked > 0);
                check("bed occupied (Forge setBedOccupied on the real bed)", true, level.getBlockState(bedPos).getValue(BedBlock.OCCUPIED));
                sleepTimeAtStart = sleepTime;
                level.removeBlock(bedPos.east(), false); level.removeBlock(bedPos, false);   // ALLOW_BED keeps the bed-less sleeper
            } catch (Throwable e) { System.out.println("[amber-ffprobe] sleep: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    /** 10 ticks after run: still asleep in daytime without a bed (Player.tick time check, LivingEntity.tick bed check). */
    static void later(MinecraftServer server) {
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                BlockPos base = bedPos.west();
                check("ALLOW_SLEEP_TIME asked while sleeping (Player.tick)", true, sleepTime > sleepTimeAtStart);
                check("ALLOW_BED asked", true, bedAsked > 0);
                check("still sleeping after 10 ticks (day, bed gone)", true, p.isSleeping());
                p.stopSleepInBed(false, true);
                check("MODIFY_WAKE_UP_POSITION asked", true, wakeAsked > 0);
                check("woke at the Fabric position", Vec3.atLowerCornerOf(bedPos).add(WAKE).add(0, 1, 0), p.position());
                check("no bed put where it was removed (ALLOW_BED stand-in not placed)", "air", level.getBlockState(bedPos).getBlock().builtInRegistryHolder().key().location().getPath());

                nullBed = base.offset(1, 0, -2); bed(level, nullBed);
                var r2 = p.startSleepInBed(nullBed);
                check("MODIFY_SLEEPING_DIRECTION asked", true, dirAsked > 0);
                check("null direction -> NOT_POSSIBLE_HERE", "Left NOT_POSSIBLE_HERE", r2.map(l -> "Left " + l, u -> "Right"));
                check("player not sleeping", false, p.isSleeping());
                p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                for (BlockPos b : new BlockPos[]{bedPos, nullBed}) { level.removeBlock(b, false); level.removeBlock(b.east(), false); }
            } catch (Throwable e) { System.out.println("[amber-ffprobe] sleep: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] sleep total: " + ok + "/" + total + " OK");
        });
    }
}
