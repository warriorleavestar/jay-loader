package amber.ul.fabricbridge.mixin.fapi;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.serialization.DataResult;
import java.util.Optional;
import net.fabricmc.fabric.impl.item.EnchantmentUtil;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * fabric-item-api-v1 re-base: EnchantmentEvents.MODIFY. Fabric's RegistryLoaderMixin (vetoed) wraps the register call in
 * loadElementFromResource; NeoForge decodes through ConditionalOps into an Optional and registers in a lambda that no
 * longer sees the Resource. Same effect one step earlier: the decoded Optional is mapped through EnchantmentUtil.modify
 * with the source determined from the Resource, before NeoForge registers it.
 */
@Mixin(value = RegistryDataLoader.class, remap = false)
public abstract class RegistryDataLoaderFabricMixin {
    @SuppressWarnings({"unchecked", "rawtypes"})
    @WrapOperation(method = "loadElementFromResource", remap = false,
            at = @At(value = "INVOKE", remap = false, target = "Lcom/mojang/serialization/DataResult;getOrThrow()Ljava/lang/Object;"))
    private static Object amber$fabricModifyEnchantment(DataResult<?> result, Operation<Object> op,
                                                        @Local(argsOnly = true) ResourceKey key, @Local(argsOnly = true) Resource resource) {
        Object o = op.call(result);
        if (o instanceof Optional<?> opt && opt.isPresent() && opt.get() instanceof Enchantment e)
            return Optional.of(EnchantmentUtil.modify((ResourceKey<Enchantment>) key, e, EnchantmentUtil.determineSource(resource)));
        return o;
    }
}
