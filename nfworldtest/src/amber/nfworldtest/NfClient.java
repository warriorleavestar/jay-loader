package amber.nfworldtest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client half of NfWorldTest (title screen auto-world / auto-join, join report, in-world ticks). Only loaded on Dist.CLIENT. */
final class NfClient {
    private static boolean started, transferQueried; private static long joinedAt = -1; private static int lastReport;
    static void init(String name, String join, String serve) {
        if (name == null && join == null) return;
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, e -> {
            if (started || !(e.getScreen() instanceof TitleScreen)) return;
            started = true;
            Minecraft mc = Minecraft.getInstance();
            RegProbe.report("local");
            if (serve != null) mc.options.pauseOnLostFocus = false;   // the host keeps ticking while the joining client has focus
            if (join != null) {
                System.out.println("[amber-nfworldtest] joining " + join);
                mc.execute(() -> ConnectScreen.startConnecting(e.getScreen(), mc, ServerAddress.parseString(join), new ServerData("amber-regtest", join, ServerData.Type.OTHER), false, null));
                return;
            }
            mc.execute(() -> {
                if (mc.getLevelSource().levelExists(name)) {
                    System.out.println("[amber-nfworldtest] opening existing world " + name);
                    mc.createWorldOpenFlows().openWorld(name, () -> mc.setScreen(new TitleScreen()));
                } else {
                    System.out.println("[amber-nfworldtest] creating world " + name);
                    LevelSettings s = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel(name, s, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, new TitleScreen());
                }
            });
        });
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, e -> {
            joinedAt = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("[amber-nfworldtest] JOINED world; items=" + BuiltInRegistries.ITEM.size() + " blocks=" + BuiltInRegistries.BLOCK.size());
            for (String id : new String[]{"amber_nfworldtest:probe", "torchmaster:megatorch", "ironfurnaces:iron_furnace"}) {
                ResourceLocation rl = ResourceLocation.parse(id);
                sb.append(" | ").append(id).append(" item=").append(BuiltInRegistries.ITEM.containsKey(rl)).append(" block=").append(BuiltInRegistries.BLOCK.containsKey(rl))
                  .append(" synced=").append(e.getPlayer().connection.registryAccess().registryOrThrow(Registries.ITEM).containsKey(rl));
            }
            System.out.println(sb);
            RegProbe.report(Minecraft.getInstance().getSingleplayerServer() != null ? "client-host" : "client-remote");
        });
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            if (joinedAt < 0 || mc.level == null) return;
            int secs = (int) ((System.currentTimeMillis() - joinedAt) / 1000);
            if (!transferQueried && secs >= 6 && Boolean.getBoolean("amber.transferprobe")) { transferQueried = true; TransferProbe.query(mc.getSingleplayerServer()); }
            if (secs >= lastReport + NfWorldTest.REPORT_EVERY.get()) {
                lastReport = secs;
                if (Boolean.getBoolean("amber.autotravel") && mc.player != null) {   // force new chunk generation: fly 400 blocks east each report
                    if (secs < 2 * NfWorldTest.REPORT_EVERY.get()) mc.player.connection.sendCommand("gamemode spectator");
                    mc.player.connection.sendCommand("tp @s ~400 120 ~");
                }
                System.out.println("[amber-nfworldtest] in world " + secs + " s, fps=" + mc.getFps() + ", loaded chunks=" + mc.level.getChunkSource().getLoadedChunksCount()
                        + ", x=" + (mc.player == null ? 0 : (int) mc.player.getX()) + ", integrated server running=" + (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isRunning()));
            }
        });
    }
}
