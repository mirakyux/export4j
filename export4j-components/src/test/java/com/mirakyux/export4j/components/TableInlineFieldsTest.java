// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextOutline;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableInlineFieldsTest {
    private static final PageSettings SETTINGS = new PageSettings(new PageSize(380, 180), PageMargins.uniform(12));
    private static final CellFormat CELL = new CellFormat(12, 22, com.mirakyux.export4j.model.ParagraphAlignment.LEFT);
    private static final TableFormat SPLIT = new TableFormat(PageMargins.uniform(3), 0, 0, true);
    private static FontGroup fonts;

    @BeforeAll
    static void fonts() throws IOException {
        var faces = new ArrayList<FontFace>();
        for (var name : List.of("LiberationSans-Regular.ttf", "LXGWWenKaiTC-Regular.ttf")) {
            try (var input = TableInlineFieldsTest.class.getResourceAsStream("/fonts/" + name)) {
                faces.add(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
            }
        }
        fonts = FontGroup.strict(faces);
    }

    @Test
    void repeatedHeadersResolveOnEachFinalPageWithContentsAndRestartedForwardReferences() {
        var rows = new ArrayList<TableRow>();
        rows.add(row(TableCell.mixed(List.of(text("HEADER="), field(InlineField.Kind.PAGE, ""), text(" TOTAL="),
                field(InlineField.Kind.PAGES, ""), text(" REF="), field(InlineField.Kind.REFERENCE_PAGE, "caption")), CELL)));
        for (int i = 0; i < 12; i++) { rows.add(row(new TableCell("ROW-" + i, CELL))); }
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), rows, 1, SPLIT);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("a", SETTINGS, List.of(new Heading("h", "Heading", 1, ParagraphFormat.of(12)), table), 0),
                new Section("b", SETTINGS, List.of(new Caption("caption", "Figure", "Target", ParagraphFormat.of(12))), 7)));
        var result = new ComponentDocumentRenderer().layout(source,
                source.sections().stream().map(s -> SectionTemplate.uniform(PageTemplate.EMPTY)).toList(),
                Navigation.fromHeadings(source), fonts, Map.of(), List.of());
        assertEquals(1, result.bodyPageOffset());
        int occurrences = 0;
        for (var page : result.body().pages()) {
            for (var fragment : page.elements()) {
                if (fragment instanceof TableFragment t) {
                    occurrences++;
                    var header = t.rows().getFirst();
                    assertEquals(0, header.rowIndex());
                    assertTrue(header.header());
                    assertEquals(occurrences > 1, header.repeatedHeader());
                    assertEquals("HEADER=" + (page.pageIndex() + 2) + " TOTAL=" + result.display().pages().size() + " REF=7",
                            logical(header.cells().getFirst().layout()));
                    var link = result.display().extras().links().stream().filter(l -> l.pageIndex() == page.pageIndex() + 1).findFirst().orElseThrow();
                    assertEquals(result.display().pages().size() - 1, link.targetPageIndex());
                    assertEquals(24, link.bounds().width());
                    assertEquals(t.origin().y() + header.cells().getFirst().textOrigin().y(), link.bounds().y(), 1e-9);
                }
            }
        }
        assertTrue(occurrences >= 3);
        assertEquals(occurrences + 1, result.display().extras().links().size());
    }

    @Test
    void splitCellsKeepSourceGeometryAutoWidthAndExternalLinksAfterPageFields() {
        var content = new ArrayList<InlineContent>();
        for (int i = 0; i < 12; i++) {
            content.add(text("LINE-" + i + "=")); content.add(field(InlineField.Kind.PAGE, ""));
            content.add(i == 11 ? new InlineText("LINK", InlineStyle.of(12), "https://example.com/cell") : text("\n"));
        }
        var table = new Table("t", List.of(new ColumnWidth.Auto(100, 320)),
                List.of(row(TableCell.mixed(content, CELL))), 0, SPLIT);
        var source = new Document(DocumentMetadata.EMPTY, SETTINGS, List.of(table));
        var raw = new DocumentLayouter().layout(source, fonts);
        assertThrows(IllegalArgumentException.class, () -> DocumentPainter.paint(raw));
        var result = new ComponentDocumentRenderer().layout(source, PageTemplate.EMPTY, Navigation.EMPTY, fonts);
        assertEquals(2, result.body().pages().size());
        for (int index = 0; index < raw.pages().size(); index++) {
            var before = (TableFragment) raw.pages().get(index).elements().getFirst();
            var after = (TableFragment) result.body().pages().get(index).elements().getFirst();
            assertEquals(before.origin(), after.origin());
            assertEquals(before.width(), after.width());
            assertEquals(before.height(), after.height());
            var a = before.rows().getFirst(); var b = after.rows().getFirst();
            assertEquals(a.continuedRow(), b.continuedRow());
            assertEquals(index > 0, b.continuedRow());
            var x = a.cells().getFirst(); var y = b.cells().getFirst();
            assertEquals(x.firstLineIndex(), y.firstLineIndex());
            assertEquals(x.bounds(), y.bounds());
            assertEquals(x.textOrigin(), y.textOrigin());
            assertEquals(x.layout().height(), y.layout().height());
            for (int line = 0; line < x.layout().lines().size(); line++) {
                var old = x.layout().lines().get(line); var resolved = y.layout().lines().get(line);
                assertEquals(List.of(old.sourceStart(), old.sourceEnd(), old.offsetX(), old.baselineY(), old.width()),
                        List.of(resolved.sourceStart(), resolved.sourceEnd(), resolved.offsetX(), resolved.baselineY(), resolved.width()));
                assertTrue(resolved.fields().isEmpty());
                String value = resolved.outlines().stream().map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining());
                String expected = "LINE-" + (x.firstLineIndex() + line) + "=" + (index + 1);
                assertTrue(value.startsWith(expected), () -> "expected=" + expected + " actual=" + value + " width=" + before.width()
                        + " lines=" + logical(y.layout()));
            }
        }
        var link = result.display().extras().externalLinks().getFirst();
        assertEquals(1, link.pageIndex());
        var last = (TableFragment) raw.pages().getLast().elements().getFirst();
        var cell = last.rows().getFirst().cells().getFirst();
        var line = cell.layout().lines().getLast();
        var box = line.fields().getFirst();
        double fieldEnd = last.origin().x() + cell.textOrigin().x() + line.offsetX() + box.x() + box.field().width();
        assertTrue(link.bounds().x() >= fieldEnd - .001, () -> "link=" + link.bounds().x() + " fieldEnd=" + fieldEnd);
    }

    @Test
    void resolutionFailuresReportOriginalTableRowColumnAndCellUtf16() {
        for (var field : List.of(new InlineField(InlineField.Kind.PAGE, "", .1, InlineStyle.of(12)),
                field(InlineField.Kind.REFERENCE_PAGE, "missing"))) {
            var table = new Table("t", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                    List.of(new TableRow(List.of(new TableCell("plain", CELL), TableCell.mixed(List.of(text("a "), field), CELL)))), 0, SPLIT);
            var error = assertThrows(DocumentLayoutException.class, () -> new ComponentDocumentRenderer().layout(
                    new Document(DocumentMetadata.EMPTY, SETTINGS, List.of(table)), PageTemplate.EMPTY, Navigation.EMPTY, fonts));
            assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, error.code());
            assertEquals("t", error.blockId());
            assertEquals(0, error.pageIndex());
            assertEquals(0, error.rowIndex());
            assertEquals(1, error.columnIndex());
            assertEquals(2, error.sourceIndex());
        }
    }

    @Test
    void cellGeneratedWarningsKeepRowColumnAndOriginalMarkerSeparateFromGeneratedUtf16() {
        var group = FontGroup.placeholder(List.of(fonts.fonts().getFirst()), '?');
        var field = new InlineField(InlineField.Kind.REFERENCE_NUMBER, "caption", 100, new InlineStyle("label", 12, 0x285080, 0, true, false));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)),
                List.of(row(TableCell.mixed(List.of(text("😀"), field), CELL))), 0, SPLIT);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", SETTINGS,
                List.of(table, new Caption("caption", "图", "Target", ParagraphFormat.of(12))), 0)));
        var result = new ComponentDocumentRenderer().layout(source, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)),
                Navigation.EMPTY, FontGroup.placeholder(fonts.fonts(), '?'), Map.of("label", group), List.of());
        var generated = result.contextualDiagnostics().stream().filter(d -> d.region().equals("INLINE_FIELD:2")
                && d.diagnostic().severity() == TextDiagnostic.Severity.WARNING).findFirst().orElseThrow();
        assertEquals("t", generated.blockId());
        assertEquals(0, generated.rowIndex());
        assertEquals(0, generated.columnIndex());
        assertEquals(0, generated.diagnostic().sourceStart());
        assertEquals('图', generated.diagnostic().sourceCodePoint());
    }

    @Test
    void generationAndLinkBudgetsAreSharedAcrossCellsAndRepeatedHeaders() {
        var field = field(InlineField.Kind.REFERENCE_PAGE, "t");
        var rows = new ArrayList<TableRow>();
        rows.add(row(TableCell.mixed(List.of(field), CELL)));
        for (int i = 0; i < 8; i++) { rows.add(row(new TableCell("body", CELL))); }
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), rows, 1, SPLIT);
        var raw = new DocumentLayouter().layout(new Document(DocumentMetadata.EMPTY, SETTINGS, List.of(table)), fonts);
        var anchors = Map.of("t", new ResolvedAnchor(0, new com.mirakyux.export4j.graphics.Point(12, 12)));
        for (boolean links : List.of(true, false)) {
            var error = assertThrows(DocumentLayoutException.class, () -> InlineFields.resolve(raw, 0, i -> i + 1,
                    anchors, Map.of(), List.of(), fonts, Map.of(), links ? 1 : 100, links ? 100 : 1));
            assertEquals(DocumentLayoutException.Code.BUDGET_EXCEEDED, error.code());
            assertEquals(1, error.pageIndex());
            assertEquals(0, error.rowIndex());
            assertEquals(0, error.columnIndex());
        }
    }

    private static TableRow row(TableCell cell) { return new TableRow(List.of(cell)); }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(12)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 24, InlineStyle.of(12)); }
    private static String logical(ParagraphLayout layout) {
        return layout.lines().stream().flatMap(line -> line.outlines().stream()).map(TextOutline::logicalText).collect(java.util.stream.Collectors.joining());
    }
}
