package amber.fabricforgeprobe;

import java.util.Objects;
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.particle.v1.ParticleRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.particle.HeartParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * fabric-rendering-v1 colours and fabric-particles-v1 on Forge (bridge: Block/ItemColorsFabricMixin over Forge's
 * holder-keyed maps, re-based ParticleFactoryRegistryImpl through ParticleEngine.register, TerrainParticleFabricMixin on
 * Forge's areBreakingParticlesTinted; registry-sync's client colour/particle remap mixins vetoed). Client side.
 */
final class ColorsParticlesProbe {
    private ColorsParticlesProbe() {}
    static Block block; static Item item; static SimpleParticleType pending, direct;
    static final BlockColor BLOCK_COLOR = (s, l, p, i) -> 0x123456;
    static FabricSpriteProvider sprites; static int pendingMade, directCalls, tintAsked, ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] colors: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void registerMain() {
        block = Registry.register(BuiltInRegistries.BLOCK, new ResourceLocation("amber_ffprobe", "tinted"), new Block(BlockBehaviour.Properties.copy(Blocks.STONE)));
        item = Registry.register(BuiltInRegistries.ITEM, new ResourceLocation("amber_ffprobe", "tinted"), new BlockItem(block, new Item.Properties()));
        pending = Registry.register(BuiltInRegistries.PARTICLE_TYPE, new ResourceLocation("amber_ffprobe", "probe"), FabricParticleTypes.simple());
        direct = Registry.register(BuiltInRegistries.PARTICLE_TYPE, new ResourceLocation("amber_ffprobe", "direct"), FabricParticleTypes.simple());
    }

    static void registerClient() {
        ColorProviderRegistry.BLOCK.register(BLOCK_COLOR, block);
        ColorProviderRegistry.ITEM.register((stack, i) -> 0x654321, item);
        ParticleFactoryRegistry.getInstance().register(pending, s -> { pendingMade++; sprites = s; return new HeartParticle.Provider(s); });
        ParticleFactoryRegistry.getInstance().register(direct, (type, level, x, y, z, vx, vy, vz) -> { directCalls++; return null; });
        ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT.register((state, level, pos) -> { if (state.is(block)) { tintAsked++; return false; } return true; });
    }

    static void run(Minecraft mc) {
        try {
            check("BlockColors has the Fabric provider", Integer.toHexString(0x123456), Integer.toHexString(mc.getBlockColors().getColor(block.defaultBlockState(), null, null, 0)));
            check("ColorProviderRegistry.BLOCK.get(Fabric block)", true, ColorProviderRegistry.BLOCK.get(block) == BLOCK_COLOR);
            check("ColorProviderRegistry.BLOCK.get(grass) sees vanilla's", true, ColorProviderRegistry.BLOCK.get(Blocks.GRASS_BLOCK) != null);
            check("ItemColors has the Fabric provider", Integer.toHexString(0x654321), Integer.toHexString(mc.getItemColors().getColor(new ItemStack(item), 0)));
            check("ColorProviderRegistry.ITEM.get(Fabric item)", true, ColorProviderRegistry.ITEM.get(item) != null);
            check("pending particle factory created", true, pendingMade > 0);
            Particle p = mc.particleEngine.createParticle(pending, mc.player.getX(), mc.player.getY() + 1, mc.player.getZ(), 0, 0, 0);
            check("Fabric particle made by its provider", "HeartParticle", p == null ? "null" : p.getClass().getSimpleName());
            check("FabricSpriteProvider got the json's sprite", 1, sprites == null ? -1 : sprites.getSprites().size());
            mc.particleEngine.createParticle(direct, mc.player.getX(), mc.player.getY() + 1, mc.player.getZ(), 0, 0, 0);
            check("direct particle provider called", 1, directCalls);
            new TerrainParticle(mc.level, mc.player.getX(), mc.player.getY(), mc.player.getZ(), 0, 0, 0, block.defaultBlockState(), BlockPos.ZERO);
            check("ALLOW_BLOCK_DUST_TINT asked for the Fabric block", true, tintAsked > 0);
        } catch (Throwable e) { System.out.println("[amber-ffprobe] colors: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-ffprobe] colors total: " + ok + "/" + total + " OK");
    }
}
