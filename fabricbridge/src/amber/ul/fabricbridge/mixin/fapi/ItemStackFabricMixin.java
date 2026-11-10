package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.item.v1.CustomDamageHandler;
import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.fabricmc.fabric.api.item.v1.EnchantmentEvents;
import net.fabricmc.fabric.api.item.v1.FabricItemStack;
import net.fabricmc.fabric.api.util.TriState;
import net.fabricmc.fabric.impl.item.ItemExtensions;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-item-api-v1 re-base, ItemStack side. Fabric's ItemStackMixin (vetoed: its hookDamage wraps the ServerPlayer
 * overload, NeoForge's hurtAndBreak(int, LivingEntity, EquipmentSlot) calls the LivingEntity one) also made ItemStack a
 * FabricItemStack; both come back here. EnchantmentEvents.ALLOW_ENCHANTING: Fabric redirects the anvil, /enchant,
 * enchant_randomly and the enchanting table to it; on NeoForge all four already ask ItemStack.supportsEnchantment /
 * isPrimaryItemFor, so those two answer the event first (a non-DEFAULT TriState wins), then the item
 * (ItemFabricExtensionMixin adds FabricItem.canBeEnchantedWith there).
 */
@Mixin(value = ItemStack.class, remap = false)
public abstract class ItemStackFabricMixin implements FabricItemStack {
    @Shadow(remap = false) public abstract Item getItem();
    @Shadow(remap = false) public abstract void shrink(int n);

    @WrapOperation(method = "hurtAndBreak(ILnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/entity/EquipmentSlot;)V", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lnet/minecraft/world/item/ItemStack;hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;Ljava/util/function/Consumer;)V"))
    private void amber$fabricDamage(ItemStack stack, int amount, ServerLevel level, LivingEntity entity, Consumer<Item> onBreak, Operation<Void> op,
                                    @Local(argsOnly = true) EquipmentSlot slot) {
        CustomDamageHandler h = ((ItemExtensions) getItem()).fabric_getCustomDamageHandler();
        if (h != null && !entity.hasInfiniteMaterials()) {
            boolean[] broke = {false};
            amount = h.damage((ItemStack) (Object) this, amount, entity, slot, () -> { broke[0] = true; shrink(1); onBreak.accept(getItem()); });
            if (broke[0]) return;
        }
        op.call(stack, amount, level, entity, onBreak);
    }

    public boolean supportsEnchantment(Holder<Enchantment> ench) {
        TriState t = EnchantmentEvents.ALLOW_ENCHANTING.invoker().allowEnchanting(ench, (ItemStack) (Object) this, EnchantingContext.ACCEPTABLE);
        return t != TriState.DEFAULT ? t.get() : getItem().supportsEnchantment((ItemStack) (Object) this, ench);
    }

    public boolean isPrimaryItemFor(Holder<Enchantment> ench) {
        TriState t = EnchantmentEvents.ALLOW_ENCHANTING.invoker().allowEnchanting(ench, (ItemStack) (Object) this, EnchantingContext.PRIMARY);
        return t != TriState.DEFAULT ? t.get() : getItem().isPrimaryItemFor((ItemStack) (Object) this, ench);
    }
}
