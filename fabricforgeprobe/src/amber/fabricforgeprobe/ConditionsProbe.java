package amber.fabricforgeprobe;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootDataType;
import net.minecraft.world.level.storage.loot.LootTable;

/**
 * fabric-resource-conditions-api-v1 on Forge: "fabric:load_conditions" on loot tables. The probe ships
 * amber_ffprobe:cond_off (needs a mod that isn't there) and amber_ffprobe:cond_on (needs minecraft); only the second may
 * load (bridge: LootConditionsFabricMixin at HEAD of Forge's per-file lambda).
 */
final class ConditionsProbe {
    private ConditionsProbe() {}
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] conditions: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                var data = server.getLootData();
                check("table whose conditions fail is not loaded", false, data.getElement(LootDataType.TABLE, new ResourceLocation("amber_ffprobe", "cond_off")) != null);
                check("table whose conditions hold is loaded", true, data.getElement(LootDataType.TABLE, new ResourceLocation("amber_ffprobe", "cond_on")) != null);
                check("vanilla table still loaded", true, data.getLootTable(new ResourceLocation("minecraft", "chests/simple_dungeon")) != LootTable.EMPTY);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] conditions: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] conditions total: " + ok + "/" + total + " OK");
        });
    }
}
