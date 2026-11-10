/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;
import java.util.Optional;
public interface SemanticVersion extends Version {
    int COMPONENT_WILDCARD = Integer.MIN_VALUE;
    int getVersionComponentCount();
    int getVersionComponent(int pos);
    Optional<String> getPrereleaseKey();
    Optional<String> getBuildKey();
    default boolean hasWildcard() { return false; }
    static SemanticVersion parse(String s) throws VersionParsingException { return amber.ul.personality.FabricFacade.semver(s, true); }
}
