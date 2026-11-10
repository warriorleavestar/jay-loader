package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-lifecycle-events-v1 re-base: block-entity unload when getBlockEntity drops a removed block entity. Fabric's
 * redirect (stripped) slices from createBlockEntity, after which vanilla does {@code blockEntities.remove(pos)} for a
 * removed one; NeoForge moved that check before the pending/create path, so the slice is empty. Same event on NeoForge's
 * first Map.remove in the method (blockEntities; the second is pendingBlockEntities).
 */
@Mixin(value = LevelChunk.class, remap = false)
public abstract class LevelChunkUnloadFabricMixin {
    @Shadow(remap = false) public abstract Level getLevel();

    @WrapOperation(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private Object amber$fabricUnloadRemoved(Map<Object, Object> map, Object key, Operation<Object> original) {
        Object removed = original.call(map, key);
        if (removed instanceof BlockEntity be) {
            if (getLevel() instanceof ServerLevel sl) ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(be, sl);
            else if (getLevel() instanceof ClientLevel cl) ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.invoker().onUnload(be, cl);
        }
        return removed;
    }
}
