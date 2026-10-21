// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;

class XmlBlockCellsIntegrationTest {
    private static final PageSettings PAGE = new PageSettings(new PageSize(420, 300), PageMargins.uniform(20));
    private static final CellFormat CELL = new CellFormat(10, 18, ParagraphAlignment.LEFT);
    private static final ParagraphFormat PARAGRAPH = new ParagraphFormat(10, 18, 0, 0,
            ParagraphAlignment.LEFT, 0, 0, false);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = XmlBlockCellsIntegrationTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void finalFieldsAndDistinctDescendantAnchorsSurviveDualSpansContentsAndSectionRestart() throws IOException {
        var rendered = compiler().compile(xml(dualSpans("L P=<page-number width='18'/> N=<page-count width='18'/>"
                + " R=<page-reference target='target' width='18'/> #=<number-reference target='target' width='18'/>")
                + "<numbered id='target' list-id='steps' restart='3'>Target</numbered>"
                + "<p id='outside'>BACK=<page-reference target='after' width='18'/></p>"))
                .render(Map.of());
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, rendered.blocks().subList(0, 1), 0),
                new Section("second", PAGE, rendered.blocks().subList(1, 3), 7)));
        var navigation = Navigation.builder().bookmark("Before", "before", 1).bookmark("Inner", "inner", 1)
                .bookmark("Leaf", "leaf", 1).bookmark("After", "after", 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(source);
        assertEquals(1, result.bodyPageOffset());
        assertEquals(3, result.display().pages().size());
        var outer = outer(result, 0);
        var blocks = outer.rows().getFirst().cells().getFirst().blocks();
        assertEquals("B P=2", logical(((ParagraphFragment) blocks.get(0)).layout()));
        assertEquals("L P=2 N=3 R=7 #=3", logical(leaf(outer).layout()));
        assertEquals("A R=2", logical(((ParagraphFragment) blocks.get(2)).layout()));
        assertEquals("BACK=2", logical(result.body().pages().getLast().fragments().getLast().layout()));
        var nested = (TableFragment) blocks.get(1);
        assertEquals(outer.rows().getFirst().cells().getFirst().bounds().width() - 12, nested.width(), 1e-9);
        assertEquals(List.of(new Point(26, 26), new Point(26, 44), new Point(32, 50), new Point(26, 80), new Point(20, 20)),
                result.display().extras().bookmarks().stream().map(bookmark -> bookmark.destination()).toList());
        assertEquals(List.of(1, 1, 1, 1, 2), result.display().extras().bookmarks().stream().map(bookmark -> bookmark.pageIndex()).toList());
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() == 1
                && link.targetPageIndex() == 2 && link.destination().equals(new Point(20, 20))));
        assertTrue(result.display().extras().links().stream().anyMatch(link -> link.pageIndex() == 2
                && link.targetPageIndex() == 1 && link.destination().equals(new Point(26, 80))));
        var sourceOuter = (Table) rendered.blocks().getFirst();
        assertEquals("L P=\ufffc N=\ufffc R=\ufffc #=\ufffc", leaf(sourceOuter).text());
        assertEquals(List.of(4, 8, 12, 16), leaf(sourceOuter).fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
        assertTrue(leaf(outer).layout().lines().stream().allMatch(line -> line.fields().isEmpty()));

        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        var imported = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        assertEquals(7, imported.document().sections().getLast().firstPageNumber());
        var importedOuter = (Table) imported.document().sections().getFirst().blocks().getFirst();
        assertMerges(importedOuter);
        assertEquals(leaf(sourceOuter).text(), leaf(importedOuter).text());
        assertEquals(leaf(sourceOuter).format(), leaf(importedOuter).format());
        assertEquals(leaf(sourceOuter).fields(), leaf(importedOuter).fields());
        String nativeXml = new String(parts(output.toByteArray()).get("word/document.xml"), StandardCharsets.UTF_8);
        assertEquals(2, occurrences(nativeXml, "<w:tbl "));
        assertEquals(2, occurrences(nativeXml, "<w:vMerge w:val=\"restart\"/>"));
        assertEquals(2, occurrences(nativeXml, "<w:vMerge w:val=\"continue\"/>"));
        assertEquals(7, occurrences(nativeXml, "<w:fldSimple "));
        assertTrue(nativeXml.contains("PAGEREF") && nativeXml.contains("NUMPAGES") && nativeXml.contains("REF "));
    }

    @Test
    void eachCopiesResolveTheirOwnDescendantReferencesOnDistinctFinalPages() {
        String repeated = "<p id='prefix'>Prefix</p><page-break/><each items='items' as='item'>"
                + simpleTable("outer", "<p id='before'>R=<page-reference target='leaf' width='18'/></p>"
                        + simpleTable("inner", "<p id='leaf'>${item} P=<page-number width='18'/> T=<page-count width='18'/></p>"))
                + "<page-break/></each>";
        var compiled = compiler().compile(xml(repeated));
        var source = compiled.render(Map.of("items", List.of("A", "B")));
        var navigation = Navigation.builder().bookmark("A", "leaf@0", 1).bookmark("B", "leaf@1", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var result = DocumentEngine.builder(fonts).navigation(navigation).build().layout(source);
        assertEquals(4, result.display().pages().size());
        assertEquals(List.of(2, 3), result.display().extras().bookmarks().stream().map(bookmark -> bookmark.pageIndex()).toList());
        for (int index = 0; index < 2; index++) {
            var table = outer(result, index + 1);
            assertEquals("outer@" + index, table.blockId());
            var before = (ParagraphFragment) table.rows().getFirst().cells().getFirst().blocks().getFirst();
            assertEquals("R=" + (index + 3), logical(before.layout()));
            assertEquals((index == 0 ? "A" : "B") + " P=" + (index + 3) + " T=4", logical(leaf(table).layout()));
            assertEquals("leaf@" + index, leaf(table).blockId());
        }
        var missing = compiler().compile(xml(repeated + "<p>Outside=<page-reference target='leaf' width='18'/></p>"))
                .render(Map.of("items", List.of("A", "B")));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().layout(missing));
    }

    @Test
    void dynamicRowsRelocateDeepReferencesPerRowAndUseTheirActualPage() {
        var smallPage = new PageSettings(new PageSize(240, 120), PageMargins.uniform(10));
        var source = compiler(smallPage).compile(xml("<rows id='records' items='items' as='item'>"
                + "<columns><column weight='1'/></columns><row><cell>"
                + "<p id='row-before'>R=<page-reference target='row-leaf' width='18'/></p>"
                + simpleTable("row-inner", "<p id='row-leaf'>${item} P=<page-number width='18'/></p>")
                + "</cell></row></rows>")).render(Map.of("items", List.of("A", "B")));
        var result = DocumentEngine.builder(fonts).build().layout(source);
        assertEquals(2, result.body().pages().size());
        var model = (Table) source.blocks().getFirst();
        for (int index = 0; index < 2; index++) {
            String suffix = "@row:records:" + index;
            var sourceBefore = (Paragraph) model.rows().get(index).cells().getFirst().blocks().getFirst();
            assertEquals("row-leaf" + suffix, sourceBefore.fields().getFirst().field().targetId());
            var table = outer(result, index);
            assertEquals(index, table.rows().getFirst().rowIndex());
            var before = (ParagraphFragment) table.rows().getFirst().cells().getFirst().blocks().getFirst();
            assertEquals("R=" + (index + 1), logical(before.layout()));
            assertEquals("row-leaf" + suffix, leaf(table).blockId());
            assertEquals((index == 0 ? "A" : "B") + " P=" + (index + 1), logical(leaf(table).layout()));
        }
        assertEquals(List.of(0, 1), result.display().extras().links().stream().map(link -> link.targetPageIndex()).toList());
    }

    @Test
    void deepLinkedImagesAndUtf16WarningsRetainTheirSourcesAndStandardDocxMedia() throws IOException {
        var resource = image();
        String content = "<link href='https://example.com/text'>${prefix}<span underline='true'>LINK</span></link>"
                + "<link href='https://example.com/image'><image ref='mark' width='12' height='12' alt='Marker ${prefix}'/></link>"
                + "<page-number width='18'/>";
        var source = XmlTemplate.builder().pageSettings(PAGE).bodyFormat(PARAGRAPH).cellFormat(CELL)
                .image("mark", resource).build().compile(xml(dualSpans(content))).render(Map.of("prefix", "A😀"));
        var model = (Table) source.blocks().getFirst();
        assertEquals("A😀LINK\ufffc\ufffc", leaf(model).text());
        assertEquals(7, leaf(model).images().getFirst().sourceIndex());
        assertEquals(8, leaf(model).fields().getFirst().sourceIndex());
        var result = DocumentEngine.builder(FontGroup.placeholder(fonts.fonts(), '?')).build().layout(source);
        var warning = result.contextualDiagnostics().stream().filter(value -> value.blockId().equals("leaf")
                && value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).findFirst().orElseThrow();
        assertEquals(deepPath(), warning.sourcePath());
        assertEquals(1, warning.diagnostic().sourceStart());
        assertEquals(3, warning.diagnostic().sourceEnd());
        var imageLinks = result.display().extras().externalLinks().stream()
                .filter(link -> link.destination().uri().endsWith("/image")).toList();
        assertEquals(1, imageLinks.size());
        assertEquals(paintedImages(result.display().pages().getFirst()), List.of(imageLinks.getFirst().bounds()));
        assertEquals(12, imageLinks.getFirst().bounds().width());
        assertEquals(12, imageLinks.getFirst().bounds().height());
        var textLinks = result.display().extras().externalLinks().stream()
                .filter(link -> link.destination().uri().endsWith("/text")).toList();
        assertFalse(textLinks.isEmpty());
        assertTrue(textLinks.stream().allMatch(link -> link.pageIndex() == 0 && link.bounds().x() >= 32
                && Math.abs(link.bounds().y() - 50) < 1e-9 && link.bounds().width() > 0));

        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        var imported = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        var restored = (Table) imported.document().sections().getFirst().blocks().getFirst();
        assertMerges(restored);
        assertEquals(leaf(model).text(), leaf(restored).text());
        assertEquals(leaf(model).inlines(), leaf(restored).inlines());
        assertEquals(leaf(model).images(), leaf(restored).images());
        assertEquals(leaf(model).fields(), leaf(restored).fields());
        assertEquals(1, parts(output.toByteArray()).keySet().stream().filter(name -> name.startsWith("word/media/")).count());
    }

    @Test
    void finalFieldWidthFailureKeepsTheRootIndexDeepPathAndOriginalUtf16Position() {
        var source = compiler().compile(xml("<p id='prefix'>Prefix</p>" + simpleTable("outer",
                "<p id='before'>Before</p>" + simpleTable("inner", "<p id='leaf'>A😀Z R=<page-count width='0.01'/></p>"))))
                .render(Map.of());
        var failure = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(FontGroup.placeholder(fonts.fonts(), '?')).build().layout(source));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, failure.code());
        assertEquals("leaf", failure.blockId());
        assertEquals(1, failure.blockIndex());
        assertEquals(7, failure.sourceIndex());
        assertEquals(deepPath(), failure.sourcePath());
        assertFalse(failure.getMessage().contains("A😀Z"));
    }

    @Test
    void cancellationLeavesTheCompiledTemplateAndEngineReusableOutsideTheScope() throws IOException {
        var compiled = compiler().compile(xml(simpleTable("outer", "<p id='before'>Before</p>"
                + simpleTable("inner", "<p id='leaf'>P=<page-number width='18'/></p>"))));
        var source = compiled.render(Map.of());
        var engine = DocumentEngine.builder(fonts).build();
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("BODY") && progress.blockId().equals("outer")) { cancelled.set(true); }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> engine.layout(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        assertEquals("outer", failure.context().blockId());
        ExecutionContext.checkpoint();
        assertEquals(source.blocks(), compiled.render(Map.of()).blocks());
        assertEquals("P=1", logical(leaf(outer(engine.layout(source), 0)).layout()));
    }

    @Test
    void unsupportedTreesInUnselectedBranchesFailDuringCompilationWithSafeOwnSourceLocations() {
        assertUnselectedFailure(simpleTable("outer", "<p id='before'>Before</p>\n"
                        + simpleTable("inner", "<p id='inside'>Inside</p>\n" + simpleTable("third", "<p>SECRET</p>"))),
                TemplateException.Code.INVALID_STRUCTURE, "<table id='third'");
        assertUnselectedFailure(simpleTable("outer", "SECRET<p id='leaf'>Leaf</p>"),
                TemplateException.Code.INVALID_STRUCTURE, "<cell>");
        String header = "<table id='outer'><columns><column weight='1'/></columns><header>\n"
                + "<cell><p id='leaf'>SECRET</p></cell></header><row><cell>Body</cell></row></table>";
        assertUnselectedFailure(header, TemplateException.Code.INVALID_STRUCTURE, "<cell><p id='leaf'");
        String duplicate = simpleTable("outer", "<p id='leaf'>First</p>\n"
                + simpleTable("inner", "<p id='leaf'>SECRET</p>"));
        assertUnselectedFailure(duplicate, TemplateException.Code.INVALID_VALUE, "<p id='leaf'>SECRET");
        String split = simpleTable("outer", "<p id='before'>Before</p>\n"
                + "<table id='inner' row-split='true'><columns><column weight='1'/></columns><row><cell>SECRET</cell></row></table>");
        assertUnselectedFailure(split, TemplateException.Code.INVALID_VALUE, "<table id='inner'");
        assertUnselectedFailure(simpleTable("outer", "\n<p id='invalid' size='-123.456'>SECRET</p>"),
                TemplateException.Code.INVALID_VALUE, "<p id='invalid'");
    }

    private static XmlTemplate compiler() { return compiler(PAGE); }

    private static XmlTemplate compiler(PageSettings page) {
        return XmlTemplate.builder().pageSettings(page).bodyFormat(PARAGRAPH).cellFormat(CELL).build();
    }

    private static String xml(String body) { return "<document version='1'>" + body + "</document>"; }

    private static String simpleTable(String id, String content) {
        return "<table id='" + id + "'><columns><column weight='1'/></columns><row><cell>" + content
                + "</cell></row></table>";
    }

    private static String dualSpans(String leafContent) {
        return "<table id='outer'><columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>"
                + "<row><cell column-span='2' row-span='2'><p id='before'>B P=<page-number width='18'/></p>"
                + "<table id='inner'><columns><column weight='1'/><column weight='1'/></columns>"
                + "<row><cell column-span='2' row-span='2'><p id='leaf'>" + leafContent + "</p></cell></row><row/></table>"
                + "<p id='after'>A R=<page-reference target='leaf' width='18'/></p></cell><cell>Side A</cell></row>"
                + "<row><cell>Side B</cell></row></table>";
    }

    private static TableFragment outer(ComponentDocumentLayout result, int bodyPage) {
        return result.body().pages().get(bodyPage).elements().stream().filter(TableFragment.class::isInstance)
                .map(TableFragment.class::cast).findFirst().orElseThrow();
    }

    private static ParagraphFragment leaf(TableFragment table) {
        var inner = (TableFragment) table.rows().getFirst().cells().getFirst().blocks().get(1);
        return (ParagraphFragment) inner.rows().getFirst().cells().getFirst().blocks().getFirst();
    }

    private static Paragraph leaf(Table table) {
        var inner = (Table) table.rows().getFirst().cells().getFirst().blocks().get(1);
        return (Paragraph) inner.rows().getFirst().cells().getFirst().blocks().getFirst();
    }

    private static void assertMerges(Table outer) {
        var cell = outer.rows().getFirst().cells().getFirst();
        assertEquals(2, cell.columnSpan());
        assertEquals(2, cell.rowSpan());
        var inner = (Table) cell.blocks().get(1);
        assertEquals(2, inner.rows().getFirst().cells().getFirst().columnSpan());
        assertEquals(2, inner.rows().getFirst().cells().getFirst().rowSpan());
        assertTrue(inner.rows().getLast().cells().isEmpty());
    }

    private static List<CellLocation> deepPath() {
        return List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, 0));
    }

    private static String logical(ParagraphLayout paragraph) {
        var result = new StringBuilder();
        for (var line : paragraph.lines()) {
            if (line.outlines().isEmpty()) { line.runs().forEach(run -> result.append(run.text())); }
            else { line.outlines().forEach(outline -> result.append(outline.logicalText())); }
        }
        return result.toString();
    }

    private static List<Rect> paintedImages(DisplayPage page) {
        var result = new ArrayList<Rect>();
        var stack = new ArrayDeque<Point>();
        Point offset = new Point(0, 0);
        for (var command : page.commands()) {
            switch (command) {
                case DrawCommand.Save ignored -> stack.push(offset);
                case DrawCommand.Restore ignored -> offset = stack.pop();
                case DrawCommand.Transform transform -> offset = new Point(offset.x() + transform.matrix().e(), offset.y() + transform.matrix().f());
                case DrawCommand.Image image -> result.add(new Rect(offset.x() + image.bounds().x(), offset.y() + image.bounds().y(),
                        image.bounds().width(), image.bounds().height()));
                default -> { }
            }
        }
        return result;
    }

    private static void assertUnselectedFailure(String fragment, TemplateException.Code code, String ownElement) {
        String source = "<document version='1'>\n<if test='flag'><then><p>Safe</p></then><else>\n" + fragment
                + "\n</else></if></document>";
        int expectedLine = 1 + (int) source.substring(0, source.indexOf(ownElement)).chars().filter(value -> value == '\n').count();
        var failure = assertThrows(TemplateException.class, () -> compiler().compile(source));
        assertEquals(code, failure.code());
        assertEquals(expectedLine, failure.line());
        assertTrue(failure.column() > 0);
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("123.456"));
    }

    private static int occurrences(String source, String token) {
        return source.split(java.util.regex.Pattern.quote(token), -1).length - 1;
    }

    private static Map<String, byte[]> parts(byte[] bytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                result.put(entry.getName(), input.readAllBytes());
            }
        }
        return result;
    }

    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return ImageResource.load(output.toByteArray());
    }
}
