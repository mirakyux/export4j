// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Shared task-local composition and validation of immutable inline source views. */
final class InlineContents {
    private InlineContents() { }

    record Mixed(String text, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors) {
        Mixed {
            inlines = List.copyOf(inlines);
            images = List.copyOf(images);
            fields = List.copyOf(fields);
            vectors = List.copyOf(vectors);
        }
    }

    static Mixed mixed(List<? extends InlineContent> content, double fontSize) {
        var snapshot = List.copyOf(content);
        if (snapshot.size() == 1 && snapshot.getFirst() instanceof InlineText run) {
            return new Mixed(run.text(), List.of(run), List.of(), List.of(), List.of());
        }
        var text = new StringBuilder();
        var runs = new ArrayList<InlineText>(snapshot.size());
        ArrayList<Paragraph.ImageSpan> images = null;
        ArrayList<Paragraph.FieldSpan> fields = null;
        ArrayList<Paragraph.VectorSpan> vectors = null;
        for (var item : snapshot) {
            switch (item) {
                case InlineText run -> { text.append(run.text()); runs.add(run); }
                case InlineImage image -> {
                    if (images == null) { images = new ArrayList<>(); }
                    images.add(new Paragraph.ImageSpan(text.length(), image));
                    text.append('\ufffc');
                    runs.add(new InlineText("\ufffc", InlineStyle.of(fontSize), image.hyperlink()));
                }
                case InlineField field -> {
                    if (fields == null) { fields = new ArrayList<>(); }
                    fields.add(new Paragraph.FieldSpan(text.length(), field));
                    text.append('\ufffc');
                    runs.add(new InlineText("\ufffc", field.style()));
                }
                case InlineVector vector -> {
                    if (vectors == null) { vectors = new ArrayList<>(); }
                    vectors.add(new Paragraph.VectorSpan(text.length(), vector));
                    text.append('\ufffc');
                    runs.add(new InlineText("\ufffc", InlineStyle.of(fontSize), vector.hyperlink()));
                }
            }
        }
        return new Mixed(text.toString(), runs, images == null ? List.of() : images,
                fields == null ? List.of() : fields, vectors == null ? List.of() : vectors);
    }

    static String text(List<InlineText> inlines) {
        if (inlines.size() == 1) { return inlines.getFirst().text(); }
        var text = new StringBuilder();
        for (var run : inlines) { text.append(run.text()); }
        return text.toString();
    }

    static void validate(String text, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
            List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors, double fontSize) {
        if (!fields.isEmpty() && inlines.isEmpty()) {
            throw new IllegalArgumentException("field markers require explicit inline styles");
        }
        if (!vectors.isEmpty() && inlines.isEmpty()) {
            throw new IllegalArgumentException("vector markers require explicit inline styles");
        }
        if (images.size() > 1024) {
            throw new IllegalArgumentException("image markers must be unique and match paragraph source");
        }
        var positions = images.isEmpty() && fields.isEmpty() && vectors.isEmpty() ? null : new HashSet<Integer>();
        for (var image : images) {
            if (image.sourceIndex() >= text.length() || text.charAt(image.sourceIndex()) != '\ufffc'
                    || !positions.add(image.sourceIndex())) {
                throw new IllegalArgumentException("image markers must be unique and match paragraph source");
            }
        }
        if (fields.size() > 1024) {
            throw new IllegalArgumentException("field markers must be unique and match paragraph source");
        }
        for (var field : fields) {
            if (field.sourceIndex() >= text.length() || text.charAt(field.sourceIndex()) != '\ufffc'
                    || !positions.add(field.sourceIndex())) {
                throw new IllegalArgumentException("field markers must be unique and match paragraph source");
            }
        }
        if (vectors.size() > 1024) { throw new IllegalArgumentException("vector marker budget exceeded"); }
        for (var vector : vectors) {
            if (vector.sourceIndex() >= text.length() || text.charAt(vector.sourceIndex()) != '\ufffc'
                    || !positions.add(vector.sourceIndex())) {
                throw new IllegalArgumentException("vector markers must be unique and match paragraph source");
            }
        }
        if (!inlines.isEmpty() && !matches(text, inlines)) {
            throw new IllegalArgumentException("inline text must match paragraph source");
        }
        if (images.isEmpty() && fields.isEmpty() && vectors.isEmpty()) { return; }
        var imageStyle = images.isEmpty() && vectors.isEmpty() ? null : InlineStyle.of(fontSize);
        int offset = 0;
        for (var run : inlines) {
            int end = offset + run.text().length();
            for (var image : images) {
                if (image.sourceIndex() >= offset && image.sourceIndex() < end
                        && (!image.image().hyperlink().equals(run.hyperlink()) || !run.style().equals(imageStyle))) {
                    throw new IllegalArgumentException("image markers require the paragraph's default style and their image hyperlink");
                }
            }
            for (var field : fields) {
                if (field.sourceIndex() >= offset && field.sourceIndex() < end
                        && (!run.hyperlink().isEmpty() || !run.style().equals(field.field().style()))) {
                    throw new IllegalArgumentException("field marker style must match its field and have no external hyperlink");
                }
            }
            for (var vector : vectors) {
                if (vector.sourceIndex() >= offset && vector.sourceIndex() < end
                        && (!vector.vector().hyperlink().equals(run.hyperlink()) || !run.style().equals(imageStyle))) {
                    throw new IllegalArgumentException("vector markers require the paragraph's default style and their vector hyperlink");
                }
            }
            offset = end;
        }
    }

    private static boolean matches(String text, List<InlineText> inlines) {
        int offset = 0;
        for (var run : inlines) {
            int length = run.text().length();
            if (length > text.length() - offset || !text.regionMatches(offset, run.text(), 0, length)) {
                return false;
            }
            offset += length;
        }
        return offset == text.length();
    }
}
