/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
/*
 * Amber re-base of Fabric API's CustomIngredient (fabric-recipe-api-v1 5.0.16, Apache-2.0) for the NeoForge platform.
 * Same interface; only toVanilla() differs: NeoForge's Ingredient is final, so Fabric's CustomIngredientImpl (a subclass
 * of Ingredient) cannot exist there. The custom ingredient is wrapped as a NeoForge ICustomIngredient instead.
 */
package net.fabricmc.fabric.api.recipe.v1.ingredient;

import java.util.List;
import net.fabricmc.fabric.impl.recipe.ingredient.AmberNeoIngredient;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

public interface CustomIngredient {
    /** Checks if a stack matches this ingredient. The stack must not be modified. */
    boolean test(ItemStack stack);

    /** The stacks that may match this ingredient (shown by recipe viewers). Must be cached by the caller if needed. */
    List<ItemStack> getMatchingStacks();

    /** Whether test() must be called; false means getMatchingStacks() is exact (the ingredient is "simple"). */
    boolean requiresTesting();

    /** The serializer for this ingredient. */
    CustomIngredientSerializer<?> getSerializer();

    /** A vanilla ingredient backed by this custom ingredient (on NeoForge: new Ingredient(ICustomIngredient)). */
    default Ingredient toVanilla() {
        return new Ingredient(new AmberNeoIngredient(this));
    }
}
