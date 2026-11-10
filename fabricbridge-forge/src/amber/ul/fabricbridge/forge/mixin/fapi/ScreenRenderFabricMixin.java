package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-screen-api-v1 on Forge: ScreenEvents.BEFORE_RENDER / AFTER_RENDER. Fabric's GameRendererMixin hooks
 * Screen.renderWithTooltip in GameRenderer.render and captures its locals; Forge moved that call into
 * ForgeHooksClient.drawScreenInternal (Render.Pre/Post, also used for Forge's GUI layers). Same two points there, for the
 * current screen only (layers are Forge's own), no local capture.
 */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class ScreenRenderFabricMixin {
    @Unique private static Screen amber$renderingScreen;

    @Inject(method = "drawScreenInternal", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"), remap = false)
    private static void amber$beforeRender(Screen screen, GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (screen != Minecraft.getInstance().screen) return;
        amber$renderingScreen = screen;
        ScreenEvents.beforeRender(screen).invoker().beforeRender(screen, g, mouseX, mouseY, delta);
    }

    @Inject(method = "drawScreenInternal", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", shift = At.Shift.AFTER), remap = false)
    private static void amber$afterRender(Screen screen, GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Screen s = amber$renderingScreen;
        if (s == null || screen != s) return;
        ScreenEvents.afterRender(s).invoker().afterRender(s, g, mouseX, mouseY, delta);
        amber$renderingScreen = null;
    }
}
