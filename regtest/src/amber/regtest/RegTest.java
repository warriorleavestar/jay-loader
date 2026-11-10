package amber.regtest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.registry.FabricRegistryBuilder;
import net.fabricmc.fabric.api.event.registry.RegistryAttribute;
import net.fabricmc.fabric.api.event.registry.RegistryEntryAddedCallback;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Fabric side of the registry sync probe (phase 5): plain Registry.register of 3 blocks + block items in onInitialize, a
 * custom registry amber_regtest:widgets built with FabricRegistryBuilder + RegistryAttribute.SYNCED, and a
 * RegistryEntryAddedCallback on the block registry. -Damber.regtest.skew=true registers in reverse order (see the
 * NeoForge test mod's RegProbe, which prints the ids).
 */
public class RegTest implements ModInitializer {
    public static final String ID = "amber_regtest";
    public record Widget(String name) {}
    public static final ResourceKey<Registry<Widget>> WIDGETS_KEY = ResourceKey.createRegistryKey(ResourceLocation.fromNamespaceAndPath(ID, "widgets"));
    public static Registry<Widget> WIDGETS;
    static int blockAdds;

    @Override
    public void onInitialize() {
        boolean skew = Boolean.getBoolean("amber.regtest.skew");
        RegistryEntryAddedCallback.event(BuiltInRegistries.BLOCK).register((raw, id, block) -> { if (id.getNamespace().equals(ID)) blockAdds++; });
        WIDGETS = FabricRegistryBuilder.createSimple(WIDGETS_KEY).attribute(RegistryAttribute.SYNCED).buildAndRegister();
        for (String n : order(new String[]{"fab_a", "fab_b", "fab_c"}, skew)) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ID, n);
            Block b = Registry.register(BuiltInRegistries.BLOCK, id, new Block(BlockBehaviour.Properties.of().strength(1.0f)));
            Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(b, new Item.Properties()));
        }
        for (String n : order(new String[]{"w_a", "w_b", "w_c"}, skew)) Registry.register(WIDGETS, ResourceLocation.fromNamespaceAndPath(ID, n), new Widget(n));
        NetTest.init();
        System.out.println("[amber-regtest] onInitialize: widgets=" + WIDGETS.key().location() + " size=" + WIDGETS.size()
                + ", RegistryEntryAddedCallback(block) saw " + blockAdds + " of 3, skew=" + skew);
    }

    static List<String> order(String[] a, boolean reverse) {
        List<String> l = new ArrayList<>(List.of(a));
        if (reverse) Collections.reverse(l);
        return l;
    }
}
