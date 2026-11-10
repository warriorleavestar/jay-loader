package amber.ul.fabricbridge.fapi.transfer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * A NeoForge IFluidHandler seen as a Fabric fluid Storage. Fabric counts droplets (81000 per bucket), NeoForge millibuckets
 * (1000 per bucket): 1 mB = 81 droplets exactly, and only whole millibuckets are moved, so nothing is lost to rounding.
 */
final class NeoFluidStorage implements Storage<FluidVariant> {
    static final long R = 81;
    final IFluidHandler h;
    NeoFluidStorage(IFluidHandler h) { this.h = h; }

    static FluidStack stack(FluidVariant v, int mb) { return new FluidStack(v.getRegistryEntry(), mb, v.getComponents()); }
    static FluidVariant variant(FluidStack s) { return s.isEmpty() ? FluidVariant.blank() : FluidVariant.of(s.getFluid(), s.getComponentsPatch()); }

    @Override public long insert(FluidVariant res, long max, TransactionContext t) {
        StoragePreconditions.notBlankNotNegative(res, max);
        int mb = (int) Math.min(max / R, Integer.MAX_VALUE);
        if (mb <= 0) return 0;
        int can = h.fill(stack(res, mb), IFluidHandler.FluidAction.SIMULATE);
        if (can <= 0) return 0;
        int done = h.fill(stack(res, can), IFluidHandler.FluidAction.EXECUTE);
        if (done > 0) Journal.undo(t, () -> h.drain(stack(res, done), IFluidHandler.FluidAction.EXECUTE));
        return done * R;
    }
    @Override public long extract(FluidVariant res, long max, TransactionContext t) {
        StoragePreconditions.notBlankNotNegative(res, max);
        int mb = (int) Math.min(max / R, Integer.MAX_VALUE);
        if (mb <= 0) return 0;
        FluidStack sim = h.drain(stack(res, mb), IFluidHandler.FluidAction.SIMULATE);
        if (sim.isEmpty()) return 0;
        FluidStack got = h.drain(stack(res, sim.getAmount()), IFluidHandler.FluidAction.EXECUTE);
        if (got.isEmpty()) return 0;
        FluidStack back = got.copy();
        Journal.undo(t, () -> h.fill(back, IFluidHandler.FluidAction.EXECUTE));
        return got.getAmount() * R;
    }
    @Override public Iterator<StorageView<FluidVariant>> iterator() {
        List<StorageView<FluidVariant>> l = new ArrayList<>(h.getTanks());
        for (int i = 0; i < h.getTanks(); i++) l.add(new Tank(i));
        return l.iterator();
    }

    final class Tank implements StorageView<FluidVariant> {
        final int i;
        Tank(int i) { this.i = i; }
        @Override public long extract(FluidVariant res, long max, TransactionContext t) {
            return res.equals(getResource()) ? NeoFluidStorage.this.extract(res, Math.min(max, getAmount()), t) : 0;
        }
        @Override public boolean isResourceBlank() { return h.getFluidInTank(i).isEmpty(); }
        @Override public FluidVariant getResource() { return variant(h.getFluidInTank(i)); }
        @Override public long getAmount() { return h.getFluidInTank(i).getAmount() * R; }
        @Override public long getCapacity() { return h.getTankCapacity(i) * R; }
    }
}
