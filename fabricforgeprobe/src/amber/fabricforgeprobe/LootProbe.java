package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.loot.v1.FabricLootSupplier;
import net.fabricmc.fabric.api.loot.v2.FabricLootTableBuilder;
import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;

/**
 * fabric-loot-api-v2 / fabric-loot-tables-v1 on Forge (bridge: LootTableSupplierFabricMixin, LootTablePoolsFabricMixin).
 * MODIFY adds a pool to blocks/dirt; REPLACE swaps blocks/gravel for FabricLootTableBuilder.copyOf(original) plus a pool
 * (copyOf reads LootTableAccessor.fabric_getPools). Checked through FabricLootSupplier (v1) on the loaded tables.
 */
final class LootProbe {
    private LootProbe() {}
    static final ResourceLocation DIRT = new ResourceLocation("minecraft", "blocks/dirt"), GRAVEL = new ResourceLocation("minecraft", "blocks/gravel");
    static int modified, replaced, copiedPools = -1, ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] loot: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static LootPool.Builder pool(net.minecraft.world.item.Item item) { return LootPool.lootPool().add(LootItem.lootTableItem(item)); }

    static void register() {
        LootTableEvents.MODIFY.register((rm, data, id, builder, source) -> { if (id.equals(DIRT)) { modified++; builder.withPool(pool(Items.GOLD_INGOT)); } });
        LootTableEvents.REPLACE.register((rm, data, id, original, source) -> {
            if (!id.equals(GRAVEL)) return null;
            replaced++;
            LootTable.Builder b = FabricLootTableBuilder.copyOf(original);
            copiedPools = ((FabricLootSupplier) b.build()).getPools().size();
            return b.withPool(pool(Items.DIAMOND)).build();
        });
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                LootTable dirt = server.getLootData().getLootTable(DIRT), gravel = server.getLootData().getLootTable(GRAVEL);
                check("MODIFY called for blocks/dirt", true, modified > 0);
                check("REPLACE called for blocks/gravel", true, replaced > 0);
                check("copyOf kept the original gravel pool (fabric_getPools)", 1, copiedPools);
                check("dirt pools (vanilla + Fabric MODIFY)", 2, ((FabricLootSupplier) dirt).getPools().size());
                check("gravel pools (copy + Fabric REPLACE)", 2, ((FabricLootSupplier) gravel).getPools().size());
                check("FabricLootSupplier functions list", true, ((FabricLootSupplier) dirt).getFunctions() != null);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] loot: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] loot total: " + ok + "/" + total + " OK");
        });
    }
}
