package amber.forgecompat;

import amber.forgecompat.amber_internal.Caps;
import amber.forgecompat.amber_internal.Net;
import amber.forgecompat.common.capabilities.RegisterCapabilitiesEvent;
import amber.forgecompat.event.TickEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Forge 1.21.1 API on NeoForge 21.1: classes under amber.forgecompat mirror net.minecraftforge (Amber's ForgeCompat bake
 * renames references to them). Added by the loader on the NeoForge platform when Forge 52 mods are present.
 */
@Mod("amber_forgecompat")
public final class ForgeCompatMod {
    public ForgeCompatMod(IEventBus modBus) {
        TickEvent.install(NeoForge.EVENT_BUS);
        Caps.register(modBus);
        Net.register(modBus);
        // Forge posted RegisterCapabilitiesEvent on every mod bus; registration only marks the capability as registered here
        modBus.addListener((FMLCommonSetupEvent e) -> ModList.get().forEachModContainer((id, c) -> {
            IEventBus bus = c.getEventBus();
            if (bus != null) bus.post(new RegisterCapabilitiesEvent());
        }));
        System.out.println("[amber-forgecompat] Forge 1.21.1 API layer ready (events, registries, ticks, capabilities, SimpleChannel)");
    }
}
