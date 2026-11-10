package amber.ul.fabricbridge.forge.mixin.fapi;

import java.util.Optional;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.ForgeEventFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fabric-entity-events-v1 on Forge: EntitySleepEvents.ALLOW_SLEEP_TIME while sleeping. The official PlayerEntityMixin
 * #redirectDaySleepCheck (stripped) redirects Level.isDay() in Player.tick, which wakes a sleeping player when it is day;
 * Forge replaced that call with ForgeEventFactory.fireSleepingTimeCheck. Under strict require that dead redirect got the
 * whole PlayerEntityMixin excluded (TRY_SLEEP / ALLOW_RESETTING_TIME gone too). Same semantics as
 * ServerPlayerSleepTimeFabricMixin: Forge's answer is the vanilla result, a non-PASS Fabric result overrides it.
 */
@Mixin(value = Player.class, remap = false)
public abstract class PlayerSleepTickFabricMixin {
    @Redirect(method = "tick", remap = false, at = @At(value = "INVOKE", remap = false,
        target = "Lnet/minecraftforge/event/ForgeEventFactory;fireSleepingTimeCheck(Lnet/minecraft/world/entity/player/Player;Ljava/util/Optional;)Z"))
    private boolean amber$fabricSleepTimeTick(Player player, Optional<BlockPos> pos) {
        boolean canSleep = ForgeEventFactory.fireSleepingTimeCheck(player, pos);
        if (pos.isEmpty()) return canSleep;
        InteractionResult r = EntitySleepEvents.ALLOW_SLEEP_TIME.invoker().allowSleepTime(player, pos.get(), canSleep);
        return r != InteractionResult.PASS ? r.consumesAction() : canSleep;
    }
}
