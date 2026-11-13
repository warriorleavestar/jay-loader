package amber.ul.runtime;

import amber.ul.LevelDat;
import amber.ul.RuntimeProblems;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Interface v1.3.3 maintenance mode, runtime side, for 1.20.1 / 1.21.1 (vanilla, NeoForge, Forge): started from the
 * first title screen when {@code -Damber.ul.maintenance=<world>} is set (or, if a mod replaced the title screen so the
 * hook never fires, by a watchdog once the game has finished loading). Everything happens inside the normal client and
 * its integrated server, through the game's own code, reached by reflection in the runtime (Mojmap) names:
 * <ol>
 *   <li>upgrade: if the world's DataVersion is older than the game's, the game's own OptimizeWorldScreen (WorldUpgrader)
 *       runs on it, as "Optimize World" in the world list would;</li>
 *   <li>the world is opened (WorldOpenFlows, as a click in the world list);</li>
 *   <li>spawn + pregen: overworld chunks in a square spiral around the world spawn, loaded through the server's chunk
 *       cache on the server thread (ServerLevel.getChunk), a few per server task. After each batch the chunk cache is
 *       ticked without ticking chunks (ServerChunkCache.tick(() -> true, false): stale tickets purged, unloads processed),
 *       so chunks are unloaded even while the game is paused (pause menu, or the window lost focus); progress is saved in
 *       the world folder after each forced save, so a cancelled or closed run continues where it stopped;</li>
 *   <li>the world is saved and closed and the game quits; if the game is still running long after that, the JVM is halted.</li>
 * </ol>
 * Progress lines: {@code @amber-ul/progress {"type":"progress","schema":"amber-ul/maintenance-progress@1",...}}, pure ASCII
 * JSON, written straight to the process's standard output (not through System.out, which the game redirects into its log).
 */
public final class MaintenanceRun {
    private MaintenanceRun() {}
    public static final String PREFIX = "@amber-ul/progress ";
    static final String PROGRESS_FILE = "amber-ul-pregen.json";
    private static volatile boolean started;
    /** The process's own stdout, UTF-8 (the lines are ASCII anyway), not the game's log4j-redirected System.out. */
    static final PrintStream OUT = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    /** One line, one write(2): other output on the same stdout (the game log) cannot land inside it. */
    static void line(String s) {
        byte[] b = (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (OUT) { OUT.write(b, 0, b.length); OUT.flush(); }
    }

    /** The bake percent-encodes the world folder and paths (UTF-8): ASCII arguments whatever the code page. */
    static String decode(String s) {
        if (s == null) return null;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() && Character.digit(s.charAt(i + 1), 16) >= 0 && Character.digit(s.charAt(i + 2), 16) >= 0) { out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; }
            else out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
        }
        return out.toString(StandardCharsets.UTF_8);
    }
    static String world() { return decode(System.getProperty("amber.ul.maintenance", "")); }

    public static boolean requested() { String w = System.getProperty("amber.ul.maintenance"); return w != null && !w.isEmpty(); }

    /**
     * From Minecraft.&lt;init&gt; (GameHooks.startClient): if a mod replaces the title screen, the title screen hook never fires.
     * Once the game has finished loading (no loading overlay, a screen up, no world) for 15 s without maintenance having
     * started, start it from here.
     */
    public static void watch(Object minecraft) {
        if (!requested() || minecraft == null) return;
        Thread t = new Thread(() -> {
            long idleSince = -1;
            while (!started) {
                try {
                    Thread.sleep(1000);
                    boolean idle = field(minecraft, "overlay") == null && field(minecraft, "screen") != null && field(minecraft, "level") == null;
                    if (!idle) { idleSince = -1; continue; }
                    if (idleSince < 0) idleSince = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - idleSince > 15_000) {
                        System.out.println("[amber-ul] 维护: 没有经过标题画面的钩子 (" + field(minecraft, "screen").getClass().getName() + "), 由看门狗启动");
                        start(minecraft);
                    }
                } catch (Throwable e) { return; }
            }
        }, "amber-ul maintenance watchdog");
        t.setDaemon(true); t.start();
    }

    /** From the title screen hook (client thread): the work runs on its own thread and hands steps to the client / server threads. */
    public static synchronized void start(Object minecraft) {
        if (started || minecraft == null) return;
        started = true;
        Thread t = new Thread(() -> {
            String world = world();
            try { new Job(minecraft, world).run(); }
            catch (Throwable e) {
                Throwable c = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
                if (c instanceof ExecutionException ee && ee.getCause() != null) c = ee.getCause();
                if (c instanceof Cancelled) progress("cancelled", 0, 0, world, c.getMessage());
                else {
                    try { RuntimeProblems.report("error", "maintenance_failed", null, "maintenance of world '" + world + "' failed: " + c, "world", world); } catch (Throwable ignored) { }
                    progress("failed", 0, 0, world, String.valueOf(c));
                }
                quitQuietly(minecraft, world);
            }
        }, "amber-ul maintenance");
        t.setDaemon(true); t.start();
    }

    static void progress(String step, long done, long total, String world, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "progress"); o.addProperty("schema", "amber-ul/maintenance-progress@1"); o.addProperty("stage", "maintenance");
        o.addProperty("step", step); o.addProperty("done", done); o.addProperty("total", total); o.addProperty("world", world);
        if (message != null) o.addProperty("message", message);
        line(PREFIX + ascii(o.toString()));
    }
    /** JSON with every non-ASCII character as a \\uXXXX escape (they only occur inside strings). */
    static String ascii(String json) {
        StringBuilder b = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) { char c = json.charAt(i); if (c < 0x80) b.append(c); else b.append(String.format("\\u%04x", (int) c)); }
        return b.toString();
    }

    /** The user asked to stop (the cancel file): not a failure. */
    static final class Cancelled extends Exception { Cancelled(String m) { super(m); } }
    /** The integrated server stopped under us (the player chose Save and Quit, or the game is closing). */
    static final class ServerGone extends Exception { ServerGone() { super("the world was closed"); } }

    static Path cancelFile() {
        String p = decode(System.getProperty("amber.ul.maintenance.cancelFile"));
        return Path.of(p != null ? p : ".amber-ul-maintenance.cancel");
    }

    /** Leave the world as the pause menu's "Save and Quit to Title" does, then close the game; never throws. */
    static void quitQuietly(Object mc, String world) {
        try { new Job(mc, world).quit(); }
        catch (Throwable e) {
            System.err.println("[amber-ul] 维护: 退出游戏失败: " + e);
            try { call(mc, "stop"); } catch (Throwable ignored) { }
            haltLater();
        }
    }
    /** If the game has not exited a while after quitting (a mod or a stuck server), the JVM is halted: the launcher waits for the exit. */
    static void haltLater() {
        long ms = Long.getLong("amber.ul.maintenance.exitWaitMs", 300_000);
        Thread t = new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
            line("[amber-ul] maintenance: the game did not exit within " + ms / 1000 + " s after quitting; halting the JVM");
            Runtime.getRuntime().halt(1);
        }, "amber-ul maintenance exit watchdog");
        t.setDaemon(true); t.start();
    }

    static final class Job {
        final Object mc; final String world; final ClassLoader cl; final boolean v121;
        final Path cancel = cancelFile();
        Job(Object mc, String world) {
            this.mc = mc; this.world = world; this.cl = mc.getClass().getClassLoader();
            this.v121 = method(mc.getClass(), "disconnect", 0) != null;   // 1.21.1: disconnect(); 1.20.1: clearLevel()
        }

        void run() throws Throwable {
            // a request left by an earlier run is older than this JVM; one made while the game was loading is kept
            try {
                if (Files.exists(cancel) && Files.getLastModifiedTime(cancel).toMillis() < ManagementFactory.getRuntimeMXBean().getStartTime()) Files.deleteIfExists(cancel);
                Files.createDirectories(cancel.toAbsolutePath().getParent());
            } catch (Exception ignored) { }
            checkCancel("before the world was opened");
            if (world.isEmpty() || world.equals(".") || world.equals("..") || world.contains("/") || world.contains("\\") || world.contains(":")) throw new IllegalStateException("not a world folder name: '" + world + "'");
            Object source = call(mc, "getLevelSource");
            Path dir = ((Path) call(source, "getBaseDir")).resolve(world), dat = dir.resolve("level.dat");
            if (!Files.isRegularFile(dat)) throw new IllegalStateException("no level.dat in " + dir);
            String expected = decode(System.getProperty("amber.ul.maintenance.worldDir"));   // the folder the bake checked
            if (expected != null && !Files.isSameFile(dir, Path.of(expected))) throw new IllegalStateException("the game's saves folder is not the one the bake checked: " + dir + " vs " + expected + " (the launch must pass the request's gameDir as --gameDir)");
            progress("start", 0, 0, world, null);
            int gameDv = Integer.getInteger("amber.ul.maintenance.gameDataVersion", -1), dv = LevelDat.dataVersion(dat);
            if (Boolean.parseBoolean(System.getProperty("amber.ul.maintenance.upgrade", "true")) && gameDv > 0 && dv < gameDv) {
                if (!upgrade(source)) throw new Cancelled("upgrade cancelled; the world was not opened");
                System.out.println("[amber-ul] 维护: 世界已升级, DataVersion " + dv + " -> " + LevelDat.dataVersion(dat));
            }
            open();
            Object server = waitForServer();
            boolean complete;
            try { complete = pregen(server, dir); }
            catch (ServerGone e) { complete = false; }
            progress(complete ? "done" : "cancelled", 0, 0, world, complete ? null : "pregen stopped; the next maintenance run continues from here");
            quit();
        }

        void checkCancel(String when) throws Cancelled {
            if (!Files.exists(cancel)) return;
            cancelled();
            throw new Cancelled("cancelled " + when);
        }
        void cancelled() { try { Files.deleteIfExists(cancel); } catch (Exception ignored) { } }

        /** The game's own world upgrade (OptimizeWorldScreen.create, eraseCache false). False when it was cancelled. */
        boolean upgrade(Object source) throws Throwable {
            CompletableFuture<Boolean> result = new CompletableFuture<>();
            Object[] screen = new Object[1], access = new Object[1];
            onClient(() -> {
                Object back = field(mc, "screen");
                access[0] = call(source, "validateAndCreateAccess", world);
                Class<?> bc = Class.forName("it.unimi.dsi.fastutil.booleans.BooleanConsumer", false, cl);
                Object callback = Proxy.newProxyInstance(cl, new Class<?>[] { bc }, (p, m, a) -> {
                    if (m.getName().equals("accept") && a != null && a.length == 1) {
                        // as EditWorldScreen: the screen goes first (its removed() cancels and joins the upgrader), then the lock
                        try { call(mc, "setScreen", back); }
                        finally { try { ((AutoCloseable) access[0]).close(); } catch (Exception ignored) { } }
                        result.complete(Boolean.TRUE.equals(a[0]));
                        return null;
                    }
                    if (m.getName().equals("hashCode")) return System.identityHashCode(p);
                    if (m.getName().equals("equals")) return p == a[0];
                    if (m.getName().equals("toString")) return "amber-ul maintenance callback";
                    throw new UnsupportedOperationException(m.getName());
                });
                Class<?> ows = Class.forName("net.minecraft.client.gui.screens.worldselection.OptimizeWorldScreen", false, cl);
                Object s = staticCall(ows, "create", mc, callback, call(mc, "getFixerUpper"), access[0], false);
                if (s == null) { try { ((AutoCloseable) access[0]).close(); } catch (Exception ignored) { } throw new IllegalStateException("the game could not load the world for upgrading (see the game log)"); }
                screen[0] = s;
                call(mc, "setScreen", s);
                return null;
            });
            Object upgrader = field(screen[0], "upgrader");
            long last = 0, cancelAt = -1;
            while (!result.isDone()) {
                Thread.sleep(250);
                if (cancelAt < 0 && Files.exists(cancel)) { cancelled(); cancelAt = System.currentTimeMillis(); onClient(() -> { call(screen[0], "onClose"); return null; }); }
                if (cancelAt > 0 && System.currentTimeMillis() - cancelAt > 120_000) throw new IllegalStateException("the upgrade did not stop within 120 s of the cancel request");
                if (field(mc, "screen") != screen[0] && !result.isDone() && cancelAt < 0) {
                    Thread.sleep(2000);   // the callback runs right after the screen changes; anything else replaced the screen
                    if (!result.isDone()) throw new IllegalStateException("the upgrade screen was closed without a result");
                }
                if (System.currentTimeMillis() - last >= 1000) {
                    last = System.currentTimeMillis();
                    int total = (Integer) call(upgrader, "getTotalChunks"), done = (Integer) call(upgrader, "getConverted") + (Integer) call(upgrader, "getSkipped");
                    progress("upgrade", done, total, world, String.valueOf(call(call(upgrader, "getStatus"), "getString")));
                }
            }
            boolean ok = result.get();
            if (ok) progress("upgrade", 1, 1, world, "upgraded");
            return ok;
        }

        CompletableFuture<Void> openFailed = new CompletableFuture<>();
        Class<?> openedFrom;
        void open() throws Throwable {
            progress("open", 0, 1, world, null);
            onClient(() -> {
                Object screen = field(mc, "screen");
                openedFrom = screen == null ? null : screen.getClass();
                Object flows = call(mc, "createWorldOpenFlows");
                if (v121) call(flows, "openWorld", world, (Runnable) () -> openFailed.complete(null));
                else call(flows, "loadLevel", screen, world);   // 1.20.1: no failure callback (see waitForServer)
                return null;
            });
        }

        Object waitForServer() throws Throwable {
            long t0 = System.currentTimeMillis(), until = t0 + Long.getLong("amber.ul.maintenance.openWaitMs", 30L * 60 * 1000);
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250);
                if (openFailed.isDone()) throw new IllegalStateException("the game did not open the world (cancelled or failed; see the game log)");
                Object server = call(mc, "getSingleplayerServer");
                if (server != null && Boolean.TRUE.equals(call(server, "isRunning")) && field(mc, "player") != null && field(mc, "level") != null) {
                    progress("open", 1, 1, world, null);
                    return server;
                }
                checkCancel("while the world was opening");
                Object screen = field(mc, "screen");
                if (server == null && screen != null && System.currentTimeMillis() - t0 > 60_000) {
                    String n = screen.getClass().getSimpleName();
                    if (screen.getClass() == openedFrom) throw new IllegalStateException("the game did not open the world (back on " + screen.getClass().getName() + "; see the game log)");
                    if (n.contains("Confirm") || n.contains("Alert") || n.contains("Backup") || n.contains("Error") || n.contains("Failure"))
                        throw new IllegalStateException("the game is asking something before it opens the world (" + screen.getClass().getName() + "); open the world in the game once, then run maintenance again");
                }
            }
            throw new IllegalStateException("the world was not open within the wait (amber.ul.maintenance.openWaitMs)");
        }

        /** The "has more time" of ServerChunkCache.tick: like the game's own, a time limit (with an always-true one ChunkMap.processUnloads
         *  can run forever, an unload task re-queues itself while its chunk's save is still changing). The chunk map still unloads at least
         *  200 chunks per call, and all queued ones above 2000. */
        static BooleanSupplier budget() { long end = System.nanoTime() + 50_000_000L; return () -> System.nanoTime() < end; }
        /** Spawn chunks (always) then the user radius, one square spiral; true when it ran to the end. */
        boolean pregen(Object server, Path dir) throws Throwable {
            int spawnR = Integer.getInteger("amber.ul.maintenance.spawnRadius", 8), userR = Integer.getInteger("amber.ul.maintenance.radius", 0);
            int r = Math.max(spawnR, userR);
            Object level = call(server, "overworld");
            Object chunks = call(level, "getChunkSource");
            Object spawn = submit(server, () -> call(level, "getSharedSpawnPos"));
            int cx = ((Integer) call(spawn, "getX")) >> 4, cz = ((Integer) call(spawn, "getZ")) >> 4;
            Path pf = dir.resolve(PROGRESS_FILE);
            long done = 0;
            try {   // resume: same spiral (same centre) -> continue at the saved index
                JsonObject p = JsonParser.parseString(Files.readString(pf, StandardCharsets.UTF_8)).getAsJsonObject();
                if (p.get("centerX").getAsInt() == cx && p.get("centerZ").getAsInt() == cz && "minecraft:overworld".equals(p.get("dimension").getAsString())) done = Math.max(0, p.get("done").getAsLong());
            } catch (Exception ignored) { }
            long spawnTotal = side(spawnR), total = side(r);
            if (done > 0) { System.out.println("[amber-ul] 维护: 从上次的进度继续, 已完成 " + Math.min(done, total) + " / " + total + " 区块"); progress("resume", Math.min(done, total), total, world, null); }
            Spiral s = new Spiral(); for (long i = 0; i < done && i < total; i++) s.next();
            long lastLine = 0, lastSave = System.currentTimeMillis(), savedAt = done;
            int batch = Integer.getInteger("amber.ul.maintenance.batch", 16), cap = Integer.getInteger("amber.ul.maintenance.maxLoadedChunks", 6000);
            boolean stats = Boolean.getBoolean("amber.ul.maintenance.stats"), debugPause = Boolean.getBoolean("amber.ul.maintenance.debugPause");
            while (done < total) {
                if (Files.exists(cancel)) { cancelled(); save(server, level, pf, cx, cz, r, done); return false; }
                if (!Boolean.TRUE.equals(call(server, "isRunning"))) return false;   // the game was closed: the last saved index stays
                if (debugPause && field(mc, "screen") == null) onClient(() -> { call(mc, "pauseGame", false); return null; });   // tests: as when the window loses focus
                int n = (int) Math.min(batch, (done < spawnTotal ? spawnTotal : total) - done);   // a batch ends exactly at the edge of the spawn area
                int[][] xs = new int[n][]; for (int i = 0; i < n; i++) { int[] d = s.next(); xs[i] = new int[] { cx + d[0], cz + d[1] }; }
                // load the batch, then let the chunk cache drop what nothing holds any more: purges the getChunk tickets
                // (TicketType.UNKNOWN) and processes unloads, which the level tick would do but does not while paused
                int loaded = (Integer) submit(server, () -> {
                    for (int[] c : xs) call(level, "getChunk", c[0], c[1]);
                    call(chunks, "tick", budget(), false);
                    return call(chunks, "getLoadedChunksCount");
                });
                for (int i = 0; loaded > cap && i < 200; i++) { Thread.sleep(25); loaded = (Integer) submit(server, () -> { call(chunks, "tick", budget(), false); return call(chunks, "getLoadedChunksCount"); }); }
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastLine >= 1000 || done == total || done == spawnTotal) {
                    lastLine = now;
                    if (done <= spawnTotal) progress("spawn", done, spawnTotal, world, null);
                    else progress("pregen", done, total, world, null);
                    if (stats) { Runtime rt = Runtime.getRuntime(); line("@amber-ul/maintenance-stats {\"done\":" + done + ",\"loadedChunks\":" + loaded + ",\"heapMB\":" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + ",\"paused\":" + field(server, "paused") + "}"); }
                }
                if (now - lastSave >= 30_000 || done - savedAt >= 4096) { save(server, level, pf, cx, cz, r, done); lastSave = now; savedAt = done; }
            }
            save(server, level, pf, cx, cz, r, done);
            return true;
        }

        static long side(int r) { long d = 2L * r + 1; return d * d; }

        /** Chunks to disk first (forced save, as /save-all flush), then the index: a saved index never runs ahead of the chunks. */
        void save(Object server, Object level, Path pf, int cx, int cz, int r, long done) throws Throwable {
            if (Boolean.TRUE.equals(field(level, "noSave"))) throw new IllegalStateException("saving is turned off in this world (/save-off): the chunks would not reach the disk");
            submit(server, () -> call(server, "saveEverything", true, true, true));
            JsonObject p = new JsonObject();
            p.addProperty("schema", "amber-ul/pregen@1"); p.addProperty("dimension", "minecraft:overworld");
            p.addProperty("centerX", cx); p.addProperty("centerZ", cz); p.addProperty("radius", r); p.addProperty("done", done); p.addProperty("total", side(r));
            p.addProperty("complete", done >= side(r));
            Path tmp = pf.resolveSibling(PROGRESS_FILE + ".tmp");
            Files.writeString(tmp, p + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, pf, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        /** Leave the world as the pause menu's "Save and Quit to Title" does, then close the game. */
        void quit() throws Throwable {
            progress("quit", 0, 0, world, null);
            haltLater();
            onClient(() -> {
                Object level = field(mc, "level");
                if (level != null) {
                    call(level, "disconnect");
                    if (v121) call(mc, "disconnect"); else call(mc, "clearLevel");
                }
                call(mc, "stop");
                return null;
            });
        }

        interface Step { Object run() throws Throwable; }
        Object onClient(Step s) throws Throwable { return submit(mc, s); }
        /**
         * Run on the client or server thread (BlockableEventLoop.execute) and wait. For the server: refused when it is not
         * running, and given up when it stops before the task ran (a task queued on a stopped server never runs).
         */
        static Object submit(Object loop, Step s) throws Throwable {
            boolean server = method(loop.getClass(), "isRunning", 0) != null;
            if (server && !Boolean.TRUE.equals(call(loop, "isRunning"))) throw new ServerGone();
            CompletableFuture<Object> f = new CompletableFuture<>();
            Runnable r = () -> { try { f.complete(s.run()); } catch (Throwable e) { f.completeExceptionally(e instanceof InvocationTargetException i && i.getCause() != null ? i.getCause() : e); } };
            call(loop, "execute", r);
            while (true) {
                try { return f.get(1, TimeUnit.SECONDS); }
                catch (ExecutionException e) { throw e.getCause(); }
                catch (TimeoutException e) { if (server && !Boolean.TRUE.equals(call(loop, "isRunning"))) throw new ServerGone(); }
            }
        }
    }

    /** Square spiral offsets: (0,0), then ring 1, ring 2, ... (a prefix of a bigger spiral is a smaller one). */
    static final class Spiral {
        int x, z, ring, leg, step;
        int[] next() {
            if (ring == 0) { ring = 1; x = -1; z = -1; leg = 0; step = 0; return new int[] { 0, 0 }; }
            int[] out = { x, z };
            int len = 2 * ring;
            switch (leg) { case 0 -> x++; case 1 -> z++; case 2 -> x--; default -> z--; }
            if (++step == len) { step = 0; if (++leg == 4) { leg = 0; ring++; x = -ring; z = -ring; } }
            return out;
        }
    }

    // reflection on the game's runtime (Mojmap) names
    static Method method(Class<?> c, String name, int n) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == n && !m.isBridge()) return m;
        return null;
    }
    static Object call(Object o, String name, Object... a) throws Throwable {
        Method m = find(o.getClass(), name, a);
        try { return m.invoke(o, a); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static Object staticCall(Class<?> c, String name, Object... a) throws Throwable {
        Method m = find(c, name, a);
        try { return m.invoke(null, a); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static Method find(Class<?> c, String name, Object[] a) {
        Method best = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != a.length) continue;
            Class<?>[] p = m.getParameterTypes(); boolean ok = true;
            for (int i = 0; i < p.length && ok; i++) ok = a[i] == null ? !p[i].isPrimitive() : box(p[i]).isInstance(a[i]);
            if (ok && (best == null || best.isBridge())) best = m;
        }
        if (best == null) throw new IllegalStateException("game method " + c.getName() + "." + name + "/" + a.length + " not found");
        best.setAccessible(true);
        return best;
    }
    static Class<?> box(Class<?> p) {
        if (!p.isPrimitive()) return p;
        return p == int.class ? Integer.class : p == boolean.class ? Boolean.class : p == long.class ? Long.class : p == float.class ? Float.class : p == double.class ? Double.class : p == short.class ? Short.class : p == byte.class ? Byte.class : Character.class;
    }
    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }
}
