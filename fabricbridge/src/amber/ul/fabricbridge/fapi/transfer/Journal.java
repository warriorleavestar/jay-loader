package amber.ul.fabricbridge.fapi.transfer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Fabric transactions over NeoForge handlers, which only know "simulate" and "do it now". Operations are applied at once;
 * what is needed to take them back is recorded per open transaction and replayed if that transaction aborts. On a nested
 * commit the record moves to the parent (like Fabric's SnapshotParticipant).
 *  - IItemHandlerModifiable: the slot's stack before the first change in this transaction (restored with setStackInSlot,
 *    so slots that refuse insertion, e.g. furnace outputs, still roll back exactly).
 *  - anything else (IFluidHandler, plain IItemHandler): the inverse operation, replayed newest first (best effort).
 * One record per transaction, keyed by identity, shared by every adapter, so two wrappers of the same handler inside one
 * transaction still restore the oldest snapshot.
 */
final class Journal {
    private Journal() {}
    static final class Tx {
        final IdentityHashMap<IItemHandlerModifiable, Map<Integer, ItemStack>> snaps = new IdentityHashMap<>();
        final List<Runnable> undos = new ArrayList<>();
    }
    private static final ThreadLocal<IdentityHashMap<TransactionContext, Tx>> OPEN = ThreadLocal.withInitial(IdentityHashMap::new);

    private static Tx tx(TransactionContext t) {
        Tx x = OPEN.get().get(t);
        if (x == null) { x = new Tx(); OPEN.get().put(t, x); t.addCloseCallback(Journal::close); }
        return x;
    }
    static void snapshot(TransactionContext t, IItemHandlerModifiable h, int slot) {
        tx(t).snaps.computeIfAbsent(h, k -> new HashMap<>()).computeIfAbsent(slot, s -> h.getStackInSlot(s).copy());
    }
    static void undo(TransactionContext t, Runnable r) { tx(t).undos.add(r); }

    private static void close(TransactionContext t, TransactionContext.Result r) {
        Tx x = OPEN.get().remove(t);
        if (x == null) return;
        if (r.wasAborted()) {
            for (int i = x.undos.size() - 1; i >= 0; i--) x.undos.get(i).run();
            x.snaps.forEach((h, m) -> m.forEach(h::setStackInSlot));
        } else if (t.nestingDepth() > 0) {
            Tx p = tx(t.getOpenTransaction(t.nestingDepth() - 1));
            x.snaps.forEach((h, m) -> { Map<Integer, ItemStack> pm = p.snaps.computeIfAbsent(h, k -> new HashMap<>()); m.forEach(pm::putIfAbsent); });
            p.undos.addAll(x.undos);
        }
    }
}
