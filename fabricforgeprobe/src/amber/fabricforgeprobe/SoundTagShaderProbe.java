package amber.fabricforgeprobe;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.fabricmc.fabric.api.tag.v1.FabricTagFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Group 7 probes on Forge: fabric-sound-api-v1 (a FabricSoundInstance overriding getAudioStream is streamed through it;
 * bridge: SoundEngineStreamFabricMixin on Forge's SoundInstance.getStream), fabric-tag-api-v1 ("fabric:remove" in tag
 * JSON; bridge: TagFileFabricMixin onto Forge's own "remove"), fabric-rendering-v1 shaders (CoreShaderRegistrationCallback
 * with a namespaced core shader, stages and #moj_import; Forge 47 handles the namespaces itself, Fabric's two shader
 * mixins are vetoed).
 */
final class SoundTagShaderProbe {
    private SoundTagShaderProbe() {}
    static volatile int soundCalls; static volatile String soundId;
    static ShaderInstance shader; static int shaderCallbacks;
    static int ok, total;

    static void check(String what, Object expected, Object got) {
        total++;
        boolean pass = Objects.equals(String.valueOf(expected), String.valueOf(got));
        if (pass) ok++;
        System.out.println("[amber-ffprobe] sts: " + (pass ? "OK   " : "FAIL ") + what + " expected=" + expected + " got=" + got);
    }

    static void registerClient() {
        CoreShaderRegistrationCallback.EVENT.register(ctx -> ctx.register(new ResourceLocation("amber_ffprobe", "probe"), DefaultVertexFormat.POSITION, s -> { shader = s; shaderCallbacks++; }));
    }

    static boolean in(Item item, TagKey<Item> tag) { return BuiltInRegistries.ITEM.wrapAsHolder(item).is(tag); }

    /** tick 100: tags (server thread), shaders, start the sound. */
    static void run(Minecraft mc, MinecraftServer server) {
        server.execute(() -> {
            try {
                TagKey<Item> own = TagKey.create(Registries.ITEM, new ResourceLocation("amber_ffprobe", "probe_removal"));
                TagKey<Item> sherds = TagKey.create(Registries.ITEM, new ResourceLocation("minecraft", "decorated_pot_sherds"));
                check("tag: own tag keeps stone", true, in(Items.STONE, own));
                check("tag: own tag keeps gravel", true, in(Items.GRAVEL, own));
                check("tag: own tag fabric:remove dropped dirt", false, in(Items.DIRT, own));
                check("tag: vanilla tag keeps archer sherd", true, in(Items.ARCHER_POTTERY_SHERD, sherds));
                check("tag: vanilla tag fabric:remove dropped angler sherd", false, in(Items.ANGLER_POTTERY_SHERD, sherds));
                Object file = net.minecraft.tags.TagFile.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString("{\"values\":[\"minecraft:stone\"],\"fabric:remove\":[\"minecraft:dirt\"]}")).result().orElseThrow();
                check("tag: TagFile is a FabricTagFile", true, file instanceof FabricTagFile);
                check("tag: parsed removal entries", 1, file instanceof FabricTagFile f ? f.remove().size() : -1);
            } catch (Throwable e) { System.out.println("[amber-ffprobe] sts: crashed (tag) " + e); e.printStackTrace(System.out); }
        });
        try {
            check("shader: core shader callback", true, shaderCallbacks > 0);
            check("shader: namespaced core shader loaded", "amber_ffprobe:probe", shader == null ? "null" : shader.getName());
            check("shader: its uniform", true, shader != null && shader.getUniform("AmberTint") != null);
            check("shader: vanilla core shader still loads", true, GameRenderer.getPositionShader() != null);
            // the test game dir runs with master volume 0 and SoundEngine.play returns early on listener gain 0: raise the
            // engine's gain (not the saved options) to an inaudible level, put it back in report()
            mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, 1e-4f);
            mc.getSoundManager().play(new Probe(SoundEvents.MUSIC_DISC_CAT.getLocation()));
        } catch (Throwable e) { System.out.println("[amber-ffprobe] sts: crashed " + e); e.printStackTrace(System.out); }
    }

    /** tick 180. */
    static void report(Minecraft mc) {
        mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, mc.options.getSoundSourceVolume(SoundSource.MASTER));
        check("sound: getAudioStream override streamed", true, soundCalls > 0);
        check("sound: its path", "minecraft:sounds/records/cat.ogg", soundId);
        System.out.println("[amber-ffprobe] sts total: " + ok + "/" + total + " OK");
    }

    static final class Probe extends SimpleSoundInstance implements FabricSoundInstance {
        Probe(ResourceLocation loc) { super(loc, SoundSource.MASTER, 0.01f, 1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true); }
        @Override public boolean canStartSilent() { return true; }
        @Override public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation path, boolean repeatInstantly) {
            soundCalls++; soundId = String.valueOf(path);
            return loader.getStream(path, repeatInstantly);
        }
    }
}
