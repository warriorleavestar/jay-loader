package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-screen-api-v1 on Forge: ScreenKeyboardEvents allow/before/after key press and release. Fabric's KeyboardMixin
 * targets vanilla's keyPress lambda (int, Screen, boolean[], int, int, int); Forge 47's is lambda$keyPress$5
 * (int, boolean[], Screen, int, int, int) with its Pre/Post events around the same Screen.keyPressed / keyReleased calls.
 * Same four points on Forge's lambda; cancelling skips the screen's handler and Forge's Post like vanilla Fabric does.
 */
@Mixin(value = KeyboardHandler.class, remap = false)
public abstract class ScreenKeyboardFabricMixin {
    private static final String LAMBDA = "lambda$keyPress$5(I[ZLnet/minecraft/client/gui/screens/Screen;III)V";

    @Inject(method = LAMBDA, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(III)Z"), cancellable = true, remap = false)
    private static void amber$beforeKeyPressed(int action, boolean[] result, Screen screen, int key, int scancode, int modifiers, CallbackInfo ci) {
        if (!ScreenKeyboardEvents.allowKeyPress(screen).invoker().allowKeyPress(screen, key, scancode, modifiers)) { ci.cancel(); return; }
        ScreenKeyboardEvents.beforeKeyPress(screen).invoker().beforeKeyPress(screen, key, scancode, modifiers);
    }

    @Inject(method = LAMBDA, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyPressed(III)Z", shift = At.Shift.AFTER), remap = false)
    private static void amber$afterKeyPressed(int action, boolean[] result, Screen screen, int key, int scancode, int modifiers, CallbackInfo ci) {
        ScreenKeyboardEvents.afterKeyPress(screen).invoker().afterKeyPress(screen, key, scancode, modifiers);
    }

    @Inject(method = LAMBDA, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyReleased(III)Z"), cancellable = true, remap = false)
    private static void amber$beforeKeyReleased(int action, boolean[] result, Screen screen, int key, int scancode, int modifiers, CallbackInfo ci) {
        if (!ScreenKeyboardEvents.allowKeyRelease(screen).invoker().allowKeyRelease(screen, key, scancode, modifiers)) { ci.cancel(); return; }
        ScreenKeyboardEvents.beforeKeyRelease(screen).invoker().beforeKeyRelease(screen, key, scancode, modifiers);
    }

    @Inject(method = LAMBDA, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;keyReleased(III)Z", shift = At.Shift.AFTER), remap = false)
    private static void amber$afterKeyReleased(int action, boolean[] result, Screen screen, int key, int scancode, int modifiers, CallbackInfo ci) {
        ScreenKeyboardEvents.afterKeyRelease(screen).invoker().afterKeyRelease(screen, key, scancode, modifiers);
    }
}
