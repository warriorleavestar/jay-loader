/*
 * API declaration following the Quilt Loader / QSL APIs (https://github.com/QuiltMC, Apache License 2.0) so that mods
 * compiled against it link; implementation by the Jay Loader authors. Licensed under the Apache License, Version 2.0
 * (licenses/Apache-2.0.txt).
 */
package org.quiltmc.loader.api.minecraft;

import java.lang.annotation.*;

/** Quilt's marker for dedicated-server-only code (class retention; Amber does not strip by it). */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface DedicatedServerOnly {}
