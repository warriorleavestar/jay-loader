/*
 * API declaration following the Quilt Loader / QSL APIs (https://github.com/QuiltMC, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package org.quiltmc.loader.api;

import java.nio.file.Path;

/** Amber's Quilt Loader API subset (loader matrix item 3): a mod as Quilt mods see it. Backed by Amber's Fabric container. */
public interface ModContainer {
    ModMetadata metadata();
    /** The mod's root (inside its jar). */
    Path rootPath();
    default Path getPath(String file) { return rootPath().resolve(file.replace("/", rootPath().getFileSystem().getSeparator())); }
}
