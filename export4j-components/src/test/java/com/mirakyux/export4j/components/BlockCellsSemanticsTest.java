// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockCellsSemanticsTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 300), PageMargins.uniform(20));
    private static final CellFormat CELL = new CellFormat(10, 18, ParagraphAlignment.LEFT);
    private static final ParagraphFormat PARAGRAPH = new ParagraphFormat(10, 18, 0, 0, ParagraphAlignment.LEFT, 0, 0, false);
    private static final InlineStyle STYLE = InlineStyle.of(10);
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = BlockCellsSemanticsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void descendantsResolveAfterContentsAndSectionRestartWithoutChangingSources() {
        var before = Paragraph.mixed("before", List.of(text("BEFORE P="), field(InlineField.Kind.PAGE, "", 18)), PARAGRAPH);
        var inner = inner(List.of(text("INNER P="), field(InlineField.Kind.PAGE, "", 18), text(" N="),
                field(InlineField.Kind.PAGES, "", 18), text(" R="), field(InlineField.Kind.REFERENCE_PAGE, "target", 18),
                text(" #="), field(InlineField.Kind.REFERENCE_NUMBER, "target", 54)));
        var after = Paragraph.mixed("after", List.of(text("BACK="), field(InlineField.Kind.REFERENCE_PAGE, "before", 18)), PARAGRAPH);
        var table = outer("outer", before, inner, after);
        var back = Paragraph.mixed("outside", List.of(text("LEAF="), field(InlineField.Kind.REFERENCE_PAGE, "after", 18)), PARAGRAPH);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(table), 0),
                new Section("second", PAGE, List.of(new Caption("target", "Figure", "Target", PARAGRAPH), back), 7)));
        var navigation = Navigation.builder().bookmark("Before", "before", 1).bookmark("Inner", "inner", 1)
                .bookmark("After", "after", 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(source);
        assertEquals(1, result.bodyPageOffset());
        assertEquals(3, result.display().pages().size());
        var parent = (TableFragment) result.body().pages().getFirst().elements().getFirst();
        var blocks = parent.rows().getFirst().cells().getFirst().blocks();
        assertEquals(3, blocks.size());
        assertEquals("BEFORE P=2", logical(((ParagraphFragment) blocks.get(0)).layout()));
        var nested = (TableFragment) blocks.get(1);
        assertEquals("INNER P=2 N=3 R=7 #=Figure 1", logical(nested.rows().getFirst().cells().getFirst().layout()));
        assertEquals("BACK=2", logical(((ParagraphFragment) blocks.get(2)).layout()));
        assertEquals("LEAF=2", logical(result.body().pages().getLast().fragments().getLast().layout()));
        assertEquals(List.of(new Point(33, 25), new Point(33, 43), new Point(33, 69), new Point(20, 20)),
                result.display().extras().bookmarks().stream().map(bookmark -> bookmark.destination()).toList());
        assertEquals(List.of(1, 1, 1, 2), result.display().extras().bookmarks().stream().map(bookmark -> bookmark.pageIndex()).toList());
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() == 2 && link.targetPageIndex() == 1
                && link.destination().equals(new Point(33, 69))));
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() == 1 && link.targetPageIndex() == 1
                && link.destination().equals(new Point(33, 25))));
        assertEquals("BEFORE P=\ufffc", before.text());
        assertEquals(1, before.fields().size());
        assertTrue(nested.rows().getFirst().cells().getFirst().layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
    }

    @Test void atomicDescendantsMoveThroughMixedColumnsAndGridWithFinalPageFields() {
        List<Function<Table, Component>> wrappers = List.of(
                table -> FlowColumn.builder().padding(4).semanticTable(table).build(),
                table -> ContinuousColumns.builder(FlowColumn.builder().padding(3).semanticTable(table).build()).gap(16).build(),
                table -> GridTable.builder().columns(1).allowRowSplit(true).defaults(cell -> cell.padding(6))
                        .row(row -> row.semanticTable(table)).build());
        for (var wrapper : wrappers) {
            var table = outer("outer", Paragraph.mixed("before", List.of(text("BEFORE "), field(InlineField.Kind.PAGE, "", 18)), PARAGRAPH),
                    inner(List.of(text("INNER "), field(InlineField.Kind.PAGE, "", 18))),
                    Paragraph.mixed("after", List.of(text("AFTER "), field(InlineField.Kind.PAGE, "", 18)), PARAGRAPH));
            var source = Documents.builder().pageSettings(PAGE).component(new Spacer(238)).component(wrapper.apply(table)).build();
            var result = DocumentEngine.builder(fonts).build().layout(source);
            assertEquals(2, result.body().pages().size());
            assertTrue(result.body().pages().getFirst().elements().stream().filter(ComponentFragment.class::isInstance)
                    .map(ComponentFragment.class::cast).allMatch(component -> component.layout().semanticPlacements().isEmpty()));
            var placements = result.body().pages().getLast().elements().stream().filter(ComponentFragment.class::isInstance)
                    .map(ComponentFragment.class::cast).flatMap(component -> component.layout().semanticPlacements().stream()).toList();
            assertEquals(1, placements.size());
            assertEquals(table, placements.getFirst().source());
            var blocks = placements.getFirst().fragment().rows().getFirst().cells().getFirst().blocks();
            assertEquals("BEFORE 2", logical(((ParagraphFragment) blocks.get(0)).layout()));
            assertEquals("INNER 2", logical(((TableFragment) blocks.get(1)).rows().getFirst().cells().getFirst().layout()));
            assertEquals("AFTER 2", logical(((ParagraphFragment) blocks.get(2)).layout()));
        }
    }

    @Test void nestedExternalLinksMatchPaintedImagesAndParentPadding() throws IOException {
        var inlineImage = image();
        var before = Paragraph.mixed("before", List.of(new InlineText("BEFORE", STYLE, "https://example.com/before")), PARAGRAPH);
        var table = outer("outer", before,
                inner(List.of(text("P="), field(InlineField.Kind.PAGE, "", 18),
                        new InlineText("LINK ", STYLE, "https://example.com/inner"), inlineImage)),
                new Paragraph("after", "AFTER", PARAGRAPH));
        var result = DocumentEngine.builder(fonts).build().layout(document(table));
        var links = result.display().extras().externalLinks();
        assertEquals(3, links.size());
        var beforeLink = links.stream().filter(link -> link.destination().uri().endsWith("/before")).findFirst().orElseThrow();
        assertEquals(33, beforeLink.bounds().x(), 1e-9);
        assertEquals(25, beforeLink.bounds().y(), 1e-9);
        var innerLink = links.stream().filter(link -> link.destination().uri().endsWith("/inner")).findFirst().orElseThrow();
        assertEquals(46, innerLink.bounds().y(), 1e-9);
        assertTrue(innerLink.bounds().x() >= 39 + 18);
        var imageLink = links.stream().filter(link -> link.destination().uri().endsWith("/image")).findFirst().orElseThrow();
        assertEquals(List.of(imageLink.bounds()), images(result.display().pages().getFirst()));
        assertEquals(12, imageLink.bounds().width());
        assertEquals(12, imageLink.bounds().height());
        var defaults = ComponentLimits.DEFAULT;
        var limited = new ComponentDocumentRenderer(DocumentLayoutLimits.DEFAULT,
                new ComponentLimits(defaults.maxPlacements(), defaults.maxCommands(), 1));
        var failure = assertThrows(DocumentLayoutException.class, () -> limited.layout(document(table), PageTemplate.EMPTY, Navigation.EMPTY, fonts));
        assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals("inner", failure.blockId());
        assertEquals(innerPath(), failure.sourcePath());
    }

    @Test void nestedSourceAndGeneratedWarningsPreserveSeparateUtf16Owners() {
        var table = outer("outer", new Paragraph("before", "A😀B", PARAGRAPH),
                inner(List.of(text("Q😀Z R="), field(InlineField.Kind.REFERENCE_NUMBER, "caption", 100))),
                new Paragraph("after", "AFTER", PARAGRAPH));
        var source = new Document(DocumentMetadata.EMPTY, PAGE,
                List.of(table, new Caption("caption", "Figure😀", "Target", PARAGRAPH)));
        var placeholder = FontGroup.placeholder(fonts.fonts(), '?');
        var result = DocumentEngine.builder(placeholder).build().layout(source);
        var warnings = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        var paragraph = warnings.stream().filter(value -> value.blockId().equals("before")).findFirst().orElseThrow();
        assertEquals(List.of(new CellLocation("outer", 0, 0, 0)), paragraph.sourcePath());
        assertEquals(1, paragraph.diagnostic().sourceStart());
        assertEquals(3, paragraph.diagnostic().sourceEnd());
        var inline = warnings.stream().filter(value -> value.blockId().equals("inner") && value.region().equals("TABLE")).findFirst().orElseThrow();
        assertEquals(innerPath(), inline.sourcePath());
        assertEquals(1, inline.diagnostic().sourceStart());
        assertEquals(3, inline.diagnostic().sourceEnd());
        var generated = warnings.stream().filter(value -> value.region().equals("INLINE_FIELD:7")).findFirst().orElseThrow();
        assertEquals("inner", generated.blockId());
        assertEquals(innerPath(), generated.sourcePath());
        assertEquals(6, generated.diagnostic().sourceStart());
        assertEquals(8, generated.diagnostic().sourceEnd());
        assertThrows(UnsupportedOperationException.class, () -> generated.sourcePath().clear());
    }

    @Test void nestedFieldFailureRetainsRootIndexAndDeepestPath() {
        var table = outer("outer", new Paragraph("before", "BEFORE", PARAGRAPH),
                inner(List.of(text("X="), field(InlineField.Kind.PAGE, "", .1))), new Paragraph("after", "AFTER", PARAGRAPH));
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(fonts).build().layout(document(table)));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("inner", failure.blockId());
        assertEquals(0, failure.blockIndex());
        assertEquals(0, failure.pageIndex());
        assertEquals(2, failure.sourceIndex());
        assertEquals(0, failure.rowIndex());
        assertEquals(0, failure.columnIndex());
        assertEquals(innerPath(), failure.sourcePath());
    }

    @Test void allTreeIdsIncludeNotesAndIndependentlyPlacedSources() {
        var table = outer("outer", new Paragraph("before", "BEFORE", PARAGRAPH), inner(List.of(text("INNER"))),
                new Paragraph("after", "AFTER", PARAGRAPH));
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("section", PAGE,
                List.of(new Paragraph("top", "TOP", PARAGRAPH), table), 0)));
        assertThrows(IllegalArgumentException.class, () -> SemanticBlocks.compile(source,
                List.of(new Note("before", "top", "NOTE", Note.Kind.FOOTNOTE))));
        var another = Table.builder("another").row(TableCell.builder().blocks(List.of(new Paragraph("before", "DUPLICATE", PARAGRAPH))).build()).build();
        var repeated = Documents.builder().pageSettings(PAGE).column(column -> column.semanticTable(table).semanticTable(another)).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(repeated));
        var topConflict = new Document(DocumentMetadata.EMPTY, PAGE,
                List.of(new Paragraph("before", "TOP", PARAGRAPH), new ComponentBlock("host", new SemanticTable(table), 0, 0)));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(topConflict));
    }

    @Test void cachedParentHeadersRejectNewBlockTreesInBothPreparationModes() {
        var table = outer("outer", new Paragraph("before", "BEFORE", PARAGRAPH), inner(List.of(text("INNER"))),
                new Paragraph("after", "AFTER", PARAGRAPH));
        for (boolean split : List.of(false, true)) {
            var grid = GridTable.builder().columns(1).headerRows(1).allowRowSplit(split)
                    .row(row -> row.semanticTable(table)).row(row -> row.cell("BODY")).build();
            var failure = assertThrows(IllegalArgumentException.class, () -> grid.layoutParts(new ComponentContext(380, 260, fonts, 0, 0, 1)));
            assertTrue(failure.getMessage().contains("block-cell"));
        }
        var fields = outer("field-outer", new Paragraph("before", "BEFORE", PARAGRAPH),
                inner(List.of(field(InlineField.Kind.PAGE, "", 18))), new Paragraph("after", "AFTER", PARAGRAPH));
        assertThrows(IllegalArgumentException.class, () -> new TableComponent(fields));
        var staticLayout = new TableComponent(table).layout(new ComponentContext(380, 260, fonts, 0, 0, 1));
        assertEquals(3, staticLayout.textLines());
    }

    private static Table outer(String id, Block before, Table inner, Block after) {
        return Table.builder(id).format(new TableFormat(new PageMargins(5, 7, 11, 13), 0, 0, false))
                .row(TableCell.builder().format(CELL).blocks(List.of(before, inner, after)).build()).build();
    }
    private static Table inner(List<? extends InlineContent> content) {
        return Table.builder("inner").format(new TableFormat(new PageMargins(3, 4, 5, 6), 0, 0, false))
                .row(TableCell.mixed(content, CELL)).build();
    }
    private static Document document(Table table) { return new Document(DocumentMetadata.EMPTY, PAGE, List.of(table)); }
    private static InlineText text(String value) { return new InlineText(value, STYLE); }
    private static InlineField field(InlineField.Kind kind, String target, double width) { return new InlineField(kind, target, width, STYLE); }
    private static List<CellLocation> innerPath() { return List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, -1)); }
    private static InlineImage image() throws IOException {
        var bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes)) { throw new IOException("PNG encoder unavailable"); }
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "Generated nested image", "https://example.com/image");
    }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(TextOutline::logicalText).collect(Collectors.joining())).collect(Collectors.joining());
    }
    private static List<Rect> images(DisplayPage page) {
        var result = new ArrayList<Rect>();
        var offsets = new ArrayDeque<Point>();
        Point offset = new Point(0, 0);
        for (var command : page.commands()) {
            switch (command) {
                case DrawCommand.Save ignored -> offsets.push(offset);
                case DrawCommand.Restore ignored -> offset = offsets.pop();
                case DrawCommand.Transform transform -> offset = new Point(offset.x() + transform.matrix().e(), offset.y() + transform.matrix().f());
                case DrawCommand.Image image -> result.add(new Rect(offset.x() + image.bounds().x(), offset.y() + image.bounds().y(),
                        image.bounds().width(), image.bounds().height()));
                default -> { }
            }
        }
        return result;
    }
}
