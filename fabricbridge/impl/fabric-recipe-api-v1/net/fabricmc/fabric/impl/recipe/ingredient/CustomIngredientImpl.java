/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
/*
 * Amber re-base of Fabric API's CustomIngredientImpl (fabric-recipe-api-v1 5.0.16, Apache-2.0) for the NeoForge platform.
 * The official class extends Ingredient, which is final on NeoForge; here it keeps only the serializer registry (the part
 * CustomIngredientSerializer.register/get and CustomIngredientSync use) and, on registration, also registers a NeoForge
 * IngredientType under the same id so NeoForge's Ingredient codecs and network sync carry the ingredient.
 */
package net.fabricmc.fabric.impl.recipe.ingredient;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.resources.ResourceLocation;

public class CustomIngredientImpl {
    private CustomIngredientImpl() {}
    public static final String TYPE_KEY = "fabric:type";
    static final Map<ResourceLocation, CustomIngredientSerializer<?>> REGISTERED_SERIALIZERS = new ConcurrentHashMap<>();
    public static final Codec<CustomIngredientSerializer<?>> CODEC = ResourceLocation.CODEC.flatXmap(
            id -> Optional.<CustomIngredientSerializer<?>>ofNullable(REGISTERED_SERIALIZERS.get(id)).map(DataResult::success)
                    .orElseGet(() -> DataResult.error(() -> "Unknown custom ingredient serializer: " + id)),
            s -> DataResult.success(s.getIdentifier()));

    public static void registerSerializer(CustomIngredientSerializer<?> serializer) {
        if (REGISTERED_SERIALIZERS.putIfAbsent(serializer.getIdentifier(), serializer) != null)
            throw new IllegalArgumentException("CustomIngredientSerializer with identifier " + serializer.getIdentifier() + " already registered.");
        AmberNeoIngredient.registerType(serializer);
    }

    public static CustomIngredientSerializer<?> getSerializer(ResourceLocation identifier) {
        java.util.Objects.requireNonNull(identifier, "Identifier may not be null.");
        return REGISTERED_SERIALIZERS.get(identifier);
    }
}
