package amber.ul.fabricbridge;

import amber.ul.runtime.UniversalClassLoader;

/** Class presence without loading: also sees classes the loader serves from overrides (amber-impl replacements). */
public final class BridgeEnv {
    private BridgeEnv() {}
    public static boolean has(String internalName) {
        ClassLoader cl = BridgeEnv.class.getClassLoader();
        if (cl instanceof UniversalClassLoader u) return u.classBytes(internalName) != null;
        return cl.getResource(internalName + ".class") != null;
    }
}
