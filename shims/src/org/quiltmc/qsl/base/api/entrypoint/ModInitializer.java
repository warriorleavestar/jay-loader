/*
 * API declaration following the Quilt Loader / QSL APIs (https://github.com/QuiltMC, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package org.quiltmc.qsl.base.api.entrypoint;

import org.quiltmc.loader.api.ModContainer;

/** QSL's common entrypoint ("init"), run by Amber together with Fabric's "main" entrypoints. */
public interface ModInitializer {
    void onInitialize(ModContainer mod);
}
