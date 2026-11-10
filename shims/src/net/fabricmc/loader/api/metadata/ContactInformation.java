/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api.metadata;
import java.util.*;
public interface ContactInformation {
    ContactInformation EMPTY = new ContactInformation() { public Optional<String> get(String k) { return Optional.empty(); } public Map<String, String> asMap() { return Map.of(); } };
    Optional<String> get(String key);
    Map<String, String> asMap();
}
