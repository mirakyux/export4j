// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Binds only our occurrence-specific temporary slots; ordinary user resources remain untouched. */
final class NativeMathBinding {
    record Equation(InlineVector slot, DocxMath expression, boolean display, double fontSize) {
        Equation(InlineVector slot, DocxMath expression, boolean display) { this(slot, expression, display, 0); }
    }

    private final Map<String, Equation> expressions;
    private final java.util.IdentityHashMap<com.mirakyux.export4j.model.VectorResource, String> byResource = new java.util.IdentityHashMap<>();
    private final List<DocxMathDocument.Equation> equations = new ArrayList<>();
    private final Set<String> bound = new HashSet<>();
    private final boolean preserveAppearance;

    private NativeMathBinding(Map<String, Equation> expressions, boolean preserveAppearance) {
        this.expressions = expressions;
        this.preserveAppearance = preserveAppearance;
        expressions.forEach((key, value) -> byResource.put(value.slot().resource(), key));
    }

    static DocxMathDocument bind(Document source, Map<String, Equation> expressions) {
        return bind(source, expressions, false);
    }

    static DocxMathDocument bind(Document source, Map<String, Equation> expressions, boolean preserveAppearance) {
        var state = new NativeMathBinding(expressions, preserveAppearance);
        var blocks = state.rewrite(source.blocks());
        if (state.bound.size() != expressions.size()) { throw unsupported(); }
        return new DocxMathDocument(new SectionedDocument(source.metadata(), List.of(
                new Section("math-body", source.pageSettings(), blocks, 0))), state.equations);
    }

    static void verifySlots(Document source, Collection<InlineVector> slots) {
        var expected = new HashMap<InlineVector, Integer>();
        slots.forEach(vector -> expected.merge(vector, 1, Integer::sum));
        SemanticTree.walk(source.blocks(), visit -> {
            if (visit.block() instanceof Paragraph paragraph) { count(paragraph.vectors(), expected); }
            else if (visit.block() instanceof Heading heading) { count(heading.vectors(), expected); }
            else if (visit.block() instanceof NumberedItem item) { count(item.vectors(), expected); }
            else if (visit.block() instanceof BulletItem item) { count(item.vectors(), expected); }
            if (visit.block() instanceof Table table) {
                for (var row : table.rows()) {
                    for (var cell : row.cells()) { if (!cell.hasBlocks()) { count(cell.vectors(), expected); } }
                }
            }
        });
        if (expected.values().stream().anyMatch(value -> value != 0)) { throw unsupported(); }
    }

    private static void count(List<Paragraph.VectorSpan> vectors, Map<InlineVector, Integer> expected) {
        for (var span : vectors) {
            if (expected.containsKey(span.vector())) { expected.compute(span.vector(), (key, value) -> value - 1); }
        }
    }

    private List<Block> rewrite(List<Block> source) {
        var result = new ArrayList<Block>();
        for (var block : source) {
            ExecutionContext.checkpoint();
            if (block instanceof Paragraph paragraph) { block = paragraph(paragraph); }
            else if (block instanceof Heading heading) {
                var p = paragraph(heading.paragraph());
                block = Heading.mixed(heading.id(), p.content(), heading.level(), p.format());
            } else if (block instanceof NumberedItem item) {
                var p = paragraph(item.paragraph());
                block = NumberedItem.mixed(item.id(), item.listId(), p.content(), item.restartAt(), p.format(), item.level(), item.markerStyle());
            } else if (block instanceof BulletItem item) {
                var p = paragraph(item.paragraph());
                block = BulletItem.mixed(item.id(), p.content(), item.marker(), item.level(), p.format(), item.markerStyle());
            }
            else if (block instanceof ColumnFlow columns) {
                block = columns.toBuilder().blocks(rewrite(columns.blocks())).build();
            } else if (block instanceof GridLayout grid) {
                var items = new ArrayList<GridItem>();
                for (var item : grid.items()) { items.add(item.toBuilder().blocks(rewrite(item.blocks())).build()); }
                block = grid.toBuilder().items(items).build();
            } else if (block instanceof GridItem item) { block = item.toBuilder().blocks(rewrite(item.blocks())).build(); }
            else if (block instanceof Table table) {
                var rows = new ArrayList<TableRow>();
                var grid = table.grid();
                for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
                    var cells = new ArrayList<TableCell>();
                    for (var anchor : grid.anchorRows().get(rowIndex)) {
                        var cell = anchor.cell();
                        if (cell.hasBlocks()) { cell = cell.toBuilder().blocks(rewrite(cell.blocks())).build(); }
                        else if (cell.vectors().stream().anyMatch(vector -> own(vector.vector()))) {
                            var f = cell.format();
                            var format = new ParagraphFormat(f.fontSize(), f.lineHeight(), 0, 0, f.alignment(), 0, 0, false)
                                    .withLineBreakPolicy(f.lineBreakPolicy());
                            var p = new Paragraph(DocxSemanticDocument.cellParagraphId(table.id(), rowIndex, anchor.columnIndex()),
                                    cell.text(), format, cell.inlines(), cell.images(), cell.fields(), cell.vectors());
                            var boundParagraph = paragraph(p);
                            cell = cell.toBuilder().content(boundParagraph.content()).build();
                        }
                        cells.add(cell);
                    }
                    rows.add(new TableRow(cells));
                }
                block = new Table(table.id(), table.columns(), rows, table.headerRows(), table.format());
            }
            result.add(block);
        }
        return List.copyOf(result);
    }

    private boolean own(InlineVector vector) {
        return byResource.containsKey(vector.resource());
    }

    private Paragraph paragraph(Paragraph source) {
        var positions = new HashSet<Integer>();
        var slots = new HashMap<Integer, InlineVector>();
        var appearances = new HashMap<Integer, Double>();
        var retained = new ArrayList<Paragraph.VectorSpan>();
        for (var span : source.vectors()) {
            ExecutionContext.checkpoint();
            if (!own(span.vector())) { retained.add(span); continue; }
            String key = byResource.get(span.vector().resource());
            if (!bound.add(key)) { throw unsupported(); }
            var value = expressions.get(key);
            positions.add(span.sourceIndex());
            slots.put(span.sourceIndex(), span.vector());
            appearances.put(span.sourceIndex(), value.fontSize() == 0 ? source.format().fontSize() : value.fontSize());
            var placement = value.display() && source.text().equals("\ufffc")
                    ? DocxMathDocument.Placement.DISPLAY_CENTER : DocxMathDocument.Placement.INLINE;
            equations.add(new DocxMathDocument.Equation("equation-" + equations.size(),
                    new DocxSemanticDocument.Point(source.id(), span.sourceIndex()), placement, value.expression()));
        }
        if (positions.isEmpty()) { return source; }
        var runs = new ArrayList<InlineText>();
        int offset = 0;
        for (var run : source.inlines()) {
            int start = 0;
            for (int i = 0; i < run.text().length(); i++) {
                if (!positions.contains(offset + i)) { continue; }
                if (i > start) { runs.add(new InlineText(run.text().substring(start, i), run.style(), run.hyperlink())); }
                var vector = slots.get(offset + i);
                runs.add(preserveAppearance
                        ? new InlineText("\ufffc", InlineStyle.of(appearances.get(offset + i)).toBuilder().rgb(vector.rgb()).build(), vector.hyperlink())
                        : new InlineText("\ufffc", InlineStyle.of(source.format().fontSize())));
                start = i + 1;
            }
            if (start < run.text().length()) { runs.add(new InlineText(run.text().substring(start), run.style(), run.hyperlink())); }
            offset += run.text().length();
        }
        return new Paragraph(source.id(), source.text(), source.format(), runs,
                source.images(), source.fields(), retained);
    }

    private static MathException unsupported() { return new MathException(MathException.Reason.UNSUPPORTED_SYNTAX); }
}
