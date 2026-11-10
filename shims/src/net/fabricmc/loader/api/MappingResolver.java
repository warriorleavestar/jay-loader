/*
 * API declaration following Fabric Loader (https://github.com/FabricMC/fabric-loader, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package net.fabricmc.loader.api;
import java.util.Collection;
/** Same API as Fabric's. Amber's runtime namespace is "named" (Mojmap). Names use '.' for classes, descriptors use '/'. */
public interface MappingResolver {
    Collection<String> getNamespaces();
    String getCurrentRuntimeNamespace();
    String mapClassName(String namespace, String className);
    String unmapClassName(String targetNamespace, String className);
    String mapFieldName(String namespace, String owner, String name, String descriptor);
    String mapMethodName(String namespace, String owner, String name, String descriptor);
}
