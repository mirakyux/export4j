// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentThemeCompositionTest {
    private static FontGroup regular;
    private static FontGroup bold;

    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf"); bold = font("LiberationSans-Bold.ttf");
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = DocumentThemeCompositionTest.class.getResourceAsStream("/fonts/" + name)) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static DocumentTheme theme(double size, double height) {
        return DocumentTheme.builder().bodyFormat(format -> format.fontSize(size).lineHeight(height).spaceAfter(0)).build();
    }

    private static List<DrawCommand.GlyphText> glyphs(ComponentDocumentLayout result) {
        return result.display().pages().stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }

    private static String logical(ComponentDocumentLayout result) {
        return result.display().pages().stream().flatMap(page -> page.commands().stream()).map(command -> {
            if (command instanceof DrawCommand.GlyphText glyph) { return glyph.run().text(); }
            if (command instanceof DrawCommand.OutlineText outline) { return outline.logicalText(); }
            return "";
        }).collect(Collectors.joining());
    }

    private static Document defaultFixture(Documents.Builder builder) {
        return builder.pageSettings(PageSettings.builder().size(220, 180).margins(10).build())
                .heading("Report", 1).paragraph("Body")
                .column(column -> column.text("Column A").text("Column B"))
                .grid(grid -> grid.defaults(cell -> cell.padding(0)).row(row -> row.cell("Grid")))
                .table(List.of("Header"), List.of(List.of("Value"))).build();
    }

    @Test void defaultThemeKeepsEffectiveLayoutSourcePaginationAndDrawnGeometry() {
        var engine = DocumentEngine.builder(regular).build();
        var plain = engine.layout(defaultFixture(Documents.builder()));
        var themed = engine.layout(defaultFixture(Documents.builder().theme(DocumentTheme.builder().build())));
        assertEquals(plain.body(), themed.body());
        assertEquals(glyphs(plain), glyphs(themed));
        assertEquals(plain.contextualDiagnostics(), themed.contextualDiagnostics());
        assertEquals(plain.display().extras(), themed.display().extras());
    }

    @Test void componentDefaultsAreTakenWhenAddedAndThemeDoesNotEraseExplicitCellDefaults() {
        var first = theme(10, 20).toBuilder().cellFormat(CellFormat.builder().fontSize(8).lineHeight(16).build()).build();
        var builder = Documents.builder().theme(first).component("a", FlowText.builder("A").build())
                .table(table -> table.header("H").row("V"));
        var snapshot = builder.build();
        var source = builder.bodyFormat(format -> format.fontSize(12).lineHeight(24))
                .component("b", FlowText.builder("B").build()).theme(theme(14, 28))
                .component("c", FlowText.builder("C").build()).build();
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertEquals(2, snapshot.blocks().size());
        assertEquals(8, ((Table) snapshot.blocks().get(1)).rows().getFirst().cells().getFirst().format().fontSize());
        assertEquals(List.of(10.0, 12.0, 14.0), glyphs(result).stream()
                .filter(value -> List.of("A", "B", "C").contains(value.run().text())).map(value -> value.run().fontSize()).toList());
        var heights = result.body().pages().stream().flatMap(page -> page.elements().stream())
                .filter(ComponentFragment.class::isInstance).map(ComponentFragment.class::cast)
                .map(value -> value.layout().height()).toList();
        assertEquals(List.of(20.0, 24.0, 28.0), heights);
    }

    private static Component observed(FlowText text, AtomicReference<ParagraphStyle> actual) {
        return context -> {
            var resolved = text.resolved(context); actual.set(resolved.style()); return resolved.layout(context);
        };
    }

    @Test void themeSixPropertiesComposeWithLocalOverridesRecoveryAndLegacyCompleteStyle() {
        var format = ParagraphFormat.builder().fontSize(10).lineHeight(24).firstLineIndent(7).subsequentLineIndent(3)
                .alignment(ParagraphAlignment.RIGHT).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).spaceAfter(0).build();
        var first = new AtomicReference<ParagraphStyle>(); var second = new AtomicReference<ParagraphStyle>();
        var complete = new AtomicReference<ParagraphStyle>();
        var source = Documents.builder().theme(DocumentTheme.builder().bodyFormat(format).build())
                .component("partial", observed(FlowText.builder("A\nB").fontSize(12).firstLineIndent(0).build(), first))
                .component("recovered", StyleScope.builder(observed(FlowText.builder("C\nD").fontSize(12)
                        .inheritFontSize().build(), second)).fontSize(14).alignment(TextAlignment.LEFT).build())
                .component("complete", observed(FlowText.builder("E\nF").style(style -> style.alignment(TextAlignment.LEFT)).build(), complete))
                .build();
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertEquals(new ParagraphStyle(12, 24, 0, 3, TextAlignment.RIGHT, LineBreakPolicy.CJK_STRICT), first.get());
        assertEquals(new ParagraphStyle(14, 24, 7, 3, TextAlignment.LEFT, LineBreakPolicy.CJK_STRICT), second.get());
        assertEquals(new ParagraphStyle(12, 18, 0, 0, TextAlignment.LEFT), complete.get());
        assertEquals(List.of(12.0, 12.0, 14.0, 14.0, 12.0, 12.0), glyphs(result).stream().map(value -> value.run().fontSize()).toList());
        assertTrue(glyphs(result).stream().allMatch(value -> value.color().equals(RgbColor.BLACK)));
    }

    @Test void richForwardingRetainsCompleteNamedInkAndUsesRegisteredPhysicalFont() {
        var ink = InlineStyle.builder().fontRole("bold").fontSize(9).rgb(0x224488).underline(true).build();
        var theme = theme(12, 24).toBuilder().inlineStyle("emphasis", ink)
                .paragraphStyle("note", ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceAfter(0).build()).build();
        var runs = List.of(new InlineText("Bold", theme.inlineStyle("emphasis")));
        var source = Documents.builder().theme(theme).paragraph("named", runs).paragraph("Note", theme.paragraphStyle("note"))
                .heading(runs, 1).bullet(runs).numbered("items", runs).build();
        assertEquals(runs, ((Paragraph) source.blocks().getFirst()).inlines());
        assertEquals(theme.paragraphStyle("note"), ((Paragraph) source.blocks().get(1)).format());
        assertEquals(runs, ((Heading) source.blocks().get(2)).inlines());
        var result = DocumentEngine.builder(regular).fontRole("bold", bold).build().layout(source);
        var marked = glyphs(result).stream().filter(value -> value.run().text().equals("Bold")).toList();
        assertEquals(4, marked.size());
        assertTrue(marked.stream().allMatch(value -> value.run().font().id().equals(bold.fonts().getFirst().data().id())
                && value.run().fontSize() == 9 && value.run().rgb() == 0x224488 && value.run().underline()));
        assertEquals("bold", theme.inlineStyle("emphasis").fontRole());
    }

    @Test void everyThemedHeadingKeepsWithItsFollowingBodyAtThePageBoundary() {
        var page = PageSettings.builder().size(160, 100).margins(10).build();
        for (int level = 1; level <= 6; level++) {
            var theme = theme(10, 20).toBuilder().headingFormat(level,
                    format -> format.fontSize(10).lineHeight(20).spaceBefore(0).spaceAfter(0)).build();
            var source = Documents.builder().pageSettings(page).theme(theme)
                    .paragraph("prefix", "PREFIX").add(new Paragraph("tall-prefix", "SPACE",
                            ParagraphFormat.builder().fontSize(10).lineHeight(40).spaceAfter(0).build()))
                    .heading("heading", "HEADING", level).paragraph("body", "BODY").build();
            var result = DocumentEngine.builder(regular).build().layout(source);
            assertEquals(2, result.body().pages().size());
            for (String id : List.of("heading", "body")) {
                assertTrue(result.body().pages().get(1).elements().stream().anyMatch(value -> value.blockId().equals(id)));
                assertFalse(result.body().pages().getFirst().elements().stream().anyMatch(value -> value.blockId().equals(id)));
            }
        }
    }

    @Test void themedTableSplitSettingReachesSimpleShortcutAndLocalOverrideCanRejectIt() {
        var theme = theme(10, 20).toBuilder().tableFormat(format -> format.padding(0).spaceAfter(0).allowRowSplit(true)).build();
        var page = PageSettings.builder().size(140, 80).margins(10).build();
        var source = Documents.builder().pageSettings(page).theme(theme)
                .table(List.of("HEAD"), List.of(List.of("A\nB\nC\nD"))).build();
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertEquals(2, result.body().pages().size());
        var fragments = result.body().pages().stream().flatMap(value -> value.elements().stream())
                .filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList();
        assertEquals(List.of(0, 2), fragments.stream().map(value -> value.rows().getLast().cells().getFirst().firstLineIndex()).toList());
        assertTrue(fragments.getLast().rows().getFirst().repeatedHeader());
        var strict = Documents.builder().pageSettings(page).theme(theme)
                .table(table -> table.format(format -> format.allowRowSplit(false)).header("HEAD").row("A\nB\nC\nD")).build();
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(strict)).code());
    }

    @Test void shortFirstPageMergedGridAndStaticFormUseThemeLineFramesThroughContinuation() {
        var text = java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(index -> "☑ LINE" + index).collect(Collectors.joining("\n"));
        var source = Documents.builder().pageSettings(PageSettings.builder().size(180, 100).margins(10).build())
                .theme(theme(10, 20)).paragraph("PREFIX")
                .grid(grid -> grid.headerRows(1).allowRowSplit(true).defaults(cell -> cell.padding(0))
                        .row(row -> row.cell("HEAD"))
                        .row(row -> row.cell(FormText.builder(text).build(), cell -> cell.rowSpan(2))).row(row -> { })).build();
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertTrue(result.body().pages().size() > 1);
        String logical = logical(result);
        int previous = -1;
        for (int index = 1; index <= 8; index++) {
            String marker = "LINE" + index;
            assertEquals(1, logical.split(marker, -1).length - 1);
            int current = logical.indexOf(marker); assertTrue(current > previous); previous = current;
        }
        assertTrue(glyphs(result).stream().allMatch(value -> value.run().fontSize() == 10));
        for (var page : result.body().pages()) {
            for (var fragment : page.elements()) {
                if (fragment instanceof ComponentFragment component) { assertTrue(component.layout().height() <= 80); }
            }
        }
    }

    @Test void namedRoleWarningsKeepLeafUtf16AndTableRowColumnSources() {
        var theme = theme(12, 24).toBuilder().inlineStyle("warn", style -> style.fontRole("warn").fontSize(10)).build();
        var runs = List.of(new InlineText("A", InlineStyle.of(10)), new InlineText("😀", theme.inlineStyle("warn")));
        var source = Documents.builder().theme(theme).paragraph("source", runs)
                .table(table -> table.row(TableCell.rich(runs, theme.cellFormat()))).build();
        var result = DocumentEngine.builder(regular).fontRole("warn", FontGroup.placeholder(bold.fonts(), '?')).build().layout(source);
        var warnings = result.contextualDiagnostics().stream()
                .filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING).toList();
        assertEquals(2, warnings.size());
        assertEquals("source", warnings.getFirst().blockId()); assertEquals("BODY", warnings.getFirst().region());
        assertEquals(-1, warnings.getFirst().rowIndex());
        assertEquals(source.blocks().getLast().id(), warnings.getLast().blockId());
        assertEquals(0, warnings.getLast().rowIndex()); assertEquals(0, warnings.getLast().columnIndex());
        assertTrue(warnings.stream().allMatch(value -> value.diagnostic().sourceStart() == 1 && value.diagnostic().sourceEnd() == 3
                && value.diagnostic().sourceCodePoint() == 0x1f600 && value.diagnostic().fontId().equals(bold.fonts().getFirst().data().id())));
    }

    @Test void themeCannotResolveUnknownRoleOrExpandAnInsufficientFixedLineFrame() {
        var missing = theme(10, 20).toBuilder().inlineStyle("missing", style -> style.fontRole("missing")).build();
        var source = Documents.builder().theme(missing)
                .paragraph("source", List.of(new InlineText("SECRET", missing.inlineStyle("missing")))).build();
        var unknown = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(source));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, unknown.code()); assertEquals("source", unknown.blockId());
        assertFalse(unknown.getMessage().contains("SECRET"));
        var insufficient = Documents.builder().theme(theme(12, 5)).paragraph("tiny", "TEXT").build();
        var failure = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(insufficient));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code()); assertEquals("tiny", failure.blockId());
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, assertInstanceOf(TextLayoutException.class, failure.getCause()).code());
    }

    @Test void explicitSemanticTableInsideThemedComponentsRetainsItsOriginalFormatAndSource() {
        var format = CellFormat.builder().fontSize(12).lineHeight(18).build();
        var table = Table.builder("explicit-table").cellFormat(format).format(value -> value.padding(0).spaceAfter(0))
                .header("HEAD").row("EXPLICIT").build();
        var source = Documents.builder().theme(theme(16, 32)).column(column -> column.semanticTable(table)).build();
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertTrue(glyphs(result).stream().allMatch(value -> value.run().fontSize() == 12));
        assertEquals("HEADEXPLICIT", logical(result));
        var ids = new ArrayList<String>();
        for (var page : result.body().pages()) { for (var fragment : page.elements()) { ids.add(fragment.blockId()); } }
        assertFalse(ids.isEmpty());
        assertTrue(result.contextualDiagnostics().isEmpty());
        assertEquals(format, table.rows().getLast().cells().getFirst().format());
    }
}
