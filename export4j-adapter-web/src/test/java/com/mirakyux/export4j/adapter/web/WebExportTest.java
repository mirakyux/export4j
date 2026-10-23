// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebExportTest {
    @Test void pageOverlaysAreFlattenedWithExplicitGeometryAndPaintOrderLoss() throws IOException {
        var regions = PageRegions.builder()
                .background(new com.mirakyux.export4j.model.PageOverlay(0, 0, 100, new PageRegion(20, 0, List.of(p("bg", "BACKGROUND")))))
                .foreground(new com.mirakyux.export4j.model.PageOverlay(0, 0, 100, new PageRegion(20, 0, List.of(p("fg", "FOREGROUND"))))).build();
        var source = new PageDocument(doc(p("body", "BODY")), regions);
        var output = new ByteArrayOutputStream();
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(source).to(output)); assertEquals(0, output.size());
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source).to(output);
        String html = output.toString(StandardCharsets.UTF_8);
        assertTrue(html.indexOf("BACKGROUND</span>") < html.indexOf("BODY</span>"));
        assertTrue(html.indexOf("BODY</span>") < html.indexOf("FOREGROUND</span>"));
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.feature() == ExportFeature.PAGE_REGIONS && value.location().region().equals("BACKGROUND:0")));
        var markdown = text(MarkdownAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source));
        assertTrue(markdown.contains("BACKGROUND") && markdown.contains("FOREGROUND"));
    }

    @TempDir Path temporary;
    private static final ParagraphFormat BODY = ParagraphFormat.of(12);
    private static Document doc(Block... blocks) { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(blocks)); }
    private static Paragraph p(String id, String text) { return new Paragraph(id, text, BODY); }
    private static ExportPolicy all(UnsupportedAction action) { return ExportPolicy.builder().defaultAction(action).build(); }
    private static String text(WebExportRequest request) throws IOException {
        var output = new ByteArrayOutputStream(); request.to(output); return output.toString(StandardCharsets.UTF_8);
    }
    private static ImageResource image() throws IOException {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        var bytes = new ByteArrayOutputStream();
        try { assertTrue(ImageIO.write(image, "png", bytes)); } finally { image.flush(); }
        return ImageResource.load(bytes.toByteArray());
    }
    private static Table merged() {
        var inner = Table.text("inner", List.of("INNER-HEAD"), List.of(List.of("INNER-BODY")));
        return Table.builder("outer").columns(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1))
                .headerCells(TableCell.builder().text("MERGED-HEAD").columnSpan(2).build())
                .row(TableCell.builder().blocks(List.of(p("before", "BEFORE"), inner, p("after", "AFTER"))).build(),
                        TableCell.builder().text("TALL").rowSpan(2).build())
                .row(TableCell.builder().text("LAST").build()).build();
    }

    @Test void firstEvenBandsAreNeverSilentlyOmittedAndBlankSelectorsReportDegradation() throws IOException {
        var regions = PageRegions.builder().header(PageRegions.Variant.FIRST,
                PageRegion.builder().height(20).paragraph(p("first", "FIRST-CONTENT")).build())
                .header(PageRegions.Variant.EVEN, PageRegion.builder().height(20).paragraph(p("even", "EVEN-CONTENT")).build()).build();
        var source = new PageDocument(doc(p("body", "BODY")), regions);
        var output = new ByteArrayOutputStream();
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(source).to(output));
        assertEquals(0, output.size());
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source).to(output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("FIRST-CONTENT"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("EVEN-CONTENT"));
        assertTrue(result.diagnostics().stream().anyMatch(value -> value.location().region().equals("HEADER_FIRST")));
        var blank = new PageDocument(doc(p("body", "BODY")), PageRegions.builder().variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY).build());
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(blank).to(new ByteArrayOutputStream()));
    }

    @Test void htmlEscapesTextAttributesMetadataAndNeverAddsExecutableSource() throws IOException {
        var source = new Document(new DocumentMetadata("</title><script>alert(1)</script>", "\" onload=\"bad"), PageSettings.A4,
                List.of(p("\" onload=\"bad", "<script> & &#65; 😀 中文"), new Heading("h", "Title", 2)));
        String result = text(new HtmlAdapter().export(source));
        assertTrue(result.startsWith("<!doctype html>"));
        assertFalse(result.contains("<script>")); assertFalse(result.contains(" onload=\"bad\""));
        assertTrue(result.contains("&lt;script&gt; &amp; &amp;#65; 😀 中文"));
        assertTrue(result.contains("<h2")); assertTrue(result.contains("Content-Security-Policy"));
    }

    @Test void markdownEscapesLiteralMarkupEntitiesAndBlockSyntax() throws IOException {
        String result = text(new MarkdownAdapter().export(doc(p("p", "# [x](url) <script> &amp; * _ | ` !\n  indented\ttext"))));
        assertTrue(result.contains("\\# \\[x\\]\\(url\\) &lt;script&gt; &amp;amp\\;"), result);
        assertTrue(result.contains("\\* \\_ \\| \\` \\!<br>"), result);
        assertFalse(result.contains("<script>")); assertTrue(result.contains("&#9;"));
    }

    @Test void errorWarnIgnoreAndSpecificOverridesHaveDistinctObservableResults() throws IOException {
        var source = doc(p("p", "before"), new PageBreak("break"));
        var output = new ByteArrayOutputStream();
        var error = assertThrows(ExportException.class, () -> new MarkdownAdapter().export(source).to(output));
        assertEquals(ExportFeature.PAGE_BREAK, error.diagnostic().feature()); assertEquals(0, output.size());
        var warn = new ByteArrayOutputStream();
        var warned = MarkdownAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source).to(warn);
        var ignore = new ByteArrayOutputStream();
        var ignored = MarkdownAdapter.builder().policy(all(UnsupportedAction.IGNORE)).build().export(source).to(ignore);
        assertEquals(1, warned.diagnostics().size()); assertTrue(ignored.diagnostics().isEmpty());
        assertEquals(warn.toString(StandardCharsets.UTF_8), ignore.toString(StandardCharsets.UTF_8));
        var override = ExportPolicy.builder().defaultAction(UnsupportedAction.IGNORE).on(ExportFeature.PAGE_BREAK, UnsupportedAction.ERROR).build();
        assertThrows(ExportException.class, () -> MarkdownAdapter.builder().policy(override).build().export(source).to(output));
    }

    @Test void htmlPreservesSpansNestedBlockOrderAndActualAnchorsOnly() throws IOException {
        String result = text(new HtmlAdapter().export(doc(merged())));
        assertTrue(result.contains("colspan=\"2\"")); assertTrue(result.contains("rowspan=\"2\""));
        for (String token : List.of("MERGED-HEAD", "INNER-HEAD", "INNER-BODY", "BEFORE", "AFTER", "TALL", "LAST")) {
            assertEquals(1, result.split(token, -1).length - 1, token);
        }
        assertTrue(result.indexOf("BEFORE") < result.indexOf("INNER-BODY"));
        assertTrue(result.indexOf("INNER-BODY") < result.indexOf("AFTER"));
    }

    @Test void markdownTableFallbackKeepsEachAnchorAndNestedLeafOnce() throws IOException {
        var output = new ByteArrayOutputStream();
        var error = assertThrows(ExportException.class, () -> new MarkdownAdapter().export(doc(merged())).to(output));
        assertEquals(ExportFeature.TABLE_STRUCTURE, error.diagnostic().feature());
        assertEquals(0, error.diagnostic().location().cellPath().getFirst().rowIndex());
        var warn = new ByteArrayOutputStream();
        var result = MarkdownAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(doc(merged())).to(warn);
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.location().cellPath().size() == 1));
        String rendered = warn.toString(StandardCharsets.UTF_8);
        assertTrue(rendered.contains("| --- | --- |"));
        for (String token : List.of("BEFORE", "AFTER", "TALL", "LAST")) { assertEquals(1, rendered.split(token, -1).length - 1); }
    }

    @Test void fieldsReportOriginalUtf16AndNestedPhysicalCellLocationWithoutLeakingText() throws IOException {
        var field = new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(12));
        var cell = TableCell.builder().blocks(List.of(Paragraph.mixed("leaf", List.of(new InlineText("😀secret", InlineStyle.of(12)), field), BODY))).build();
        var table = Table.builder("table").columns(new ColumnWidth.Fraction(1)).row(cell).build();
        var output = new ByteArrayOutputStream();
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(doc(table)).to(output);
        var diagnostic = result.diagnostics().getFirst();
        assertEquals(ExportFeature.INLINE_FIELD, diagnostic.feature()); assertEquals("leaf", diagnostic.location().blockId());
        assertEquals(8, diagnostic.location().sourceOffset());
        assertEquals(0, diagnostic.location().cellPath().getFirst().columnIndex());
        assertFalse(diagnostic.message().contains("secret")); assertTrue(output.toString(StandardCharsets.UTF_8).contains("[unresolved PAGE]"));
    }

    @Test void namedRolesRequireExplicitMappingsAndBuilderResultsAreSnapshots() throws IOException {
        var paragraph = Paragraph.mixed("p", List.of(new InlineText("strong text", new InlineStyle("bold", 12, 0, 0, false, false))), BODY);
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(doc(paragraph)).to(new ByteArrayOutputStream()));
        var builder = HtmlAdapter.builder().fontRole("bold", new FontStyle("A\";color:red;", 700, false));
        var first = builder.build(); builder.fontRole("bold", new FontStyle("Other", 400, true));
        String result = text(first.export(doc(paragraph)));
        assertTrue(result.contains("font-weight:700")); assertFalse(result.contains("color:red"));
        String markdown = text(MarkdownAdapter.builder().fontRole("bold", new FontStyle("Arial", 700, false)).build().export(doc(paragraph)));
        assertEquals("**strong text**\n\n", markdown);
    }

    @Test void imageBytesAndLinksAreEmbeddedWithoutChangingPercentEncodedDestinations() throws IOException {
        var picture = new InlineImage(image(), 20, 20, 0, "<logo>", "https://example.com/a%20b?q=1&x=2");
        var source = doc(Paragraph.mixed("p", List.of(picture), BODY));
        String html = text(new HtmlAdapter().export(source));
        assertTrue(html.contains("data:image/png;base64,")); assertTrue(html.contains("alt=\"&lt;logo&gt;\""));
        assertTrue(html.contains("a%20b?q=1&amp;x=2"));
        String markdown = text(MarkdownAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source));
        assertTrue(markdown.contains("a%20b?q=1&amp;x=2")); assertFalse(markdown.contains("%2520"));
    }

    @Test void adjacentEmphasisAndNumberRestartsRetainUnambiguousMarkdownSyntax() throws IOException {
        var bold = new InlineStyle("bold", 12, 0, 0, false, false);
        var paragraph = Paragraph.mixed("p", List.of(new InlineText("a", InlineStyle.of(12)),
                new InlineText("B!", bold), new InlineText("C", bold), new InlineText("z", InlineStyle.of(12))), BODY);
        var source = doc(paragraph, new NumberedItem("n1", "steps", "first", 3, BODY),
                new NumberedItem("n2", "steps", "restart", 8, BODY), new NumberedItem("n3", "steps", "next", 0, BODY));
        String result = text(MarkdownAdapter.builder().fontRole("bold", new FontStyle("Arial", 700, false)).build().export(source));
        assertTrue(result.contains("a<strong>B\\!</strong><strong>C</strong>z"), result);
        assertTrue(result.contains("3. first\n\n<!-- -->\n\n8. restart\n\n9. next"), result);
        String emptyBetween = text(new MarkdownAdapter().export(doc(new NumberedItem("a", "steps", "first", 3, BODY),
                p("empty", ""), new NumberedItem("b", "steps", "restart", 8, BODY))));
        assertTrue(emptyBetween.contains("<!-- -->\n\n8. restart"), emptyBetween);
    }

    @Test void customPaperGeometryUsesPolicyWithoutPretendingToReproducePages() throws IOException {
        var source = new Document(DocumentMetadata.EMPTY, PageSettings.builder().size(300, 200).margins(10).build(), List.of(p("p", "flow")));
        var error = assertThrows(ExportException.class, () -> new HtmlAdapter().export(source).to(new ByteArrayOutputStream()));
        assertEquals(ExportFeature.PAGE_GEOMETRY, error.diagnostic().feature());
        var output = new ByteArrayOutputStream();
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(source).to(output);
        assertEquals(1, result.diagnostics().size()); assertTrue(output.toString(StandardCharsets.UTF_8).contains("flow"));
    }

    @Test void regionsAndSectionNumberingUsePoliciesAndRepeatAsFlowOnlyWhenAuthorized() throws IOException {
        var bands = new PageRegions(PageRegion.builder().height(20).paragraph(p("brand", "BRAND")).build(), PageRegion.EMPTY);
        var sections = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("s1", PageSettings.A4, List.of(p("p1", "FIRST")), 0, bands),
                new Section("s2", PageSettings.A4, List.of(p("p2", "SECOND")), 7, bands)));
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(sections).to(new ByteArrayOutputStream()));
        var output = new ByteArrayOutputStream();
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(sections).to(output);
        assertEquals(3, result.diagnostics().size());
        assertEquals(2, output.toString(StandardCharsets.UTF_8).split("BRAND", -1).length - 1);
        assertThrows(ExportException.class, () -> new MarkdownAdapter().export(new PageDocument(doc(p("p", "body")), bands)).to(new ByteArrayOutputStream()));
    }

    @Test void sourceAndOutputBudgetsCannotBeIgnoredAndFailBeforeWriting() throws IOException {
        var source = doc(p("p", "中文😀"));
        for (var limits : List.of(ExportLimits.builder().maxCodePoints(2).build(), ExportLimits.builder().maxOutputBytes(10).build())) {
            var output = new ByteArrayOutputStream();
            var adapter = HtmlAdapter.builder().policy(all(UnsupportedAction.IGNORE)).limits(limits).build();
            assertEquals(ExportException.Code.LIMIT_EXCEEDED, assertThrows(ExportException.class, () -> adapter.export(source).to(output)).code());
            assertEquals(0, output.size());
        }
        var output = new ByteArrayOutputStream();
        assertThrows(ExecutionException.class, () -> new HtmlAdapter().export(source)
                .execution(ExecutionOptions.builder().maxOutputBytes(1).build()).to(output));
        assertEquals(0, output.size());
    }

    @Test void utf8ByteBudgetUsesBytesRatherThanCharactersAndAllowsExactLimit() throws IOException {
        var source = doc(p("p", "中文😀"));
        byte[] expected = text(new HtmlAdapter().export(source)).getBytes(StandardCharsets.UTF_8);
        var at = HtmlAdapter.builder().limits(ExportLimits.builder().maxOutputBytes(expected.length).build()).build();
        assertEquals(expected.length, at.export(source).to(new ByteArrayOutputStream()).bytesWritten());
        var below = HtmlAdapter.builder().limits(ExportLimits.builder().maxOutputBytes(expected.length - 1).build()).build();
        assertThrows(ExportException.class, () -> below.export(source).to(new ByteArrayOutputStream()));
    }

    @Test void imageAndDiagnosticBudgetsIncludeActualResourcesAndWarnings() throws IOException {
        var source = doc(Paragraph.mixed("p", List.of(new InlineImage(image(), 10, 10, 0, "logo")), BODY));
        var small = ExportLimits.builder().maxImageBytes(1).build();
        assertThrows(ExportException.class, () -> HtmlAdapter.builder().limits(small).policy(all(UnsupportedAction.IGNORE)).build().export(source).to(new ByteArrayOutputStream()));
        var diagnostics = ExportLimits.builder().maxDiagnostics(1).build();
        var adapter = MarkdownAdapter.builder().limits(diagnostics).policy(all(UnsupportedAction.WARN)).build();
        assertThrows(ExportException.class, () -> adapter.export(doc(new PageBreak("a"), new PageBreak("b"))).to(new ByteArrayOutputStream()));
    }

    @Test void invalidUnicodeAndNumberingStillFailUnderIgnore() {
        var adapter = HtmlAdapter.builder().policy(all(UnsupportedAction.IGNORE)).build();
        assertEquals(ExportException.Code.INVALID_TEXT, assertThrows(ExportException.class,
                () -> adapter.export(doc(p("p", "\ud800"))).to(new ByteArrayOutputStream())).code());
        assertEquals(ExportException.Code.INVALID_NUMBERING, assertThrows(ExportException.class,
                () -> adapter.export(doc(new NumberedItem("n", "list", "orphan", 0, BODY, 2))).to(new ByteArrayOutputStream())).code());
        var source = doc(new NumberedItem("one", "list", "one", Integer.MAX_VALUE, BODY), new NumberedItem("two", "list", "two", 0, BODY));
        assertThrows(ExportException.class, () -> adapter.export(source).to(new ByteArrayOutputStream()));
    }

    @Test void unknownCustomBlocksAreNeverRenderedOrStringified() throws IOException {
        Block custom = new Block() {
            @Override public String id() { return "custom"; }
            @Override public String toString() { throw new AssertionError("must not stringify custom code"); }
        };
        var output = new ByteArrayOutputStream();
        assertThrows(ExportException.class, () -> new HtmlAdapter().export(doc(custom)).to(output));
        var result = HtmlAdapter.builder().policy(all(UnsupportedAction.WARN)).build().export(doc(custom)).to(output);
        assertEquals(ExportFeature.CUSTOM_BLOCK, result.diagnostics().getFirst().feature());
    }

    @Test void atomicFailureCancellationAndSuccessPreserveOwnershipAndCleanTemporaryFiles() throws IOException {
        Path target = temporary.resolve("report.md"); Files.writeString(target, "OLD");
        assertThrows(ExportException.class, () -> new MarkdownAdapter().export(doc(new PageBreak("break"))).to(target));
        assertEquals("OLD", Files.readString(target));
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(event -> cancelled.set(true)).build();
        assertThrows(ExecutionException.class, () -> new HtmlAdapter().export(doc(p("p", "text"))).execution(options).to(target));
        assertEquals("OLD", Files.readString(target));
        assertTrue(text(new HtmlAdapter().export(doc(p("p", "AFTER")))).contains("AFTER"));
        new MarkdownAdapter().export(doc(p("p", "NEW"))).to(target);
        assertEquals("NEW\n\n", Files.readString(target));
        try (var entries = Files.list(temporary)) { assertEquals(1, entries.count()); }
    }

    @Test void borrowedStreamsAreNeverClosedOrFlushedAndIoFailuresPropagate() throws IOException {
        class Borrowed extends ByteArrayOutputStream {
            @Override public void close() { throw new AssertionError("closed borrowed stream"); }
            @Override public void flush() { throw new AssertionError("flushed borrowed stream"); }
        }
        new HtmlAdapter().export(doc(p("p", "text"))).to(new Borrowed());
        OutputStream failing = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("test write failure"); } };
        assertEquals("test write failure", assertThrows(IOException.class, () -> new HtmlAdapter().export(doc(p("p", "text"))).to(failing)).getMessage());
    }

    @Test void outerCancellationScopeIsObservedAndRestored() throws IOException {
        var options = ExecutionOptions.builder().cancelled(() -> true).build();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options,
                () -> new HtmlAdapter().export(doc(p("p", "text"))).to(new ByteArrayOutputStream())));
        assertTrue(text(new HtmlAdapter().export(doc(p("p", "ok")))).contains("ok"));
    }

    @Test void immutableAdapterAndRequestCanBeUsedConcurrently() throws Exception {
        var request = new HtmlAdapter().export(doc(p("p", "SAME")));
        String expected = text(request);
        var executor = Executors.newFixedThreadPool(3);
        try {
            var work = new ArrayList<Callable<String>>();
            for (int i = 0; i < 8; i++) { work.add(() -> text(request)); }
            for (var result : executor.invokeAll(work, 5, TimeUnit.SECONDS)) { assertEquals(expected, result.get(1, TimeUnit.SECONDS)); }
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS)); }
    }
}
