/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;
public interface Version extends Comparable<Version> {
    String getFriendlyString();
    default int compareTo(Version o) { return amber.ul.resolve.Versions.compare(getFriendlyString(), o.getFriendlyString()); }
    /** Semantic when it parses as one, otherwise an opaque string version (like Fabric's StringVersion). */
    static Version parse(String s) throws VersionParsingException {
        try { return amber.ul.personality.FabricFacade.semver(s, false); } catch (VersionParsingException e) { return amber.ul.personality.FabricFacade.stringVersion(s); }
    }
}
