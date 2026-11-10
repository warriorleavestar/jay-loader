package amber.forgecompat.registries;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.StructureModifier;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Forge's ForgeRegistries over the vanilla registries (Forge 1.21.1 names). */
public final class ForgeRegistries {
    private ForgeRegistries() {}
    public static final IForgeRegistry<Block> BLOCKS = IForgeRegistry.of(BuiltInRegistries.BLOCK);
    public static final IForgeRegistry<Item> ITEMS = IForgeRegistry.of(BuiltInRegistries.ITEM);
    public static final IForgeRegistry<EntityType<?>> ENTITY_TYPES = IForgeRegistry.of(BuiltInRegistries.ENTITY_TYPE);
    public static final IForgeRegistry<net.minecraft.sounds.SoundEvent> SOUND_EVENTS = IForgeRegistry.of(BuiltInRegistries.SOUND_EVENT);
    public static final IForgeRegistry<net.minecraft.world.level.material.Fluid> FLUIDS = IForgeRegistry.of(BuiltInRegistries.FLUID);
    public static final IForgeRegistry<net.minecraft.world.effect.MobEffect> MOB_EFFECTS = IForgeRegistry.of(BuiltInRegistries.MOB_EFFECT);
    public static final IForgeRegistry<net.minecraft.world.item.alchemy.Potion> POTIONS = IForgeRegistry.of(BuiltInRegistries.POTION);
    public static final IForgeRegistry<net.minecraft.world.level.block.entity.BlockEntityType<?>> BLOCK_ENTITY_TYPES = IForgeRegistry.of(BuiltInRegistries.BLOCK_ENTITY_TYPE);
    public static final IForgeRegistry<net.minecraft.core.particles.ParticleType<?>> PARTICLE_TYPES = IForgeRegistry.of(BuiltInRegistries.PARTICLE_TYPE);
    public static final IForgeRegistry<net.minecraft.world.inventory.MenuType<?>> MENU_TYPES = IForgeRegistry.of(BuiltInRegistries.MENU);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeType<?>> RECIPE_TYPES = IForgeRegistry.of(BuiltInRegistries.RECIPE_TYPE);
    public static final IForgeRegistry<net.minecraft.world.item.crafting.RecipeSerializer<?>> RECIPE_SERIALIZERS = IForgeRegistry.of(BuiltInRegistries.RECIPE_SERIALIZER);
    public static final IForgeRegistry<net.minecraft.world.entity.ai.attributes.Attribute> ATTRIBUTES = IForgeRegistry.of(BuiltInRegistries.ATTRIBUTE);
    public static final IForgeRegistry<net.minecraft.world.level.levelgen.feature.Feature<?>> FEATURES = IForgeRegistry.of(BuiltInRegistries.FEATURE);
    public static final IForgeRegistry<net.minecraft.world.entity.ai.village.poi.PoiType> POI_TYPES = IForgeRegistry.of(BuiltInRegistries.POINT_OF_INTEREST_TYPE);
    public static final IForgeRegistry<net.minecraft.world.entity.npc.VillagerProfession> VILLAGER_PROFESSIONS = IForgeRegistry.of(BuiltInRegistries.VILLAGER_PROFESSION);
    public static final IForgeRegistry<net.minecraft.world.item.CreativeModeTab> CREATIVE_MODE_TABS = IForgeRegistry.of(BuiltInRegistries.CREATIVE_MODE_TAB);

    /** Forge's registry keys; the Forge-only ones are NeoForge's. */
    public static final class Keys {
        private Keys() {}
        public static final ResourceKey<Registry<Block>> BLOCKS = Registries.BLOCK;
        public static final ResourceKey<Registry<Item>> ITEMS = Registries.ITEM;
        public static final ResourceKey<Registry<EntityType<?>>> ENTITY_TYPES = Registries.ENTITY_TYPE;
        public static final ResourceKey<Registry<net.minecraft.sounds.SoundEvent>> SOUND_EVENTS = Registries.SOUND_EVENT;
        public static final ResourceKey<Registry<net.minecraft.world.level.block.entity.BlockEntityType<?>>> BLOCK_ENTITY_TYPES = Registries.BLOCK_ENTITY_TYPE;
        public static final ResourceKey<Registry<net.minecraft.world.level.biome.Biome>> BIOMES = Registries.BIOME;
        public static final ResourceKey<Registry<com.mojang.serialization.MapCodec<? extends BiomeModifier>>> BIOME_MODIFIER_SERIALIZERS = NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS;
        public static final ResourceKey<Registry<com.mojang.serialization.MapCodec<? extends StructureModifier>>> STRUCTURE_MODIFIER_SERIALIZERS = NeoForgeRegistries.Keys.STRUCTURE_MODIFIER_SERIALIZERS;
        public static final ResourceKey<Registry<BiomeModifier>> BIOME_MODIFIERS = NeoForgeRegistries.Keys.BIOME_MODIFIERS;
    }
}
