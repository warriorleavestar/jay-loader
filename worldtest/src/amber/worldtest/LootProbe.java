package amber.worldtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.fabricmc.fabric.api.loot.v3.LootTableSource;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-loot-api-v3 probe (-Damber.lootprobe=true). MODIFY adds a diamond pool to blocks/dirt, REPLACE swaps
 * blocks/gravel for an emerald table, ALL_LOADED counts tables; the source Fabric reports for each is recorded.
 * In the world the server's own loot tables are rolled (block drops from a real break, and getRandomItems).
 * On NeoForge, nfworldtest's LootTableLoadEvent adds a gold ingot pool to blocks/dirt too: both must land.
 */
final class LootProbe {
    private LootProbe() {}
    static final Map<String, String> sources = new ConcurrentHashMap<>();
    static volatile int loadedCount = -1;
    static final ResourceKey<LootTable> DIRT = Blocks.DIRT.getLootTable(), GRAVEL = Blocks.GRAVEL.getLootTable();

    static void register() {
        LootTableEvents.MODIFY.register((key, builder, source, regs) -> {
            if (key.equals(DIRT) || key.equals(GRAVEL)) sources.put(key.location().getPath(), source.name());
            if (key.equals(DIRT)) builder.withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.DIAMOND)));
        });
        LootTableEvents.REPLACE.register((key, original, source, regs) -> {
            if (!key.equals(GRAVEL)) return null;
            sources.put("gravel(replace)", source.name());
            return LootTable.lootTable().withPool(LootPool.lootPool().add(LootItem.lootTableItem(Items.EMERALD))).build();
        });
        LootTableEvents.ALL_LOADED.register((rm, registry) -> loadedCount = registry.size());
        System.out.println("[amber-worldtest] loot probe: registered");
    }

    static String roll(ServerLevel level, ResourceKey<LootTable> key, BlockPos pos) {
        LootTable t = level.getServer().reloadableRegistries().getLootTable(key);
        LootParams p = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY).withParameter(LootContextParams.BLOCK_STATE, level.getBlockState(pos)).create(LootContextParamSets.BLOCK);
        Map<String, Integer> got = new TreeMap<>();
        for (ItemStack s : t.getRandomItems(p)) got.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
        return got.toString();
    }

    static void run(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var player = server.getPlayerList().getPlayers().get(0);
            BlockPos pos = new BlockPos(player.getBlockX() - 4, 300, player.getBlockZ() - 8);
            List<String> out = new ArrayList<>();
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            out.add("roll blocks/dirt=" + roll(level, DIRT, pos));
            level.setBlockAndUpdate(pos, Blocks.GRAVEL.defaultBlockState());
            out.add("roll blocks/gravel=" + roll(level, GRAVEL, pos));
            level.setBlockAndUpdate(pos, Blocks.DIRT.defaultBlockState());
            List<String> drops = new ArrayList<>();
            for (ItemStack s : net.minecraft.world.level.block.Block.getDrops(level.getBlockState(pos), level, pos, null)) drops.add(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath());
            out.add("dirt Block.getDrops=" + drops);
            out.add("sources=" + new TreeMap<>(sources));
            out.add("ALL_LOADED tables=" + loadedCount + " (server registry " + server.reloadableRegistries().get().registryOrThrow(Registries.LOOT_TABLE).size() + ")");
            out.add("neo LootTableLoadEvent saw dirt=" + System.getProperty("amber.lootprobe.neoDirt", "-") + " gravel=" + System.getProperty("amber.lootprobe.neoGravel", "-"));
            for (String s : out) System.out.println("[amber-worldtest] loot probe: " + s);
            System.out.println("[amber-worldtest] loot probe total: done");
        });
    }
}
