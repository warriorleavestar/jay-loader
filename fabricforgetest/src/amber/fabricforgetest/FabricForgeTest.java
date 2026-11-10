package amber.fabricforgetest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Fabric-on-Forge window test (no Fabric API): a plain Fabric mod that registers a block and its item through vanilla
 * Registry.register in onInitialize, as most Fabric mods do. Written in Mojang names, shipped in intermediary
 * (fabricforgetest/build.sh). On Forge it only works inside Forge's registration window; Forge's own registry must see
 * the entries (they then take part in Forge's snapshot and id sync). The world side is checked by forgeworldtest.
 */
public class FabricForgeTest implements ModInitializer, ClientModInitializer {
    public static final ResourceLocation ID = new ResourceLocation("amber_fft", "test_block");
    public static Block BLOCK;

    @Override public void onInitialize() {
        try {
            BLOCK = Registry.register(BuiltInRegistries.BLOCK, ID, new Block(BlockBehaviour.Properties.of().strength(1.0f)));
            Registry.register(BuiltInRegistries.ITEM, ID, new BlockItem(BLOCK, new Item.Properties()));
        } catch (RuntimeException e) {
            System.out.println("[amber-fft] FAIL main: registration refused: " + e);
            throw e;
        }
        System.out.println("[amber-fft] main: registered " + ID + " block+item; vanilla registry has it=" + BuiltInRegistries.BLOCK.containsKey(ID)
                + ", Forge registry has it=" + forgeHas("BLOCKS") + "/" + forgeHas("ITEMS"));
    }

    @Override public void onInitializeClient() {
        System.out.println("[amber-fft] client: init after main, block set=" + (BLOCK != null));
    }

    /** ForgeRegistries.<name>.containsKey(ID), by reflection: this is a Fabric mod and must not link against Forge. */
    static String forgeHas(String name) {
        try {
            Object reg = Class.forName("net.minecraftforge.registries.ForgeRegistries").getField(name).get(null);
            return String.valueOf(reg.getClass().getMethod("containsKey", ResourceLocation.class).invoke(reg, ID));
        } catch (ReflectiveOperationException e) { return "n/a (" + e + ")"; }
    }
}
