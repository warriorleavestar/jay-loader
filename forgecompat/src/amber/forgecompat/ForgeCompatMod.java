package amber.forgecompat;

import amber.forgecompat.event.TickEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Forge 1.21.1 API on NeoForge 21.1: classes under amber.forgecompat mirror net.minecraftforge (Amber's ForgeCompat bake
 * renames references to them). Added by the loader on the NeoForge platform when Forge 52 mods are present.
 */
@Mod("amber_forgecompat")
public final class ForgeCompatMod {
    public ForgeCompatMod() {
        TickEvent.install(NeoForge.EVENT_BUS);
        System.out.println("[amber-forgecompat] Forge 1.21.1 API layer ready");
    }
}
