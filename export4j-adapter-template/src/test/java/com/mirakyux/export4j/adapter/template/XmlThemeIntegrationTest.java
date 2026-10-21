// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlThemeIntegrationTest {
    private static final PageSettings PAGE = PageSettings.builder().size(300, 260).margins(20).build();
    private static final String COLUMNS = "<columns><column weight='1'/></columns>";
    private static FontGroup regular;
    private static FontGroup bold;

    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf"); bold = font("LiberationSans-Bold.ttf");
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = XmlThemeIntegrationTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(24).spaceAfter(0))
                .tableFormat(format -> format.padding(4).spaceAfter(0))
                .paragraphStyle("note", ParagraphFormat.builder().fontSize(10).lineHeight(24).spaceAfter(0).build())
                .inlineStyle("ink", new InlineStyle("report-bold", 10, 0x224488, 2, true, true))
                .inlineStyle("warn", new InlineStyle("warn-bold", 10, 0, 0, false, false)).build();
    }

    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static XmlTemplate compiler(DocumentTheme theme) { return XmlTemplate.builder().pageSettings(PAGE).theme(theme).build(); }
    private static String table(String id, String content) {
        return "<table id='" + id + "'>" + COLUMNS + "<row><cell>" + content + "</cell></row></table>";
    }
    private static String deep(String content) {
        return "<p id='prefix'>Prefix</p>" + table("outer", "<p id='before'>Before</p>"
                + table("inner", "<p id='leaf' style='note'>" + content + "</p>"));
    }
    private static List<DrawCommand.GlyphText> glyphs(ComponentDocumentLayout result) {
        return result.display().pages().stream().flatMap(page -> page.commands().stream())
                .filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
    }
    private static TableFragment outer(ComponentDocumentLayout result) {
        return result.body().pages().getFirst().elements().stream().filter(TableFragment.class::isInstance)
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
    private static List<CellLocation> path() {
        return List.of(new CellLocation("outer", 0, 0, 1), new CellLocation("inner", 0, 0, 0));
    }
    private static String logical(ParagraphLayout paragraph) {
        return paragraph.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(outline -> outline.logicalText()).collect(Collectors.joining()))
                .collect(Collectors.joining());
    }

    @Test void defaultThemeKeepsActualLayoutSourcesGeometryAndDiagnostics() {
        String source = xml("<heading level='1' size='10' line-height='20'>Title</heading><p id='body'>A\nB</p>"
                + "<table row-split='true'>" + COLUMNS + "<header><cell>HEAD</cell></header><row><cell>C\nD\nE</cell></row></table>"
                + table("outer", "<p>Before</p>" + table("inner", "Inner") + "<p>After</p>"));
        var plain = XmlTemplate.builder().pageSettings(PAGE).build().compile(source).render(Map.of());
        var themed = compiler(DocumentTheme.builder().build()).compile(source).render(Map.of());
        var engine = DocumentEngine.builder(regular).build();
        var first = engine.layout(plain); var second = engine.layout(themed);
        assertEquals(plain, themed); assertEquals(first.body(), second.body());
        assertEquals(first.display().pages(), second.display().pages());
        assertEquals(first.display().extras(), second.display().extras());
        assertEquals(first.contextualDiagnostics(), second.contextualDiagnostics());
    }

    @Test void namedPhysicalRoleColorShiftAndDecorationsReachDrawnInkWithoutChangingPlainNeighbors() {
        var source = compiler(theme()).compile(xml("<p id='styled' style='note'>A<span style='ink'>BOLD</span>C</p>"))
                .render(Map.of());
        var result = DocumentEngine.builder(regular).fontRole("report-bold", bold).build().layout(source);
        var runs = glyphs(result);
        var marked = runs.stream().filter(value -> value.run().text().equals("BOLD")).findFirst().orElseThrow();
        assertEquals(bold.fonts().getFirst().data().id(), marked.run().font().id());
        assertEquals(10, marked.run().fontSize()); assertEquals(0x224488, marked.run().rgb());
        assertEquals(2, marked.run().baselineShift());
        assertTrue(marked.run().underline()); assertTrue(marked.run().strikeThrough());
        for (var plain : runs.stream().filter(value -> !value.run().text().equals("BOLD")).toList()) {
            assertEquals(regular.fonts().getFirst().data().id(), plain.run().font().id());
            assertEquals(0, plain.run().rgb()); assertEquals(0, plain.run().baselineShift());
            assertFalse(plain.run().underline()); assertFalse(plain.run().strikeThrough());
        }
        assertEquals("ABOLDC", logical(result.body().pages().getFirst().fragments().getFirst().layout()));
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void everyThemedHeadingActuallyKeepsItsFollowingBodyOnTheNextPage() {
        var page = PageSettings.builder().size(160, 100).margins(10).build();
        for (int level = 1; level <= 6; level++) {
            var selected = DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                    .headingFormat(level, format -> format.fontSize(10).lineHeight(20).spaceBefore(0).spaceAfter(0)).build();
            var source = XmlTemplate.builder().pageSettings(page).theme(selected).build().compile(xml("<p id='prefix'>Prefix</p>"
                    + "<p id='space' line-height='40'>Space</p><heading id='heading' level='" + level + "'>Heading</heading>"
                    + "<p id='body'>Body</p>")).render(Map.of());
            var result = DocumentEngine.builder(regular).build().layout(source);
            assertEquals(2, result.body().pages().size());
            for (String id : List.of("heading", "body")) {
                assertFalse(result.body().pages().getFirst().elements().stream().anyMatch(value -> value.blockId().equals(id)));
                assertTrue(result.body().pages().getLast().elements().stream().anyMatch(value -> value.blockId().equals(id)));
            }
        }
    }

    @Test void themedSplitAndRepeatedHeadersSurviveDynamicRowsAndExplicitFalseStillFails() {
        var selected = DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .tableFormat(format -> format.padding(0).spaceAfter(0).allowRowSplit(true)).build();
        var page = PageSettings.builder().size(140, 80).margins(10).build();
        String rows = "<rows items='items' as='item'%s>" + COLUMNS + "<header><cell>HEAD</cell></header>"
                + "<row><cell><span>${item}</span></cell></row></rows>";
        var compiler = XmlTemplate.builder().pageSettings(page).theme(selected).build();
        var data = Map.of("items", List.of("A\nB\nC\nD"));
        var result = DocumentEngine.builder(regular).build().layout(compiler.compile(xml(rows.formatted(""))).render(data));
        assertEquals(2, result.body().pages().size());
        var fragments = result.body().pages().stream().flatMap(value -> value.elements().stream())
                .filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList();
        assertEquals(List.of(0, 2), fragments.stream().map(value -> value.rows().getLast().cells().getFirst().firstLineIndex()).toList());
        assertTrue(fragments.getLast().rows().getFirst().repeatedHeader());
        assertEquals(List.of("AB", "CD"), fragments.stream().map(value -> logical(value.rows().getLast().cells().getFirst().layout())).toList());
        var unsplit = compiler.compile(xml(rows.formatted(" row-split='false'"))).render(data);
        assertEquals(DocumentLayoutException.Code.CONTENT_TOO_TALL,
                assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(unsplit)).code());
    }

    @Test void namedDeepWarningsImagesLinksAndFinalFieldsKeepTheirLeafSourcesAndPaintedBounds() throws IOException {
        var resource = image();
        String content = "A<span style='warn'>😀</span><link href='https://example.com/text'><span style='ink'>LINK</span></link>"
                + "<link href='https://example.com/image'><image ref='mark' width='8' height='9' alt='Marker'/></link>"
                + " P=<page-number width='18'/><page-reference target='leaf' width='18'/>";
        var source = XmlTemplate.builder().pageSettings(PAGE).theme(theme()).image("mark", resource).build()
                .compile(xml(deep(content))).render(Map.of());
        var original = leaf((Table) source.blocks().getLast());
        assertEquals("A😀LINK\ufffc P=\ufffc\ufffc", original.text());
        assertEquals(7, original.images().getFirst().sourceIndex());
        assertEquals(List.of(11, 12), original.fields().stream().map(Paragraph.FieldSpan::sourceIndex).toList());
        var result = DocumentEngine.builder(regular).fontRole("report-bold", bold)
                .fontRole("warn-bold", FontGroup.placeholder(bold.fonts(), '?')).build().layout(source);
        var warnings = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(1, warnings.size());
        var warning = warnings.getFirst();
        assertEquals("leaf", warning.blockId()); assertEquals(0, warning.pageIndex());
        assertEquals("TABLE", warning.region());
        assertEquals(path(), warning.sourcePath()); assertEquals(0, warning.rowIndex()); assertEquals(0, warning.columnIndex());
        assertEquals(1, warning.diagnostic().sourceStart()); assertEquals(3, warning.diagnostic().sourceEnd());
        assertEquals(0x1f600, warning.diagnostic().sourceCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), warning.diagnostic().fontId());
        assertTrue(logical(leaf(outer(result)).layout()).endsWith(" P=11"));
        assertEquals(13, leaf(outer(result)).layout().lines().getFirst().sourceEnd());
        assertEquals(7, leaf(outer(result)).layout().lines().getFirst().images().getFirst().sourceIndex());
        assertTrue(leaf(outer(result)).layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
        var imageLinks = result.display().extras().externalLinks().stream().filter(link -> link.destination().uri().endsWith("/image")).toList();
        assertEquals(1, imageLinks.size());
        assertEquals(paintedImages(result.display().pages().getFirst()), List.of(imageLinks.getFirst().bounds()));
        assertEquals(8, imageLinks.getFirst().bounds().width()); assertEquals(9, imageLinks.getFirst().bounds().height());
        assertTrue(result.display().extras().externalLinks().stream().anyMatch(link -> link.destination().uri().endsWith("/text")
                && link.bounds().width() > 0 && link.bounds().x() >= 20 && link.bounds().y() >= 20));
        assertEquals(1, result.display().extras().links().size());
        assertEquals(0, result.display().extras().links().getFirst().targetPageIndex());
    }

    @Test void unknownRolesStrictMissingGlyphsAndTooSmallFixedFramesStillFailAtTheNamedLeaf() {
        var unknown = compiler(theme()).compile(xml(deep("A<span style='ink'>SECRET</span>"))).render(Map.of());
        var missingRole = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(unknown));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, missingRole.code());
        assertEquals("leaf", missingRole.blockId()); assertEquals(path(), missingRole.sourcePath());
        assertEquals(1, missingRole.sourceIndex()); assertFalse(missingRole.getMessage().contains("SECRET"));
        var unsupported = compiler(theme()).compile(xml(deep("A<span style='warn'>😀</span>"))).render(Map.of());
        var strict = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(regular).fontRole("warn-bold", bold).build().layout(unsupported));
        assertEquals(1, strict.sourceIndex()); assertEquals("leaf", strict.blockId()); assertEquals(path(), strict.sourcePath());
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, textCause(strict).code());
        var selected = theme().toBuilder().paragraphStyle("tiny", ParagraphFormat.builder().fontSize(10).lineHeight(5).spaceAfter(0).build()).build();
        var tiny = compiler(selected).compile(xml(table("outer", "<p id='tiny' style='tiny'><span style='ink'>TEXT</span></p>")))
                .render(Map.of());
        var frame = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(regular).fontRole("report-bold", bold).build().layout(tiny));
        assertEquals("tiny", frame.blockId());
        assertEquals(TextLayoutException.Code.LINE_HEIGHT_TOO_SMALL, textCause(frame).code());
    }

    @Test void namedParagraphAndInkRemainNativeSemanticValuesThroughStrictDocxRoundTrip() throws IOException {
        var resource = image();
        String sourceXml = xml(deep("A<span style='ink'>Bold</span><link href='https://example.com/report'>"
                + "<span style='ink' role='' color='#000000' underline='false' strike='false'>Black</span>"
                + "<image ref='mark' width='8' height='9' alt='Marker'/></link><page-reference target='leaf' width='18'/>"));
        var source = XmlTemplate.builder().pageSettings(PAGE).theme(theme()).image("mark", resource).build().compile(sourceXml).render(Map.of());
        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        var restored = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        var before = leaf((Table) source.blocks().getLast());
        var after = leaf((Table) restored.document().sections().getFirst().blocks().getLast());
        assertEquals(before.id(), after.id()); assertEquals(before.format(), after.format());
        assertEquals(before.text(), after.text()); assertEquals(before.inlines(), after.inlines());
        assertEquals(before.images(), after.images()); assertEquals(before.fields(), after.fields());
        assertEquals(((Table) source.blocks().getLast()).format(), ((Table) restored.document().sections().getFirst().blocks().getLast()).format());
    }

    @Test void cancellationDuringNamedContentLayoutRestoresTheScopeAndLeavesCompilerAndEngineReusable() throws IOException {
        var compiled = compiler(theme()).compile(xml(deep("A<span style='ink'>BOLD</span><page-number width='18'/>")));
        var source = compiled.render(Map.of());
        var engine = DocumentEngine.builder(regular).fontRole("report-bold", bold).build();
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("BODY") && progress.blockId().equals("outer")) { cancelled.set(true); }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> engine.layout(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals("outer", failure.context().blockId());
        ExecutionContext.checkpoint();
        assertEquals(source, compiled.render(Map.of()));
        assertEquals("ABOLD1", logical(leaf(outer(engine.layout(source))).layout()));
    }

    private static TextLayoutException textCause(Throwable failure) {
        var cause = failure;
        while (!(cause instanceof TextLayoutException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return assertInstanceOf(TextLayoutException.class, cause);
    }

    private static List<Rect> paintedImages(DisplayPage page) {
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

    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return ImageResource.load(output.toByteArray());
    }
}
