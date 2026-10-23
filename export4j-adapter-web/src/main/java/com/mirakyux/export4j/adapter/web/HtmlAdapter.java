// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.SectionedDocument;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Semantic HTML5 export, with escaped text, inline CSS and embedded PNG/JPEG. Browser flow determines
 * line wrapping; PDF pagination/font shaping is not reproduced. No scripts, remote assets or HTML import.
 * Immutable and thread-safe; owns no resources. Default unsupported action is ERROR.
 */
public final class HtmlAdapter {
    private final ExportPolicy policy;
    private final ExportLimits limits;
    private final Map<String, FontStyle> fonts;
    public HtmlAdapter() { this(ExportPolicy.STRICT, ExportLimits.DEFAULT, Map.of()); }
    private HtmlAdapter(ExportPolicy policy, ExportLimits limits, Map<String, FontStyle> fonts) {
        this.policy = policy; this.limits = limits; this.fonts = Map.copyOf(fonts);
    }
    public WebExportRequest export(Document document) { return export(WebExportRequest.source(document, PageRegions.EMPTY)); }
    public WebExportRequest export(PageDocument document) { return export(WebExportRequest.source(document)); }
    public WebExportRequest export(SectionedDocument document) {
        return new WebExportRequest(document, true, policy, limits, fonts, ExecutionOptions.DEFAULT);
    }
    public static Builder builder() { return new Builder(); }
    /** Shared-policy conversion target; old ExportPolicy remains specific to export().
     * Reports include IGNORE losses. Prepared conversion snapshots the encoded result before final I/O.
     */
    public com.mirakyux.export4j.model.processing.DocumentConversion.Target conversionTarget() {
        return WebConversionTargets.target(true, limits, fonts);
    }
    /** Task-confined builder. Role names never imply bold/italic; mappings are explicit. */
    public static final class Builder {
        private ExportPolicy policy = ExportPolicy.STRICT;
        private ExportLimits limits = ExportLimits.DEFAULT;
        private final Map<String, FontStyle> fonts = new HashMap<>();
        private Builder() { }
        public Builder policy(ExportPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Builder limits(ExportLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder fontRole(String role, FontStyle style) {
            if (Objects.requireNonNull(role, "role").isBlank()) { throw new IllegalArgumentException("role must not be blank"); }
            fonts.put(role, Objects.requireNonNull(style, "style")); return this;
        }
        public HtmlAdapter build() { return new HtmlAdapter(policy, limits, fonts); }
    }
}
