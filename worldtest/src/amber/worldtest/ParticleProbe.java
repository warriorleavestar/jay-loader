package amber.worldtest;

import java.util.Objects;
import net.fabricmc.fabric.api.client.particle.v1.FabricSpriteProvider;
import net.fabricmc.fabric.api.client.particle.v1.ParticleFactoryRegistry;
import net.fabricmc.fabric.api.client.particle.v1.ParticleRenderEvents;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * fabric-particles-v1 probe (-Damber.particleprobe=true): ParticleFactoryRegistry with a plain provider and with a
 * pending (sprite-aware) factory, both registered before the particle engine exists and reached through
 * ParticleEngine.createParticle in the world; the pending factory's FabricSpriteProvider sees the particle atlas and the
 * sprites from assets/amber-worldtest/particles/particle_probe_sprite.json. ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT:
 * oak-leaves dust is tinted by default and untinted where a listener vetoes it.
 */
final class ParticleProbe {
    private ParticleProbe() {}
    static SimpleParticleType DIRECT, SPRITE;
    static int directCalls, spriteCalls, tintAsked, ok, total; static FabricSpriteProvider sprites; static BlockPos vetoPos;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-worldtest] particle probe: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void register() {
        DIRECT = Registry.register(BuiltInRegistries.PARTICLE_TYPE, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "particle_probe_direct"), FabricParticleTypes.simple());
        SPRITE = Registry.register(BuiltInRegistries.PARTICLE_TYPE, ResourceLocation.fromNamespaceAndPath("amber-worldtest", "particle_probe_sprite"), FabricParticleTypes.simple());
    }

    static void registerClient() {
        ParticleFactoryRegistry.getInstance().register(DIRECT, (type, level, x, y, z, dx, dy, dz) -> { directCalls++; return null; });
        ParticleFactoryRegistry.getInstance().register(SPRITE, provider -> { sprites = provider; return (type, level, x, y, z, dx, dy, dz) -> { spriteCalls++; return null; }; });
        ParticleRenderEvents.ALLOW_BLOCK_DUST_TINT.register((state, level, pos) -> { if (state.is(Blocks.OAK_LEAVES)) tintAsked++; return !pos.equals(vetoPos); });
    }

    /** Exposes the protected colour fields; vanilla's public constructor, so the same class works on both platforms. */
    static final class Dust extends TerrainParticle {
        Dust(ClientLevel level, BlockPos pos, BlockState state) { super(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 0, 0, 0, state, pos); }
        String rgb() { return rCol + "," + gCol + "," + bCol; }
    }

    static void run(Minecraft client) {
        try {
            client.particleEngine.createParticle(DIRECT, 0, 100, 0, 0, 0, 0);
            client.particleEngine.createParticle(SPRITE, 0, 100, 0, 0, 0, 0);
            check("direct provider reached by createParticle", 1, directCalls);
            check("pending factory's provider reached by createParticle", 1, spriteCalls);
            check("pending factory got a FabricSpriteProvider", true, sprites != null);
            if (sprites != null) {
                check("sprite provider atlas is the particle atlas", "minecraft:textures/atlas/particles.png", sprites.getAtlas().location());
                check("sprite provider has the json's 2 sprites", 2, sprites.getSprites().size());
                check("first sprite is minecraft:flame", "minecraft:flame", sprites.getSprites().get(0).contents().name());
                check("random sprite comes from the set", true, sprites.getSprites().contains(sprites.get(client.level.random)));
            }
            BlockPos at = client.player.blockPosition(); vetoPos = at.east(3);
            BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
            String tinted = new Dust(client.level, at.west(3), leaves).rgb(), vetoed = new Dust(client.level, vetoPos, leaves).rgb();
            System.out.println("[amber-worldtest] particle probe: leaves dust rgb tinted=" + tinted + " vetoed=" + vetoed);
            check("ALLOW_BLOCK_DUST_TINT asked for leaves", 2, tintAsked);
            check("leaves dust tinted by default", false, tinted.equals("0.6,0.6,0.6"));
            check("vetoed leaves dust untinted", "0.6,0.6,0.6", vetoed);
        } catch (Throwable e) { System.out.println("[amber-worldtest] particle probe: crashed " + e); e.printStackTrace(System.out); }
        System.out.println("[amber-worldtest] particle probe total: " + ok + "/" + total + " OK");
    }
}
