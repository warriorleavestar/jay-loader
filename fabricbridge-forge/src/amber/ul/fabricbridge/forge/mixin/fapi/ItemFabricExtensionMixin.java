package amber.ul.fabricbridge.forge.mixin.fapi;

import com.google.common.collect.Multimap;
import net.fabricmc.fabric.api.item.v1.FabricItem;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.extensions.IForgeItem;
import org.spongepowered.asm.mixin.Mixin;

/**
 * fabric-item-api-v1 on Forge 47, Item side (port of the NeoForge bridge's ItemFabricExtensionMixin). Fabric's
 * RecipeMixin / AbstractFurnaceBlockEntityMixin / BrewingStandBlockEntityMixin (vetoed) redirect vanilla's stack-less
 * Item.hasCraftingRemainingItem / getCraftingRemainingItem to FabricItem.getRecipeRemainder(stack); ItemStackMixin
 * #hookGetAttributeModifiers / #hookIsSuitableFor (stripped) redirect the stack-less getDefaultAttributeModifiers /
 * isCorrectToolForDrops. Forge's crafting, furnace, brewing stand, ItemStack.getAttributeModifiers and
 * ItemStack.isCorrectToolForDrops already ask the stack-aware IForgeItem methods, so those answer from the Fabric
 * methods. Only for item classes that override the Fabric method; everything else keeps Forge's default (and Forge items
 * overriding the Forge method keep theirs).
 */
@Mixin(value = Item.class, remap = false)
public abstract class ItemFabricExtensionMixin implements IForgeItem {
    private static final ClassValue<Boolean> REMAINDER = overrides("getRecipeRemainder", ItemStack.class);
    private static final ClassValue<Boolean> ATTRIBUTES = overrides("getAttributeModifiers", ItemStack.class, EquipmentSlot.class);
    private static final ClassValue<Boolean> SUITABLE = overrides("isSuitableFor", ItemStack.class, BlockState.class);

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
        return IForgeItem.super.getCraftingRemainingItem(stack);
    }

    @Override public boolean hasCraftingRemainingItem(ItemStack stack) {
        if (REMAINDER.get(getClass())) return !((FabricItem) this).getRecipeRemainder(stack).isEmpty();
        return IForgeItem.super.hasCraftingRemainingItem(stack);
    }

    @Override public Multimap<Attribute, AttributeModifier> getAttributeModifiers(EquipmentSlot slot, ItemStack stack) {
        if (ATTRIBUTES.get(getClass())) return ((FabricItem) this).getAttributeModifiers(stack, slot);
        return IForgeItem.super.getAttributeModifiers(slot, stack);
    }

    @Override public boolean isCorrectToolForDrops(ItemStack stack, BlockState state) {
        if (SUITABLE.get(getClass())) return ((FabricItem) this).isSuitableFor(stack, state);
        return IForgeItem.super.isCorrectToolForDrops(stack, state);
    }
}
