// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics;

/** Explicit immutable colorant coordinates. Color-space conversion is an output decision, not model mutation. */
public sealed interface ColorValue permits RgbColor, CmykColor, IccColor, SpotColor { }
