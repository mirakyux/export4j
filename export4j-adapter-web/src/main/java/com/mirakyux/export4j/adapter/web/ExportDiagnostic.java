// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import java.util.Objects;

/** One WARN fallback. Messages contain no document text, resource URLs or filesystem paths. */
public record ExportDiagnostic(ExportFeature feature, ExportLocation location, String message) implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    public ExportDiagnostic {
        Objects.requireNonNull(feature, "feature"); Objects.requireNonNull(location, "location"); Objects.requireNonNull(message, "message");
    }
}
