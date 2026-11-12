package amber.ul.legacy;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Interface v1.3.3 quick play, singleplayer, on Forge 1.12.2 (which has no quick play of its own): with
 * {@code -Damber.ul.legacy.quickPlay=<world folder>} a daemon thread waits for FML to finish loading and for the main
 * menu to be up (vanilla's GuiMainMenu, a subclass, or a replacement such as CustomMainMenu's GuiCustom in RLCraft),
 * then opens that world on the client thread, the same call the world list makes.
 * <p>
 * It never loads a game class itself (that would run it through the transformers ahead of the game's own order): it only
 * looks at classes LaunchClassLoader has already defined, through its {@code cachedClasses} map. Minecraft is reached by
 * reflection with the 1.12 runtime (SRG) names. If the world cannot be opened the game stays on the main menu and a
 * {@code legacy_quickplay_failed} problem is reported.
 */
public final class LegacyQuickPlay {
    private LegacyQuickPlay() {}
    static final String PROPERTY = "amber.ul.legacy.quickPlay";
    /** Give up waiting for the main menu after this long (huge packs load for minutes). */
    static final long WAIT_MS = Long.getLong("amber.ul.legacy.quickPlayWaitMs", 30L * 60 * 1000);

    static void start(final String world) {
        Thread t = new Thread("amber-ul quick play") {
            @Override public void run() {
                try { waitAndOpen(world); }
                catch (Throwable e) { LegacyProblems.report("error", "legacy_quickplay_failed", "quick play: could not open world '" + world + "': " + e, "world", world); }
            }
        };
        t.setDaemon(true); t.start();
    }

    static void waitAndOpen(final String world) throws Exception {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(250);
            Map<String, Class<?>> loaded = loadedClasses();
            if (loaded == null) continue;
            Class<?> mcCls = loaded.get("net.minecraft.client.Minecraft"), loader = loaded.get("net.minecraftforge.fml.common.Loader"), state = loaded.get("net.minecraftforge.fml.common.LoaderState");
            if (mcCls == null || loader == null || state == null || !loadingDone(loader, state)) continue;
            final Object mc = mcCls.getMethod("func_71410_x").invoke(null);                 // getMinecraft()
            if (mc == null) continue;
            final Field screenF = field(mcCls, "field_71462_r");                             // currentScreen
            Object screen = screenF.get(mc);
            if (screen == null || !mainMenu(screen)) continue;
            System.out.println("[amber-ul legacy] quick play: main menu up (" + screen.getClass().getName() + "), opening world " + world);
            final Method launch = mcCls.getMethod("func_71371_a", String.class, String.class, Class.forName("net.minecraft.world.WorldSettings", false, mcCls.getClassLoader()));   // launchIntegratedServer
            Runnable open = new Runnable() {
                @Override public void run() {
                    try {
                        Object s = screenF.get(mc);
                        if (s != null && !mainMenu(s)) { System.out.println("[amber-ul legacy] quick play: menu changed to " + s.getClass().getName() + ", not opening"); return; }
                        launch.invoke(mc, world, world, null);   // an existing world: its own level.dat gives the name and settings
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        Throwable c = e.getCause();
                        if (c != null && c.getClass().getName().endsWith("StartupQuery$AbortedException")) return;   // the player declined FML's missing-mods question
                        LegacyProblems.report("error", "legacy_quickplay_failed", "quick play: opening world '" + world + "' failed: " + c, "world", world);
                    } catch (Throwable e) {
                        LegacyProblems.report("error", "legacy_quickplay_failed", "quick play: opening world '" + world + "' failed: " + e, "world", world);
                    }
                }
            };
            mcCls.getMethod("func_152344_a", Runnable.class).invoke(mc, open);              // addScheduledTask: on the client thread
            return;
        }
        LegacyProblems.report("warning", "legacy_quickplay_failed", "quick play: no main menu within " + WAIT_MS / 1000 + " s; world '" + world + "' not opened", "world", world);
    }

    /** The main menu: GuiMainMenu or a subclass, or a known replacement (CustomMainMenu's GuiCustom, RLCraft) or any *MainMenu screen. */
    static boolean mainMenu(Object screen) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) if (c.getName().equals("net.minecraft.client.gui.GuiMainMenu")) return true;
        String n = screen.getClass().getName();
        return n.equals("lumien.custommainmenu.gui.GuiCustom") || screen.getClass().getSimpleName().contains("MainMenu");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    static boolean loadingDone(Class<?> loader, Class<?> state) {
        try {
            Object inst = loader.getMethod("instance").invoke(null);
            Object available = Enum.valueOf((Class) state, "AVAILABLE");
            return Boolean.TRUE.equals(loader.getMethod("hasReachedState", state).invoke(inst, available));
        } catch (Throwable e) { return false; }
    }

    /** LaunchClassLoader.cachedClasses of Launch.classLoader, or null before LaunchWrapper has set it up. */
    @SuppressWarnings("unchecked")
    static Map<String, Class<?>> loadedClasses() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch");
            Object cl = launch.getField("classLoader").get(null);
            if (cl == null) return null;
            for (Class<?> c = cl.getClass(); c != null; c = c.getSuperclass()) {
                try { Field f = c.getDeclaredField("cachedClasses"); f.setAccessible(true); return (Map<String, Class<?>>) f.get(cl); }
                catch (NoSuchFieldException ignored) { }
            }
        } catch (Throwable e) { }
        return null;
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
}
