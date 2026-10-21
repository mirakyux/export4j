// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.text.TextDiagnostic;

/** A static table inside one positioned viewport. Fields, hyperlinks and named font roles require
 * semantic body flow and are rejected here instead of losing their context. Use semantic Table
 * directly in body flow for interactions, cell source diagnostics and multi-page rows.
 * Immutable; owns no resources. Dimensions are points and local coordinates start at the top left.
 */
public record TableComponent(Table table) implements Component {
    public TableComponent {
        Objects.requireNonNull(table, "table");
        SemanticTree.walk(table, visit -> {
            if (visit.block() instanceof Paragraph paragraph) {
                validateContent(paragraph.inlines(), paragraph.images(), paragraph.fields());
            } else if (visit.block() instanceof Table nested) {
                for (var row : nested.rows()) {
                    for (var cell : row.cells()) {
                        if (!cell.hasBlocks()) { validateContent(cell.inlines(), cell.images(), cell.fields()); }
                    }
                }
            }
        });
    }

    private static void validateContent(List<InlineText> inlines, List<Paragraph.ImageSpan> images, List<Paragraph.FieldSpan> fields) {
        if (!fields.isEmpty() || inlines.stream().anyMatch(inline -> !inline.hyperlink().isEmpty() || !inline.style().fontRole().isEmpty())
                || images.stream().anyMatch(image -> !image.image().hyperlink().isEmpty())) {
            throw new IllegalArgumentException("positioned table fields, hyperlinks and font roles require semantic body flow");
        }
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        var layout = new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(context.width(), context.height()), PageMargins.uniform(0)), List.of(table)), context.fontGroup());
        if (layout.pages().size() != 1) {
            throw new IllegalArgumentException("positioned table must fit a single viewport");
        }
        var fragment = (TableFragment) layout.pages().getFirst().elements().getFirst();
        List<TextDiagnostic> diagnostics = new ArrayList<>();
        long lines = 0;
        for (var row : fragment.rows()) {
            for (var cell : row.cells()) {
                diagnostics.addAll(cell.diagnostics());
                lines += cell.lineCount();
            }
        }
        return new ComponentLayout(fragment.width(), fragment.height(), Math.toIntExact(lines),
                DocumentPainter.paint(layout).pages().getFirst().commands(), diagnostics);
    }
}
