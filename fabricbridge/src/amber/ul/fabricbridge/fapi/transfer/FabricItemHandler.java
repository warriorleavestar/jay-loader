package amber.ul.fabricbridge.fapi.transfer;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * A Fabric item Storage seen as a NeoForge IItemHandler. Slotted storages map slot for slot; other storages expose their
 * views plus one empty slot that inserts into the storage as a whole (NeoForge callers insert slot by slot). Each call is
 * its own (nested, if a Fabric transaction is already open on this thread) transaction; simulate = abort.
 */
final class FabricItemHandler implements IItemHandler {
    final Storage<ItemVariant> s;
    FabricItemHandler(Storage<ItemVariant> s) { this.s = s; }

    private List<StorageView<ItemVariant>> views() {
        if (s instanceof SlottedStorage<ItemVariant> ss) return new ArrayList<>(ss.getSlots());
        List<StorageView<ItemVariant>> l = new ArrayList<>();
        for (StorageView<ItemVariant> v : s) l.add(v);
        return l;
    }
    static Transaction open() { return Transaction.openNested(Transaction.getCurrentUnsafe()); }

    @Override public int getSlots() { int n = views().size(); return s instanceof SlottedStorage ? n : n + 1; }
    @Override public ItemStack getStackInSlot(int i) {
        List<StorageView<ItemVariant>> v = views();
        if (i < 0 || i >= v.size() || v.get(i).isResourceBlank()) return ItemStack.EMPTY;
        return v.get(i).getResource().toStack((int) Math.min(v.get(i).getAmount(), Integer.MAX_VALUE));
    }
    @Override public ItemStack insertItem(int i, ItemStack st, boolean simulate) {
        if (st.isEmpty() || !s.supportsInsertion()) return st;
        ItemVariant var = ItemVariant.of(st);
        long done;
        try (Transaction t = open()) {
            if (s instanceof SlottedStorage<ItemVariant> ss) {
                if (i < 0 || i >= ss.getSlotCount()) return st;
                done = ss.getSlot(i).insert(var, st.getCount(), t);
            } else done = s.insert(var, st.getCount(), t);
            if (!simulate) t.commit();
        }
        return done <= 0 ? st : st.copyWithCount(st.getCount() - (int) done);
    }
    @Override public ItemStack extractItem(int i, int amount, boolean simulate) {
        List<StorageView<ItemVariant>> v = views();
        if (amount <= 0 || i < 0 || i >= v.size() || v.get(i).isResourceBlank()) return ItemStack.EMPTY;
        StorageView<ItemVariant> view = v.get(i);
        ItemVariant var = view.getResource();
        long got;
        try (Transaction t = open()) {
            got = view.extract(var, Math.min(amount, var.toStack().getMaxStackSize()), t);
            if (!simulate) t.commit();
        }
        return got <= 0 ? ItemStack.EMPTY : var.toStack((int) got);
    }
    @Override public int getSlotLimit(int i) {
        List<StorageView<ItemVariant>> v = views();
        return i >= 0 && i < v.size() ? (int) Math.min(v.get(i).getCapacity(), Integer.MAX_VALUE) : 64;
    }
    @Override public boolean isItemValid(int i, ItemStack st) { return !st.isEmpty() && insertItem(i, st.copyWithCount(1), true).isEmpty(); }
}
