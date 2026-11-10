package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

/**
 * fabric-content-registries-v0 FuelRegistry on Forge: Forge 47 asks ForgeHooks.getBurnTime, which reads VANILLA_BURNS,
 * filled from AbstractFurnaceBlockEntity.getFuel() on every tag reload; Fabric's getFuel RETURN hook (kept) adds its
 * entries there. Fabric's two redirects of vanilla's getFuel calls in isFuel / getBurnDuration (gone on Forge) are stripped.
 */
final class FuelProbe {
    private FuelProbe() {}
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] fuel: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        FuelRegistry.INSTANCE.add(Items.ROTTEN_FLESH, 1234);
        FuelRegistry.INSTANCE.remove(Items.STICK);
    }

    static void run(MinecraftServer server) {
        server.execute(() -> {
            try {
                check("Fabric fuel is a furnace fuel", true, AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.ROTTEN_FLESH)));
                check("Fabric fuel burn time (Forge)", 1234, net.minecraftforge.common.ForgeHooks.getBurnTime(new ItemStack(Items.ROTTEN_FLESH), null));
                check("FuelRegistry.get", 1234, FuelRegistry.INSTANCE.get(Items.ROTTEN_FLESH));
                check("Fabric-removed fuel no longer burns", false, AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.STICK)));
                check("vanilla coal still burns", 1600, net.minecraftforge.common.ForgeHooks.getBurnTime(new ItemStack(Items.COAL), null));
            } catch (Throwable e) { System.out.println("[amber-ffprobe] fuel: crashed " + e); e.printStackTrace(System.out); }
            System.out.println("[amber-ffprobe] fuel total: " + ok + "/" + total + " OK");
        });
    }
}
