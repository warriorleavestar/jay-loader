package amber.ul.fabricbridge.forge.mixin.fapi;

import java.util.List;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * fabric-loot-api-v2 on Forge: LootTableAccessor.fabric_getPools()[LootPool; has @Accessor("pools"), but Forge's pools is a
 * List. The Forge bridge manifest turns it into @Invoker("amber$fabricPools"); this adds that method over the List.
 * Priority 900 so it is merged before Fabric's accessor mixin (priority 1000) looks for it.
 */
@Mixin(value = LootTable.class, remap = false, priority = 900)
public abstract class LootTablePoolsFabricMixin {
    @Shadow(remap = false) @Final private List<LootPool> pools;

    public LootPool[] amber$fabricPools() { return pools.toArray(new LootPool[0]); }
}
