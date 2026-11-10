/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.loader.api.metadata.*;
public interface ModContainer {
    ModMetadata getMetadata();
    List<Path> getRootPaths();
    ModOrigin getOrigin();
    Optional<ModContainer> getContainingMod();
    Collection<ModContainer> getContainedMods();
    default Optional<Path> findPath(String file) { for (Path r : getRootPaths()) { Path p = r.resolve(file.replace("/", r.getFileSystem().getSeparator())); if (java.nio.file.Files.exists(p)) return Optional.of(p); } return Optional.empty(); }
    @Deprecated default Path getRootPath() { return getRootPaths().get(0); }
    @Deprecated default Path getPath(String file) { return findPath(file).orElse(getRootPath().resolve(file)); }
}
