// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.font;

import java.io.IOException;

/** A source-private immutable snapshot; neither bytes nor identity can be supplied independently. */
final class SnapshotFontSource implements FontSource {
    private final byte[] bytes;
    private final String identifier;

    SnapshotFontSource(byte[] source) {
        bytes = source.clone();
        identifier = FontFaceCache.identifier(bytes);
    }

    @Override public FontFace load(FontFaceCache cache) throws IOException {
        return cache.loadSnapshot(bytes, identifier);
    }
}
