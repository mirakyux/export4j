// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.util.Optional;

/** Font mapping SPI. Loaded faces are immutable, shareable, and own no open resources. */
public interface FontFace {
    FontData data();

    FontMetrics metrics();

    /** Returns no value for an unsupported Unicode scalar, never a missing-glyph substitute. */
    Optional<GlyphMetrics> glyph(int codePoint);
}
