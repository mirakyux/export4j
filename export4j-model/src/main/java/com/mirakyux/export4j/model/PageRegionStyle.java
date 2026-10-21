// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.function.Consumer;

/** Complete immutable header/footer text defaults. Geometry uses points; RGB is 24-bit.
 * An empty role selects the caller's explicit default fonts. Owns no resources and is thread-safe.
 * Defaults are resolved when a paragraph is authored, without a later cascade into explicit runs.
 */
public record PageRegionStyle(ParagraphFormat format, String fontRole, int rgb) {
    public static final PageRegionStyle DEFAULT = new PageRegionStyle(
            ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build(), "", 0);

    public PageRegionStyle {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fontRole, "fontRole");
        if (!fontRole.isEmpty() && !fontRole.matches("[A-Za-z_][A-Za-z_0-9]{0,255}") || rgb < 0 || rgb > 0xffffff) {
            throw new IllegalArgumentException("invalid page region text defaults");
        }
    }

    public static Builder builder() { return DEFAULT.toBuilder(); }
    public Builder toBuilder() { return new Builder(this); }

    /** Task-confined builder; callbacks run synchronously and completed values are snapshotted. */
    public static final class Builder {
        private ParagraphFormat format;
        private String fontRole;
        private int rgb;
        private Builder(PageRegionStyle value) { format = value.format; fontRole = value.fontRole; rgb = value.rgb; }
        public Builder format(ParagraphFormat value) { format = Objects.requireNonNull(value, "format"); return this; }
        public Builder format(Consumer<ParagraphFormat.Builder> configure) {
            var value = format.toBuilder(); Objects.requireNonNull(configure, "configure").accept(value);
            return format(value.build());
        }
        public Builder fontRole(String value) { fontRole = Objects.requireNonNull(value, "fontRole"); return this; }
        public Builder rgb(int value) { rgb = value; return this; }
        public PageRegionStyle build() { return new PageRegionStyle(format, fontRole, rgb); }
    }
}
