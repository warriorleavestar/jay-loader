package amber.ul.fabricbridge.fapi.transfer;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * A Fabric fluid Storage seen as a NeoForge IFluidHandler (one tank per view, at least one). Amounts are converted at
 * 81 droplets per mB; a storage that would only take or give a part of a millibucket is asked again for the whole
 * millibuckets only, so both sides always agree on what moved.
 */
final class FabricFluidHandler implements IFluidHandler {
    static final long R = NeoFluidStorage.R;
    final Storage<FluidVariant> s;
    FabricFluidHandler(Storage<FluidVariant> s) { this.s = s; }

    private List<StorageView<FluidVariant>> views() { List<StorageView<FluidVariant>> l = new ArrayList<>(); for (StorageView<FluidVariant> v : s) l.add(v); return l; }

    @Override public int getTanks() { return Math.max(1, views().size()); }
    @Override public FluidStack getFluidInTank(int i) {
        List<StorageView<FluidVariant>> v = views();
        if (i < 0 || i >= v.size() || v.get(i).isResourceBlank()) return FluidStack.EMPTY;
        return NeoFluidStorage.stack(v.get(i).getResource(), (int) Math.min(v.get(i).getAmount() / R, Integer.MAX_VALUE));
    }
    @Override public int getTankCapacity(int i) {
        List<StorageView<FluidVariant>> v = views();
        return i >= 0 && i < v.size() ? (int) Math.min(v.get(i).getCapacity() / R, Integer.MAX_VALUE) : 0;
    }
    @Override public boolean isFluidValid(int i, FluidStack st) { return !st.isEmpty() && fill(st.copyWithAmount(1), FluidAction.SIMULATE) > 0; }

    @Override public int fill(FluidStack st, FluidAction action) {
        if (st.isEmpty() || !s.supportsInsertion()) return 0;
        FluidVariant v = NeoFluidStorage.variant(st);
        long mb;
        try (Transaction t = FabricItemHandler.open()) { mb = s.insert(v, st.getAmount() * R, t) / R; }   // aborted: just asking
        if (mb <= 0 || action.simulate()) return (int) mb;
        try (Transaction t = FabricItemHandler.open()) {
            if (s.insert(v, mb * R, t) != mb * R) return 0;
            t.commit();
        }
        return (int) mb;
    }
    @Override public FluidStack drain(FluidStack st, FluidAction action) {
        if (st.isEmpty() || !s.supportsExtraction()) return FluidStack.EMPTY;
        FluidVariant v = NeoFluidStorage.variant(st);
        long mb;
        try (Transaction t = FabricItemHandler.open()) { mb = s.extract(v, st.getAmount() * R, t) / R; }
        if (mb <= 0) return FluidStack.EMPTY;
        if (action.execute()) try (Transaction t = FabricItemHandler.open()) {
            if (s.extract(v, mb * R, t) != mb * R) return FluidStack.EMPTY;
            t.commit();
        }
        return NeoFluidStorage.stack(v, (int) mb);
    }
    @Override public FluidStack drain(int max, FluidAction action) {
        for (StorageView<FluidVariant> v : views())
            if (!v.isResourceBlank() && v.getAmount() >= R) return drain(NeoFluidStorage.stack(v.getResource(), max), action);
        return FluidStack.EMPTY;
    }
}
