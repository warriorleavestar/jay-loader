package amber.nfworldtest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

/**
 * Registry sync probe (phase 5). NeoForge side: 3 blocks + block items and a synced custom registry amber_nfworldtest:gadgets.
 * -Damber.regtest.skew=true registers everything in reverse order, so a joining client's local numeric ids differ from the
 * host's and only a working neoforge:sync_registries can make them equal. REGIDS lines list the numeric ids of these entries
 * and of the Fabric test mod's (amber_regtest) entries, looked up by key, for tools/regsync_test.py.
 */
public final class RegProbe {
    private RegProbe() {}
    public record Gadget(String name) {}
    static final String MOD = "amber_nfworldtest", FAB = "amber_regtest";
    static final ResourceKey<Registry<Gadget>> GADGETS_KEY = ResourceKey.createRegistryKey(rl(MOD, "gadgets"));
    static final Registry<Gadget> GADGETS = new RegistryBuilder<>(GADGETS_KEY).sync(true).create();
    static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD);
    static final DeferredRegister<Gadget> GAD = DeferredRegister.create(GADGETS_KEY, MOD);
    static final String[] NF_BLOCKS = {"nf_a", "nf_b", "nf_c"}, NF_GADGETS = {"g_a", "g_b", "g_c"};
    static final String[] FAB_BLOCKS = {"fab_a", "fab_b", "fab_c"}, FAB_WIDGETS = {"w_a", "w_b", "w_c"};

    static ResourceLocation rl(String ns, String p) { return ResourceLocation.fromNamespaceAndPath(ns, p); }

    static void init(IEventBus modBus, DeferredRegister.Items items) {
        boolean skew = Boolean.getBoolean("amber.regtest.skew");
        modBus.addListener(NewRegistryEvent.class, e -> e.register(GADGETS));
        for (String n : order(NF_BLOCKS, skew)) { var b = BLOCKS.registerSimpleBlock(n); items.registerSimpleBlockItem(n, b); }
        for (String n : order(NF_GADGETS, skew)) GAD.register(n, () -> new Gadget(n));
        BLOCKS.register(modBus);
        GAD.register(modBus);
        System.out.println("[amber-nfworldtest] regprobe: 3 blocks, 3 gadgets, skew=" + skew);
    }

    static List<String> order(String[] a, boolean reverse) {
        List<String> l = new ArrayList<>(List.of(a));
        if (reverse) Collections.reverse(l);
        return l;
    }

    /** One line: REGIDS <side> registry|id=raw;... (missing entry -1, missing registry "absent:ns"). */
    static void report(String side) {
        StringBuilder sb = new StringBuilder("[amber-nfworldtest] REGIDS " + side + " ");
        ids(sb, BuiltInRegistries.BLOCK, MOD, NF_BLOCKS);
        ids(sb, BuiltInRegistries.ITEM, MOD, NF_BLOCKS);
        ids(sb, BuiltInRegistries.BLOCK, FAB, FAB_BLOCKS);
        ids(sb, BuiltInRegistries.ITEM, FAB, FAB_BLOCKS);
        ids(sb, BuiltInRegistries.REGISTRY.get(GADGETS_KEY.location()), MOD, NF_GADGETS);
        Registry<?> w = BuiltInRegistries.REGISTRY.get(rl(FAB, "widgets"));
        ids(sb, w, FAB, FAB_WIDGETS);
        sb.append("sync:gadgets=").append(GADGETS.doesSync()).append(";sync:widgets=").append(w == null ? "absent" : String.valueOf(w.doesSync()));
        Block fa = BuiltInRegistries.BLOCK.get(rl(FAB, "fab_a"));
        Item fi = Item.byBlock(fa);
        sb.append(";byBlock:fab_a=").append(BuiltInRegistries.ITEM.getKey(fi));
        System.out.println(sb);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void ids(StringBuilder sb, Registry<?> r, String ns, String[] names) {
        if (r == null) { sb.append("absent:").append(ns).append(';'); return; }
        for (String n : names) {
            ResourceLocation id = rl(ns, n);
            sb.append(r.key().location().getPath()).append('|').append(id).append('=').append(r.containsKey(id) ? ((Registry) r).getId(r.get(id)) : -1).append(';');
        }
    }
}
