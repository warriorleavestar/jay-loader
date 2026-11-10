/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;
import java.util.function.BiConsumer;
/** Same API as Fabric's: a global key/value store mods use to share objects without hard dependencies. */
public interface ObjectShare {
    Object get(String key);
    void whenAvailable(String key, BiConsumer<String, Object> consumer);
    Object put(String key, Object value);
    Object putIfAbsent(String key, Object value);
    Object remove(String key);
}
