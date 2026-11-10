package amber.nfworldtest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;

/** NeoForge half of worldtest's transfer probe (-Damber.transferprobe=true): NeoForge-only handlers on emerald/gold and capability lookups on the whole row. */
final class TransferProbe {
    static final ItemStackHandler NF_ITEMS = new ItemStackHandler(3);
    static final FluidTank NF_FLUID = new FluidTank(4000);
    static final String[] NAMES = {"diamond(fabric item)", "lapis(fabric fluid)", "emerald(neo item)", "gold(neo fluid)", "chest(vanilla)", "iron_furnace(neo mod)"};

    static void init(IEventBus modBus) {
        NF_ITEMS.setStackInSlot(0, new ItemStack(Items.STONE, 7));
        NF_FLUID.fill(new FluidStack(Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE);
        modBus.addListener(RegisterCapabilitiesEvent.class, e -> {
            e.registerBlock(Capabilities.ItemHandler.BLOCK, (l, p, s, be, side) -> NF_ITEMS, Blocks.EMERALD_BLOCK);
            e.registerBlock(Capabilities.FluidHandler.BLOCK, (l, p, s, be, side) -> NF_FLUID, Blocks.GOLD_BLOCK);
            System.out.println("[amber-nfworldtest] transfer probe: NeoForge capabilities registered (emerald items, gold fluid)");
        });
    }
    static String items(IItemHandler h) {
        if (h == null) return "null";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < h.getSlots(); i++) { ItemStack st = h.getStackInSlot(i); if (!st.isEmpty()) b.append(' ').append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath()).append('x').append(st.getCount()); }
        return b.append(" ] (").append(h.getClass().getSimpleName()).append(", ").append(h.getSlots()).append(" slots)").toString();
    }
    static String fluids(IFluidHandler h) {
        if (h == null) return "null";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < h.getTanks(); i++) { FluidStack st = h.getFluidInTank(i); if (!st.isEmpty()) b.append(' ').append(BuiltInRegistries.FLUID.getKey(st.getFluid()).getPath()).append('x').append(st.getAmount()).append("mB"); }
        return b.append(" ] (").append(h.getClass().getSimpleName()).append(')').toString();
    }
    static void query(MinecraftServer server) {
        String bs = System.getProperty("amber.transferprobe.base");
        if (server == null || bs == null) { System.out.println("[amber-nfworldtest] transfer probe: no row placed (worldtest missing?)"); return; }
        String[] xyz = bs.split(",");
        BlockPos base = new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        server.execute(() -> {
            ServerLevel level = server.overworld();
            for (int i = 0; i < NAMES.length; i++) {
                BlockPos p = base.east(2 * i);
                System.out.println("[amber-nfworldtest] transfer probe: neo view " + NAMES[i] + " items=" + items(level.getCapability(Capabilities.ItemHandler.BLOCK, p, Direction.UP))
                        + " fluids=" + fluids(level.getCapability(Capabilities.FluidHandler.BLOCK, p, Direction.UP)));
            }
            IItemHandler fab = level.getCapability(Capabilities.ItemHandler.BLOCK, base, Direction.UP);
            if (fab != null) {
                int slot = -1;
                for (int i = 0; i < fab.getSlots(); i++) if (fab.getStackInSlot(i).is(Items.APPLE)) slot = i;
                ItemStack got = slot < 0 ? ItemStack.EMPTY : fab.extractItem(slot, 1, false);
                ItemStack rest = new ItemStack(Items.STONE, 2);
                for (int i = 0; i < fab.getSlots() && !rest.isEmpty(); i++) rest = fab.insertItem(i, rest, false);
                System.out.println("[amber-nfworldtest] transfer probe: neo moved with fabric storage: extracted apple " + got.getCount() + ", inserted stone 2 (left " + rest.getCount() + ") -> now " + items(fab));
            }
            IFluidHandler fabF = level.getCapability(Capabilities.FluidHandler.BLOCK, base.east(2), Direction.UP);
            if (fabF != null) {
                FluidStack got = fabF.drain(new FluidStack(Fluids.WATER, 250), IFluidHandler.FluidAction.EXECUTE);
                System.out.println("[amber-nfworldtest] transfer probe: neo drained water " + got.getAmount() + " mB -> now " + fluids(fabF));
            }
        });
    }
}
