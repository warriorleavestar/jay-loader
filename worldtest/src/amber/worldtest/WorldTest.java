package amber.worldtest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Written in Mojmap names and remapped to intermediary at build time, so it ships like any other Fabric mod.
 * -Damber.autoworld=<name>: on the first title screen, open that singleplayer world (create it if missing, no EULA/server needed),
 * then report registry sync + time spent in the world. Uses Fabric API events (screen, connection, tick).
 */
public class WorldTest implements ClientModInitializer {
    private boolean started, probed, probeReported, biomeReported, attachServer, attachClient, transferPlaced, transferQueried, contentRan, lootRan, screenOpened, screenReported, recipeRan, fluidPlaced, fluidReported, modelRan, itemRan, renderRan, interactServer, interactAim, interactReported, particleRan, objectServer, objectClient, sleepRan, tagRan, lcPlaced, lcRemoved, lcReported, terrainPlaced, terrainReported; private int lcRemovedAt; private long joinedAt = -1; private int lastReport;

    @Override public void onInitializeClient() {
        String name = System.getProperty("amber.autoworld");
        boolean soundProbe = Boolean.getBoolean("amber.soundprobe"), biomeProbe = Boolean.getBoolean("amber.biomeprobe"),
                attachProbe = Boolean.getBoolean("amber.attachprobe"), transferProbe = Boolean.getBoolean("amber.transferprobe"), contentProbe = Boolean.getBoolean("amber.contentprobe"), lootProbe = Boolean.getBoolean("amber.lootprobe"), screenProbe = Boolean.getBoolean("amber.screenprobe"), recipeProbe = Boolean.getBoolean("amber.recipeprobe"), fluidProbe = Boolean.getBoolean("amber.fluidprobe"), modelProbe = Boolean.getBoolean("amber.modelprobe"), itemProbe = Boolean.getBoolean("amber.itemprobe"), renderProbe = Boolean.getBoolean("amber.renderprobe"), interactProbe = Boolean.getBoolean("amber.interactprobe"), particleProbe = Boolean.getBoolean("amber.particleprobe"), objectProbe = Boolean.getBoolean("amber.objectprobe"), sleepProbe = Boolean.getBoolean("amber.sleepprobe"), tagProbe = Boolean.getBoolean("amber.tagprobe"), lifecycleProbe = Boolean.getBoolean("amber.lifecycleprobe"), terrainProbe = Boolean.getBoolean("amber.terrainprobe");
        if (biomeProbe) BiomeProbe.register();
        if (attachProbe) AttachProbe.register();
        if (transferProbe) TransferProbe.register();
        if (contentProbe) ContentProbe.register();
        if (lootProbe) LootProbe.register();
        if (screenProbe) ScreenProbe.registerScreen();
        if (fluidProbe) FluidProbe.registerClient();
        if (modelProbe) ModelProbe.registerClient();
        if (renderProbe) RenderProbe.registerClient();
        if (particleProbe) ParticleProbe.registerClient();
        if (lifecycleProbe) LifecycleProbe.registerClient();
        if (terrainProbe) TerrainProbe.registerClient();
        if (name == null && !soundProbe && !biomeProbe && !attachProbe && !transferProbe && !contentProbe && !lootProbe && !screenProbe) return;
        // -Damber.worldtest.open=false: another test mod (nfworldtest on NeoForge) opens the world; only report here
        boolean open = name != null && !"false".equals(System.getProperty("amber.worldtest.open"));
        if (open) ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (started || !(screen instanceof TitleScreen)) return;
            started = true;
            client.execute(() -> {
                if (client.getLevelSource().levelExists(name)) {
                    System.out.println("[amber-worldtest] opening existing world " + name);
                    client.createWorldOpenFlows().openWorld(name, () -> client.setScreen(new TitleScreen()));
                } else {
                    System.out.println("[amber-worldtest] creating world " + name);
                    LevelSettings s = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    client.createWorldOpenFlows().createFreshLevel(name, s, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, new TitleScreen());
                }
            });
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            joinedAt = System.currentTimeMillis();
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath("copperhopper", "copper_hopper");
            System.out.println("[amber-worldtest] JOINED world; items=" + BuiltInRegistries.ITEM.size() + " blocks=" + BuiltInRegistries.BLOCK.size()
                    + " copperhopper:copper_hopper item=" + BuiltInRegistries.ITEM.containsKey(id) + " block=" + BuiltInRegistries.BLOCK.containsKey(id)
                    + " (client registry via connection: " + handler.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ITEM).containsKey(id) + ")");
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (joinedAt < 0 || client.level == null) return;
            int secs = (int) ((System.currentTimeMillis() - joinedAt) / 1000);
            if (soundProbe && !probed && secs >= 3) { probed = true; SoundProbe.play(client); }
            if (soundProbe && !probeReported && secs >= 10) { probeReported = true; SoundProbe.report(client); }
            if (biomeProbe && !biomeReported && secs >= 2) { biomeReported = true; BiomeProbe.report(client); }
            if (attachProbe && !attachServer && secs >= 2) { attachServer = true; AttachProbe.server(client); }
            if (attachProbe && !attachClient && secs >= 6) { attachClient = true; AttachProbe.client(client); }
            if (transferProbe && !transferPlaced && secs >= 2) { transferPlaced = true; TransferProbe.place(client); }
            if (transferProbe && !transferQueried && secs >= 4) { transferQueried = true; TransferProbe.query(client); }
            if (contentProbe && !contentRan && secs >= 3) { contentRan = true; ContentProbe.run(client); }
            if (screenProbe && !screenOpened && secs >= 3) { screenOpened = true; ScreenProbe.open(client); }
            if (screenProbe && !screenReported && secs >= 6) { screenReported = true; ScreenProbe.report(client); }
            if (lootProbe && !lootRan && secs >= 3) { lootRan = true; LootProbe.run(client); }
            if (recipeProbe && !recipeRan && secs >= 3) { recipeRan = true; RecipeProbe.run(client); }
            if (fluidProbe && !fluidPlaced && secs >= 3) { fluidPlaced = true; FluidProbe.place(client); }
            if (fluidProbe && !fluidReported && secs >= 7) { fluidReported = true; FluidProbe.report(client); }
            if (modelProbe && !modelRan && secs >= 3) { modelRan = true; ModelProbe.run(client); }
            if (itemProbe && !itemRan && secs >= 3) { itemRan = true; ItemProbe.run(client); }
            if (renderProbe && !renderRan && secs >= 3) { renderRan = true; RenderProbe.run(client); }
            if (particleProbe && !particleRan && secs >= 4) { particleRan = true; ParticleProbe.run(client); }
            if (lifecycleProbe && !lcPlaced && secs >= 3) { lcPlaced = true; LifecycleProbe.place(client); }
            // fresh worlds: the integrated server can lag seconds behind; wait for both chests on the client (block entities,
            // independent of the events under test), at most until 15 s
            if (lifecycleProbe && lcPlaced && !lcRemoved && secs >= 5 && (LifecycleProbe.onClient(client) || secs >= 15)) { lcRemoved = true; lcRemovedAt = secs; LifecycleProbe.removeA(client); }
            if (lifecycleProbe && lcRemoved && !lcReported && secs >= lcRemovedAt + 2) { lcReported = true; LifecycleProbe.report(client); }
            if (terrainProbe && !terrainPlaced && secs >= 3) { terrainPlaced = true; TerrainProbe.place(client); }
            if (terrainProbe && !terrainReported && secs >= 9 && (TerrainProbe.meshed() || secs >= 20)) { terrainReported = true; TerrainProbe.report(client); }
            if (tagProbe && !tagRan && secs >= 3) { tagRan = true; TagProbe.run(client.getSingleplayerServer()); }
            if (sleepProbe && !sleepRan && secs >= 3) { sleepRan = true; SleepProbe.run(client.getSingleplayerServer()); }
            if (objectProbe && !objectServer && secs >= 3) { objectServer = true; ObjectProbe.server(client); }
            if (objectProbe && !objectClient && secs >= 5) { objectClient = true; ObjectProbe.client(client); }
            if (interactProbe && !interactServer && secs >= 3) { interactServer = true; InteractProbe.server(client); }
            if (interactProbe && !interactAim && secs >= 6) { interactAim = true; InteractProbe.aim(client); }
            if (interactProbe && !interactReported && secs >= 9) { interactReported = true; InteractProbe.report(client); }
            if (secs >= lastReport + 15) {
                lastReport = secs;
                System.out.println("[amber-worldtest] in world " + secs + " s, fps=" + client.getFps() + ", loaded chunks=" + client.level.getChunkSource().getLoadedChunksCount()
                        + ", integrated server running=" + (client.getSingleplayerServer() != null && client.getSingleplayerServer().isRunning()));
            }
        });
    }
}
