package amber.ul.fabricbridge.forge.mixin.fapi;

import net.minecraft.world.level.storage.loot.LootDataId;
import net.minecraft.world.level.storage.loot.LootDataManager;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * fabric-loot-api-v2 on Forge: Forge keeps each loot table's id on the table (set when it is parsed, used by
 * ForgeHooks.modifyLoot for global loot modifiers and the forge:loot_table_id condition) and freezes it. Fabric's
 * LootManagerMixin rebuilds every table after reload (FabricLootTableBuilder.copyOf(table) ... build()) for its
 * REPLACE/MODIFY events; the copies have no id and are not frozen, so every Forge mod's global loot modifier with a
 * loot_table_id condition stopped applying. Every table lookup goes through getElement: the id and the freeze are
 * restored there, once per table.
 */
@Mixin(value = LootDataManager.class, remap = false)
public abstract class LootDataManagerTableIdFabricMixin {
    @Inject(method = "getElement", at = @At("RETURN"), remap = false)
    private <T> void amber$forgeTableId(LootDataId<T> id, CallbackInfoReturnable<T> cir) {
        if (id.type() != LootDataType.TABLE || !(cir.getReturnValue() instanceof LootTable t) || t == LootTable.EMPTY) return;
        if (t.getLootTableId() == null) synchronized (t) {
            if (t.getLootTableId() == null) { t.setLootTableId(id.location()); if (!t.isFrozen()) t.freeze(); }
        }
    }
}
