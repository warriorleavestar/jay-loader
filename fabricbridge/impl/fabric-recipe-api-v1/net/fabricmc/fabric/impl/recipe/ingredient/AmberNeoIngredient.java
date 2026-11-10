/*
 * Derived from Fabric API (https://github.com/FabricMC/fabric), Copyright (c) FabricMC and contributors.
 * Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt).
 * Modified by the Jay Loader authors: re-based for the NeoForge 1.21.1 / Forge 1.20.1 platforms (see the class comment).
 */
package net.fabricmc.fabric.impl.recipe.ingredient;

import com.mojang.serialization.MapCodec;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredient;
import net.fabricmc.fabric.api.recipe.v1.ingredient.CustomIngredientSerializer;
import net.minecraft.core.Registry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.crafting.ICustomIngredient;
import net.neoforged.neoforge.common.crafting.IngredientType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Amber (NeoForge platform): a Fabric CustomIngredient seen as a NeoForge ICustomIngredient. Each Fabric
 * CustomIngredientSerializer gets a NeoForge IngredientType under the same id (its MapCodec and packet codec, wrapped),
 * so recipes, NeoForge's Ingredient codecs, network sync and NeoForge's isSimple() fast path all work. Fabric's own
 * JSON key "fabric:type" is read by the bridge's CraftingHelper codec wrapper; written JSON uses NeoForge's "type".
 */
public record AmberNeoIngredient(CustomIngredient custom) implements ICustomIngredient {
    static final Map<ResourceLocation, IngredientType<AmberNeoIngredient>> TYPES = new ConcurrentHashMap<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void registerType(CustomIngredientSerializer<?> s) {
        CustomIngredientSerializer<CustomIngredient> cs = (CustomIngredientSerializer) s;
        MapCodec<AmberNeoIngredient> codec = cs.getCodec(true).xmap(AmberNeoIngredient::new, a -> a.custom);
        StreamCodec<RegistryFriendlyByteBuf, AmberNeoIngredient> stream = cs.getPacketCodec().map(AmberNeoIngredient::new, a -> a.custom);
        IngredientType<AmberNeoIngredient> type = new IngredientType<>(codec, stream);
        TYPES.put(s.getIdentifier(), type);
        try {
            Registry.register(NeoForgeRegistries.INGREDIENT_TYPES, s.getIdentifier(), type);
        } catch (RuntimeException e) {   // frozen (registered after the registration window): the ingredient still works locally, but can't be synced or parsed by NeoForge
            System.out.println("[amber-ul] 问题 fabric_api_recipe (error) CustomIngredientSerializer " + s.getIdentifier() + " 注册太晚, NeoForge IngredientType 未注册: " + e);
        }
    }

    @Override public boolean test(ItemStack stack) { return custom.test(stack); }
    @Override public Stream<ItemStack> getItems() { return custom.getMatchingStacks().stream(); }
    @Override public boolean isSimple() { return !custom.requiresTesting(); }
    @Override public IngredientType<?> getType() {
        IngredientType<AmberNeoIngredient> t = TYPES.get(custom.getSerializer().getIdentifier());
        if (t == null) throw new IllegalStateException("Fabric custom ingredient serializer not registered: " + custom.getSerializer().getIdentifier());
        return t;
    }
}
