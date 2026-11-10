package amber.forgeworldtest;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge mod content in the two Forge registries Vanilla's late validate reports empty when Fabric API is present
 * (forge:global_loot_modifier_serializers, forge:entity_data_serializers): a global loot modifier that adds an emerald
 * to blocks/dirt (data/forge/loot_modifiers) and a custom entity data serializer (always registered; the check runs with -Damber.forgeworldtest.loot=true).
 */
final class FwtLoot {
    private FwtLoot() {}
    static final DeferredRegister<Codec<? extends IGlobalLootModifier>> GLM = DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, "amber_forgeworldtest");
    static final DeferredRegister<EntityDataSerializer<?>> EDS = DeferredRegister.create(ForgeRegistries.Keys.ENTITY_DATA_SERIALIZERS, "amber_forgeworldtest");
    static final RegistryObject<Codec<DirtEmerald>> DIRT_EMERALD = GLM.register("dirt_emerald", () -> DirtEmerald.CODEC.get());
    static final RegistryObject<EntityDataSerializer<Integer>> PROBE_SERIALIZER = EDS.register("probe", () -> EntityDataSerializer.simple(FriendlyByteBuf::writeVarInt, FriendlyByteBuf::readVarInt));

    static void register(IEventBus modBus) { GLM.register(modBus); EDS.register(modBus); }

    static final class DirtEmerald extends LootModifier {
        static final Supplier<Codec<DirtEmerald>> CODEC = Suppliers.memoize(() -> RecordCodecBuilder.create(i -> codecStart(i).apply(i, DirtEmerald::new)));
        static int applied;
        DirtEmerald(LootItemCondition[] conditions) { super(conditions); }
        @Override protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext ctx) { applied++; loot.add(new ItemStack(Items.EMERALD)); return loot; }
        @Override public Codec<? extends IGlobalLootModifier> codec() { return CODEC.get(); }
    }

    static void check(MinecraftServer server, BlockPos pos) {
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                ResourceLocation glmId = new ResourceLocation("amber_forgeworldtest", "dirt_emerald");
                System.out.println("[amber-fwt] loot: GLM serializer registered=" + ForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS.get().containsKey(glmId)
                        + " entity data serializer registered=" + ForgeRegistries.ENTITY_DATA_SERIALIZERS.get().containsKey(new ResourceLocation("amber_forgeworldtest", "probe"))
                        + " serializer id=" + EntityDataSerializers.getSerializedId(PROBE_SERIALIZER.get()));
                LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                        .withParameter(LootContextParams.TOOL, ItemStack.EMPTY).withParameter(LootContextParams.BLOCK_STATE, Blocks.DIRT.defaultBlockState())
                        .create(LootContextParamSets.BLOCK);
                var drops = server.getLootData().getLootTable(Blocks.DIRT.getLootTable()).getRandomItems(p);
                StringBuilder sb = new StringBuilder();
                for (ItemStack s : drops) sb.append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem())).append(' ');
                boolean emerald = drops.stream().anyMatch(s -> s.is(Items.EMERALD));
                System.out.println("[amber-fwt] loot: dirt drops [" + sb.toString().trim() + "] Forge GLM applied=" + DirtEmerald.applied + " emerald=" + emerald
                        + " -> " + (emerald && DirtEmerald.applied > 0 ? "OK" : "FAIL"));
            } catch (Throwable t) { System.out.println("[amber-fwt] loot: crashed " + t); t.printStackTrace(System.out); }
        });
    }
}
