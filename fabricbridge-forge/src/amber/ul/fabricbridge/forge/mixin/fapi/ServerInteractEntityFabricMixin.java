package amber.ul.fabricbridge.forge.mixin.fapi;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * fabric-events-interaction-v0 on Forge 47: UseEntityCallback on the server. Fabric's ServerPlayNetworkHandlerMixin
 * (vetoed by the Forge bridge manifest) shadows the interact handler's captured entity as val$target; Forge's source
 * names that local entity (val$entity), as NeoForge's does. Same two HEAD hooks on the same anonymous class.
 */
@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl$1", remap = false)
public abstract class ServerInteractEntityFabricMixin {
    @Shadow(remap = false) @Final ServerGamePacketListenerImpl this$0;
    @Shadow(remap = false) @Final Entity val$entity;

    @Inject(method = "onInteraction(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/Vec3;)V", remap = false, at = @At("HEAD"), cancellable = true)
    private void amber$fabricInteractAt(InteractionHand hand, Vec3 at, CallbackInfo ci) {
        ServerPlayer player = this$0.player;
        EntityHitResult hit = new EntityHitResult(val$entity, at.add(val$entity.getX(), val$entity.getY(), val$entity.getZ()));
        if (UseEntityCallback.EVENT.invoker().interact(player, player.level(), hand, val$entity, hit) != InteractionResult.PASS) ci.cancel();
    }

    @Inject(method = "onInteraction(Lnet/minecraft/world/InteractionHand;)V", remap = false, at = @At("HEAD"), cancellable = true)
    private void amber$fabricInteract(InteractionHand hand, CallbackInfo ci) {
        ServerPlayer player = this$0.player;
        if (UseEntityCallback.EVENT.invoker().interact(player, player.level(), hand, val$entity, null) != InteractionResult.PASS) ci.cancel();
    }
}
