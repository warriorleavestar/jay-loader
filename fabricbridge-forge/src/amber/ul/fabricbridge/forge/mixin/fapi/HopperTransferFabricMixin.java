package amber.ul.fabricbridge.forge.mixin.fapi;

import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-transfer-api-v1 on Forge 47: hoppers push into and pull from Fabric item Storages that are not vanilla Containers.
 * Fabric's HopperBlockEntityMixin (vetoed) targets vanilla's insert(Level, BlockPos, BlockState, Container), which Forge
 * turned into ejectItems(.., HopperBlockEntity), and captures suckInItems' locals, where Forge added the extractHook result
 * first. Same logic at the same point: after the vanilla container lookup came back null (Forge's IItemHandler hooks have
 * already run), move one item through ItemStorage.SIDED.
 */
@Mixin(value = HopperBlockEntity.class, remap = false)
public abstract class HopperTransferFabricMixin {
    @Inject(method = "ejectItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Z",
            remap = false, cancellable = true, at = @At(value = "INVOKE_ASSIGN", remap = false,
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getAttachedContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/Container;"))
    private static void amber$fabricInsert(Level level, BlockPos pos, BlockState state, HopperBlockEntity hopper, CallbackInfoReturnable<Boolean> cir, @Local Container target) {
        if (target != null) return;
        Direction facing = state.getValue(HopperBlock.FACING);
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos.relative(facing), facing.getOpposite());
        if (storage != null && StorageUtil.move(InventoryStorage.of(hopper, facing), storage, iv -> true, 1, null) == 1) cir.setReturnValue(true);
    }

    @Inject(method = "suckInItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Z",
            remap = false, cancellable = true, at = @At(value = "INVOKE_ASSIGN", remap = false,
            target = "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getSourceContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/entity/Hopper;)Lnet/minecraft/world/Container;"))
    private static void amber$fabricExtract(Level level, Hopper hopper, CallbackInfoReturnable<Boolean> cir, @Local Container source) {
        if (source != null) return;
        BlockPos above = BlockPos.containing(hopper.getLevelX(), hopper.getLevelY() + 1.0, hopper.getLevelZ());
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, above, Direction.DOWN);
        if (storage != null && StorageUtil.move(storage, InventoryStorage.of(hopper, Direction.UP), iv -> true, 1, null) == 1) cir.setReturnValue(true);
    }
}
