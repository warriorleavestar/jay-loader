package amber.testmod.mixin;

import net.minecraft.class_442;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(class_442.class)
public abstract class TitleScreenMixin {
    @Inject(method = "init", at = @At("HEAD"))
    private void amberTestmod$init(CallbackInfo ci) {
        System.out.println("[amber-testmod] TitleScreen.init mixin applied; Minecraft.fps via @Accessor = " + MinecraftAccessor.amberTestmod$getFps());
    }
}
