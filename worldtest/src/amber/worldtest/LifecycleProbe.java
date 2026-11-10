package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * fabric-lifecycle-events-v1 block-entity probe on the client (-Damber.lifecycleprobe=true): two chests placed by the
 * server load on the client (LevelChunk.setBlockEntity); chest A removed by the server unloads (removeBlockEntity);
 * chest B marked removed on the client unloads when getBlockEntity drops it (the re-based path).
 */
final class LifecycleProbe {
    private LifecycleProbe() {}
    static BlockPos a, b; static int loadA, loadB, unloadA, unloadB; static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] lifecycle probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void registerClient() {
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((be, level) -> { if (be.getBlockPos().equals(a)) loadA++; if (be.getBlockPos().equals(b)) loadB++; });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((be, level) -> { if (be.getBlockPos().equals(a)) unloadA++; if (be.getBlockPos().equals(b)) unloadB++; });
    }

    static void place(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        BlockPos base = client.player.blockPosition();
        a = base.offset(-6, 0, -3); b = base.offset(-2, 0, -3);   // west of the player: InteractProbe breaks blocks at (2..3, 0, -2)
        server.execute(() -> { var l = server.overworld(); boolean sa = l.setBlockAndUpdate(a, Blocks.CHEST.defaultBlockState()), sb = l.setBlockAndUpdate(b, Blocks.CHEST.defaultBlockState());
            System.out.println("[amber-worldtest] lifecycle probe: placed A=" + a + " set=" + sa + " B=" + b + " set=" + sb + " now A=" + l.getBlockState(a) + " B=" + l.getBlockState(b) + " BE A=" + l.getBlockEntity(a)); });
    }

    static boolean onClient(Minecraft client) {
        return a != null && client.level.getBlockEntity(a) != null && client.level.getBlockEntity(b) != null;
    }

    static void removeA(Minecraft client) {
        check("chest A loaded on the client", 1, loadA);
        check("chest B loaded on the client", 1, loadB);
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> server.overworld().removeBlock(a, false));
        // B: mark removed on the client, then the lookup drops it from the chunk
        LevelChunk chunk = client.level.getChunkAt(b);
        BlockEntity be = chunk.getBlockEntity(b);
        check("chest B on the client chunk", true, be != null);
        if (be == null) System.out.println("[amber-worldtest] lifecycle probe: client at B: " + client.level.getBlockState(b) + " chunk=" + chunk.getPos() + " empty=" + chunk.isEmpty()
            + " BEs=" + chunk.getBlockEntities().keySet() + " server at B: " + server.overworld().getBlockState(b) + " player=" + client.player.blockPosition());
        if (be != null) {
            be.setRemoved();
            BlockEntity after = chunk.getBlockEntity(b, LevelChunk.EntityCreationType.CHECK);
            check("removed chest B dropped by getBlockEntity", null, after);
            check("chest B unload fired by getBlockEntity", 1, unloadB);
        }
    }

    static void report(Minecraft client) {
        check("chest A unload fired by removeBlockEntity", 1, unloadA);
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> server.overworld().removeBlock(b, false));
        System.out.println("[amber-worldtest] lifecycle probe total: " + ok + "/" + total + " OK");
    }
}
