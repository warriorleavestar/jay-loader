package amber.ul.fabricbridge.mixin.fapi;

import amber.ul.fabricbridge.fapi.screen.ExtendedMenuProvider;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** fabric-screen-handler-api-v1 re-base: openMenu(MenuProvider) delegates here on NeoForge, so this is every opening. */
@Mixin(value = ServerPlayer.class, remap = false)
public abstract class ServerPlayerOpenMenuMixin {
    @ModifyVariable(method = "openMenu(Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)Ljava/util/OptionalInt;", remap = false,
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private MenuProvider amber$extended(MenuProvider p) {
        return p instanceof ExtendedScreenHandlerFactory<?> f && !(p instanceof ExtendedMenuProvider) ? new ExtendedMenuProvider(f, (ServerPlayer) (Object) this) : p;
    }
}
