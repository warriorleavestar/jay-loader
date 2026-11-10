package amber.fabricforgeprobe;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

/**
 * Fabric-on-Forge bridge probes (Fabric 1.20.1 mod with Fabric API 0.92, run on the Forge 1.20.1 platform next to
 * forgeworldtest, which creates the world). -Damber.ffprobe=interact,sleep,loot,keys,screen,break,item,colors,sts,chunk,hopper,fuel,conditions,dim,dimplayer,model,fluid,net,regsync picks the probes. Ticks counted from the
 * first client tick with a level: 100 server side, 140 client aim, 180 report; screen probe 190 open, 220 keys, 240 close,
 * 250 report (done then).
 */
public class ProbeMod implements ModInitializer, ClientModInitializer {
    static final String WHICH = System.getProperty("amber.ffprobe", "");
    static boolean on(String p) { return ("," + WHICH + ",").contains("," + p + ","); }
    int ticks = -1;

    @Override public void onInitialize() {
        if (on("interact")) InteractProbe.register();
        if (on("sleep")) SleepProbe.register();
        if (on("loot")) LootProbe.register();
        if (on("break")) BreakProbe.register();
        if (on("item")) ItemProbe.register();
        if (on("colors")) ColorsParticlesProbe.registerMain();
        if (on("chunk")) ChunkEventsProbe.register();
        if (on("hopper")) HopperProbe.register();
        if (on("fuel")) FuelProbe.register();
        if (on("net")) NetProbe.register();
        if (on("regsync")) RegSyncProbe.register();
        System.out.println("[amber-ffprobe] main: probes " + WHICH);
    }

    @Override public void onInitializeClient() {
        if (on("keys")) KeyProbe.register();
        if (on("screen")) ScreenProbe.register();
        if (on("colors")) ColorsParticlesProbe.registerClient();
        if (on("sts")) SoundTagShaderProbe.registerClient();
        if (on("chunk")) ChunkEventsProbe.registerClient();
        if (on("model")) ModelProbe.registerClient();
        if (on("net")) NetProbe.registerClient();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null) return;
            ticks++;
            if (ticks == 100) { if (on("interact")) InteractProbe.server(mc); if (on("sleep")) SleepProbe.run(mc.getSingleplayerServer()); if (on("loot")) LootProbe.run(mc.getSingleplayerServer()); if (on("break")) BreakProbe.run(mc.getSingleplayerServer()); if (on("item")) ItemProbe.run(mc.getSingleplayerServer()); if (on("colors")) ColorsParticlesProbe.run(mc); if (on("sts")) SoundTagShaderProbe.run(mc, mc.getSingleplayerServer()); if (on("chunk")) ChunkEventsProbe.run(mc, mc.getSingleplayerServer()); if (on("hopper")) HopperProbe.run(mc.getSingleplayerServer(), mc.player.blockPosition()); if (on("fuel")) FuelProbe.run(mc.getSingleplayerServer()); if (on("conditions")) ConditionsProbe.run(mc.getSingleplayerServer()); if (on("dim")) DimensionProbe.run(mc.getSingleplayerServer()); if (on("model")) ModelProbe.run(mc); if (on("fluid")) FluidProbe.place(mc.getSingleplayerServer(), mc.player.blockPosition()); if (on("keys")) KeyProbe.run(mc); if (on("net")) NetProbe.run(mc, mc.getSingleplayerServer(), mc.player.getUUID()); if (on("regsync")) RegSyncProbe.run(mc, mc.getSingleplayerServer()); }
            if (ticks == 110 && on("sleep")) SleepProbe.later(mc.getSingleplayerServer());
            if (ticks == 100 && on("dimplayer")) DimensionProbe.playerToEnd(mc.getSingleplayerServer(), mc.player.getUUID());
            if (ticks == 140 && on("dimplayer")) DimensionProbe.playerBack(mc.getSingleplayerServer(), mc.player.getUUID());
            if (ticks >= 180 && ticks <= 245 && on("hopper")) HopperProbe.report(mc.getSingleplayerServer(), ticks == 245);
            if (ticks == 140 && on("fluid")) FluidProbe.run(mc);
            if (ticks == 150 && on("fluid")) FluidProbe.clear(mc.getSingleplayerServer());
            if (ticks == 130 && on("chunk")) ChunkEventsProbe.clear(mc.getSingleplayerServer());
            if (ticks == 140 && on("interact")) InteractProbe.aim(mc);
            if (ticks == 180) { if (on("interact")) InteractProbe.report(mc); if (on("sts")) SoundTagShaderProbe.report(mc); if (on("chunk")) ChunkEventsProbe.report(); if (on("net")) NetProbe.report(); if (!on("screen")) System.out.println("[amber-ffprobe] done"); }
            if (on("screen")) {
                if (ticks == 190) ScreenProbe.open(mc);
                if (ticks == 220) ScreenProbe.keys(mc);
                if (ticks == 240) ScreenProbe.close(mc);
                if (ticks == 250) { ScreenProbe.report(); System.out.println("[amber-ffprobe] done"); }
            }
        });
    }
}
