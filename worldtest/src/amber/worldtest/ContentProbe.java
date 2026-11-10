package amber.worldtest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.registry.CompostingChanceRegistry;
import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistryBuilder;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.registry.FlattenableBlockRegistry;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.api.registry.LandPathNodeTypesRegistry;
import net.fabricmc.fabric.api.registry.OxidizableBlocksRegistry;
import net.fabricmc.fabric.api.registry.SculkSensorFrequencyRegistry;
import net.fabricmc.fabric.api.registry.StrippableBlockRegistry;
import net.fabricmc.fabric.api.registry.TillableBlockRegistry;
import net.fabricmc.fabric.api.registry.VillagerInteractionRegistries;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * fabric-content-registries-v0 probe (-Damber.contentprobe=true). Registers one entry in every content registry at
 * client init, then, in the world, asks the game the way the game itself asks (furnace fuel check, composter insert,
 * axe/hoe/shovel/honeycomb use, WeatheringCopper.getNext, VibrationSystem frequency, potion brewing, villager pickup,
 * path types). On NeoForge, where the answer comes from a data map or an extension method, that NeoForge path is the
 * one asked (by reflection: this mod is compiled against the vanilla game). Prints one line per check and a total.
 */
final class ContentProbe {
    private ContentProbe() {}
    static final boolean NEO = cls("net.neoforged.neoforge.common.NeoForge") != null;

    static void register() {
        FuelRegistry.INSTANCE.add(Items.APPLE, 333);              // new fuel
        FuelRegistry.INSTANCE.remove(Items.STICK);                 // vanilla fuel (100) removed
        FuelRegistry.INSTANCE.add(ItemTags.SMALL_FLOWERS, 77);    // tag fuel
        CompostingChanceRegistry.INSTANCE.add(Items.ROTTEN_FLESH, 1.0f);
        CompostingChanceRegistry.INSTANCE.remove(Items.WHEAT_SEEDS);   // vanilla 0.3 removed
        FlammableBlockRegistry.getDefaultInstance().add(Blocks.SMOOTH_STONE, 5, 20);
        StrippableBlockRegistry.register(Blocks.BONE_BLOCK, Blocks.QUARTZ_PILLAR);
        TillableBlockRegistry.register(Blocks.CLAY, HoeItem::onlyIfAirAbove, Blocks.FARMLAND.defaultBlockState());
        FlattenableBlockRegistry.register(Blocks.GRAVEL, Blocks.DIRT_PATH.defaultBlockState());
        OxidizableBlocksRegistry.registerOxidizableBlockPair(Blocks.SANDSTONE, Blocks.RED_SANDSTONE);
        OxidizableBlocksRegistry.registerWaxableBlockPair(Blocks.TERRACOTTA, Blocks.WHITE_TERRACOTTA);
        SculkSensorFrequencyRegistry.register(GameEvent.SHRIEK.unwrapKey().orElseThrow(), 9);
        VillagerInteractionRegistries.registerCollectable(Items.PUMPKIN_PIE);
        VillagerInteractionRegistries.registerFood(Items.COOKIE, 3);
        VillagerInteractionRegistries.registerCompostable(Items.ROTTEN_FLESH);
        VillagerInteractionRegistries.registerGiftLootTable(VillagerProfession.NITWIT, BuiltInLootTables.FISHING);
        LandPathNodeTypesRegistry.register(Blocks.POLISHED_GRANITE, PathType.DAMAGE_OTHER, PathType.DANGER_OTHER);
        FabricBrewingRecipeRegistryBuilder.BUILD.register(b -> ((FabricBrewingRecipeRegistryBuilder) (Object) b).registerPotionRecipe(Potions.AWKWARD, Ingredient.of(Items.FLINT), Potions.LUCK));
        System.out.println("[amber-worldtest] content probe: registered (platform " + (NEO ? "neoforge" : "fabric") + ")");
    }

    static Class<?> cls(String n) { try { return Class.forName(n); } catch (Throwable e) { return null; } }

    /** NeoForge: holder.getData(NeoForgeDataMaps.<field>), else "-". */
    static Object neoData(Holder<?> h, String field) {
        try {
            Object type = cls("net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps").getField(field).get(null);
            for (var m : Holder.class.getMethods()) if (m.getName().equals("getData") && m.getParameterCount() == 1) return m.invoke(h, type);
            return "no getData";
        } catch (Throwable e) { return "err " + e; }
    }
    static Object neoCall(Object target, String name, Object... args) {
        try {
            for (var m : target.getClass().getMethods()) if (m.getName().equals(name) && m.getParameterCount() == args.length) return m.invoke(target, args);
            return "no " + name;
        } catch (Throwable e) { return "err " + e.getCause(); }
    }

    static final List<String> lines = new ArrayList<>();
    static int ok, total;
    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] content probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    /** The composter's chance: NeoForge ComposterBlock.getValue (data map), vanilla COMPOSTABLES. -1 = not compostable. */
    static Object compost(net.minecraft.world.item.Item i) {
        if (!NEO) return ComposterBlock.COMPOSTABLES.getOrDefault(i, -1.0f);
        try { return ComposterBlock.class.getMethod("getValue", ItemStack.class).invoke(null, new ItemStack(i)); } catch (Throwable e) { return "err " + e; }
    }

    static String name(Block b) { return BuiltInRegistries.BLOCK.getKey(b).getPath(); }

    /** Uses the item on a fresh block at pos and returns what the block became. */
    static String use(ServerLevel level, ServerPlayer player, BlockPos pos, Block start, ItemStack tool) {
        level.setBlockAndUpdate(pos, start.defaultBlockState());
        level.setBlockAndUpdate(pos.above(), Blocks.AIR.defaultBlockState());
        ItemStack old = player.getItemInHand(InteractionHand.MAIN_HAND);
        player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        try {
            tool.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)));
        } finally { player.setItemInHand(InteractionHand.MAIN_HAND, old); }
        return name(level.getBlockState(pos).getBlock());
    }

    static void run(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            try { runOnServer(server.overworld(), server.getPlayerList().getPlayers().get(0), server); }
            catch (Throwable e) { System.out.println("[amber-worldtest] content probe total: crashed " + e); e.printStackTrace(System.out); }
        });
    }

    static void runOnServer(ServerLevel level, ServerPlayer player, net.minecraft.server.MinecraftServer server) {
        // fuel: isFuel is the furnace's own slot check (NeoForge: ItemStack.getBurnTime -> data map + FurnaceFuelBurnTimeEvent)
        check("fuel apple isFuel", true, AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.APPLE)));
        check("fuel stick removed", false, AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.STICK)));
        check("fuel dandelion (tag) isFuel", true, AbstractFurnaceBlockEntity.isFuel(new ItemStack(Items.DANDELION)));
        if (NEO) {
            check("fuel apple burn time (neo)", 333, neoCall(new ItemStack(Items.APPLE), "getBurnTime", (Object) null));
            check("fuel dandelion burn time (neo)", 77, neoCall(new ItemStack(Items.DANDELION), "getBurnTime", (Object) null));
            check("fuel coal untouched (neo)", 1600, neoCall(new ItemStack(Items.COAL), "getBurnTime", (Object) null));
        } else {
            check("fuel apple burn time", 333, FuelRegistry.INSTANCE.get(Items.APPLE));
            check("fuel coal untouched", 1600, FuelRegistry.INSTANCE.get(Items.COAL));
        }
        // composting: the composter's own insert path
        BlockPos base = new BlockPos(player.getBlockX() + 4, 300, player.getBlockZ() - 6);
        check("compost getValue rotten_flesh", 1.0f, compost(Items.ROTTEN_FLESH));
        check("compost getValue wheat_seeds removed", -1.0f, compost(Items.WHEAT_SEEDS));
        level.setBlockAndUpdate(base, Blocks.COMPOSTER.defaultBlockState());
        var after = ComposterBlock.insertItem(player, level.getBlockState(base), level, new ItemStack(Items.ROTTEN_FLESH), base);
        check("composter insert rotten_flesh -> level", 1, after.getValue(ComposterBlock.LEVEL));
        check("compost oak_leaves untouched", 0.3f, compost(Items.OAK_LEAVES));
        // flammability
        if (NEO) check("flammable smooth_stone getFlammability (neo, = Fabric spread)", 20, neoCall(Blocks.SMOOTH_STONE.defaultBlockState(), "getFlammability", level, base, Direction.UP));
        if (NEO) check("flammable smooth_stone getFireSpreadSpeed (neo, = Fabric burn)", 5, neoCall(Blocks.SMOOTH_STONE.defaultBlockState(), "getFireSpreadSpeed", level, base, Direction.UP));
        if (NEO) check("flammable oak_planks untouched (neo)", 20, neoCall(Blocks.OAK_PLANKS.defaultBlockState(), "getFlammability", level, base, Direction.UP));
        var fe = FlammableBlockRegistry.getDefaultInstance().get(Blocks.SMOOTH_STONE);
        check("flammable smooth_stone registry spread/burn", "20/5", fe == null ? null : fe.getSpreadChance() + "/" + fe.getBurnChance());
        // tools (the items' own useOn)
        BlockPos t = base.east(2);
        check("strip bone_block with axe", "quartz_pillar", use(level, player, t, Blocks.BONE_BLOCK, new ItemStack(Items.IRON_AXE)));
        check("strip oak_log untouched", "stripped_oak_log", use(level, player, t, Blocks.OAK_LOG, new ItemStack(Items.IRON_AXE)));
        check("till clay with hoe", "farmland", use(level, player, t, Blocks.CLAY, new ItemStack(Items.IRON_HOE)));
        check("flatten gravel with shovel", "dirt_path", use(level, player, t, Blocks.GRAVEL, new ItemStack(Items.IRON_SHOVEL)));
        check("wax terracotta with honeycomb", "white_terracotta", use(level, player, t, Blocks.TERRACOTTA, new ItemStack(Items.HONEYCOMB)));
        check("wax off white_terracotta with axe", "terracotta", use(level, player, t, Blocks.WHITE_TERRACOTTA, new ItemStack(Items.IRON_AXE)));
        check("scrape red_sandstone with axe", "sandstone", use(level, player, t, Blocks.RED_SANDSTONE, new ItemStack(Items.IRON_AXE)));
        check("oxidize next sandstone", "red_sandstone", WeatheringCopper.getNext(Blocks.SANDSTONE).map(ContentProbe::name).orElse("none"));
        check("oxidize next copper untouched", "exposed_copper", WeatheringCopper.getNext(Blocks.COPPER_BLOCK).map(ContentProbe::name).orElse("none"));
        // sculk
        check("vibration frequency shriek", 9, VibrationSystem.getGameEventFrequency(GameEvent.SHRIEK));
        check("vibration frequency step untouched", 1, VibrationSystem.getGameEventFrequency(GameEvent.STEP));
        // villagers
        Villager v = EntityType.VILLAGER.create(level);
        check("villager wants pumpkin_pie", true, v.wantsToPickUp(new ItemStack(Items.PUMPKIN_PIE)));
        check("villager food cookie", 3, Villager.FOOD_POINTS.get(Items.COOKIE));
        if (NEO) {
            Object c = neoData(Items.ROTTEN_FLESH.builtInRegistryHolder(), "COMPOSTABLES");
            check("villager compost rotten_flesh (neo data map canVillagerCompost)", true, c == null ? null : neoCall(c, "canVillagerCompost"));
            Object g = neoData(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(VillagerProfession.NITWIT), "RAID_HERO_GIFTS");
            check("hero gift nitwit (neo data map)", BuiltInLootTables.FISHING.location(), g == null ? null : ((net.minecraft.resources.ResourceKey<?>) neoCall(g, "lootTable")).location());
            Object g2 = neoData(BuiltInRegistries.VILLAGER_PROFESSION.wrapAsHolder(VillagerProfession.FARMER), "RAID_HERO_GIFTS");
            check("hero gift farmer untouched (neo data map)", BuiltInLootTables.FARMER_GIFT.location(), g2 == null ? null : ((net.minecraft.resources.ResourceKey<?>) neoCall(g2, "lootTable")).location());
        }
        // brewing (the server's PotionBrewing)
        ItemStack awkward = PotionContents.createItemStack(Items.POTION, Potions.AWKWARD);
        check("brewing awkward+flint hasMix", true, server.potionBrewing().hasMix(awkward, new ItemStack(Items.FLINT)));
        check("brewing awkward+flint -> luck", true, server.potionBrewing().mix(new ItemStack(Items.FLINT), awkward).get(net.minecraft.core.component.DataComponents.POTION_CONTENTS).is(Potions.LUCK));
        // path types
        BlockPos p = base.east(6);
        for (int dx = 0; dx < 8; dx++) for (int dy = 0; dy < 3; dy++) level.setBlockAndUpdate(p.offset(dx, dy, 0), (dy == 0 ? Blocks.STONE : Blocks.AIR).defaultBlockState());
        level.setBlockAndUpdate(p, Blocks.POLISHED_GRANITE.defaultBlockState());              // A: granite floor
        level.setBlockAndUpdate(p.east(4).above(), Blocks.POLISHED_GRANITE.defaultBlockState());  // B: granite wall beside p.east(3)
        v.setPos(Vec3.atCenterOf(p.above()));
        // Fabric 0.116's PathfindingContext hook asks its registry with neighbor=true for every lookup (also the block a mob
        // stands in or on), so DANGER_OTHER is what Fabric itself answers here; vanilla would say BLOCKED. Same answer on
        // both platforms = the hook is live. (Standing on/next to the block gives WALKABLE on Fabric too, Lithium or not.)
        check("path type inside polished_granite (fabric hook)", PathType.DANGER_OTHER, WalkNodeEvaluator.getPathTypeStatic(v, p));
        check("path type inside stone untouched", PathType.BLOCKED, WalkNodeEvaluator.getPathTypeStatic(v, p.east()));
        check("path type plain stone floor untouched", PathType.WALKABLE, WalkNodeEvaluator.getPathTypeStatic(v, p.east(7).above()));
        System.out.println("[amber-worldtest] content probe total: " + ok + "/" + total + " OK");
    }
}
