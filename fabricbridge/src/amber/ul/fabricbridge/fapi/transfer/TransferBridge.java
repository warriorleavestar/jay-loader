package amber.ul.fabricbridge.fapi.transfer;

import amber.ul.fabricbridge.NeoBus;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * fabric-transfer-api-v1 on NeoForge (brief: transfer row). Fabric's Storage lookups and NeoForge's block capabilities
 * are two separate worlds; this connects them both ways, for items and fluids:
 *  - NeoForge -> Fabric: a Fabric lookup fallback (after Fabric's own vanilla-Container fallback) that asks NeoForge's
 *    capability and wraps the handler (NeoItemStorage / NeoFluidStorage).
 *  - Fabric -> NeoForge: a capability provider on every block, registered at LOWEST priority so NeoForge's own and every
 *    NeoForge mod's providers answer first, that asks Fabric's lookup and wraps the storage (FabricItemHandler / ...).
 * A wrapper of a wrapper is unwrapped, and a per-thread set of positions being asked stops the two directions from
 * asking each other in a loop. Hoppers need nothing more: NeoForge's VanillaInventoryCodeHooks run before Fabric's
 * hopper mixin and now see Fabric storages as capabilities.
 * Not covered yet: item-stack capabilities (ItemHandler.ITEM, FluidHandler.ITEM / ContainerItemContext), entity ones.
 */
public final class TransferBridge {
    private TransferBridge() {}
    private static final ThreadLocal<Set<Long>> ASKING = ThreadLocal.withInitial(HashSet::new);

    public static void init() {
        NeoBus.mod().addListener(EventPriority.LOWEST, RegisterCapabilitiesEvent.class, TransferBridge::register);
    }

    static <T> T guarded(BlockPos pos, Supplier<T> s) {
        Set<Long> a = ASKING.get();
        long k = pos.asLong();
        if (!a.add(k)) return null;
        try { return s.get(); } finally { a.remove(k); }
    }

    static void register(RegisterCapabilitiesEvent e) {
        Block[] all = BuiltInRegistries.BLOCK.stream().toArray(Block[]::new);
        e.registerBlock(Capabilities.ItemHandler.BLOCK, (l, p, s, be, side) -> {
            Storage<ItemVariant> st = guarded(p, () -> ItemStorage.SIDED.find(l, p, s, be, side));
            return st == null ? null : st instanceof NeoItemStorage n ? n.h : new FabricItemHandler(st);
        }, all);
        e.registerBlock(Capabilities.FluidHandler.BLOCK, (l, p, s, be, side) -> {
            Storage<FluidVariant> st = guarded(p, () -> FluidStorage.SIDED.find(l, p, s, be, side));
            return st == null ? null : st instanceof NeoFluidStorage n ? n.h : new FabricFluidHandler(st);
        }, all);
        ItemStorage.SIDED.registerFallback((w, p, s, be, d) -> {
            IItemHandler h = guarded(p, () -> w.getCapability(Capabilities.ItemHandler.BLOCK, p, s, be, d));
            return h == null ? null : h instanceof FabricItemHandler f ? f.s : new NeoItemStorage(h);
        });
        FluidStorage.SIDED.registerFallback((w, p, s, be, d) -> {
            IFluidHandler h = guarded(p, () -> w.getCapability(Capabilities.FluidHandler.BLOCK, p, s, be, d));
            return h == null ? null : h instanceof FabricFluidHandler f ? f.s : new NeoFluidStorage(h);
        });
        NeoBus.log("transfer: Fabric Storage <-> NeoForge ItemHandler/FluidHandler 互通已接上 (%d 个方块, 81 droplets = 1 mB)", all.length);
    }
}
