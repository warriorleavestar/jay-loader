package amber.ul.runtime;

import java.lang.management.ManagementFactory;
import java.util.function.Consumer;

/** Targets of the calls the game bake injects (see amber-ul/hooks/&lt;mc&gt;.json). */
public final class GameHooks {
    public enum Event { CLIENT_MAIN, START_CLIENT, START_SERVER, TITLE_SCREEN }
    private static volatile Consumer<Event> listener = e -> {};
    private static volatile Object gameInstance;
    private static boolean titleSeen, clientStarted, serverStarted;
    public static void listen(Consumer<Event> l) { listener = l; }
    public static Object gameInstance() { return gameInstance; }

    /** Head of the client main class. */
    public static void clientMain() { mark("client Main.main"); listener.accept(Event.CLIENT_MAIN); }
    /** Minecraft.&lt;init&gt; right after the run directory is set (Fabric's client entrypoint location). */
    public static synchronized void startClient(Object minecraft) {
        if (clientStarted) return;
        clientStarted = true; gameInstance = minecraft;
        mark("Minecraft.<init> (main+client 入口点)"); listener.accept(Event.START_CLIENT);
    }
    /** Dedicated server Main, after Bootstrap. */
    public static synchronized void startServer() {
        if (serverStarted) return;
        serverStarted = true; mark("server Main (main+server 入口点)"); listener.accept(Event.START_SERVER);
    }
    public static synchronized void titleScreen() {
        if (titleSeen) return;
        titleSeen = true; mark("TitleScreen.init"); listener.accept(Event.TITLE_SCREEN);
    }
    public static long sinceJvmStart() { return System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime(); }
    static void mark(String what) { System.out.printf("[amber-ul] @%d ms (自 JVM 启动) %s%n", sinceJvmStart(), what); }
}
