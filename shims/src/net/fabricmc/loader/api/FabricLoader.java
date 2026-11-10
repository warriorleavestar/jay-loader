/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;

import amber.ul.runtime.ModRegistry;
import java.nio.file.Path;
import java.util.*;
import net.fabricmc.api.EnvType;

/** Fabric Loader API facade backed by Amber's single mod registry: a Fabric mod can see Forge mods here. */
public interface FabricLoader {
    FabricLoader INSTANCE = new FabricLoader() {};
    static FabricLoader getInstance() { return INSTANCE; }
    default boolean isModLoaded(String id) { return ModRegistry.isLoaded(id); }
    default Optional<ModContainer> getModContainer(String id) { return Optional.ofNullable(amber.ul.personality.FabricFacade.container(id)); }
    default Collection<ModContainer> getAllMods() { return amber.ul.personality.FabricFacade.all(); }
    default Path getGameDir() { return ModRegistry.gameDir.toAbsolutePath(); }
    @Deprecated default java.io.File getGameDirectory() { return getGameDir().toFile(); }
    default Path getConfigDir() { Path p = getGameDir().resolve("config"); try { java.nio.file.Files.createDirectories(p); } catch (java.io.IOException ignored) {} return p; }
    @Deprecated default java.io.File getConfigDirectory() { return getConfigDir().toFile(); }
    default boolean isDevelopmentEnvironment() { return false; }
    default EnvType getEnvironmentType() { return ModRegistry.server ? EnvType.SERVER : EnvType.CLIENT; }
    default MappingResolver getMappingResolver() { return amber.ul.personality.FabricFacade.RESOLVER; }
    default String[] getLaunchArguments(boolean sanitize) { return new String[0]; }
    default <T> List<T> getEntrypoints(String key, Class<T> type) { return amber.ul.personality.FabricFacade.entrypoints(key, type); }
    default <T> List<net.fabricmc.loader.api.entrypoint.EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) { return amber.ul.personality.FabricFacade.entrypointContainers(key, type); }
    default ObjectShare getObjectShare() { return amber.ul.personality.FabricFacade.OBJECT_SHARE; }
    default Object getGameInstance() { return amber.ul.runtime.GameHooks.gameInstance(); }
}
