package amber.forgecompat.fml;

import java.io.Serializable;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/** Forge's DistExecutor (removed in NeoForge): run code only on one physical side. */
public final class DistExecutor {
    private DistExecutor() {}
    public interface SafeRunnable extends Runnable, Serializable {}
    public interface SafeCallable<T> extends Callable<T>, Serializable {}
    public interface SafeSupplier<T> extends Supplier<T>, Serializable {}

    static <T> T call(Callable<T> c) { try { return c.call(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); } }

    public static void unsafeRunWhenOn(Dist dist, Supplier<Runnable> toRun) { if (FMLEnvironment.dist == dist) toRun.get().run(); }
    public static void safeRunWhenOn(Dist dist, Supplier<SafeRunnable> toRun) { if (FMLEnvironment.dist == dist) toRun.get().run(); }
    public static <T> T unsafeCallWhenOn(Dist dist, Supplier<Callable<T>> toRun) { return FMLEnvironment.dist == dist ? call(toRun.get()) : null; }
    public static <T> T safeCallWhenOn(Dist dist, Supplier<SafeCallable<T>> toRun) { return FMLEnvironment.dist == dist ? call(toRun.get()) : null; }
    public static <T> T unsafeRunForDist(Supplier<Supplier<T>> client, Supplier<Supplier<T>> server) { return (FMLEnvironment.dist.isClient() ? client : server).get().get(); }
    public static <T> T safeRunForDist(Supplier<SafeSupplier<T>> client, Supplier<SafeSupplier<T>> server) { return (FMLEnvironment.dist.isClient() ? client : server).get().get(); }
    @Deprecated public static void runWhenOn(Dist dist, Supplier<Runnable> toRun) { unsafeRunWhenOn(dist, toRun); }
    @Deprecated public static <T> T callWhenOn(Dist dist, Supplier<Callable<T>> toRun) { return unsafeCallWhenOn(dist, toRun); }
    @Deprecated public static <T> T runForDist(Supplier<Supplier<T>> client, Supplier<Supplier<T>> server) { return unsafeRunForDist(client, server); }
}
