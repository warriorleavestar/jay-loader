/*
 * API declaration following the Quilt Loader / QSL APIs (https://github.com/QuiltMC, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package org.quiltmc.loader.api;

/** Amber's Quilt Loader API subset: the identity fields of quilt.mod.json. */
public interface ModMetadata {
    String id();
    String group();
    String name();
    String description();
}
