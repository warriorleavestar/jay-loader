package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-events-interaction-v0 on Forge, ServerPlayerInteractionManagerMixin (strict require excluded all of it for its
 * two break hooks; bridge: those two stripped, ServerBlockBreakFabricMixin on Forge's removeBlock calls). Survival
 * destroyBlock of a stone: BEFORE + AFTER, block gone; of a gold block BEFORE refuses: CANCELED, false, block kept. And the
 * three HEAD hooks of the same mixin: AttackBlockCallback (FAIL), UseBlockCallback, UseItemCallback.
 */
final class BreakProbe {
    private BreakProbe() {}
    static BlockPos stone, gold, attack, use; static int before, after, canceled, attacked, usedBlock, usedItem, ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] break: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static boolean ours(BlockPos p) { return p.equals(stone) || p.equals(gold); }

    static void register() {
        PlayerBlockBreakEvents.BEFORE.register((w, p, pos, state, be) -> { if (!ours(pos)) return true; before++; return !pos.equals(gold); });
        PlayerBlockBreakEvents.AFTER.register((w, p, pos, state, be) -> { if (ours(pos)) after++; });
        PlayerBlockBreakEvents.CANCELED.register((w, p, pos, state, be) -> { if (ours(pos)) canceled++; });
        AttackBlockCallback.EVENT.register((p, w, hand, pos, dir) -> { if (!pos.equals(attack)) return InteractionResult.PASS; attacked++; return InteractionResult.FAIL; });
        UseBlockCallback.EVENT.register((p, w, hand, hit) -> { if (w.isClientSide || !hit.getBlockPos().equals(use)) return InteractionResult.PASS; usedBlock++; return InteractionResult.SUCCESS; });
        UseItemCallback.EVENT.register((p, w, hand) -> { ItemStack s = p.getItemInHand(hand); if (w.isClientSide || !s.is(Items.BLAZE_ROD)) return InteractionResultHolder.pass(s); usedItem++; return InteractionResultHolder.success(s); });
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                ServerPlayer p = server.getPlayerList().getPlayers().get(0); ServerLevel level = p.serverLevel();
                BlockPos base = p.blockPosition();
                stone = base.offset(-2, 0, 2); gold = base.offset(-2, 1, 2); attack = base.offset(-2, 0, 3); use = base.offset(-2, 1, 3);
                level.setBlock(stone, Blocks.STONE.defaultBlockState(), 3); level.setBlock(gold, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                level.setBlock(attack, Blocks.DIRT.defaultBlockState(), 3); level.setBlock(use, Blocks.DIRT.defaultBlockState(), 3);
                GameType old = p.gameMode.getGameModeForPlayer();
                p.setGameMode(GameType.SURVIVAL);
                boolean r1 = p.gameMode.destroyBlock(stone);
                check("destroyBlock(stone) result", true, r1);
                check("BEFORE fired for stone", true, before >= 1);
                check("AFTER fired for stone", 1, after);
                check("stone gone", true, level.getBlockState(stone).isAir());
                boolean r2 = p.gameMode.destroyBlock(gold);
                check("destroyBlock(gold) refused by BEFORE", false, r2);
                check("CANCELED fired", 1, canceled);
                check("AFTER not fired for gold", 1, after);
                check("gold kept", true, level.getBlockState(gold).is(Blocks.GOLD_BLOCK));
                p.gameMode.handleBlockBreakAction(attack, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, level.getMaxBuildHeight(), 0);
                check("AttackBlockCallback fired (server)", 1, attacked);
                check("dirt kept after FAIL", true, level.getBlockState(attack).is(Blocks.DIRT));
                InteractionResult u = p.gameMode.useItemOn(p, level, ItemStack.EMPTY, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(use), Direction.UP, use, false));
                check("UseBlockCallback fired (server)", 1, usedBlock);
                check("useItemOn result from Fabric", InteractionResult.SUCCESS, u);
                ItemStack rod = new ItemStack(Items.BLAZE_ROD);
                p.setItemInHand(InteractionHand.MAIN_HAND, rod);
                InteractionResult i = p.gameMode.useItem(p, level, rod, InteractionHand.MAIN_HAND);
                check("UseItemCallback fired (server)", 1, usedItem);
                check("useItem result from Fabric", InteractionResult.SUCCESS, i);
                p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                p.setGameMode(old);
                for (BlockPos b : new BlockPos[]{stone, gold, attack, use}) level.removeBlock(b, false);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] break: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] break total: " + ok + "/" + total + " OK");
        });
    }
}
