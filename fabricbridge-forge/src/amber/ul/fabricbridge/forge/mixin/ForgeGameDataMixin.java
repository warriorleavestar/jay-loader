package amber.ul.fabricbridge.forge.mixin;

import amber.ul.fabricbridge.forge.ForgeFabricWindow;
import net.minecraftforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric entrypoint window on Forge 47: HEAD of GameData.postRegisterEvents (after unfreezeData, before the first RegisterEvent). */
@Mixin(value = GameData.class, remap = false)
public abstract class ForgeGameDataMixin {
    @Inject(method = "postRegisterEvents", at = @At("HEAD"), remap = false)
    private static void amber$fabricWindow(CallbackInfo ci) { ForgeFabricWindow.run(); }
}
