// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.adapter.docx.DocxFontFamilies;
import com.mirakyux.export4j.font.FontSource;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

/** Explicit Word family name and physical variants, declared once for every output format.
 * Sources are reopened per operation; no parsed font is retained. Missing variants are not synthesized.
 * Source implementations must support the application's concurrent use.
 */
public final class FontFamily {
    private final String name;
    private final Map<String, FontSource> variants;

    private FontFamily(Builder builder) {
        name = builder.name; variants = Map.copyOf(builder.variants);
    }

    public static Builder builder(String name, FontSource regular) { return new Builder(name, regular); }
    public static FontFamily of(String name, FontSource regular) { return builder(name, regular).build(); }
    public String name() { return name; }
    Map<String, FontSource> variants() { return variants; }

    /** Task-confined builder. A built family snapshots all declared variants. */
    public static final class Builder {
        private final String name;
        private final Map<String, FontSource> variants = new LinkedHashMap<>();
        private Builder(String name, FontSource regular) {
            this.name = DocxFontFamilies.uniform(name).ascii();
            variants.put("", Objects.requireNonNull(regular, "regular"));
        }
        public Builder bold(FontSource value) { variants.put("bold", Objects.requireNonNull(value, "bold")); return this; }
        public Builder italic(FontSource value) { variants.put("italic", Objects.requireNonNull(value, "italic")); return this; }
        public Builder boldItalic(FontSource value) { variants.put("boldItalic", Objects.requireNonNull(value, "boldItalic")); return this; }
        public FontFamily build() { return new FontFamily(this); }
    }
}
