package amber.ul.fabricbridge.mixin.fapi;

import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredient;
import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient;
import net.fabricmc.fabric.impl.recipe.ingredient.AmberNeoIngredient;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;

/**
 * fabric-recipe-api-v1 re-base: Fabric's IngredientMixin (vetoed) made every Ingredient a FabricIngredient. Here the
 * same two methods answer from NeoForge's custom-ingredient slot. (getCustomIngredient() with Fabric's return type sits
 * next to NeoForge's getCustomIngredient() returning ICustomIngredient: same name, different descriptor.)
 */
@Mixin(value = Ingredient.class, remap = false)
public abstract class IngredientFabricMixin implements FabricIngredient {
    @Override public CustomIngredient getCustomIngredient() {
        return ((Ingredient) (Object) this).getCustomIngredient() instanceof AmberNeoIngredient a ? a.custom() : null;
    }
    @Override public boolean requiresTesting() { return !((Ingredient) (Object) this).isSimple(); }
}
