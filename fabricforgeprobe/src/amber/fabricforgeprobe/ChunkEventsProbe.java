package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * fabric-lifecycle-events-v1 block-entity events on Forge. Client side: Fabric's client.WorldChunkMixin is no longer
 * excluded under strict require (its getBlockEntity redirect is stripped), so a chest the server places shows up as a
 * client LOAD and its removal as a client UNLOAD. Server side: a block entity marked removed and then looked up through
 * getBlockEntity is dropped from the chunk and fires UNLOAD (bridge: LevelChunkUnloadFabricMixin on Forge's Map.remove).
 */
final class ChunkEventsProbe {
    private ChunkEventsProbe() {}
    static volatile BlockPos pos;
    static volatile int serverLoad, serverUnload, clientLoad, clientUnload;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] chunk: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean at(BlockEntity be) { return pos != null && pos.equals(be.getBlockPos()); }

    static void register() {
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> { if (at(be)) serverLoad++; });
        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> { if (at(be)) serverUnload++; });
    }

    static void registerClient() {
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> { if (at(be)) clientLoad++; });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> { if (at(be)) clientUnload++; });
    }

    /** tick 100: place a chest next to the player; server-side removed-lookup unload. */
    static void run(Minecraft mc, MinecraftServer server) {
        BlockPos p = mc.player.blockPosition().offset(2, 0, 2);
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                pos = p;
                level.setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
                check("server LOAD on placing a chest", true, serverLoad > 0);
                LevelChunk chunk = level.getChunkAt(p);
                BlockEntity be = chunk.getBlockEntity(p, LevelChunk.EntityCreationType.CHECK);
                int before = serverUnload;
                be.setRemoved();
                BlockEntity again = chunk.getBlockEntity(p, LevelChunk.EntityCreationType.CHECK);
                check("removed block entity dropped by getBlockEntity", "null", again);
                check("server UNLOAD for it", 1, serverUnload - before);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] chunk: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    /** tick 130: remove the chest (server); the client sees it go. */
    static void clear(MinecraftServer server) {
        server.execute(() -> { if (pos != null) server.overworld().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState()); });
    }

    /** tick 180. */
    static void report() {
        check("client LOAD for the chest", true, clientLoad > 0);
        check("client UNLOAD after removal", true, clientUnload > 0);
        System.out.println("[amber-ffprobe] chunk total: " + ok + "/" + total + " OK");
    }
}
