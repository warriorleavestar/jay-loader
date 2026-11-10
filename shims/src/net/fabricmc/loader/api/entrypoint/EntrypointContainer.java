/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api.entrypoint;
import net.fabricmc.loader.api.ModContainer;
public interface EntrypointContainer<T> {
    T getEntrypoint();
    ModContainer getProvider();
    default String getDefinition() { return ""; }
}
