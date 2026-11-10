package com.example.forgedemo;
import com.example.fabricdemo.api.RubyApi;
import net.minecraft.core.Registry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
@Mod("forgedemo")
public class ForgeDemo {
    public ForgeDemo(IEventBus modBus) {
        Registry.register("forgedemo:sapphire", "蓝宝石 (NeoForge 模组, " + RubyApi.describe() + ")");
        modBus.addListener(FMLCommonSetupEvent.class, e -> System.out.println("[forge-demo] FMLCommonSetupEvent; 经 AccessTransformer 读私有字段 frozen=" + Registry.frozen
                + "; ModList 能看到 Fabric 模组 fabricdemo? " + ModList.get().isLoaded("fabricdemo")));
    }
}
