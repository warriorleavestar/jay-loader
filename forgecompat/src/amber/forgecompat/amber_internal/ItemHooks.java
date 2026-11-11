package amber.forgecompat.amber_internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

/**
 * Forge's IForgeItem hooks whose signatures NeoForge changed. Amber's converter sends calls of the Forge signatures here:
 * {@code super.x(...)} to the {@code super*} methods (Forge's default behaviour), other calls to the dispatching ones,
 * which use the receiver's own override when its class (a converted Forge mod) has one.
 */
public final class ItemHooks {
    private ItemHooks() {}

    // ---- Forge's defaults ----
    public static boolean superCanEquip(Object item, ItemStack stack, EquipmentSlot slot, Entity entity) {
        return entity instanceof LivingEntity le && le.getEquipmentSlotForItem(stack) == slot;
    }
    public static boolean superCanApplyAtEnchantingTable(Object item, ItemStack stack, Enchantment enchantment) {
        return enchantment.isPrimaryItem(stack);
    }

    // ---- dispatch: the receiver's override (Forge signature) if it has one, else the default ----
    static final Map<String, Optional<MethodHandle>> HANDLES = new ConcurrentHashMap<>();
    static Optional<MethodHandle> find(Class<?> c, String name, MethodType t) {
        return HANDLES.computeIfAbsent(c.getName() + "." + name + t, k -> {
            try { return Optional.of(MethodHandles.publicLookup().findVirtual(c, name, t)); } catch (ReflectiveOperationException e) { return Optional.empty(); }
        });
    }
    public static boolean canEquip(Object item, ItemStack stack, EquipmentSlot slot, Entity entity) {
        var h = find(item.getClass(), "canEquip", MethodType.methodType(boolean.class, ItemStack.class, EquipmentSlot.class, Entity.class));
        if (h.isEmpty()) return superCanEquip(item, stack, slot, entity);
        try { return (boolean) h.get().invoke(item, stack, slot, entity); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    public static boolean canApplyAtEnchantingTable(Object item, ItemStack stack, Enchantment enchantment) {
        var h = find(item.getClass(), "canApplyAtEnchantingTable", MethodType.methodType(boolean.class, ItemStack.class, Enchantment.class));
        if (h.isEmpty()) return superCanApplyAtEnchantingTable(item, stack, enchantment);
        try { return (boolean) h.get().invoke(item, stack, enchantment); } catch (Throwable t) { throw new RuntimeException(t); }
    }
}
