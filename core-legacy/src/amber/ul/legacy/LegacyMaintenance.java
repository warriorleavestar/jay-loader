package amber.ul.legacy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interface v1.3.3 maintenance mode on Forge 1.12.2 (core-legacy, Java 8): with {@code -Damber.ul.maintenance=<world>}
 * a daemon thread waits for FML to finish and the main menu to be up (as {@link LegacyQuickPlay}), opens the world in
 * the integrated server exactly as the world list does (LegacyQuickPlay.openWorld: FMLClientHandler.tryLoadExistingWorld with
 * the world's WorldSummary, so it keeps its display name and FML asks its own questions; the game converts older saves itself while
 * loading them), loads the overworld chunks in a square spiral around the world spawn through the server's
 * ChunkProviderServer.provideChunk on the server thread (the spawn area always, then -Damber.ul.maintenance.radius),
 * then leaves the world as "Save and Quit to Title" does and shuts the game down. Same progress lines, progress file
 * and cancel file as the 1.20.1 / 1.21.1 runtime (amber.ul.runtime.MaintenanceRun). SRG names throughout.
 */
public final class LegacyMaintenance {
    private LegacyMaintenance() {}
    static final String PROPERTY = "amber.ul.maintenance", PREFIX = "@amber-ul/progress ", PROGRESS_FILE = "amber-ul-pregen.json";

    static void start(final String encoded) {
        final String world = LegacyQuickPlay.decode(encoded);
        Thread t = new Thread("amber-ul maintenance") {
            @Override public void run() {
                Job j = new Job(world);
                try { j.run(); }
                catch (Throwable e) {
                    Throwable c = e;
                    while ((c instanceof InvocationTargetException || c instanceof ExecutionException) && c.getCause() != null) c = c.getCause();
                    LegacyProblems.report("error", "maintenance_failed", "maintenance of world '" + world + "' failed: " + c, "world", world);
                    progress("failed", 0, 0, world, String.valueOf(c));
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
        System.out.println(PREFIX + sb.append('}')); System.out.flush();
    }

    static final class Job {
        final String world; final File cancel = new File(System.getProperty("amber.ul.maintenance.cancelFile", ".amber-ul-maintenance.cancel"));
        Object mc; Class<?> mcCls;
        Job(String world) { this.world = world; }

        void run() throws Throwable {
            cancel.delete();   // a stale request from an earlier run
            waitForMenu();
            File dir = new File(new File((File) field(mc, "field_71412_D"), "saves"), world);   // mcDataDir
            if (!new File(dir, "level.dat").isFile()) throw new IllegalStateException("no level.dat in " + dir);
            progress("start", 0, 0, world, null);
            progress("open", 0, 1, world, null);
            onClient(new Callable<Object>() { public Object call() throws Exception { String refused = LegacyQuickPlay.openWorld(mc, mcCls, world); if (refused != null) throw new IllegalStateException("world not opened: " + refused); return null; } });
            Object server = waitForServer();
            boolean complete = pregen(server, dir);
            progress(complete ? "done" : "cancelled", 0, 0, world, complete ? null : "pregen stopped; the next maintenance run continues from here");
            quit();
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
                Object screen = field(inst, "field_71462_r");
                if (screen == null || !LegacyQuickPlay.mainMenu(screen)) continue;
                mc = inst; mcCls = m;
                System.out.println("[amber-ul legacy] maintenance: main menu up (" + screen.getClass().getName() + "), opening world " + world);
                return;
            }
            throw new IllegalStateException("no main menu within " + LegacyQuickPlay.WAIT_MS / 1000 + " s");
        }

        Object waitForServer() throws Throwable {
            long t0 = System.currentTimeMillis(), until = t0 + Long.getLong("amber.ul.maintenance.openWaitMs", 30L * 60 * 1000);
            while (System.currentTimeMillis() < until) {
                Thread.sleep(250);
                Object server = mcCls.getMethod("func_71401_C").invoke(mc);                       // getIntegratedServer
                if (server != null && Boolean.TRUE.equals(call(server, "func_71278_l")) && field(mc, "field_71439_g") != null && field(mc, "field_71441_e") != null) {
                    progress("open", 1, 1, world, null);
                    return server;
                }
                Object screen = field(mc, "field_71462_r");
                if (server == null && screen != null && LegacyQuickPlay.mainMenu(screen) && System.currentTimeMillis() - t0 > 60_000)
                    throw new IllegalStateException("the game did not open the world (back on the main menu; see the game log)");
            }
            throw new IllegalStateException("the world was not open within the wait (amber.ul.maintenance.openWaitMs)");
        }

        boolean pregen(final Object server, File dir) throws Throwable {
            int spawnR = Integer.getInteger("amber.ul.maintenance.spawnRadius", 8), userR = Integer.getInteger("amber.ul.maintenance.radius", 0), r = Math.max(spawnR, userR);
            final Object level = server.getClass().getMethod("func_71218_a", int.class).invoke(server, 0);   // getWorld(0): the overworld
            Object spawn = onServer(server, new Callable<Object>() { public Object call() throws Exception { return call0(level, "func_175694_M"); } });   // getSpawnPoint
            int cx = ((Integer) call(spawn, "func_177958_n")) >> 4, cz = ((Integer) call(spawn, "func_177952_p")) >> 4;
            File pf = new File(dir, PROGRESS_FILE);
            long done = 0;
            if (pf.isFile()) {
                String p = new String(Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
                if (num(p, "centerX") == cx && num(p, "centerZ") == cz && p.contains("\"dimension\":\"minecraft:overworld\"")) done = Math.max(0, num(p, "done"));
            }
            long spawnTotal = side(spawnR), total = side(r);
            if (done > 0) progress("resume", Math.min(done, total), total, world, null);
            Spiral s = new Spiral(); for (long i = 0; i < done && i < total; i++) s.next();
            final Object provider = call(level, "func_72863_F");                                  // getChunkProvider: ChunkProviderServer
            final Method provide = method(provider.getClass(), "func_186025_d", 2), unload = method(provider.getClass(), "func_189549_a", 1);
            long lastLine = 0, lastSave = System.currentTimeMillis(), savedAt = done;
            int batch = Integer.getInteger("amber.ul.maintenance.batch", 16);
            while (done < total) {
                if (cancel.exists()) { cancel.delete(); save(server, level, pf, cx, cz, r, done); return false; }
                if (!Boolean.TRUE.equals(call(server, "func_71278_l"))) return false;
                int n = (int) Math.min(batch, (done < spawnTotal ? spawnTotal : total) - done);   // a batch ends exactly at the edge of the spawn area
                final int[][] xs = new int[n][];
                for (int i = 0; i < n; i++) { int[] d = s.next(); xs[i] = new int[] { cx + d[0], cz + d[1] }; }
                onServer(server, new Callable<Object>() { public Object call() throws Exception {
                    for (int[] c : xs) { Object chunk = provide.invoke(provider, c[0], c[1]); if (chunk != null) unload.invoke(provider, chunk); }   // provideChunk, then queueUnload (spawn chunks stay)
                    return null;
                } });
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastLine >= 1000 || done == total || done == spawnTotal) {
                    lastLine = now;
                    if (done <= spawnTotal) progress("spawn", done, spawnTotal, world, null); else progress("pregen", done, total, world, null);
                }
                if (now - lastSave >= 30000 || done - savedAt >= 4096) { save(server, level, pf, cx, cz, r, done); lastSave = now; savedAt = done; }
            }
            save(server, level, pf, cx, cz, r, done);
            return true;
        }

        static long side(int r) { long d = 2L * r + 1; return d * d; }
        static long num(String json, String key) {
            Matcher m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
            return m.find() ? Long.parseLong(m.group(1)) : Long.MIN_VALUE;
        }

        /** Chunks to disk first (saveAllChunks + flushToDisk, as /save-all flush), then the index. */
        void save(Object server, final Object level, File pf, int cx, int cz, int r, long done) throws Throwable {
            onServer(server, new Callable<Object>() { public Object call() throws Exception {
                Method sac = null;
                for (Method m : level.getClass().getMethods()) if (m.getName().equals("func_73044_a") && m.getParameterCount() == 2) sac = m;
                sac.invoke(level, true, null);                                                  // saveAllChunks(all, progress)
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
         */
        void quit() throws Throwable {
            progress("quit", 0, 0, world, null);
            if (mc == null) return;
            onClient(new Callable<Object>() { public Object call() throws Exception {
                mcCls.getMethod("func_71400_g").invoke(mc);                                     // shutdown
                return null;
            } });
        }
        void quitQuietly() { try { quit(); } catch (Throwable e) { System.err.println("[amber-ul legacy] maintenance: could not quit the game: " + e); } }

        Object onClient(Callable<Object> c) throws Throwable { return schedule(mc, c); }
        static Object onServer(Object server, Callable<Object> c) throws Throwable { return schedule(server, c); }
        /** addScheduledTask(Runnable) on the client or the server, and wait for it. */
        static Object schedule(Object owner, final Callable<Object> c) throws Throwable {
            final Object[] out = new Object[1]; final Throwable[] err = new Throwable[1];
            Runnable r = new Runnable() { public void run() { try { out[0] = c.call(); } catch (Throwable e) { err[0] = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e; } } };
            Future<?> f = (Future<?>) owner.getClass().getMethod("func_152344_a", Runnable.class).invoke(owner, r);
            f.get();
            if (err[0] != null) throw err[0];
            return out[0];
        }
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
