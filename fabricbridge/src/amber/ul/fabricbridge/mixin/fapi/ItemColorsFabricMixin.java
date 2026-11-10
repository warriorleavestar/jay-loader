package amber.ul.fabricbridge.mixin.fapi;

import java.util.Map;
import net.fabricmc.fabric.impl.client.rendering.ColorProviderRegistryImpl;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ItemLike;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** fabric-rendering-v1 re-base: ColorProviderRegistry.ITEM, same as BlockColorsFabricMixin on NeoForge's itemColors Map. */
@Mixin(value = ItemColors.class, remap = false)
public abstract class ItemColorsFabricMixin implements ColorProviderRegistryImpl.ColorMapperHolder<ItemLike, ItemColor> {
    @Shadow(remap = false) @Final private Map<Item, ItemColor> itemColors;

    @Inject(method = "createDefault(Lnet/minecraft/client/color/block/BlockColors;)Lnet/minecraft/client/color/item/ItemColors;", remap = false, at = @At("RETURN"))
    private static void amber$fabricInit(BlockColors blocks, CallbackInfoReturnable<ItemColors> cir) { ColorProviderRegistryImpl.ITEM.initialize(cir.getReturnValue()); }

    @Override public ItemColor get(ItemLike item) { return itemColors.get(item.asItem()); }
}
