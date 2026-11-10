package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-item-api-v1 re-base: FabricItem.allowContinuingBlockBreaking. Fabric's client ClientPlayerInteractionManagerMixin
 * (vetoed) redirects vanilla's ItemStack.isSameItemSameComponents in sameDestroyTarget; NeoForge asks
 * destroyingItem.shouldCauseBlockBreakReset(newStack) there instead. A reset is cancelled when the item is the same and
 * the item allows continuing (Fabric's rule), otherwise NeoForge's answer stands.
 */
@Mixin(value = MultiPlayerGameMode.class, remap = false)
public abstract class MultiPlayerGameModeFabricMixin {
    @Shadow(remap = false) @Final private Minecraft minecraft;

    @WrapOperation(method = "sameDestroyTarget", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/item/ItemStack;shouldCauseBlockBreakReset(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean amber$fabricContinueBreaking(ItemStack old, ItemStack now, Operation<Boolean> op) {
        if (!op.call(old, now)) return false;
        return !(old.is(now.getItem()) && ((net.fabricmc.fabric.api.item.v1.FabricItem) old.getItem()).allowContinuingBlockBreaking(minecraft.player, old, now));
    }
}
