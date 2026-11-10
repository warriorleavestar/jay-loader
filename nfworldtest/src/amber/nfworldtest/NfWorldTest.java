package amber.nfworldtest;

import java.net.InetAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge counterpart of the Fabric worldtest mod. -Damber.autoworld=<name>: on the first title screen, open the world
 * (create it if missing; no server jar / EULA). Exercises @Mod construction, DeferredRegister, ModConfigSpec, the mod bus
 * (FMLCommonSetupEvent) and the game bus (ScreenEvent, ClientPlayerNetworkEvent, ClientTickEvent).
 */
@Mod("amber_nfworldtest")
public class NfWorldTest {
    static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("amber_nfworldtest");
    static final DeferredItem<Item> PROBE = ITEMS.registerSimpleItem("probe");
    static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();
    static final ModConfigSpec.IntValue REPORT_EVERY = B.comment("seconds between in-world reports").defineInRange("reportEvery", 15, 1, 600);
    static final ModConfigSpec SPEC = B.build();

    public NfWorldTest(IEventBus modBus, ModContainer container) {
        RegProbe.init(modBus, ITEMS);
        if (Boolean.getBoolean("amber.transferprobe")) TransferProbe.init(modBus);
        if (Boolean.getBoolean("amber.lootprobe")) NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.LootTableLoadEvent.class, e -> {
            // NeoForge half of worldtest's loot probe: gold pool on blocks/dirt next to Fabric's diamond pool; what each table looks like here
            String n = e.getName().toString();
            if (n.equals("minecraft:blocks/dirt")) {
                System.setProperty("amber.lootprobe.neoDirt", "seen");
                e.getTable().addPool(net.minecraft.world.level.storage.loot.LootPool.lootPool().name("amber_gold")
                        .add(net.minecraft.world.level.storage.loot.entries.LootItem.lootTableItem(net.minecraft.world.item.Items.GOLD_INGOT)).build());
            }
            if (n.equals("minecraft:blocks/gravel")) System.setProperty("amber.lootprobe.neoGravel", "seen");
        });
        ITEMS.register(modBus);
        container.registerConfig(ModConfig.Type.COMMON, SPEC);
        modBus.addListener(FMLCommonSetupEvent.class, e -> System.out.println("[amber-nfworldtest] FMLCommonSetupEvent; config reportEvery=" + REPORT_EVERY.get()));
        System.out.println("[amber-nfworldtest] constructed by FML, container=" + container.getModId());
        String name = System.getProperty("amber.autoworld"), join = System.getProperty("amber.autojoin"), serve = System.getProperty("amber.servetcp");
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> {
            RegProbe.report("server");
            if (serve == null) return;
            try {   // a real TCP listener on loopback only (no LAN broadcast), so a second client joins over a non-memory connection
                e.getServer().setUsesAuthentication(false);   // offline accounts: no Mojang session check
                e.getServer().getConnection().startTcpServerListener(InetAddress.getLoopbackAddress(), Integer.parseInt(serve));
                System.out.println("[amber-nfworldtest] TCP listening 127.0.0.1:" + serve);
            } catch (Exception x) { System.out.println("[amber-nfworldtest] TCP listen failed: " + x); }
        });
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) NfClient.init(name, join, serve);   // client-only classes stay out of the dedicated server
    }
}
