package amber.ul.fabricbridge.mixin.fapi;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * fabric-sound-api-v1 re-base (brief §6.9). Fabric's SoundSystemMixin redirects SoundBufferLibrary.getStream inside
 * SoundEngine.play; NeoForge replaced that call with SoundInstance.getStream(library, sound, looping) (a default method a
 * NeoForge mod may override), so Fabric's mixin is vetoed and this one sits on NeoForge's call instead: an instance whose
 * class overrides FabricSoundInstance.getAudioStream streams through it, every other instance keeps NeoForge's path.
 * (SoundInstanceMixin, which makes every SoundInstance a FabricSoundInstance, still applies.)
 */
@Mixin(value = SoundEngine.class, remap = false)
public abstract class SoundEngineStreamMixin {
    @Unique private static final Map<Class<?>, Boolean> amber$fabricStream = new ConcurrentHashMap<>();

    @Redirect(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", remap = false, at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraft/client/resources/sounds/SoundInstance;getStream(Lnet/minecraft/client/sounds/SoundBufferLibrary;Lnet/minecraft/client/resources/sounds/Sound;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> amber$stream(SoundInstance instance, SoundBufferLibrary library, Sound sound, boolean looping) {
        if (amber$overridesAudioStream(instance.getClass())) return ((FabricSoundInstance) instance).getAudioStream(library, sound.getPath(), looping);
        return instance.getStream(library, sound, looping);
    }

    @Unique private static boolean amber$overridesAudioStream(Class<?> c) {
        return amber$fabricStream.computeIfAbsent(c, k -> {
            try {
                Method m = k.getMethod("getAudioStream", SoundBufferLibrary.class, ResourceLocation.class, boolean.class);
                return m.getDeclaringClass() != FabricSoundInstance.class;
            } catch (NoSuchMethodException e) { return false; }
        });
    }
}
