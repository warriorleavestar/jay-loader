package amber.worldtest;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;

/**
 * fabric-data-attachment-api-v1 probe (-Damber.attachprobe=true). A persistent Integer counter on the overworld, the
 * player and the player's chunk: each run logs what it found (saved by the previous run) and stores found+1, so two runs
 * in a row prove save + load. A synced attachment (server -> client) is set on the server player and read on the client.
 */
final class AttachProbe {
    static AttachmentType<Integer> COUNTER, SYNCED;
    static final int SYNC_VALUE = 4242;
    static void register() {
        COUNTER = AttachmentRegistry.create(ResourceLocation.fromNamespaceAndPath("amber_worldtest", "probe_counter"), b -> b.persistent(Codec.INT));
        SYNCED = AttachmentRegistry.create(ResourceLocation.fromNamespaceAndPath("amber_worldtest", "probe_synced"),
                b -> b.syncWith(ByteBufCodecs.INT, AttachmentSyncPredicate.all()));
        System.out.println("[amber-worldtest] attach probe: types registered");
    }
    static int bump(Object target) {
        AttachmentTarget t = (AttachmentTarget) target;
        Integer old = t.getAttached(COUNTER);
        t.setAttached(COUNTER, old == null ? 1 : old + 1);
        return old == null ? 0 : old;
    }
    static void server(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) { System.out.println("[amber-worldtest] attach probe: no integrated server"); return; }
        server.execute(() -> {
            var level = server.overworld();
            var player = server.getPlayerList().getPlayers().get(0);
            var chunk = level.getChunk(player.blockPosition());
            int w = bump(level), p = bump(player), c = bump(chunk);
            chunk.setUnsaved(true);
            ((AttachmentTarget) player).setAttached(SYNCED, SYNC_VALUE);
            System.out.println("[amber-worldtest] attach probe: found world=" + w + " player=" + p + " chunk=" + c + " (stored +1)");
        });
    }
    static void client(Minecraft client) {
        Integer v = client.player == null ? null : ((AttachmentTarget) client.player).getAttached(SYNCED);
        System.out.println("[amber-worldtest] attach probe: client sees synced=" + v + " -> " + (Integer.valueOf(SYNC_VALUE).equals(v) ? "OK" : "MISSING"));
    }
}
