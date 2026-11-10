package amber.ul.fabricbridge.forge.mixin.fapi;

import java.util.Optional;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fabric-entity-events-v1 on Forge: EntitySleepEvents.ALLOW_SLEEP_TIME. The official ServerPlayerEntityMixin
 * #redirectDaySleepCheck (stripped) redirects Level.isDay() in startSleepInBed; Forge's patch replaced that call with
 * ForgeEventFactory.fireSleepingTimeCheck (SleepingTimeCheckEvent), so the redirect found nothing and the event never
 * fired (and sleeping in daytime stayed NOT_POSSIBLE_NOW). Same semantics on Forge's check: Forge's answer (vanilla
 * time rule plus Forge listeners) is the vanilla result; a non-PASS Fabric result overrides it.
 */
@Mixin(value = ServerPlayer.class, remap = false)
public abstract class ServerPlayerSleepTimeFabricMixin {
    @Redirect(method = "startSleepInBed", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraftforge/event/ForgeEventFactory;fireSleepingTimeCheck(Lnet/minecraft/world/entity/player/Player;Ljava/util/Optional;)Z"))
    private boolean amber$fabricSleepTime(Player player, Optional<BlockPos> pos) {
        boolean canSleep = ForgeEventFactory.fireSleepingTimeCheck(player, pos);
        if (pos.isEmpty()) return canSleep;
        InteractionResult r = EntitySleepEvents.ALLOW_SLEEP_TIME.invoker().allowSleepTime(player, pos.get(), canSleep);
        return r != InteractionResult.PASS ? r.consumesAction() : canSleep;
    }
}
