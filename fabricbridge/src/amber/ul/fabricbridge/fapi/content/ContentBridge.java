package amber.ul.fabricbridge.fapi.content;

import amber.ul.fabricbridge.NeoBus;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.fabricmc.fabric.impl.content.registry.FuelRegistryImpl;
import net.fabricmc.fabric.mixin.content.registry.FarmerWorkTaskAccessor;
import net.fabricmc.fabric.mixin.content.registry.GiveGiftsToHeroTaskAccessor;
import net.fabricmc.fabric.mixin.content.registry.HoeItemAccessor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.vibrations.VibrationSystem;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.furnace.FurnaceFuelBurnTimeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.builtin.Compostable;
import net.neoforged.neoforge.registries.datamaps.builtin.NeoForgeDataMaps;
import net.neoforged.neoforge.registries.datamaps.builtin.RaidHeroGift;
import net.neoforged.neoforge.registries.datamaps.builtin.VibrationFrequency;

/**
 * fabric-content-registries-v0 on NeoForge (2026-11-08 worldtest content probe). Fabric's registries write the vanilla
 * tables; NeoForge reads some of those tables but answers others only from data maps, item abilities or events:
 *  - fuel: NeoForge asks ItemStack.getBurnTime (data map FURNACE_FUELS + FurnaceFuelBurnTimeEvent). Fabric's two
 *    furnace redirects are stripped by the manifest (their call sites are gone); its getFuel() hook stays, so
 *    FuelRegistry.get keeps working. Here: FurnaceFuelBurnTimeEvent answers every item Fabric added or removed.
 *  - composting (player + villager), sculk vibration frequencies, hero-of-the-village gifts: data maps only, no
 *    fallback to the vanilla tables. Here: the vanilla tables are snapshotted before the Fabric entrypoints run, and on
 *    every DataMapsUpdatedEvent what changed since (added, replaced, removed) is written into the data map.
 *  - tilling: NeoForge's HoeItem never reads HoeItem.TILLABLES (vanilla tilling is hard-coded in IBlockExtension).
 *    Here: BlockToolModificationEvent(HOE_TILL) runs Fabric's predicate/action for entries Fabric added or replaced.
 * Stripping, flattening, oxidizing/waxing, flammability, brewing, villager food/pickup read the vanilla tables on
 * NeoForge too and need nothing. Path node types: Fabric's mixins apply; measured without Lithium (see probe).
 * The diff also carries entries other (non-Fabric-API) code wrote straight into those vanilla tables.
 */
public final class ContentBridge {
    private ContentBridge() {}
    private static Map<Item, Float> compostSnap;
    private static Map<ResourceKey<GameEvent>, Integer> freqSnap;
    private static Map<VillagerProfession, ResourceKey<LootTable>> giftSnap;
    private static Set<Item> villagerCompostSnap;
    private static Map<Block, Object> tillSnap;

    public static void init() {
        compostSnap = compostNow();
        freqSnap = new HashMap<>(freqMap());
        giftSnap = new HashMap<>(GiveGiftsToHeroTaskAccessor.fabric_getGifts());
        villagerCompostSnap = new HashSet<>(FarmerWorkTaskAccessor.fabric_getCompostable());
        tillSnap = new IdentityHashMap<>(HoeItemAccessor.getTillingActions());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, FurnaceFuelBurnTimeEvent.class, ContentBridge::fuel);
        NeoForge.EVENT_BUS.addListener(DataMapsUpdatedEvent.class, ContentBridge::dataMaps);
        NeoForge.EVENT_BUS.addListener(BlockEvent.BlockToolModificationEvent.class, ContentBridge::tool);
        NeoBus.log("content registries: 原版表快照 compost=%d vibration=%d gifts=%d villagerCompost=%d tillables=%d", compostSnap.size(), freqSnap.size(), giftSnap.size(), villagerCompostSnap.size(), tillSnap.size());
    }

    static Map<Item, Float> compostNow() {
        Map<Item, Float> m = new HashMap<>();
        for (var e : ComposterBlock.COMPOSTABLES.object2FloatEntrySet()) m.put(e.getKey().asItem(), e.getFloatValue());
        return m;
    }
    @SuppressWarnings("unchecked")
    static Reference2IntOpenHashMap<ResourceKey<GameEvent>> freqMap() { return (Reference2IntOpenHashMap<ResourceKey<GameEvent>>) VibrationSystem.VIBRATION_FREQUENCY_FOR_EVENT; }

    /** now vs snapshot: key -> new value, or key -> null when removed. */
    static <K, V> Map<K, V> diff(Map<K, V> snap, Map<K, V> now) {
        Map<K, V> d = new HashMap<>();
        for (var e : now.entrySet()) if (!e.getValue().equals(snap.get(e.getKey()))) d.put(e.getKey(), e.getValue());
        for (K k : snap.keySet()) if (!now.containsKey(k)) d.put(k, null);
        return d;
    }

    // ---- fuel ----
    private static Map<Item, Integer> fuelBase, fuelDelta = Map.of();

    static void fuel(FurnaceFuelBurnTimeEvent e) {
        Map<Item, Integer> cur = AbstractFurnaceBlockEntity.getFuel();   // rebuilt after every invalidateCache (Fabric add/remove, tag reload)
        if (cur != fuelBase) { fuelBase = cur; fuelDelta = fuelDelta(); }
        Integer t = fuelDelta.get(e.getItemStack().getItem());
        if (t != null) e.setBurnTime(t);
    }

    /** Fabric's own apply() (tags first, then items; 0 = remove) run against a recording map: what Fabric set or removed. */
    static Map<Item, Integer> fuelDelta() {
        Set<Object> removed = new HashSet<>();
        Map<Item, Integer> put = new HashMap<>() {
            @Override public Integer remove(Object k) { removed.add(k); return super.remove(k); }
        };
        ((FuelRegistryImpl) FuelRegistry.INSTANCE).apply(put);
        for (Object k : removed) if (k instanceof Item i) put.putIfAbsent(i, 0);
        return put;
    }

    // ---- data maps ----
    static void dataMaps(DataMapsUpdatedEvent e) {
        try {
            e.ifRegistry(Registries.ITEM, ContentBridge::items);
            e.ifRegistry(Registries.GAME_EVENT, ContentBridge::gameEvents);
            e.ifRegistry(Registries.VILLAGER_PROFESSION, ContentBridge::professions);
        } catch (Throwable t) { NeoBus.problem("fabric_api_content_registries", "error", "数据映射写入失败 (%s): %s", e.getCause(), t); }
    }

    static void items(Registry<Item> reg) {
        Map<Item, Float> compost = diff(compostSnap, compostNow());
        List<Item> villagerAdded = new ArrayList<>();
        for (Item i : FarmerWorkTaskAccessor.fabric_getCompostable()) if (!villagerCompostSnap.contains(i)) villagerAdded.add(i);
        if (compost.isEmpty() && villagerAdded.isEmpty()) return;
        int[] n = new int[3];
        edit(reg, NeoForgeDataMaps.COMPOSTABLES, m -> {
            for (var c : compost.entrySet()) {
                ResourceKey<Item> k = reg.getResourceKey(c.getKey()).orElse(null);
                if (k == null) continue;
                if (c.getValue() == null) { m.remove(k); n[1]++; }
                else { Compostable old = m.get(k); m.put(k, new Compostable(c.getValue(), old != null && old.canVillagerCompost())); n[0]++; }
            }
            for (Item i : villagerAdded) {
                ResourceKey<Item> k = reg.getResourceKey(i).orElse(null);
                if (k == null) continue;
                Compostable old = m.get(k);
                Float chance = old != null ? Float.valueOf(old.chance()) : ComposterBlock.COMPOSTABLES.containsKey(i) ? ComposterBlock.COMPOSTABLES.getFloat(i) : null;
                if (chance == null) continue;   // the composter would refuse it anyway (vanilla behaves the same)
                m.put(k, new Compostable(chance, true)); n[2]++;
            }
        });
        NeoBus.log("content registries: compostables 数据映射 +%d -%d, 村民可堆肥 +%d", n[0], n[1], n[2]);
    }

    static void gameEvents(Registry<GameEvent> reg) {
        Map<ResourceKey<GameEvent>, Integer> d = diff(freqSnap, new HashMap<>(freqMap()));
        if (d.isEmpty()) return;
        edit(reg, NeoForgeDataMaps.VIBRATION_FREQUENCIES, m -> {
            for (var e : d.entrySet()) if (e.getValue() == null) m.remove(e.getKey()); else m.put(e.getKey(), new VibrationFrequency(e.getValue()));
        });
        NeoBus.log("content registries: vibration_frequencies 数据映射 %d 项", d.size());
    }

    static void professions(Registry<VillagerProfession> reg) {
        Map<VillagerProfession, ResourceKey<LootTable>> d = diff(giftSnap, new HashMap<>(GiveGiftsToHeroTaskAccessor.fabric_getGifts()));
        if (d.isEmpty()) return;
        edit(reg, NeoForgeDataMaps.RAID_HERO_GIFTS, m -> {
            for (var e : d.entrySet()) {
                ResourceKey<VillagerProfession> k = reg.getResourceKey(e.getKey()).orElse(null);
                if (k == null) continue;
                if (e.getValue() == null) m.remove(k); else m.put(k, new RaidHeroGift(e.getValue()));
            }
        });
        NeoBus.log("content registries: raid_hero_gifts 数据映射 %d 项", d.size());
    }

    private static Field dataMapsField;
    /** Replaces the registry's map for this data map type with an edited copy (the loaded one may be immutable). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <T, A> void edit(Registry<T> reg, DataMapType<T, A> type, Consumer<Map<ResourceKey<T>, A>> f) {
        try {
            if (dataMapsField == null) { Field fl = BaseMappedRegistry.class.getDeclaredField("dataMaps"); fl.setAccessible(true); dataMapsField = fl; }
            Map outer = (Map) dataMapsField.get(reg);
            Map<ResourceKey<T>, A> m = new HashMap<>((Map<ResourceKey<T>, A>) outer.getOrDefault(type, Map.of()));
            f.accept(m);
            outer.put(type, m);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    // ---- tilling ----
    @SuppressWarnings("unchecked")
    static void tool(BlockEvent.BlockToolModificationEvent e) {
        if (e.getItemAbility() != ItemAbilities.HOE_TILL) return;
        BlockState state = e.getState();
        Object entry = HoeItemAccessor.getTillingActions().get(state.getBlock());
        if (entry == null || entry == tillSnap.get(state.getBlock())) return;   // vanilla entries: NeoForge's own tilling answers
        var pair = (Pair<Predicate<UseOnContext>, Consumer<UseOnContext>>) entry;
        UseOnContext ctx = e.getContext();
        if (ctx == null || !pair.getFirst().test(ctx)) { e.setFinalState(null); return; }
        if (e.isSimulated()) return;   // Fabric's action can't be dry-run: report the default answer
        if (ctx.getLevel().isClientSide) { e.setFinalState(state); return; }   // client: success + sound; the server acts
        pair.getSecond().accept(ctx);   // Fabric's action (sets the block, may drop an item)
        e.setFinalState(ctx.getLevel().getBlockState(ctx.getClickedPos()));
    }
}
