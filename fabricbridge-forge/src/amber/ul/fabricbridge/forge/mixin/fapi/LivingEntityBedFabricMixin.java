package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;

/**
 * fabric-entity-events-v1 on Forge: EntitySleepEvents.SET_BED_OCCUPATION_STATE and MODIFY_SLEEPING_DIRECTION. The official LivingEntityMixin
 * #setOccupiedState (stripped) redirects Level.setBlock(pos, state.with(OCCUPIED)) in startSleeping and the stopSleeping
 * lambda; Forge calls BlockState.setBedOccupied (IForgeBlockState) there. Same rule as Fabric, on the block actually in
 * the world: a Fabric listener may handle it; otherwise a Forge bed (isBed) sets its own state, a block with OCCUPIED gets
 * it set, anything else is left alone. The receiver may be Fabric's RED_BED stand-in (ALLOW_BED), never placed.
 * #onGetSleepingDirection (stripped) wraps BedBlock.getDirection in getBedOrientation; Forge asks
 * BlockState.getBedDirection (IForgeBlockState) instead: same wrap there.
 */
@Mixin(value = LivingEntity.class, remap = false)
public abstract class LivingEntityBedFabricMixin {
    @Redirect(method = {"startSleeping(Lnet/minecraft/core/BlockPos;)V", "lambda$stopSleeping$11(Lnet/minecraft/core/BlockPos;)V"}, remap = false, require = 2, allow = 2,
        at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V"))
    private void amber$fabricBedOccupied(BlockState stateOrStandIn, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied) {
        BlockState world = level.getBlockState(pos);
        if (EntitySleepEvents.SET_BED_OCCUPATION_STATE.invoker().setBedOccupationState(sleeper, pos, world, occupied)) return;
        if (world.isBed(level, pos, sleeper)) world.setBedOccupied(level, pos, sleeper, occupied);
        else if (world.hasProperty(BedBlock.OCCUPIED)) level.setBlock(pos, world.setValue(BedBlock.OCCUPIED, occupied), 3);
    }

    @WrapOperation(method = "getBedOrientation", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraft/world/level/block/state/BlockState;getBedDirection(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Direction;"))
    private Direction amber$fabricSleepDirection(BlockState state, LevelReader level, BlockPos pos, Operation<Direction> op) {
        Direction d = op.call(state, level, pos);
        return EntitySleepEvents.MODIFY_SLEEPING_DIRECTION.invoker().modifySleepDirection((LivingEntity) (Object) this, pos, d);
    }
}
