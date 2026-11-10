package com.example.fabricdemo;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_2378;
public class FabricDemo implements ModInitializer {
    @Override public void onInitialize() {
        class_2378.method_10230("fabricdemo:ruby", "红宝石 (Fabric 模组)");
        System.out.println("[fabric-demo] onInitialize; FabricLoader 能看到 NeoForge 模组 forgedemo? " + FabricLoader.getInstance().isModLoaded("forgedemo"));
        System.out.println("[fabric-demo] 经 AccessWidener 读私有字段 ITEMS, 当前条目数 " + class_2378.field_11144.size());
    }
}
