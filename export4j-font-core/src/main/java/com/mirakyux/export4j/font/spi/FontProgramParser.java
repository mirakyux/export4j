// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font.spi;

import com.mirakyux.export4j.font.FontMetrics;
import com.mirakyux.export4j.font.GlyphMetrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;

/** Runtime service for validating and reading a bounded standalone TrueType or CFF font. */
public interface FontProgramParser {
    /**
     * Reads from position zero of a private, read-only view of the complete font program.
     * Implementations must support concurrent calls, check embedding permissions and release
     * native or parser resources before returning.
     */
    Metadata parse(ByteBuffer font) throws IOException;

    /** Immutable information needed to construct a detached font face. */
    record Metadata(FontMetrics metrics, Map<Integer, GlyphMetrics> mappings, boolean subsetAllowed) {
        public Metadata {
            Objects.requireNonNull(metrics, "metrics");
            mappings = Map.copyOf(mappings);
        }
    }
}
