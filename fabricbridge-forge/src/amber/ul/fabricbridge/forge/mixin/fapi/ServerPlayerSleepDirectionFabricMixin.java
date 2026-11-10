package amber.ul.fabricbridge.forge.mixin.fapi;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.datafixers.util.Either;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-entity-events-v1 on Forge 47: the direction check of ServerPlayer.startSleepInBed. Fabric's
 * ServerPlayerEntityMixin#onTrySleepDirectionCheck (stripped by the Forge bridge manifest) captures the Direction local
 * by position (CAPTURE_FAILHARD); Forge's patch adds its PlayerSleepInBedEvent locals before it. Same point (after the
 * FACING lookup is stored), Direction taken by type: null (MODIFY_SLEEPING_DIRECTION gave none, through Fabric's
 * redirectSleepDirection) -> NOT_POSSIBLE_HERE. Priority 900: applied before Fabric's redirect replaces that call.
 */
@Mixin(value = ServerPlayer.class, remap = false, priority = 900)
public abstract class ServerPlayerSleepDirectionFabricMixin {
    @Inject(method = "startSleepInBed(Lnet/minecraft/core/BlockPos;)Lcom/mojang/datafixers/util/Either;", remap = false, cancellable = true,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/level/block/state/BlockState;getValue(Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/lang/Comparable;", shift = At.Shift.BY, by = 3))
    private void amber$fabricSleepDirection(BlockPos pos, CallbackInfoReturnable<Either<Player.BedSleepingProblem, Unit>> cir, @Local Direction direction) {
        if (direction == null) cir.setReturnValue(Either.left(Player.BedSleepingProblem.NOT_POSSIBLE_HERE));
    }
}
