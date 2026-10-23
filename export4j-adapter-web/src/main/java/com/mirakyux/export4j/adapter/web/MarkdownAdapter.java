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

/** UTF-8 CommonMark/GFM content export: headings, paragraphs, level-one lists, links, embedded images,
 * emphasis/strike and rectangular tables. Generated br/strong/em/del tags preserve line breaks and adjacent
 * styled runs; empty comments preserve numbered-list restarts. Source HTML is always escaped.
 * Some viewers disallow data-URI images. No Markdown import is added here. Immutable and thread-safe.
 */
public final class MarkdownAdapter {
    private final ExportPolicy policy;
    private final ExportLimits limits;
    private final Map<String, FontStyle> fonts;
    public MarkdownAdapter() { this(ExportPolicy.STRICT, ExportLimits.DEFAULT, Map.of()); }
    private MarkdownAdapter(ExportPolicy policy, ExportLimits limits, Map<String, FontStyle> fonts) {
        this.policy = policy; this.limits = limits; this.fonts = Map.copyOf(fonts);
    }
    public WebExportRequest export(Document document) { return export(WebExportRequest.source(document, PageRegions.EMPTY)); }
    public WebExportRequest export(PageDocument document) { return export(WebExportRequest.source(document)); }
    public WebExportRequest export(SectionedDocument document) {
        return new WebExportRequest(document, false, policy, limits, fonts, ExecutionOptions.DEFAULT);
    }
    public static Builder builder() { return new Builder(); }
    /** Shared-policy conversion target; retains every actual loss including IGNORE.
     * ExportPolicy remains specific to the existing export() entry point.
     */
    public com.mirakyux.export4j.model.processing.DocumentConversion.Target conversionTarget() {
        return WebConversionTargets.target(false, limits, fonts);
    }
    /** Task-confined builder; font role mappings select Markdown emphasis, without guessing role names. */
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
        public MarkdownAdapter build() { return new MarkdownAdapter(policy, limits, fonts); }
    }
}
