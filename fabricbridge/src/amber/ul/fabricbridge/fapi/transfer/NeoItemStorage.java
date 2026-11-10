package amber.ul.fabricbridge.fapi.transfer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** A NeoForge IItemHandler seen as a Fabric item Storage (one slot per handler slot); rollback through Journal. */
final class NeoItemStorage implements SlottedStorage<ItemVariant> {
    final IItemHandler h;
    NeoItemStorage(IItemHandler h) { this.h = h; }

    @Override public int getSlotCount() { return h.getSlots(); }
    @Override public SingleSlotStorage<ItemVariant> getSlot(int i) { return new Slot(i); }
    @Override public Iterator<StorageView<ItemVariant>> iterator() {
        List<StorageView<ItemVariant>> l = new ArrayList<>(h.getSlots());
        for (int i = 0; i < h.getSlots(); i++) l.add(new Slot(i));
        return l.iterator();
    }
    @Override public long insert(ItemVariant res, long max, TransactionContext t) {
        StoragePreconditions.notBlankNotNegative(res, max);
        long done = 0;
        for (int i = 0; i < h.getSlots() && done < max; i++) done += insertSlot(i, res, max - done, t);
        return done;
    }
    @Override public long extract(ItemVariant res, long max, TransactionContext t) {
        StoragePreconditions.notBlankNotNegative(res, max);
        long done = 0;
        for (int i = 0; i < h.getSlots() && done < max; i++) done += extractSlot(i, res, max - done, t);
        return done;
    }

    private void before(int i, TransactionContext t) { if (h instanceof IItemHandlerModifiable m) Journal.snapshot(t, m, i); }

    long insertSlot(int i, ItemVariant res, long max, TransactionContext t) {
        int n = (int) Math.min(max, res.toStack().getMaxStackSize());
        if (n <= 0) return 0;
        int accepted = n - h.insertItem(i, res.toStack(n), true).getCount();
        if (accepted <= 0) return 0;
        before(i, t);
        int done = accepted - h.insertItem(i, res.toStack(accepted), false).getCount();
        if (done > 0 && !(h instanceof IItemHandlerModifiable)) Journal.undo(t, () -> h.extractItem(i, done, false));
        return done;
    }
    long extractSlot(int i, ItemVariant res, long max, TransactionContext t) {
        ItemStack cur = h.getStackInSlot(i);
        if (cur.isEmpty() || !res.matches(cur)) return 0;
        int n = (int) Math.min(max, Integer.MAX_VALUE);
        ItemStack sim = h.extractItem(i, n, true);
        if (sim.isEmpty() || !res.matches(sim)) return 0;
        before(i, t);
        ItemStack got = h.extractItem(i, sim.getCount(), false);
        if (!got.isEmpty() && !(h instanceof IItemHandlerModifiable)) { ItemStack back = got.copy(); Journal.undo(t, () -> h.insertItem(i, back, false)); }
        return got.getCount();
    }

    final class Slot implements SingleSlotStorage<ItemVariant> {
        final int i;
        Slot(int i) { this.i = i; }
        @Override public long insert(ItemVariant res, long max, TransactionContext t) { StoragePreconditions.notBlankNotNegative(res, max); return insertSlot(i, res, max, t); }
        @Override public long extract(ItemVariant res, long max, TransactionContext t) { StoragePreconditions.notBlankNotNegative(res, max); return extractSlot(i, res, max, t); }
        @Override public boolean isResourceBlank() { return h.getStackInSlot(i).isEmpty(); }
        @Override public ItemVariant getResource() { return ItemVariant.of(h.getStackInSlot(i)); }
        @Override public long getAmount() { return h.getStackInSlot(i).getCount(); }
        @Override public long getCapacity() {
            ItemStack s = h.getStackInSlot(i);
            return s.isEmpty() ? h.getSlotLimit(i) : Math.min(h.getSlotLimit(i), s.getMaxStackSize());
        }
    }
}
