package amber.ul.fabricbridge.forge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.fabricmc.fabric.api.client.particle.v1.ParticleRenderEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.extensions.common.IClientBlockExtensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-particles-v1 on Forge 47: ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT (port of the NeoForge bridge's mixin).
 * Fabric's BlockDustParticleMixin (vetoed) swaps the state fed to vanilla's {@code !state.is(GRASS_BLOCK)} tint check;
 * Forge replaced that check with IClientBlockExtensions.areBreakingParticlesTinted. Tinted only when Forge says so and
 * no listener vetoes it.
 */
@Mixin(value = TerrainParticle.class, remap = false)
public abstract class TerrainParticleFabricMixin {
    @WrapOperation(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraftforge/client/extensions/common/IClientBlockExtensions;areBreakingParticlesTinted(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean amber$fabricDustTint(IClientBlockExtensions ext, BlockState state, ClientLevel level, BlockPos pos, Operation<Boolean> original) {
        return original.call(ext, state, level, pos) && ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT.invoker().allowBlockDustTint(state, level, pos);
    }
}
