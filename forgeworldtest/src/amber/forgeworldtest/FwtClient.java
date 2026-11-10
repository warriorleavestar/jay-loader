package amber.forgeworldtest;

import net.minecraft.client.Minecraft;
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
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

final class FwtClient {
    private static boolean started; private static long joinedAt = -1; private static int lastReport; private static long lastScreenLog; private static int ticks; private static boolean dumped;
    static void init(String name) {
        if (name == null) return;
        MinecraftForge.EVENT_BUS.addListener((ScreenEvent.Init.Post e) -> {
            if (started || !(e.getScreen() instanceof TitleScreen)) return;
            started = true;
            Minecraft mc = Minecraft.getInstance();
            String world = name + "-" + System.currentTimeMillis();
            System.out.println("[amber-fwt] title screen; creating world " + world);
            mc.execute(() -> {
                LevelSettings s = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(world, s, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions);
            });
        });
        MinecraftForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> {
            joinedAt = System.currentTimeMillis();
            StringBuilder sb = new StringBuilder("[amber-fwt] JOINED world; items=" + BuiltInRegistries.ITEM.size() + " blocks=" + BuiltInRegistries.BLOCK.size());
            for (String id : new String[]{"create:cogwheel", "quark:ancient_planks", "farmersdelight:cooking_pot", "supplementaries:jar", "ironchest:iron_chest", "sophisticatedbackpacks:backpack", "waystones:waystone", "amber_fft:test_block"}) {
                ResourceLocation rl = new ResourceLocation(id);
                sb.append(" | ").append(id).append(" item=").append(BuiltInRegistries.ITEM.containsKey(rl)).append(" block=").append(BuiltInRegistries.BLOCK.containsKey(rl));
            }
            System.out.println(sb);
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            ticks++;
            if (joinedAt < 0 && System.currentTimeMillis() - lastScreenLog > 5000) {   // where are we (tick events alive, which screen)
                lastScreenLog = System.currentTimeMillis();
                System.out.println("[amber-fwt] tick " + ticks + ", screen=" + (mc.screen == null ? null : mc.screen.getClass().getName()) + ", overlay=" + (mc.getOverlay() == null ? null : mc.getOverlay().getClass().getName()));
            }
            if (!dumped && mc.getOverlay() == null && mc.screen != null && mc.screen.getClass().getName().equals("net.minecraftforge.client.gui.LoadingErrorScreen")) {
                dumped = true;   // report what FML collected, then carry on to the title screen as the screen's "proceed" button does
                for (String f : new String[]{"modLoadErrors", "modLoadWarnings"}) try {
                    java.lang.reflect.Field fl = mc.screen.getClass().getDeclaredField(f); fl.setAccessible(true);
                    for (Object o : (java.util.List<?>) fl.get(mc.screen)) {
                        String msg; try { msg = (String) o.getClass().getMethod("formatToString").invoke(o); } catch (ReflectiveOperationException x) { msg = String.valueOf(o); }
                        System.out.println("[amber-fwt] FML " + f + ": " + msg);
                        if (o instanceof Throwable t && t.getCause() != null) { System.out.println("[amber-fwt]   cause:"); t.getCause().printStackTrace(System.out); }
                    }
                } catch (ReflectiveOperationException x) { x.printStackTrace(System.out); }
                mc.setScreen(new TitleScreen());
            }
            if (!started && mc.getOverlay() == null && mc.screen instanceof TitleScreen) {   // fallback when ScreenEvent.Init never reached us
                started = true;
                String world = name + "-" + System.currentTimeMillis();
                System.out.println("[amber-fwt] title screen (tick); creating world " + world);
                LevelSettings s = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(world, s, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions);
            }
            if (joinedAt < 0 || mc.level == null) return;
            int secs = (int) ((System.currentTimeMillis() - joinedAt) / 1000);
            if (secs >= lastReport + 10) {
                lastReport = secs;
                if (secs >= 10 && !fabricChecked) { fabricChecked = true; checkFabricBlock(mc); if (Boolean.getBoolean("amber.forgeworldtest.loot") && mc.getSingleplayerServer() != null) FwtLoot.check(mc.getSingleplayerServer(), mc.player.blockPosition()); }
                System.out.println("[amber-fwt] in world " + secs + " s, fps=" + mc.getFps() + ", loaded chunks=" + mc.level.getChunkSource().getLoadedChunksCount()
                        + ", integrated server running=" + (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isRunning()));
            }
        });
    }

    static boolean fabricChecked;

    /** Fabric-on-Forge test (fabricforgetest): the Fabric-registered block is in Forge's registry with a network id, and the server can place and read it back. */
    static void checkFabricBlock(Minecraft mc) {
        ResourceLocation rl = new ResourceLocation("amber_fft", "test_block");
        if (!BuiltInRegistries.BLOCK.containsKey(rl) || mc.getSingleplayerServer() == null || mc.player == null) return;
        net.minecraft.world.level.block.Block b = BuiltInRegistries.BLOCK.get(rl);
        net.minecraft.core.BlockPos pos = mc.player.blockPosition().offset(-3, 4, 3);
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var level = server.overworld();
            boolean set = level.setBlock(pos, b.defaultBlockState(), 3);
            var back = level.getBlockState(pos).getBlock();
            System.out.println("[amber-fwt] FABRIC block " + rl + ": forge registry=" + net.minecraftforge.registries.ForgeRegistries.BLOCKS.containsKey(rl)
                    + " forgeKey=" + net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(b) + " id=" + BuiltInRegistries.BLOCK.getId(b)
                    + " item=" + net.minecraftforge.registries.ForgeRegistries.ITEMS.containsKey(rl) + " placed=" + set + " readback=" + (back == b));
        });
    }
}
