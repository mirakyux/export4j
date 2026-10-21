// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.Objects;
import java.util.List;

/** Immutable semantic paragraph with styled text and explicit U+FFFC image/field markers.
 * Nonblank ID is unique within its document; empty text is valid. Legacy constructors retain text-only behavior.
 */
public record Paragraph(String id, String text, ParagraphFormat format, List<InlineText> inlines, List<ImageSpan> images,
        List<FieldSpan> fields) implements Block {
    /** Original UTF-16 position of an atomic field's single U+FFFC marker. */
    public record FieldSpan(int sourceIndex, InlineField field) {
        public FieldSpan { Objects.requireNonNull(field, "field"); if (sourceIndex < 0) { throw new IllegalArgumentException("invalid field source index"); } }
    }
    /** Original UTF-16 position of the image's single U+FFFC marker. */
    public record ImageSpan(int sourceIndex, InlineImage image) {
        public ImageSpan { Objects.requireNonNull(image, "image"); if (sourceIndex < 0) { throw new IllegalArgumentException("invalid image source index"); } }
    }

    public Paragraph {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(format, "format");
        inlines = List.copyOf(inlines);
        images = List.copyOf(images);
        fields = List.copyOf(fields);
        if (!fields.isEmpty() && inlines.isEmpty()) { throw new IllegalArgumentException("field markers require explicit inline styles"); }
        var positions = new java.util.HashSet<Integer>();
        if (images.size() > 1024 || images.stream().anyMatch(image -> image.sourceIndex() >= text.length()
                || text.charAt(image.sourceIndex()) != '\ufffc' || !positions.add(image.sourceIndex()))) {
            throw new IllegalArgumentException("image markers must be unique and match paragraph source");
        }
        if (fields.size() > 1024 || fields.stream().anyMatch(field -> field.sourceIndex() >= text.length()
                || text.charAt(field.sourceIndex()) != '\ufffc' || !positions.add(field.sourceIndex()))) {
            throw new IllegalArgumentException("field markers must be unique and match paragraph source");
        }
        if (!inlines.isEmpty() && !text.equals(inlines.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining()))) {
            throw new IllegalArgumentException("inline text must match paragraph source");
        }
        int offset = 0;
        for (var run : inlines) {
            int end = offset + run.text().length();
            for (var image : images) {
                if (image.sourceIndex() >= offset && image.sourceIndex() < end
                        && (!image.image().hyperlink().equals(run.hyperlink()) || !run.style().equals(InlineStyle.of(format.fontSize())))) {
                    throw new IllegalArgumentException("image markers require the paragraph's default style and their image hyperlink");
                }
            }
            for (var field : fields) {
                if (field.sourceIndex() >= offset && field.sourceIndex() < end
                        && (!run.hyperlink().isEmpty() || !run.style().equals(field.field().style()))) {
                    throw new IllegalArgumentException("field marker style must match its field and have no external hyperlink");
                }
            }
            offset = end;
        }
        if (id.isBlank()) {
            throw new IllegalArgumentException("paragraph ID must not be blank");
        }
    }

    public Paragraph(String id, String text, ParagraphFormat format) {
        this(id, text, format, List.of());
    }

    public Paragraph(String id, String text, ParagraphFormat format, List<InlineText> inlines) {
        this(id, text, format, inlines, List.of());
    }

    public Paragraph(String id, String text, ParagraphFormat format, List<InlineText> inlines, List<ImageSpan> images) {
        this(id, text, format, inlines, images, List.of());
    }

    /** Combines styled text and atomic images, assigning deterministic UTF-16 marker positions. */
    public static Paragraph mixed(String id, List<? extends InlineContent> content, ParagraphFormat format) {
        var text = new StringBuilder();
        var runs = new java.util.ArrayList<InlineText>();
        var images = new java.util.ArrayList<ImageSpan>();
        var fields = new java.util.ArrayList<FieldSpan>();
        for (var item : List.copyOf(content)) {
            switch (item) {
                case InlineText run -> { text.append(run.text()); runs.add(run); }
                case InlineImage image -> {
                    images.add(new ImageSpan(text.length(), image)); text.append('\ufffc');
                    runs.add(new InlineText("\ufffc", InlineStyle.of(format.fontSize()), image.hyperlink()));
                }
                case InlineField field -> {
                    fields.add(new FieldSpan(text.length(), field)); text.append('\ufffc');
                    runs.add(new InlineText("\ufffc", field.style()));
                }
            }
        }
        return new Paragraph(id, text.toString(), format, runs, images, fields);
    }

    /** Returns original logical content, retaining styled text and image marker order. */
    public List<InlineContent> content() {
        var result = new java.util.ArrayList<InlineContent>();
        var byIndex = images.stream().collect(java.util.stream.Collectors.toMap(ImageSpan::sourceIndex, ImageSpan::image));
        var fieldIndex = fields.stream().collect(java.util.stream.Collectors.toMap(FieldSpan::sourceIndex, FieldSpan::field));
        var sourceRuns = inlines.isEmpty() ? List.of(new InlineText(text, InlineStyle.of(format.fontSize()))) : inlines;
        int base = 0;
        for (var run : sourceRuns) {
            int chunk = 0;
            for (int i = 0; i < run.text().length(); i++) {
                InlineContent item = byIndex.get(base + i);
                if (item == null) { item = fieldIndex.get(base + i); }
                if (item == null) { continue; }
                if (i > chunk) { result.add(new InlineText(run.text().substring(chunk, i), run.style(), run.hyperlink())); }
                result.add(item); chunk = i + 1;
            }
            if (chunk < run.text().length()) { result.add(new InlineText(run.text().substring(chunk), run.style(), run.hyperlink())); }
            base += run.text().length();
        }
        return List.copyOf(result);
    }

    public Paragraph(String id, List<InlineText> inlines, ParagraphFormat format) {
        this(id, inlines.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining()), format, inlines);
    }
}
