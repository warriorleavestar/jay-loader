package amber.worldtest;

import net.fabricmc.api.ModInitializer;

/** Main entrypoint: registry content for probes (registrations belong in the main entrypoint on Fabric). */
public class ProbeInit implements ModInitializer {
    @Override public void onInitialize() {
        if (Boolean.getBoolean("amber.screenprobe")) ScreenProbe.registerType();
        if (Boolean.getBoolean("amber.fluidprobe")) FluidProbe.register();
        if (Boolean.getBoolean("amber.modelprobe")) ModelProbe.register();
        if (Boolean.getBoolean("amber.itemprobe")) ItemProbe.register();
        if (Boolean.getBoolean("amber.renderprobe")) RenderProbe.register();
        if (Boolean.getBoolean("amber.interactprobe")) InteractProbe.register();
        if (Boolean.getBoolean("amber.particleprobe")) ParticleProbe.register();
        if (Boolean.getBoolean("amber.objectprobe")) ObjectProbe.register();
        if (Boolean.getBoolean("amber.sleepprobe")) SleepProbe.register();
        if (Boolean.getBoolean("amber.terrainprobe")) TerrainProbe.register();
    }
}
