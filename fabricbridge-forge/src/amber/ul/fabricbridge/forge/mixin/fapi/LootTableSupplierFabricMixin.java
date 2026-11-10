package amber.ul.fabricbridge.forge.mixin.fapi;

import com.google.common.collect.ImmutableList;
import java.util.List;
import net.fabricmc.fabric.api.loot.v1.FabricLootSupplier;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * fabric-loot-tables-v1 on Forge: LootTable as FabricLootSupplier. The official LootTableMixin (vetoed) shadows
 * pools as LootPool[]; Forge 47 keeps them in a List (pools:List, for addPool/removePool). Same answers: immutable copies.
 */
@Mixin(value = LootTable.class, remap = false)
public abstract class LootTableSupplierFabricMixin implements FabricLootSupplier {
    @Shadow(remap = false) @Final private List<LootPool> pools;
    @Shadow(remap = false) @Final LootItemFunction[] functions;

    @Override public List<LootPool> getPools() { return ImmutableList.copyOf(pools); }
    @Override public List<LootItemFunction> getFunctions() { return ImmutableList.copyOf(functions); }
}
