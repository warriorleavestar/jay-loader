package amber.ul.fabricbridge.forge.mixin.fapi;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
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
 * fabric-events-interaction-v0 on Forge: PlayerBlockBreakEvents BEFORE / CANCELED / AFTER. The official
 * ServerPlayerInteractionManagerMixin#breakBlock / #onBlockBroken (stripped) hook Block.playerWillDestroy and
 * Block.destroy in destroyBlock by local capture; Forge moved both into its private removeBlock(pos, canHarvest)
 * (BlockState.onDestroyedByPlayer, then destroy), called once for creative and once for survival. Same events around
 * those calls: BEFORE (false: CANCELED, destroyBlock returns false, vanilla resyncs the client), AFTER when removed.
 * The rest of that mixin (attack / use block / use item HEAD hooks) is untouched.
 */
@Mixin(value = ServerPlayerGameMode.class, remap = false)
public abstract class ServerBlockBreakFabricMixin {
    @Shadow(remap = false) @Final protected ServerPlayer player;
    @Shadow(remap = false) protected ServerLevel level;

    @Inject(method = "destroyBlock", cancellable = true, remap = false, require = 2, allow = 2,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/server/level/ServerPlayerGameMode;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private void amber$fabricBeforeBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir, @Local BlockState state, @Local BlockEntity be) {
        if (!PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, player, pos, state, be)) {
            PlayerBlockBreakEvents.CANCELED.invoker().onBlockBreakCanceled(level, player, pos, state, be);
            cir.setReturnValue(false);
        }
    }

    @ModifyExpressionValue(method = "destroyBlock", remap = false, require = 2, allow = 2,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/server/level/ServerPlayerGameMode;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"))
    private boolean amber$fabricAfterBreak(boolean removed, @Local(argsOnly = true) BlockPos pos, @Local BlockState state, @Local BlockEntity be) {
        if (removed) PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, player, pos, state, be);
        return removed;
    }
}
