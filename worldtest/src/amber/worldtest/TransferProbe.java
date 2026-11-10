package amber.worldtest;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.InventoryStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/**
 * fabric-transfer-api-v1 interop probe (-Damber.transferprobe=true), Fabric half. Places a row of blocks at y=300 near
 * the player: diamond (Fabric-only item Storage, 5 apples), lapis (Fabric-only fluid Storage, 1 bucket water), emerald
 * (NeoForge-only IItemHandler from nfworldtest, 7 stone), gold (NeoForge-only IFluidHandler, 1000 mB lava), a vanilla
 * chest (3 sticks) and, if present, an iron furnace. Then looks each one up through Fabric's ItemStorage/FluidStorage
 * and moves items/fluid out of the NeoForge handlers inside a Transaction. nfworldtest does the NeoForge-side lookups;
 * the row's origin reaches it through the system property amber.transferprobe.base.
 */
final class TransferProbe {
    static final Container FAB_INV = new SimpleContainer(new ItemStack(Items.APPLE, 5), ItemStack.EMPTY, ItemStack.EMPTY);
    static final Storage<ItemVariant> FAB_ITEMS = InventoryStorage.of(FAB_INV, null);
    static final SingleVariantStorage<FluidVariant> FAB_FLUID = new SingleVariantStorage<>() {
        @Override protected FluidVariant getBlankVariant() { return FluidVariant.blank(); }
        @Override protected long getCapacity(FluidVariant v) { return 4 * FluidConstants.BUCKET; }
    };
    static final String[] NAMES = {"diamond(fabric item)", "lapis(fabric fluid)", "emerald(neo item)", "gold(neo fluid)", "chest(vanilla)", "iron_furnace(neo mod)"};
    static BlockPos base;

    static void register() {
        FAB_FLUID.variant = FluidVariant.of(Fluids.WATER); FAB_FLUID.amount = FluidConstants.BUCKET;
        ItemStorage.SIDED.registerForBlocks((w, p, s, be, d) -> FAB_ITEMS, Blocks.DIAMOND_BLOCK);
        FluidStorage.SIDED.registerForBlocks((w, p, s, be, d) -> FAB_FLUID, Blocks.LAPIS_BLOCK);
        System.out.println("[amber-worldtest] transfer probe: Fabric storages registered (diamond items, lapis fluid)");
    }
    static BlockPos pos(int i) { return base.east(2 * i); }

    static void place(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            var player = server.getPlayerList().getPlayers().get(0);
            base = new BlockPos(player.getBlockX() - 6, 300, player.getBlockZ() + 3);
            Block furnace = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("ironfurnaces", "iron_furnace"));
            Block[] row = {Blocks.DIAMOND_BLOCK, Blocks.LAPIS_BLOCK, Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK, Blocks.CHEST, furnace};
            for (int i = 0; i < row.length; i++) if (row[i] != Blocks.AIR) level.setBlockAndUpdate(pos(i), row[i].defaultBlockState());
            if (level.getBlockEntity(pos(4)) instanceof Container c) c.setItem(0, new ItemStack(Items.STICK, 3));
            for (int i : new int[]{0, 2}) {   // hoppers (facing down) above the Fabric-only and the NeoForge-only item blocks
                level.setBlockAndUpdate(pos(i).above(), Blocks.HOPPER.defaultBlockState());
                if (level.getBlockEntity(pos(i).above()) instanceof Container c) c.setItem(0, new ItemStack(Items.CARROT, 2));
            }
            System.setProperty("amber.transferprobe.base", base.getX() + "," + base.getY() + "," + base.getZ());
            System.out.println("[amber-worldtest] transfer probe: placed row at " + base.toShortString() + " (iron furnace " + (furnace != Blocks.AIR) + ")");
        });
    }

    static <T> String describe(Storage<T> s, java.util.function.Function<T, String> name) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("[");
        for (StorageView<T> v : s) if (!v.isResourceBlank() && v.getAmount() > 0) b.append(' ').append(name.apply(v.getResource())).append('x').append(v.getAmount());
        return b.append(" ] (").append(s.getClass().getSimpleName()).append(')').toString();
    }
    static String item(ItemVariant v) { return BuiltInRegistries.ITEM.getKey(v.getItem()).getPath(); }
    static String fluid(FluidVariant v) { return BuiltInRegistries.FLUID.getKey(v.getFluid()).getPath(); }

    static void query(Minecraft client) {
        var server = client.getSingleplayerServer();
        if (server == null || base == null) return;
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (int i : new int[]{0, 2})
                System.out.println("[amber-worldtest] transfer probe: hopper above " + NAMES[i] + " now holds " + describe(InventoryStorage.of((Container) level.getBlockEntity(pos(i).above()), null), TransferProbe::item));
            for (int i = 0; i < NAMES.length; i++) {
                System.out.println("[amber-worldtest] transfer probe: fabric view " + NAMES[i] + " items=" + describe(ItemStorage.SIDED.find(level, pos(i), Direction.UP), TransferProbe::item)
                        + " fluids=" + describe(FluidStorage.SIDED.find(level, pos(i), Direction.UP), TransferProbe::fluid));
            }
            Storage<ItemVariant> neo = ItemStorage.SIDED.find(level, pos(2), Direction.UP);
            if (neo != null) {
                long got, put;
                try (Transaction t = Transaction.openOuter()) {
                    got = neo.extract(ItemVariant.of(Items.STONE), 1, t);
                    put = neo.insert(ItemVariant.of(Items.APPLE), 2, t);
                    t.commit();
                }
                System.out.println("[amber-worldtest] transfer probe: fabric moved with neo handler: extracted stone " + got + ", inserted apple " + put + " -> now " + describe(neo, TransferProbe::item));
                long aborted;
                try (Transaction t = Transaction.openOuter()) { aborted = neo.extract(ItemVariant.of(Items.STONE), 2, t); }   // not committed: must roll back
                System.out.println("[amber-worldtest] transfer probe: fabric aborted extract of " + aborted + " stone -> now " + describe(neo, TransferProbe::item));
            }
            Storage<FluidVariant> neoF = FluidStorage.SIDED.find(level, pos(3), Direction.UP);
            if (neoF != null) {
                long got;
                try (Transaction t = Transaction.openOuter()) { got = neoF.extract(FluidVariant.of(Fluids.LAVA), FluidConstants.BOTTLE, t); t.commit(); }
                System.out.println("[amber-worldtest] transfer probe: fabric extracted lava " + got + " droplets (bottle=" + FluidConstants.BOTTLE + ") -> now " + describe(neoF, TransferProbe::fluid));
            }
        });
    }
}
