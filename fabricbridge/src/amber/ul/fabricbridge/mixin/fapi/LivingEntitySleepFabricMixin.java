package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * fabric-entity-events-v1 re-base: EntitySleepEvents ALLOW_BED, SET_BED_OCCUPATION_STATE and MODIFY_SLEEPING_DIRECTION on
 * LivingEntity. Fabric's handlers (stripped) target vanilla's {@code instanceof BedBlock} / {@code level.setBlock(pos,
 * state.setValue(OCCUPIED, b), 3)} in startSleeping and the stopSleeping lambda, and BedBlock.getBedOrientation;
 * NeoForge goes through BlockState.isBed / setBedOccupied / getBedDirection. Same decisions on those calls: an accepted
 * ALLOW_BED makes the state a red bed for the rest of the method (as Fabric does), the occupation event sees the real
 * state and, when it doesn't handle it, the real state's OCCUPIED is set (NeoForge's own setBedOccupied when the state
 * wasn't swapped), and the bed direction goes through MODIFY_SLEEPING_DIRECTION.
 */
@Mixin(value = LivingEntity.class, remap = false)
public abstract class LivingEntitySleepFabricMixin {
    private LivingEntity amber$self() { return (LivingEntity) (Object) this; }

    @ModifyVariable(method = {"startSleeping(Lnet/minecraft/core/BlockPos;)V", "/^lambda\\$stopSleeping\\$\\d+$/"}, remap = false,
            at = @At(value = "INVOKE_ASSIGN", remap = false, target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState amber$fabricAllowBed(BlockState state, BlockPos pos) {
        LivingEntity self = amber$self();
        InteractionResult r = EntitySleepEvents.ALLOW_BED.invoker().allowBed(self, pos, state, state.isBed(self.level(), pos, self));
        return r.consumesAction() ? Blocks.RED_BED.defaultBlockState() : state;
    }

    @WrapOperation(method = {"startSleeping(Lnet/minecraft/core/BlockPos;)V", "/^lambda\\$stopSleeping\\$\\d+$/"}, remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/level/block/state/BlockState;setBedOccupied(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/LivingEntity;Z)V"))
    private void amber$fabricBedOccupation(BlockState state, Level level, BlockPos pos, LivingEntity sleeper, boolean occupied, Operation<Void> original) {
        BlockState real = level.getBlockState(pos);
        if (EntitySleepEvents.SET_BED_OCCUPATION_STATE.invoker().setBedOccupationState(amber$self(), pos, real, occupied)) return;
        if (state == real) original.call(state, level, pos, sleeper, occupied);
        else if (real.hasProperty(BedBlock.OCCUPIED)) level.setBlock(pos, real.setValue(BedBlock.OCCUPIED, occupied), 3);
    }

    /**
     * Vanilla: no sleeping pos -> null, else BedBlock.getBedOrientation (null off a bed), and Fabric's event sees that
     * value whenever there is a sleeping pos. NeoForge returns UP for both "no pos" and "not a bed". The event gets
     * vanilla's value (null off a bed, so a sleeping-bag mod that accepted ALLOW_BED can set the direction); a null
     * answer keeps NeoForge's UP.
     */
    @ModifyReturnValue(method = "getBedOrientation()Lnet/minecraft/core/Direction;", remap = false, at = @At(value = "RETURN", remap = false))
    private Direction amber$fabricSleepDirection(Direction ret) {
        LivingEntity self = amber$self();
        BlockPos pos = self.getSleepingPos().orElse(null);
        if (pos == null) return ret;
        Direction vanilla = self.level().getBlockState(pos).isBed(self.level(), pos, self) ? ret : null;
        Direction d = EntitySleepEvents.MODIFY_SLEEPING_DIRECTION.invoker().modifySleepDirection(self, pos, vanilla);
        return d != null ? d : ret;
    }
}
