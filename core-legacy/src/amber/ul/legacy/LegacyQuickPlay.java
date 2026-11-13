package amber.ul.legacy;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Interface v1.3.3 quick play, singleplayer, on Forge 1.12.2 (which has no quick play of its own): with
 * {@code -Damber.ul.legacy.quickPlay=<world folder, percent-encoded UTF-8>} a daemon thread waits for FML to finish
 * loading and for the main menu to be up (vanilla's GuiMainMenu, a subclass, or CustomMainMenu's GuiCustom as in RLCraft),
 * then opens that world on the client thread the way a click in the world list does (GuiListWorldSelectionEntry.joinWorld):
 * the world's WorldSummary from the save list; if the game would ask first (WorldSummary.askToOpenWorld: a world saved by
 * a newer or snapshot version) or cannot load it (ISaveFormat.canLoadWorld), it is not opened and the game stays on the
 * menu with a warning, since nobody is there to answer; otherwise FMLClientHandler.tryLoadExistingWorld, which keeps the
 * world's own display name (level.dat LevelName is not rewritten) and asks FML's own questions (an old save without FML
 * data, missing mods).
 * <p>
 * Before the menu is up it only looks at classes LaunchClassLoader has already defined (its {@code cachedClasses} map), so
 * nothing is loaded ahead of the game's own order. Minecraft is reached by reflection with the 1.12 runtime (SRG) names.
 * If the world cannot be opened the game stays on the main menu and a {@code legacy_quickplay_failed} problem is reported.
 */
public final class LegacyQuickPlay {
    private LegacyQuickPlay() {}
    static final String PROPERTY = "amber.ul.legacy.quickPlay";
    /** Give up waiting for the main menu after this long (huge packs load for minutes). */
    static final long WAIT_MS = Long.getLong("amber.ul.legacy.quickPlayWaitMs", 30L * 60 * 1000);

    static void start(final String encoded) {
        final String world = decode(encoded);
        Thread t = new Thread("amber-ul quick play") {
            @Override public void run() {
                try { waitAndOpen(world); }
                catch (Throwable e) { LegacyProblems.report("error", "legacy_quickplay_failed", "quick play: could not open world '" + world + "': " + e, "world", world); }
            }
        };
        t.setDaemon(true); t.start();
    }

    /** The bake's percent-encoded UTF-8 (the JVM argument stays ASCII whatever the code page). */
    static String decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && hex(s.charAt(i + 1)) >= 0 && hex(s.charAt(i + 2)) >= 0) { out.write(hex(s.charAt(i + 1)) * 16 + hex(s.charAt(i + 2))); i += 2; }
            else { byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8); out.write(b, 0, b.length); }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    private static int hex(char c) { return c >= '0' && c <= '9' ? c - '0' : c >= 'A' && c <= 'F' ? c - 'A' + 10 : c >= 'a' && c <= 'f' ? c - 'a' + 10 : -1; }

    static void waitAndOpen(final String world) throws Exception {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            Thread.sleep(250);
            Object[] up = menuUp();
            if (up == null) continue;
            final Object mc = up[0]; final Class<?> mcCls = (Class<?>) up[1];
            System.out.println("[amber-ul legacy] quick play: main menu up (" + up[2] + "), opening world " + world);
            Runnable open = new Runnable() {
                @Override public void run() {
                    try {
                        Object s = field(mc, "field_71462_r");
                        if (s == null || !mainMenu(s)) { System.out.println("[amber-ul legacy] quick play: not on the main menu any more (" + (s == null ? "in game" : s.getClass().getName()) + "), not opening"); return; }
                        String refused = openWorld(mc, mcCls, world);
                        if (refused != null) LegacyProblems.report("warning", "legacy_quickplay_failed", "quick play: world '" + world + "' not opened: " + refused, "world", world);
                    } catch (Throwable e) {
                        Throwable c = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                        LegacyProblems.report("error", "legacy_quickplay_failed", "quick play: opening world '" + world + "' failed: " + c, "world", world);
                    }
                }
            };
            mcCls.getMethod("func_152344_a", Runnable.class).invoke(mc, open);              // addScheduledTask: on the client thread
            return;
        }
        LegacyProblems.report("warning", "legacy_quickplay_failed", "quick play: no main menu within " + WAIT_MS / 1000 + " s; world '" + world + "' not opened", "world", world);
    }

    /** {Minecraft, its class, the menu's class name} once FML is done and the main menu is the current screen; else null. */
    static Object[] menuUp() throws Exception {
        Map<String, Class<?>> loaded = loadedClasses();
        if (loaded == null) return null;
        Class<?> mcCls = loaded.get("net.minecraft.client.Minecraft"), loader = loaded.get("net.minecraftforge.fml.common.Loader"), state = loaded.get("net.minecraftforge.fml.common.LoaderState");
        if (mcCls == null || loader == null || state == null || !loadingDone(loader, state)) return null;
        Object mc = mcCls.getMethod("func_71410_x").invoke(null);                            // getMinecraft()
        if (mc == null) return null;
        Object screen = field(mc, "field_71462_r");                                           // currentScreen
        if (screen == null || !mainMenu(screen)) return null;
        return new Object[] { mc, mcCls, screen.getClass().getName() };
    }

    /**
     * On the client thread: open an existing world as the world list does — its WorldSummary from getSaveLoader().getSaveList()
     * to FMLClientHandler.tryLoadExistingWorld(new GuiWorldSelection(current screen), summary). The summary carries the
     * world's own display name, so level.dat keeps its LevelName. Null when it was handed to FML; else why it was not
     * opened (the world list would have asked first, or cannot load it).
     */
    static String openWorld(Object mc, Class<?> mcCls, String folder) throws Exception {
        ClassLoader cl = mcCls.getClassLoader();
        Object saves = mcCls.getMethod("func_71359_d").invoke(mc);                              // getSaveLoader()
        Object summary = null;
        for (Object s : (List<?>) method(saves.getClass(), "func_75799_b", 0).invoke(saves))   // getSaveList()
            if (folder.equals(method(s.getClass(), "func_75786_a", 0).invoke(s))) { summary = s; break; }   // getFileName()
        if (summary == null) throw new IllegalStateException("world folder '" + folder + "' is not in the game's save list");
        // the world list's own checks before it loads a world (GuiListWorldSelectionEntry.joinWorld / loadWorld)
        if (Boolean.TRUE.equals(method(summary.getClass(), "func_186356_m", 0).invoke(summary)))   // askToOpenWorld
            return "the game asks before opening it (saved by a newer or snapshot version); open it from the world list";
        if (!Boolean.TRUE.equals(method(saves.getClass(), "func_90033_f", 1).invoke(saves, folder))) return "the game cannot load it (canLoadWorld false)";   // canLoadWorld
        Class<?> selCls = Class.forName("net.minecraft.client.gui.GuiWorldSelection", true, cl), screenCls = Class.forName("net.minecraft.client.gui.GuiScreen", false, cl);
        Object sel = selCls.getConstructor(screenCls).newInstance(field(mc, "field_71462_r"));
        Class<?> fml = Class.forName("net.minecraftforge.fml.client.FMLClientHandler", true, cl);
        Object handler = fml.getMethod("instance").invoke(null);
        Method load = null;
        for (Method m : fml.getMethods()) if (m.getName().equals("tryLoadExistingWorld") && m.getParameterCount() == 2) load = m;
        if (load == null) throw new IllegalStateException("FMLClientHandler.tryLoadExistingWorld not found");
        load.invoke(handler, sel, summary);
        return null;
    }

    /** The main menu: GuiMainMenu or a subclass, or CustomMainMenu's GuiCustom (RLCraft and other packs replace the menu with it). */
    static boolean mainMenu(Object screen) {
        for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) if (c.getName().equals("net.minecraft.client.gui.GuiMainMenu")) return true;
        return screen.getClass().getName().equals("lumien.custommainmenu.gui.GuiCustom");
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

    static Method method(Class<?> c, String name, int n) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == n) return m;
        throw new IllegalStateException("game method " + c.getName() + "." + name + "/" + n + " not found");
    }
    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }
}
