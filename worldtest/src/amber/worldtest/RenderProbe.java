package amber.worldtest;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.Objects;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.item.ItemColor;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * fabric-rendering-v1 probe (-Damber.renderprobe=true): ColorProviderRegistry for blocks and items (registered before
 * the game builds its colour maps, read back through BlockColors/ItemColors and through the registry, vanilla providers
 * visible, a late registration applied directly) and CoreShaderRegistrationCallback with a namespaced core shader whose
 * stages and #moj_import are namespaced too.
 */
final class RenderProbe {
    private RenderProbe() {}
    static Block BLOCK, LATE_BLOCK; static Item ITEM;
    static final BlockColor BLOCK_COLOR = (s, l, p, i) -> 0x123456, LATE_COLOR = (s, l, p, i) -> 0x0A0B0C;
    static final ItemColor ITEM_COLOR = (stack, i) -> 0xFF654321;
    static ShaderInstance shader; static int shaderCallbacks, ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] render probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        BLOCK = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "render_probe_block"), new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)));
        LATE_BLOCK = Registry.register(BuiltInRegistries.BLOCK, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "render_probe_late"), new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)));
        ITEM = Registry.register(BuiltInRegistries.ITEM, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "render_probe_item"), new Item(new Item.Properties()));
    }

    static void registerClient() {
        ColorProviderRegistry.BLOCK.register(BLOCK_COLOR, BLOCK);
        ColorProviderRegistry.ITEM.register(ITEM_COLOR, ITEM);
        CoreShaderRegistrationCallback.EVENT.register(ctx -> ctx.register(ResourceLocation.fromNamespaceAndPath("amber-worldtest", "probe"), DefaultVertexFormat.POSITION, s -> { shader = s; shaderCallbacks++; }));
    }

    /** Minecraft.itemColors has no getter in vanilla (NeoForge adds one); found by type, the worldtest jar is remapped. */
    static net.minecraft.client.color.item.ItemColors itemColors(Minecraft client) throws Exception {
        for (java.lang.reflect.Field f : Minecraft.class.getDeclaredFields())
            if (f.getType() == net.minecraft.client.color.item.ItemColors.class) { f.setAccessible(true); return (net.minecraft.client.color.item.ItemColors) f.get(client); }
        throw new IllegalStateException("no ItemColors field");
    }

    static String hex(int c) { return Integer.toHexString(c); }

    static void run(Minecraft client) {
        try {
            BlockPos pos = client.player.blockPosition();
            check("registry block provider", true, ColorProviderRegistry.BLOCK.get(BLOCK) == BLOCK_COLOR);
            check("BlockColors uses it", hex(0x123456), hex(client.getBlockColors().getColor(BLOCK.defaultBlockState(), client.level, pos, 0)));
            check("vanilla grass provider visible", true, ColorProviderRegistry.BLOCK.get(Blocks.GRASS_BLOCK) != null);
            check("uncoloured block", "null", ColorProviderRegistry.BLOCK.get(Blocks.STONE));
            check("registry item provider", true, ColorProviderRegistry.ITEM.get(ITEM) == ITEM_COLOR);
            check("ItemColors uses it", hex(0xFF654321), hex(itemColors(client).getColor(new ItemStack(ITEM), 0)));
            check("vanilla leather provider visible", true, ColorProviderRegistry.ITEM.get(Items.LEATHER_HELMET) != null);
            ColorProviderRegistry.BLOCK.register(LATE_COLOR, LATE_BLOCK);
            check("late registration applied", hex(0x0A0B0C), hex(client.getBlockColors().getColor(LATE_BLOCK.defaultBlockState(), client.level, pos, 0)));
            check("core shader callback", true, shaderCallbacks > 0);
            check("namespaced core shader loaded", "amber-worldtest:probe", shader == null ? "null" : shader.getName());
            check("its uniform", true, shader != null && shader.getUniform("AmberTint") != null);
            check("vanilla core shader still loads", true, GameRenderer.getPositionShader() != null);
        } catch (Throwable e) { System.out.println("[amber-worldtest] render probe: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-worldtest] render probe total: " + ok + "/" + total + " OK");
    }
}
