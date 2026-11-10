package amber.forgeworldtest;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** M4 world test (Forge 1.20.1): -Damber.forgeworldtest=<world name> creates that world from the title screen. */
@Mod("amber_forgeworldtest")
public final class ForgeWorldTest {
    public ForgeWorldTest() {
        System.out.println("[amber-fwt] constructed, dist=" + FMLEnvironment.dist);
        FwtLoot.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        if (FMLEnvironment.dist == Dist.CLIENT) FwtClient.init(System.getProperty("amber.forgeworldtest"));
    }
}
