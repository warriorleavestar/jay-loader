package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-events-interaction-v0 re-base: PlayerBlockBreakEvents. Fabric's breakBlock / onBlockBroken (stripped) capture
 * vanilla destroyBlock's locals by position; NeoForge's destroyBlock has other locals (BreakEvent, canHarvest) and calls
 * Block.destroy inside its own removeBlock(pos, state, canHarvest). Same events, locals by type: BEFORE right before
 * playerWillDestroy (false -> CANCELED and destroyBlock returns false), AFTER when NeoForge's removeBlock removed the
 * block (creative and survival call), with the state and block entity from before the break. The rest of Fabric's
 * mixin (AttackBlock / UseBlock / UseItem) is kept.
 */
@Mixin(value = ServerPlayerGameMode.class, remap = false)
public abstract class ServerPlayerGameModeFabricMixin {
    @Shadow(remap = false) protected ServerLevel level;
    @Shadow(remap = false) @Final protected ServerPlayer player;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", remap = false, cancellable = true,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/level/block/Block;playerWillDestroy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private void amber$fabricBefore(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local(ordinal = 0) BlockState state, @Local BlockEntity be) {
        if (PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, be)) return;
        PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, be);
        cir.setReturnValue(false);
    }

    @WrapOperation(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/server/level/ServerPlayerGameMode;removeBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Z"))
    private boolean amber$fabricAfter(ServerPlayerGameMode self, BlockPos pos, BlockState now, boolean canHarvest, Operation<Boolean> op,
                                      @Local(ordinal = 0) BlockState state, @Local BlockEntity be) {
        boolean removed = op.call(self, pos, now, canHarvest);
        if (removed) PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, be);
        return removed;
    }
}
