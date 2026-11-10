package amber.ul.personality;

import amber.ul.model.*;
import amber.ul.runtime.UniversalClassLoader;
import java.util.List;

/**
 * A personality makes mods of one ecosystem feel at home: it knows that ecosystem's entrypoints and lifecycle.
 * Everything else (discovery, deps, remap, access rules, class loading, caches) is shared core.
 * Phases are driven by the game hooks (see amber-ul/hooks/<mc>.json), mods are passed in global dependency order.
 */
public interface Personality {
    enum Side { CLIENT, SERVER }
    boolean accepts(LoaderKind kind);
    /** Before the game's main class runs (Fabric "preLaunch"). */
    default void preLaunch(List<ModInfo> mods, UniversalClassLoader cl) throws Exception {}
    /** Client: inside the Minecraft constructor; dedicated server: after Bootstrap in server Main. */
    void start(Side side, List<ModInfo> mods, UniversalClassLoader cl) throws Exception;
}
