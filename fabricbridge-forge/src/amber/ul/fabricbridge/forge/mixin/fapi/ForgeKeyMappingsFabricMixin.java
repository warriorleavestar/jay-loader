package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.impl.client.keybinding.KeyBindingRegistryImpl;
import net.minecraft.client.Options;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-key-binding-api-v1 on Forge: hands the Fabric key bindings collected by Amber's re-based KeyBindingRegistryImpl
 * to Forge right after its own RegisterKeyMappingsEvent (ForgeHooksClient.onRegisterKeyMappings, from initClientHooks).
 */
@Mixin(value = ForgeHooksClient.class, remap = false)
public abstract class ForgeKeyMappingsFabricMixin {
    @Inject(method = "onRegisterKeyMappings", at = @At("TAIL"), remap = false)
    private static void amber$fabricKeys(Options options, CallbackInfo ci) { KeyBindingRegistryImpl.flush(new RegisterKeyMappingsEvent(options)); }
}
