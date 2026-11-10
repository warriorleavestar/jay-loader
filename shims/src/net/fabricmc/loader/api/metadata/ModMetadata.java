/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api.metadata;
import java.util.*;
import net.fabricmc.loader.api.Version;
public interface ModMetadata {
    String getType();
    String getId();
    Collection<String> getProvides();
    Version getVersion();
    ModEnvironment getEnvironment();
    Collection<ModDependency> getDependencies();
    String getName();
    String getDescription();
    Collection<Person> getAuthors();
    Collection<Person> getContributors();
    ContactInformation getContact();
    Collection<String> getLicense();
    Optional<String> getIconPath(int size);
    Map<String, CustomValue> getCustomValues();
    default boolean containsCustomValue(String key) { return getCustomValues().containsKey(key); }
    default CustomValue getCustomValue(String key) { return getCustomValues().get(key); }
}
