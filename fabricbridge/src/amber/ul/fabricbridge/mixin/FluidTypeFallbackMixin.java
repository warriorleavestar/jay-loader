package amber.ul.fabricbridge.mixin;

import amber.ul.fabricbridge.fapi.fluid.FabricFluidTypes;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric fluids on NeoForge: Fluid.getFluidType() (only reached by fluids that do not override it: vanilla's and Fabric
 * mods') answers foreign fluids from FabricFluidTypes instead of NeoForge's vanilla fallback, which throws for them.
 * Not specific to Fabric API: any Fabric mod's fluid needs it.
 */
@Mixin(value = Fluid.class, remap = false)
public abstract class FluidTypeFallbackMixin {
    @Inject(method = "getFluidType()Lnet/neoforged/neoforge/fluids/FluidType;", remap = false, at = @At("HEAD"), cancellable = true)
    private void amber$fabricFluidType(CallbackInfoReturnable<FluidType> cir) {
        Fluid self = (Fluid) (Object) this;
        if (FabricFluidTypes.foreign(self)) cir.setReturnValue(FabricFluidTypes.of(self));
    }
}
