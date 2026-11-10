package amber.ul.fabricbridge.mixin;

import amber.ul.fabricbridge.FabricWindow;
import net.neoforged.neoforge.registries.GameData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fabric entrypoint window: HEAD of GameData.postRegisterEvents (after unfreezeData, before the registration order is captured). */
@Mixin(value = GameData.class, remap = false)
public abstract class GameDataMixin {
    @Inject(method = "postRegisterEvents", at = @At("HEAD"), remap = false)
    private static void amber$fabricWindow(CallbackInfo ci) { FabricWindow.run(); }

    /** SYNCED attributes added after the entrypoints (still before NeoForge freezes and snapshots). */
    @Inject(method = "freezeData", at = @At("HEAD"), remap = false)
    private static void amber$syncAttributes(CallbackInfo ci) { FabricWindow.syncAttributes("freezeData"); }
}
