// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.io.IOException;
import java.util.Objects;

/** Optional format-specific single-file recovery bridge; callbacks use only bounded byte snapshots.
 * Implementations must not return stale source content for current-file fallback. */
public interface EmbeddedRecoveryCodec {
    String format();
    byte[] encode(byte[] standard, byte[] companion, XmlModelRecovery.Limits limits) throws IOException;
    Extracted decode(byte[] current, XmlModelRecovery.Limits limits) throws IOException;

    record Extracted(byte[] standard, byte[] companion) {
        public Extracted {
            standard = Objects.requireNonNull(standard, "standard").clone();
            companion = companion == null ? null : companion.clone();
        }
        @Override public byte[] standard() { return standard.clone(); }
        @Override public byte[] companion() { return companion == null ? null : companion.clone(); }
    }
}
