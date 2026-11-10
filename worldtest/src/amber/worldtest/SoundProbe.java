package amber.worldtest;

import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * fabric-sound-api-v1 probe (-Damber.soundprobe=true): plays a streamed sound (a music disc, near-silent) whose instance
 * overrides FabricSoundInstance.getAudioStream, and reports whether the sound engine streamed through the override.
 * On Fabric that is Fabric's own SoundSystemMixin; on NeoForge it is the bridge's re-base onto SoundInstance.getStream.
 */
final class SoundProbe {
    static volatile int calls; static volatile String id;
    static void play(Minecraft client) {
        ResourceLocation loc = SoundEvents.MUSIC_DISC_CAT.value().getLocation();
        SoundInstance s = new Probe(loc);
        // the test game dir runs with master volume 0, and SoundEngine.play returns early on listener gain 0: raise the
        // engine's gain (not the saved options) to an inaudible level for the probe, put it back in report()
        client.getSoundManager().updateSourceVolume(SoundSource.MASTER, 1e-4f);
        client.getSoundManager().play(s);
        System.out.println("[amber-worldtest] sound probe: played " + loc + " (FabricSoundInstance override)");
    }
    static void report(Minecraft client) {
        client.getSoundManager().updateSourceVolume(SoundSource.MASTER, client.options.getSoundSourceVolume(SoundSource.MASTER));
        System.out.println("[amber-worldtest] sound probe: getAudioStream calls=" + calls + " id=" + id + " -> " + (calls > 0 ? "OK" : "NOT CALLED"));
    }
    static final class Probe extends SimpleSoundInstance implements FabricSoundInstance {
        Probe(ResourceLocation loc) { super(loc, SoundSource.MASTER, 0.01f, 1f, RandomSource.create(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true); }
        @Override public boolean canStartSilent() { return true; }   // the test game dir runs with master volume 0
        @Override public CompletableFuture<AudioStream> getAudioStream(SoundBufferLibrary loader, ResourceLocation path, boolean repeatInstantly) {
            calls++; id = String.valueOf(path);
            return loader.getStream(path, repeatInstantly);
        }
    }
}
