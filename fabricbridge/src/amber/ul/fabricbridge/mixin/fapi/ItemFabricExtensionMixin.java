package amber.ul.fabricbridge.mixin.fapi;

import net.fabricmc.fabric.api.item.v1.EnchantingContext;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.neoforged.neoforge.common.extensions.IItemExtension;
import org.spongepowered.asm.mixin.Mixin;

/**
 * fabric-item-api-v1 re-base, Item side. Fabric's RecipeMixin / AbstractFurnaceBlockEntityMixin /
 * BrewingStandBlockEntityMixin (vetoed) redirect vanilla's stack-less Item.getCraftingRemainingItem() to
 * FabricItem.getRecipeRemainder(stack); NeoForge's crafting, furnace and brewing stand already ask the stack-aware
 * IItemExtension.get/hasCraftingRemainingItem(stack), so those answer from getRecipeRemainder. Likewise
 * FabricItem.canBeEnchantedWith answers supportsEnchantment (ACCEPTABLE) and isPrimaryItemFor (PRIMARY). Only for item
 * classes that override the Fabric method; everything else keeps NeoForge's default (and NeoForge items overriding the
 * NeoForge method keep theirs).
 */
@Mixin(value = Item.class, remap = false)
public abstract class ItemFabricExtensionMixin implements IItemExtension {
    private static final ClassValue<Boolean> REMAINDER = overrides("getRecipeRemainder", ItemStack.class);
    private static final ClassValue<Boolean> ENCHANT = overrides("canBeEnchantedWith", ItemStack.class, Holder.class, EnchantingContext.class);

    private static ClassValue<Boolean> overrides(String name, Class<?>... params) {
        return new ClassValue<>() {
            @Override protected Boolean computeValue(Class<?> c) {
                for (Class<?> k = c; k != null && k != Item.class; k = k.getSuperclass())
                    try { k.getDeclaredMethod(name, params); return true; } catch (NoSuchMethodException e) { /* keep walking */ }
                return false;
            }
        };
    }

    @Override public ItemStack getCraftingRemainingItem(ItemStack stack) {
        if (REMAINDER.get(getClass())) return ((FabricItem) this).getRecipeRemainder(stack);
        return IItemExtension.super.getCraftingRemainingItem(stack);
    }

    @Override public boolean hasCraftingRemainingItem(ItemStack stack) {
        if (REMAINDER.get(getClass())) return !((FabricItem) this).getRecipeRemainder(stack).isEmpty();
        return IItemExtension.super.hasCraftingRemainingItem(stack);
    }

    @Override public boolean supportsEnchantment(ItemStack stack, Holder<Enchantment> ench) {
        if (ENCHANT.get(getClass())) return ((FabricItem) this).canBeEnchantedWith(stack, ench, EnchantingContext.ACCEPTABLE);
        return IItemExtension.super.supportsEnchantment(stack, ench);
    }

    @Override public boolean isPrimaryItemFor(ItemStack stack, Holder<Enchantment> ench) {
        if (ENCHANT.get(getClass())) return ((FabricItem) this).canBeEnchantedWith(stack, ench, EnchantingContext.PRIMARY);
        return IItemExtension.super.isPrimaryItemFor(stack, ench);
    }
}
