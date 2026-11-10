package amber.worldtest;

import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.object.builder.v1.block.type.WoodTypeBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;

/**
 * fabric-object-builder-api-v1 probe (-Damber.objectprobe=true): a type-aware emerald trade that covers only some
 * villager types builds (vanilla's constructor rejects it, Fabric disables that check, NeoForge dropped it) and offers
 * nothing to a villager of an uncovered type; a hanging-sign edit screen for a namespaced modded WoodType uses that
 * namespace's texture (Fabric's HangingSignEditScreenMixin, NeoForge's own parse).
 */
final class ObjectProbe {
    private ObjectProbe() {}
    static WoodType WOOD; static Block HANGING; static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] object probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        WOOD = new WoodTypeBuilder().register(ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe_wood"), BlockSetType.OAK);
        HANGING = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe_hanging_sign"), new CeilingHangingSignBlock(WOOD, BlockBehaviour.Properties.ofFullCopy(Blocks.OAK_HANGING_SIGN)));
    }

    static void server(Minecraft client) {
        MinecraftServer server = client.getSingleplayerServer();
        server.execute(() -> {
            try {
                ServerLevel level = server.overworld();
                VillagerTrades.ItemListing listing = null; Throwable built = null;
                // private in vanilla (Fabric widens it); the remapped jar finds it as the nested class with an (int, int, int, Map) constructor
                java.lang.reflect.Constructor<?> ctor = null;
                for (Class<?> c : VillagerTrades.class.getDeclaredClasses())
                    try { ctor = c.getDeclaredConstructor(int.class, int.class, int.class, Map.class); break; } catch (NoSuchMethodException ignored) {}
                check("type-aware trade class found", true, ctor != null);
                try { ctor.setAccessible(true); listing = (VillagerTrades.ItemListing) ctor.newInstance(1, 12, 5, Map.of(VillagerType.PLAINS, Items.OAK_BOAT)); }
                catch (java.lang.reflect.InvocationTargetException e) { built = e.getCause(); }
                check("partial type-aware trade builds", null, built);
                if (listing != null) {
                    Villager plains = new Villager(EntityType.VILLAGER, level, VillagerType.PLAINS);
                    MerchantOffer a = listing.getOffer(plains, level.random);
                    check("covered type buys the boat", "minecraft:oak_boat", a == null ? null : BuiltInRegistries.ITEM.getKey(a.getCostA().getItem()));
                }
            } catch (Throwable e) { System.out.println("[amber-worldtest] object probe: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    static void client(Minecraft client) {
        try {
            check("modded wood type name", "amber-worldtest:probe_wood", WOOD.name());
            // the block entity type only accepts vanilla hanging signs; build it with one, then put our state in (by type)
            HangingSignBlockEntity be = new HangingSignBlockEntity(BlockPos.ZERO, Blocks.OAK_HANGING_SIGN.defaultBlockState());
            for (java.lang.reflect.Field f : net.minecraft.world.level.block.entity.BlockEntity.class.getDeclaredFields())
                if (f.getType() == net.minecraft.world.level.block.state.BlockState.class) { f.setAccessible(true); f.set(be, HANGING.defaultBlockState()); }
            HangingSignEditScreen screen = new HangingSignEditScreen(be, true, false);
            ResourceLocation tex = null;
            for (java.lang.reflect.Field f : HangingSignEditScreen.class.getDeclaredFields())   // remapped jar: by type
                if (f.getType() == ResourceLocation.class) { f.setAccessible(true); tex = (ResourceLocation) f.get(screen); }
            check("hanging sign screen texture", "amber-worldtest:textures/gui/hanging_signs/probe_wood.png", tex);
            HangingSignEditScreen vanilla = new HangingSignEditScreen(new HangingSignBlockEntity(BlockPos.ZERO, Blocks.OAK_HANGING_SIGN.defaultBlockState()), true, false);
            ResourceLocation vtex = null;
            for (java.lang.reflect.Field f : HangingSignEditScreen.class.getDeclaredFields())
                if (f.getType() == ResourceLocation.class) { f.setAccessible(true); vtex = (ResourceLocation) f.get(vanilla); }
            check("vanilla hanging sign screen texture", "minecraft:textures/gui/hanging_signs/oak.png", vtex);
        } catch (Throwable e) { System.out.println("[amber-worldtest] object probe: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-worldtest] object probe total: " + ok + "/" + total + " OK");
    }
}
