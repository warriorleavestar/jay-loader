package amber.ul.legacy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interface v1.3.3 maintenance mode on Forge 1.12.2 (core-legacy, Java 8): with {@code -Damber.ul.maintenance=<world>}
 * a daemon thread waits for FML to finish and the main menu to be up (as {@link LegacyQuickPlay}), opens the world in
 * the integrated server exactly as the world list does (LegacyQuickPlay.openWorld: FMLClientHandler.tryLoadExistingWorld with
 * the world's WorldSummary, so it keeps its display name and FML asks its own questions; the game converts older saves itself while
 * loading them), loads the overworld chunks in a square spiral around the world spawn through the server's
 * ChunkProviderServer.provideChunk on the server thread (the spawn area always, then -Damber.ul.maintenance.radius),
 * then shuts the game down. Same progress lines, progress file and cancel file as the 1.20.1 / 1.21.1 runtime
 * (amber.ul.runtime.MaintenanceRun). SRG names throughout.
 * <p>
 * Chunks are unloaded by a sliding window: a chunk is queued for unloading (queueUnload, then ChunkProviderServer.tick,
 * which the world tick would call but does not while the game is paused) once all eight of its neighbours have been
 * generated. 1.12.2 populates (trees, ores, structures) a chunk only while its +x/+z neighbours are loaded, so this way
 * every chunk inside the radius is populated and the loaded set stays a band around the spiral's edge. Chunks the player
 * can see (PlayerChunkMap) are left alone.
 */
public final class LegacyMaintenance {
    private LegacyMaintenance() {}
    static final String PROPERTY = "amber.ul.maintenance", PREFIX = "@amber-ul/progress ", PROGRESS_FILE = "amber-ul-pregen.json";
    /** The process's own stdout (not System.out, which FML redirects into its log), ASCII lines. */
    static final PrintStream OUT;
    /** One line, one write(2): other output on the same stdout (the game log) cannot land inside it. */
    static void line(String s) {
        byte[] b = (s + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        synchronized (OUT) { OUT.write(b, 0, b.length); OUT.flush(); }
    }
    static {
        PrintStream p;
        try { p = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8"); } catch (UnsupportedEncodingException e) { p = System.out; }
        OUT = p;
    }

    static void start(final String encoded) {
        final String world = LegacyQuickPlay.decode(encoded);
        Thread t = new Thread("amber-ul maintenance") {
            @Override public void run() {
                Job j = new Job(world);
                try { j.run(); }
                catch (Throwable e) {
                    Throwable c = e;
                    while ((c instanceof InvocationTargetException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
                    if (c instanceof Cancelled) progress("cancelled", 0, 0, world, c.getMessage());
                    else {
                        LegacyProblems.report("error", "maintenance_failed", "maintenance of world '" + world + "' failed: " + c, "world", world);
                        progress("failed", 0, 0, world, String.valueOf(c));
                    }
                    j.quitQuietly();
                }
            }
        };
        t.setDaemon(true); t.start();
    }

    static void progress(String step, long done, long total, String world, String message) {
        StringBuilder sb = new StringBuilder("{\"type\":\"progress\",\"schema\":\"amber-ul/maintenance-progress@1\",\"stage\":\"maintenance\",\"step\":")
                .append(LegacyProblems.str(step)).append(",\"done\":").append(done).append(",\"total\":").append(total).append(",\"world\":").append(LegacyProblems.str(world));
        if (message != null) sb.append(",\"message\":").append(LegacyProblems.str(message));
        line(PREFIX + ascii(sb.append('}').toString()));
    }
    /** JSON with every non-ASCII character as a \\uXXXX escape (they only occur inside strings). */
    static String ascii(String json) {
        StringBuilder b = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) { char c = json.charAt(i); if (c < 0x80) b.append(c); else b.append(String.format("\\u%04x", (int) c)); }
        return b.toString();
    }

    static final class Cancelled extends Exception { Cancelled(String m) { super(m); } }
    /** The integrated server stopped under us (Save and Quit, or the game is closing). */
    static final class ServerGone extends Exception { ServerGone() { super("the world was closed"); } }

    static File cancelFile() {
        String p = System.getProperty("amber.ul.maintenance.cancelFile");
        return new File(p != null ? LegacyQuickPlay.decode(p) : ".amber-ul-maintenance.cancel");
    }

    static final class Job {
        final String world; final File cancel = cancelFile();
        Object mc; Class<?> mcCls;
        Job(String world) { this.world = world; }

        void run() throws Throwable {
            // a request left by an earlier run is older than this JVM; one made while the game was loading is kept
            if (cancel.exists() && cancel.lastModified() < ManagementFactory.getRuntimeMXBean().getStartTime()) cancel.delete();
            File parent = cancel.getAbsoluteFile().getParentFile();
            if (parent != null) parent.mkdirs();
            if (world.isEmpty() || world.equals(".") || world.equals("..") || world.contains("/") || world.contains("\\") || world.contains(":")) throw new IllegalStateException("not a world folder name: '" + world + "'");
            waitForMenu();
            checkCancel("before the world was opened");
            File dir = new File(new File((File) field(mc, "field_71412_D"), "saves"), world);   // mcDataDir
            if (!new File(dir, "level.dat").isFile()) throw new IllegalStateException("no level.dat in " + dir);
            String expected = System.getProperty("amber.ul.maintenance.worldDir");   // the folder the bake checked
            if (expected != null && !Files.isSameFile(dir.toPath(), new File(LegacyQuickPlay.decode(expected)).toPath()))
                throw new IllegalStateException("the game's saves folder is not the one the bake checked: " + dir + " vs " + LegacyQuickPlay.decode(expected) + " (the launch must pass the request's gameDir as --gameDir)");
            progress("start", 0, 0, world, null);
            progress("open", 0, 1, world, null);
            onClient(new Callable<Object>() { public Object call() throws Exception { String refused = LegacyQuickPlay.openWorld(mc, mcCls, world); if (refused != null) throw new IllegalStateException("world not opened: " + refused); return null; } });
            Object server = waitForServer();
            boolean complete;
            try { complete = pregen(server, dir); }
            catch (ServerGone e) { complete = false; }
            progress(complete ? "done" : "cancelled", 0, 0, world, complete ? null : "pregen stopped; the next maintenance run continues from here");
            quit();
        }

        void checkCancel(String when) throws Cancelled {
            if (!cancel.exists()) return;
            cancel.delete();
            throw new Cancelled("cancelled " + when);
        }

        void waitForMenu() throws Exception {
            long until = System.currentTimeMillis() + LegacyQuickPlay.WAIT_MS;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250);
                Map<String, Class<?>> loaded = LegacyQuickPlay.loadedClasses();
                if (loaded == null) continue;
                Class<?> m = loaded.get("net.minecraft.client.Minecraft"), loader = loaded.get("net.minecraftforge.fml.common.Loader"), state = loaded.get("net.minecraftforge.fml.common.LoaderState");
                if (m == null || loader == null || state == null || !LegacyQuickPlay.loadingDone(loader, state)) continue;
                Object inst = m.getMethod("func_71410_x").invoke(null);
                if (inst == null) continue;
                mc = inst; mcCls = m;   // from here on quit() can close the game
                Object screen = field(inst, "field_71462_r");
                if (screen == null || !LegacyQuickPlay.mainMenu(screen)) continue;
                System.out.println("[amber-ul legacy] maintenance: main menu up (" + screen.getClass().getName() + "), opening world " + world);
                return;
            }
            throw new IllegalStateException("no main menu within " + LegacyQuickPlay.WAIT_MS / 1000 + " s");
        }

        Object waitForServer() throws Throwable {
            long t0 = System.currentTimeMillis(), until = t0 + Long.getLong("amber.ul.maintenance.openWaitMs", 30L * 60 * 1000), askedSince = -1;
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250);
                Object server = mcCls.getMethod("func_71401_C").invoke(mc);                       // getIntegratedServer
                if (server != null && Boolean.TRUE.equals(call(server, "func_71278_l")) && field(mc, "field_71439_g") != null && field(mc, "field_71441_e") != null) {
                    progress("open", 1, 1, world, null);
                    return server;
                }
                checkCancel("while the world was opening");
                Object screen = field(mc, "field_71462_r");
                if (server == null && screen != null && LegacyQuickPlay.mainMenu(screen) && System.currentTimeMillis() - t0 > 60_000)
                    throw new IllegalStateException("the game did not open the world (back on the main menu; see the game log)");
                // FML asks the player (StartupQuery: missing mods or blocks, an old save) and the server thread waits for the answer
                if (pendingQuery() != null) {
                    if (askedSince < 0) askedSince = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - askedSince > 60_000)
                        throw new IllegalStateException("FML is asking something before it opens the world (" + (screen == null ? "?" : screen.getClass().getName()) + "); open the world in the game once, then run maintenance again");
                } else askedSince = -1;
            }
            throw new IllegalStateException("the world was not open within the wait (amber.ul.maintenance.openWaitMs)");
        }

        boolean pregen(final Object server, File dir) throws Throwable {
            int spawnR = Integer.getInteger("amber.ul.maintenance.spawnRadius", 8), userR = Integer.getInteger("amber.ul.maintenance.radius", 0), r = Math.max(spawnR, userR);
            final Object level = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);   // getWorld(0): the overworld
            Object spawn = onServer(server, new Callable<Object>() { public Object call() throws Exception { return call0(level, "func_175694_M"); } });   // getSpawnPoint
            final int cx = ((Integer) call(spawn, "func_177958_n")) >> 4, cz = ((Integer) call(spawn, "func_177952_p")) >> 4;
            File pf = new File(dir, PROGRESS_FILE);
            long done = 0;
            if (pf.isFile()) {
                String p = new String(Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                if (num(p, "centerX") == cx && num(p, "centerZ") == cz && p.contains("\"dimension\":\"minecraft:overworld\"")) done = Math.max(0, num(p, "done"));
            }
            long spawnTotal = side(spawnR), total = side(r);
            if (done > 0) progress("resume", Math.min(done, total), total, world, null);
            final Object provider = call(level, "func_72863_F");                                  // getChunkProvider: ChunkProviderServer
            final Method provide = method(provider.getClass(), "func_186025_d", 2), unload = method(provider.getClass(), "func_189549_a", 1),   // provideChunk, queueUnload
                    tick = method(provider.getClass(), "func_73156_b", 0), count = method(provider.getClass(), "func_73152_e", 0);         // tick (unloads), getLoadedChunkCount
            final Object players = call(level, "func_184164_w");                                  // getPlayerChunkMap
            final Method seen = method(players.getClass(), "func_152621_a", 2);                   // PlayerChunkMap.contains(x, z)
            final Method loadedAt = method(provider.getClass(), "func_186026_b", 2);              // getLoadedChunk (also takes the chunk out of the unload queue)
            final long[] reloaded = { 0 };
            // the sliding window: loaded chunks of this run, each released once the spiral index passed all its neighbours
            final PriorityQueue<Object[]> held = new PriorityQueue<Object[]>(64, (a, b) -> Long.compare((Long) a[0], (Long) b[0]));
            Spiral s = new Spiral();
            long reloadFrom = done > 0 ? Math.max(0, index(-(ring(done) - 1), -(ring(done) - 1))) : done;   // on resume: the ring before and what is done of this one
            final List<int[]> reload = new ArrayList<int[]>();
            for (long i = 0; i < done && i < total; i++) { int[] d = s.next(); if (i >= reloadFrom) reload.add(new int[] { cx + d[0], cz + d[1] }); }
            if (!reload.isEmpty()) onServer(server, new Callable<Object>() { public Object call() throws Exception {
                for (int[] c : reload) { Object chunk = provide.invoke(provider, c[0], c[1]); if (chunk != null) held.add(new Object[] { release(c[0] - cx, c[1] - cz), chunk, c[0], c[1] }); }
                return null;
            } });
            long lastLine = 0, lastSave = System.currentTimeMillis(), savedAt = done;
            int batch = Integer.getInteger("amber.ul.maintenance.batch", 16), cap = Integer.getInteger("amber.ul.maintenance.maxLoadedChunks", 30000);
            boolean stats = Boolean.getBoolean("amber.ul.maintenance.stats"), debugPause = Boolean.getBoolean("amber.ul.maintenance.debugPause");
            while (done < total) {
                if (cancel.exists()) { cancel.delete(); save(server, level, pf, cx, cz, r, done); return false; }
                if (!Boolean.TRUE.equals(call(server, "func_71278_l"))) return false;
                if (debugPause && field(mc, "field_71462_r") == null) onClient(new Callable<Object>() { public Object call() throws Exception { return call0(mc, "func_71385_j"); } });   // tests: displayInGameMenu, as when the window loses focus
                int n = (int) Math.min(batch, (done < spawnTotal ? spawnTotal : total) - done);   // a batch ends exactly at the edge of the spawn area
                final int[][] xs = new int[n][];
                for (int i = 0; i < n; i++) { int[] d = s.next(); xs[i] = new int[] { cx + d[0], cz + d[1] }; }
                final long doneAfter = done + n;
                int loaded = (Integer) onServer(server, new Callable<Object>() { public Object call() throws Exception {
                    // WorldServer.saveAllChunks (our saves, "Saving and pausing game", the autosave) queues every chunk no player sees for unloading,
                    // the window too: take them out of the queue again, and load back any the game already unloaded, before new chunks are generated
                    for (Object[] h : held) {
                        Object c = loadedAt.invoke(provider, (Integer) h[2], (Integer) h[3]);
                        if (c == null) { c = provide.invoke(provider, (Integer) h[2], (Integer) h[3]); reloaded[0]++; }
                        h[1] = c;
                    }
                    for (int[] c : xs) { Object chunk = provide.invoke(provider, c[0], c[1]); if (chunk != null) held.add(new Object[] { release(c[0] - cx, c[1] - cz), chunk, c[0], c[1] }); }
                    int queued = 0;
                    while (!held.isEmpty() && (Long) held.peek()[0] <= doneAfter) {
                        Object[] h = held.poll();
                        if (!Boolean.TRUE.equals(seen.invoke(players, (Integer) h[2], (Integer) h[3]))) { unload.invoke(provider, h[1]); queued++; }   // queueUnload (the spawn area stays: canDropChunk)
                    }
                    for (int i = 0; i <= queued / 100; i++) tick.invoke(provider);                  // up to 100 unloads per call
                    return count.invoke(provider);
                } });
                for (int i = 0; loaded > cap && i < 50; i++) { Thread.sleep(25); loaded = (Integer) onServer(server, new Callable<Object>() { public Object call() throws Exception { tick.invoke(provider); return count.invoke(provider); } }); }
                done = doneAfter;
                long now = System.currentTimeMillis();
                if (now - lastLine >= 1000 || done == total || done == spawnTotal) {
                    lastLine = now;
                    if (done <= spawnTotal) progress("spawn", done, spawnTotal, world, null); else progress("pregen", done, total, world, null);
                    if (stats) { Runtime rt = Runtime.getRuntime(); line("@amber-ul/maintenance-stats {\"done\":" + done + ",\"loadedChunks\":" + loaded + ",\"heapMB\":" + (rt.totalMemory() - rt.freeMemory()) / 1048576 + ",\"paused\":" + field(server, "field_71348_o") + ",\"reloaded\":" + reloaded[0] + "}"); }
                }
                if (now - lastSave >= 30000 || done - savedAt >= 4096) { save(server, level, pf, cx, cz, r, done); lastSave = now; savedAt = done; }
            }
            save(server, level, pf, cx, cz, r, done);
            return true;
        }

        static long side(int r) { long d = 2L * r + 1; return d * d; }
        /** The ring of spiral index i (index 0 is ring 0; ring k holds the indices side(k-1) .. side(k)-1). */
        static int ring(long i) { int k = 0; while (side(k) <= i) k++; return k; }
        /** The spiral index of offset (dx, dz): the inverse of Spiral.next(). */
        static long index(int dx, int dz) {
            int k = Math.max(Math.abs(dx), Math.abs(dz));
            if (k == 0) return 0;
            long base = side(k - 1);
            if (dz == -k && dx < k) return base + (dx + k);                 // leg 0: z = -k, x from -k up
            if (dx == k && dz < k) return base + 2L * k + (dz + k);         // leg 1: x = k, z from -k up
            if (dz == k && dx > -k) return base + 4L * k + (k - dx);        // leg 2: z = k, x from k down
            return base + 6L * k + (k - dz);                                // leg 3: x = -k, z from k down
        }
        /** The spiral position after which every neighbour of (dx, dz) exists: from then on all its population squares are done. */
        static long release(int dx, int dz) {
            long m = 0;
            for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) m = Math.max(m, index(dx + i, dz + j));
            return m + 1;
        }
        static long num(String json, String key) {
            Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
            return m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
        }

        /** Chunks to disk first (saveAllChunks + flushToDisk, as /save-all flush), then the index. */
        void save(Object server, final Object level, File pf, int cx, int cz, int r, long done) throws Throwable {
            if (Boolean.TRUE.equals(field(level, "field_73058_d"))) throw new IllegalStateException("saving is turned off in this world (/save-off): the chunks would not reach the disk");   // disableLevelSaving
            final Method sac = method2(level.getClass(), "func_73044_a");                      // saveAllChunks(all, progress)
            onServer(server, new Callable<Object>() { public Object call() throws Exception {
                sac.invoke(level, true, null);
                call0(level, "func_104140_m");                                                  // flushToDisk
                return null;
            } });
            String json = "{\"schema\":\"amber-ul/pregen@1\",\"dimension\":\"minecraft:overworld\",\"centerX\":" + cx + ",\"centerZ\":" + cz + ",\"radius\":" + r
                    + ",\"done\":" + done + ",\"total\":" + side(r) + ",\"complete\":" + (done >= side(r)) + "}\n";
            File tmp = new File(pf.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
            Files.move(tmp.toPath(), pf.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        /**
         * As closing the game window while in a world: Minecraft.shutdown() ends the game loop, and shutdownMinecraftApplet
         * then calls loadWorld(null), so the integrated server saves everything and stops before the JVM exits. (Not
         * GuiIngameMenu's sendQuittingDisconnectingPacket + loadWorld(null) from here: the server may see the disconnect and
         * start stopping by itself first, and loadWorld's initiateShutdown then waits forever for a task it never runs.)
         * A pending FML question is answered "no" first (its server thread would otherwise wait forever, and so would the
         * client in loadWorld(null)). If the game is still running a while later, the JVM is halted.
         */
        void quit() throws Throwable {
            progress("quit", 0, 0, world, null);
            haltLater();
            if (mc == null) {   // failed before the main menu was seen: find the game anyway
                Map<String, Class<?>> loaded = LegacyQuickPlay.loadedClasses();
                Class<?> m = loaded == null ? null : loaded.get("net.minecraft.client.Minecraft");
                if (m != null) { mcCls = m; mc = m.getMethod("func_71410_x").invoke(null); }
                if (mc == null) return;
            }
            Object q = pendingQuery();
            if (q != null) { q.getClass().getMethod("setResult", boolean.class).invoke(q, false); q.getClass().getMethod("finish").invoke(q); }
            onClient(new Callable<Object>() { public Object call() throws Exception {
                mcCls.getMethod("func_71400_g").invoke(mc);                                     // shutdown
                return null;
            } });
        }
        void quitQuietly() { try { quit(); } catch (Throwable e) { System.err.println("[amber-ul legacy] maintenance: could not quit the game: " + e); haltLater(); } }

        /** FML's StartupQuery waiting for the player's answer, or null. */
        Object pendingQuery() {
            try {
                Class<?> c = Class.forName("net.minecraftforge.fml.common.StartupQuery", false, mcCls.getClassLoader());
                Field f = c.getDeclaredField("pending"); f.setAccessible(true);
                return f.get(null);
            } catch (Throwable e) { return null; }
        }

        Object onClient(Callable<Object> c) throws Throwable { return schedule(mc, c, false); }
        static Object onServer(Object server, Callable<Object> c) throws Throwable { return schedule(server, c, true); }
        /**
         * addScheduledTask(Runnable) on the client or the server, and wait for it. For the server: refused when it is not
         * running; never run inline (1.12.2 runs a task on the caller's thread once the server has stopped); given up
         * when the server stops before the task ran.
         */
        static Object schedule(Object owner, final Callable<Object> c, boolean server) throws Throwable {
            if (server && !Boolean.TRUE.equals(call(owner, "func_71278_l"))) throw new ServerGone();
            final Object[] out = new Object[1]; final Throwable[] err = new Throwable[1];
            final Thread caller = Thread.currentThread();
            Runnable r = new Runnable() { public void run() {
                if (Thread.currentThread() == caller) { err[0] = new ServerGone(); return; }
                try { out[0] = c.call(); } catch (Throwable e) { err[0] = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e; }
            } };
            Future<?> f = (Future<?>) owner.getClass().getMethod("func_152344_a", Runnable.class).invoke(owner, r);
            while (true) {
                try { f.get(1, TimeUnit.SECONDS); break; }
                catch (TimeoutException e) { if (server && !Boolean.TRUE.equals(call(owner, "func_71278_l"))) throw new ServerGone(); }
            }
            if (err[0] != null) throw err[0];
            return out[0];
        }
    }

    static void haltLater() {
        final long ms = Long.getLong("amber.ul.maintenance.exitWaitMs", 300000L);
        Thread t = new Thread("amber-ul maintenance exit watchdog") {
            @Override public void run() {
                try { Thread.sleep(ms); } catch (InterruptedException e) { return; }
                line("[amber-ul legacy] maintenance: the game did not exit within " + ms / 1000 + " s after quitting; halting the JVM");
                Runtime.getRuntime().halt(1);
            }
        };
        t.setDaemon(true); t.start();
    }

    /** Square spiral offsets, the same order as amber.ul.runtime.MaintenanceRun.Spiral (a progress file works on both). */
    static final class Spiral {
        int x, z, ring, leg, step;
        int[] next() {
            if (ring == 0) { ring = 1; x = -1; z = -1; leg = 0; step = 0; return new int[] { 0, 0 }; }
            int[] out = { x, z };
            int len = 2 * ring;
            switch (leg) { case 0: x++; break; case 1: z++; break; case 2: x--; break; default: z--; }
            if (++step == len) { step = 0; if (++leg == 4) { leg = 0; ring++; x = -ring; z = -ring; } }
            return out;
        }
    }

    static Method method(Class<?> c, String name, int n) {
        for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == n) return m;
        throw new IllegalStateException("game method " + c.getName() + "." + name + "/" + n + " not found");
    }
    static Method method2(Class<?> c, String name) { return method(c, name, 2); }
    static Object call(Object o, String name) throws Throwable {
        try { return method(o.getClass(), name, 0).invoke(o); } catch (InvocationTargetException e) { throw e.getCause(); }
    }
    static Object call0(Object o, String name) throws Exception { return method(o.getClass(), name, 0).invoke(o); }
    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }
}
