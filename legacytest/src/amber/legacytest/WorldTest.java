package amber.legacytest;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Test-only Forge 1.12.2 mod (never shipped): on the title screen it creates/opens a singleplayer world "amber-worldtest",
 * then logs AMBER-WORLDTEST markers the harness (tools/run_1122.py --world) times. Minecraft is reached by reflection with
 * SRG names (the 1.12 runtime names), so this compiles against Forge's own classes only.
 */
@Mod(modid = "amberworldtest", name = "Amber world test", version = "1.0", clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class WorldTest {
    private boolean started; private long inWorldAt = -1; private boolean reported;

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            Class<?> mcCls = Class.forName("net.minecraft.client.Minecraft");
            Object mc = mcCls.getMethod("func_71410_x").invoke(null);                       // getMinecraft()
            Object screen = field(mcCls, "field_71462_r").get(mc);                          // currentScreen
            Object player = field(mcCls, "field_71439_g").get(mc);                          // player
            // -Damber.worldtest.open=false: only report (quick play tests: the loader opens the world, nothing clicks the menu)
            if (!started && "false".equals(System.getProperty("amber.worldtest.open"))) {
                started = true; System.out.println("AMBER-WORLDTEST passive (amber.worldtest.open=false)");
            }
            if (!started && screen != null && titleScreen(screen)) {
                started = true;
                System.out.println("AMBER-WORLDTEST title screen, opening world");
                Class<?> gt = Class.forName("net.minecraft.world.GameType"), wt = Class.forName("net.minecraft.world.WorldType"), ws = Class.forName("net.minecraft.world.WorldSettings");
                Object survival = Enum.valueOf(gt.asSubclass(Enum.class), "CREATIVE");   // nothing can kill the player during the 60 s
                Object flat = wt.getMethod("func_77130_a", String.class).invoke(null, "flat");  // WorldType.byName
                Constructor<?> c = ws.getConstructor(long.class, gt, boolean.class, boolean.class, wt);
                Object settings = c.newInstance(1234L, survival, false, false, flat);
                Method launch = mcCls.getMethod("func_71371_a", String.class, String.class, ws); // launchIntegratedServer
                launch.invoke(mc, "amber-worldtest", "amber-worldtest", settings);
            }
            if (player != null && inWorldAt < 0 && (screen == null || !loadingScreen(screen))) { inWorldAt = System.currentTimeMillis(); System.out.println("AMBER-WORLDTEST in world " + worldName(mc)); }
            if (inWorldAt > 0 && !reported && System.currentTimeMillis() - inWorldAt > 60_000) {
                reported = true; System.out.println("AMBER-WORLDTEST in world 60 s, player alive=" + (player != null));
            }
        } catch (Throwable t) {
            if (!reported) { reported = true; System.out.println("AMBER-WORLDTEST failed: " + t); t.printStackTrace(System.out); }
        }
    }

    /** The integrated server's world folder (getIntegratedServer().getFolderName()), for the quick play test. */
    private static String worldName(Object mc) {
        try {
            Object srv = mc.getClass().getMethod("func_71401_C").invoke(mc);
            return srv == null ? "?" : "folder=" + srv.getClass().getMethod("func_71270_I").invoke(srv);
        } catch (Throwable t) { return "?"; }
    }

    /** Screens shown while a world loads (anything else, e.g. the game-over screen, still means the player is in the world). */
    private static boolean loadingScreen(Object screen) {
        String n = screen.getClass().getName();
        return n.endsWith("GuiDownloadTerrain") || n.endsWith("GuiScreenWorking") || titleScreen(screen);
    }

    /** The title screen: vanilla's GuiMainMenu, a subclass, or a replacement such as CustomMainMenu's GuiCustom (RLCraft). */
    private static boolean titleScreen(Object screen) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) if (c.getName().equals("net.minecraft.client.gui.GuiMainMenu")) return true;
        return screen.getClass().getName().equals("lumien.custommainmenu.gui.GuiCustom");
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name); f.setAccessible(true); return f;
    }
}
