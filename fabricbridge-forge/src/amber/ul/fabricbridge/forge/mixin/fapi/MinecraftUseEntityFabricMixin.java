package amber.ul.fabricbridge.forge.mixin.fapi;

import com.llamalad7.mixinextras.sugar.Local;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-events-interaction-v0 on Forge 47: UseEntityCallback on the client. Fabric's injectUseEntityCallback (stripped
 * by the Forge bridge manifest) captures startUseItem's locals by position; Forge added its
 * InputEvent.InteractionKeyMappingTriggered local (ForgeHooksClient.onClickInput). Same hook before
 * MultiPlayerGameMode.interactAt with hand / hit / entity taken by type: a non-PASS result sends the interaction packet
 * (when it consumes the action), swings when asked, and skips vanilla's handling.
 */
@Mixin(value = Minecraft.class, remap = false)
public abstract class MinecraftUseEntityFabricMixin {
    @Shadow(remap = false) public LocalPlayer player;
    @Shadow(remap = false) public abstract ClientPacketListener getConnection();

    @Inject(method = "startUseItem()V", remap = false, cancellable = true,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;interactAt(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/EntityHitResult;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"))
    private void amber$fabricUseEntity(CallbackInfo ci, @Local InteractionHand hand, @Local EntityHitResult hit, @Local Entity entity) {
        InteractionResult r = UseEntityCallback.EVENT.invoker().interact(player, player.level(), hand, entity, hit);
        if (r == InteractionResult.PASS) return;
        if (r.consumesAction()) {
            Vec3 at = hit.getLocation().subtract(entity.getX(), entity.getY(), entity.getZ());
            getConnection().send(ServerboundInteractPacket.createInteractionPacket(entity, player.isShiftKeyDown(), hand, at));
        }
        if (r.shouldSwing()) player.swing(hand);
        ci.cancel();
    }
}
