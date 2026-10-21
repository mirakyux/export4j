// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTemplateTest {
    @Test void cancellationInterruptsSupplementaryPlaneScanBeforeInvalidSuffix() {
        var checkpoints = new AtomicInteger();
        String source = "AB\n" + "😀".repeat(400_000) + "\t";
        var options = ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() >= 10).build();
        var failure = assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(options, () -> compiler().compile(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals("OK", compiler().compile("OK").render(Map.of()).paragraphs().getFirst().text());
    }

    @Test void compilesSemanticBlocksSoftBreaksAndStableIdsWithExplicitPageAndBodyDefaults() {
        var page = new PageSettings(new PageSize(420, 300), PageMargins.uniform(24));
        var body = ParagraphFormat.builder().fontSize(11).lineHeight(19).spaceAfter(4).build();
        var compiler = MarkdownTemplate.builder().pageSettings(page).bodyFormat(body).build();
        String source = "# ${title}\r\n\r\nFirst ${person.name}\rsecond line\n\n- A\n+ B\n* C\n\n## End ##\n";
        var data = Map.of("title", "Report", "person", Map.of("name", "Acme"));
        var document = compiler.compile(source).render(data);
        assertEquals(page, document.pageSettings());
        assertEquals(6, document.blocks().size());
        var title = assertInstanceOf(Heading.class, document.blocks().getFirst());
        assertEquals("Report", title.text());
        assertEquals(1, title.level());
        assertEquals(Heading.defaultFormat(1), title.format());
        var paragraph = assertInstanceOf(Paragraph.class, document.blocks().get(1));
        assertEquals("First Acme second line", paragraph.text());
        assertEquals(body, paragraph.format());
        for (int i = 2; i < 5; i++) {
            var bullet = assertInstanceOf(BulletItem.class, document.blocks().get(i));
            assertEquals("•", bullet.marker());
            assertEquals(1, bullet.level());
            assertEquals(body, bullet.format());
        }
        var end = assertInstanceOf(Heading.class, document.blocks().getLast());
        assertEquals("End", end.text());
        assertEquals(2, end.level());
        assertEquals(List.of("markdown-1", "markdown-2", "markdown-3", "markdown-4", "markdown-5", "markdown-6"),
                document.blocks().stream().map(Block::id).toList());
        assertEquals(document, compiler.compile(source).render(data));
    }

    @Test void nonnestedEmphasisPreservesTrueFontRolesInParagraphsHeadingsAndBullets() {
        var document = compiler().compile("# **${title}**\n\nPlain *italic* **bold** ***both***.\n\n- *Item*")
                .render(Map.of("title", "Report"));
        var heading = assertInstanceOf(Heading.class, document.blocks().getFirst());
        assertEquals("bold", heading.inlines().getFirst().style().fontRole());
        assertEquals(heading.format().fontSize(), heading.inlines().getFirst().style().fontSize());
        var paragraph = assertInstanceOf(Paragraph.class, document.blocks().get(1));
        assertEquals("Plain italic bold both.", paragraph.text());
        assertEquals(List.of("", "italic", "", "bold", "", "boldItalic", ""),
                paragraph.inlines().stream().map(run -> run.style().fontRole()).toList());
        assertEquals("italic", assertInstanceOf(BulletItem.class, document.blocks().getLast()).inlines().getFirst().style().fontRole());
    }

    @Test void explicitImagesKeepGeometryAltExpressionsAndRecalculateUtf16IndicesAfterBinding() throws IOException {
        var resource = image();
        var builder = MarkdownTemplate.builder().image("logo", resource, 12, 14);
        var frozen = builder.build();
        builder.image("logo", image(), 20, 22).image("later", resource, 12, 14);
        var template = frozen.compile("${name}![Logo ${name}](logo) **Reviewed** ![Again](logo)");
        for (String value : List.of("A😀", "A longer name")) {
            var paragraph = template.render(Map.of("name", value)).paragraphs().getFirst();
            assertEquals(value + "\ufffc Reviewed \ufffc", paragraph.text());
            assertEquals(2, paragraph.images().size());
            assertEquals(value.length(), paragraph.images().getFirst().sourceIndex());
            assertEquals(value.length() + 11, paragraph.images().getLast().sourceIndex());
            var placed = paragraph.images().getFirst().image();
            assertSame(resource, placed.resource());
            assertEquals(12, placed.width());
            assertEquals(14, placed.height());
            assertEquals("Logo " + value, placed.altText());
            assertEquals("", placed.hyperlink());
        }
        assertEquals(MarkdownTemplateException.Code.UNKNOWN_RESOURCE, assertThrows(MarkdownTemplateException.class,
                () -> frozen.compile("![New](later)")).code());
    }

    @Test void imageReferencesCannotSelectUrlsPathsDynamicNamesOrTitlesAndNoNetworkRequestOccurs() throws IOException {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image.png", exchange -> { requests.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.start();
        try {
            var compiler = MarkdownTemplate.builder().image("logo", image(), 12, 12).build();
            for (String reference : List.of("http://127.0.0.1:" + server.getAddress().getPort() + "/image.png",
                    "https://example.com/image.png", "file:///private.png", "../private.png", "C:\\private.png",
                    "${logo}", "logo \"title\"", "", "logo.png")) {
                assertEquals(MarkdownTemplateException.Code.INVALID_RESOURCE_REFERENCE, assertThrows(MarkdownTemplateException.class,
                        () -> compiler.compile("![ALT](" + reference + ")")).code());
            }
            assertEquals(0, requests.get());
            assertEquals(MarkdownTemplateException.Code.UNKNOWN_RESOURCE, assertThrows(MarkdownTemplateException.class,
                    () -> compiler.compile("![ALT](missing)")).code());
        } finally { server.stop(0); }
    }

    @Test void headingBulletAndEmphasisImagesRejectRatherThanLosingTheirSemantics() throws IOException {
        var compiler = MarkdownTemplate.builder().image("logo", image(), 12, 12).build();
        for (String source : List.of("# ![Logo](logo)", "- ![Logo](logo)", "**![Logo](logo)**", "![*Styled*](logo)")) {
            assertEquals(MarkdownTemplateException.Code.UNSUPPORTED_SYNTAX,
                    assertThrows(MarkdownTemplateException.class, () -> compiler.compile(source)).code());
        }
    }

    @Test void imageCountsAndAlternativeTextHaveExplicitSourceAndExpandedModelBoundaries() throws IOException {
        var compiler = MarkdownTemplate.builder().image("logo", image(), 12, 12).build();
        String exact = "![x](logo)".repeat(1024);
        assertEquals(1024, assertInstanceOf(Paragraph.class,
                assertInstanceOf(DocumentTemplate.ValueBlock.class, compiler.compile(exact).nodes().getFirst()).prototype()).images().size());
        var excessive = assertThrows(MarkdownTemplateException.class, () -> compiler.compile(exact + "![x](logo)"));
        assertEquals(MarkdownTemplateException.Code.BUDGET_EXCEEDED, excessive.code());
        assertEquals(exact.length(), excessive.offset());
        assertEquals(2, compiler.compile(exact + "\n\n![x](logo)").nodes().size());
        assertEquals(MarkdownTemplateException.Code.INVALID_VALUE, assertThrows(MarkdownTemplateException.class,
                () -> compiler.compile("![" + "x".repeat(4097) + "](logo)")).code());
        var compiled = compiler.compile("![${alt}](logo)");
        assertThrows(IllegalArgumentException.class, () -> compiled.render(Map.of("alt", "x".repeat(4097))));
    }

    @Test void dataIsInsertedOnceWithoutParsingItsMarkdownHtmlOrExpressions() {
        String payload = "# Injected\n\n![x](https://example.com) **bold** <script>${missing}</script>";
        var template = compiler().compile("Value ${value}");
        var document = template.render(Map.of("value", payload));
        assertEquals(1, document.blocks().size());
        assertEquals("Value " + payload, document.paragraphs().getFirst().text());
        assertTrue(document.paragraphs().getFirst().images().isEmpty());
        assertTrue(document.paragraphs().getFirst().inlines().stream().allMatch(run -> run.style().fontRole().isEmpty()));
    }

    @Test void backslashEscapesRenderReservedPunctuationWithoutCreatingStructureOrExpressions() {
        String source = "\\# literal \\*stars\\* \\_word\\_ \\[text\\] \\<tag\\> \\`code\\` \\| \\~ \\&amp; \\\\ \\!\\[alt\\](logo)";
        assertEquals("# literal *stars* _word_ [text] <tag> `code` | ~ &amp; \\ ![alt](logo)",
                compiler().compile(source).render(Map.of()).paragraphs().getFirst().text());
        for (String unsafe : List.of("\\${value}", "$\\{value}", "\\q", "trailing\\")) {
            assertCode(MarkdownTemplateException.Code.MALFORMED_MARKUP, unsafe);
        }
    }

    @Test void unsupportedBlockAndInlineFeaturesRejectWithStableCodes() {
        for (String source : List.of("<script>SECRET</script>", "<!-- comment -->", "<https://example.com>", "[label](https://example.com)",
                "[reference]: https://example.com", "> quote", "```java\ncode\n```", "~~~\ncode\n~~~", "`code`", "~~strike~~", "_emphasis_",
                "- [x] task", "  - nested", "    code", "1. ordered", "2) ordered", "Text\n---", "Text\n===", "***", "- - -",
                "a | b\n--- | ---", "text  \nnext", "{{#if visible}}", "{% each item %}", "&amp;", "&#123;", "&#x41;",
                "- item\nlazy continuation", "one\tcolumn", "object \ufffc marker", "text\u2028next")) {
            assertCode(MarkdownTemplateException.Code.UNSUPPORTED_SYNTAX, source);
        }
    }

    @Test void ambiguousUnclosedNestedAndCrossLineMarkupRejectsWithoutPartialOutput() {
        for (String source : List.of("#", "####### title", "#missing-space", "# #", "+", "*", "*unclosed", "**unclosed",
                "****ambiguous****", "**bold *nested***", "* empty*", "*empty *", "*cross\nline*", "![alt]", "![alt](logo", "![alt")) {
            assertCode(MarkdownTemplateException.Code.MALFORMED_MARKUP, source);
        }
    }

    @Test void expressionPathsAreAtomicAsciiBoundedAndCannotInvokeCodeOrCrossStyleBoundaries() {
        var document = compiler().compile("${_data.item2} **${name}**").render(Map.of("_data", Map.of("item2", 42), "name", "OK"));
        assertEquals("42 OK", document.paragraphs().getFirst().text());
        String exact = "a".repeat(256);
        assertEquals("OK", compiler().compile("${" + exact + "}").render(Map.of(exact, "OK")).paragraphs().getFirst().text());
        for (String expression : List.of("${}", "${missing", "${a..b}", "${a.}", "${a[0]}", "${a()}", "${a + b}",
                "${中文}", "${a.2b}", "${**name**}", "${" + "a".repeat(257) + "}")) {
            assertCode(MarkdownTemplateException.Code.INVALID_EXPRESSION, expression);
        }
    }

    @Test void diagnosticsRetainOriginalUtf16ColumnsOffsetsAndCrLfPositionsWithoutPayload() {
        String source = "# Report\r\n\r\nA😀 [SECRET](https://private.example)";
        var failure = assertCode(MarkdownTemplateException.Code.UNSUPPORTED_SYNTAX, source);
        assertEquals(3, failure.line());
        assertEquals(5, failure.column());
        assertEquals(source.indexOf('['), failure.offset());
        assertFalse(failure.getMessage().contains("SECRET"));
        assertFalse(failure.getMessage().contains("private"));
        var cr = assertCode(MarkdownTemplateException.Code.INVALID_EXPRESSION, "OK\r😀${bad()}");
        assertEquals(2, cr.line());
        assertEquals(3, cr.column());
        assertEquals(5, cr.offset());
    }

    @Test void rejectsIsolatedSurrogatesAndNoncharactersBeforeModelConstruction() {
        for (String value : List.of("\ud800", "\udc00", "\ud800X", "\ufffe", "\uffff")) {
            assertCode(MarkdownTemplateException.Code.INVALID_UNICODE, "Text " + value);
        }
        assertEquals("😀", compiler().compile("😀").render(Map.of()).paragraphs().getFirst().text());
    }

    @Test void sourceLineBlockAndInlineBudgetsHonorExactBoundariesIncludingSoftBreaks() {
        String source = "A\nB\n\n# C\n";
        var exact = new MarkdownTemplate.Limits(source.length(), 4, 2, 4);
        assertEquals(2, MarkdownTemplate.builder().limits(exact).build().compile(source).render(Map.of()).blocks().size());
        for (var limits : List.of(new MarkdownTemplate.Limits(source.length() - 1, 4, 2, 4),
                new MarkdownTemplate.Limits(source.length(), 3, 2, 4), new MarkdownTemplate.Limits(source.length(), 4, 1, 4),
                new MarkdownTemplate.Limits(source.length(), 4, 2, 3))) {
            assertEquals(MarkdownTemplateException.Code.BUDGET_EXCEEDED, assertThrows(MarkdownTemplateException.class,
                    () -> MarkdownTemplate.builder().limits(limits).build().compile(source)).code());
        }
        assertThrows(IllegalArgumentException.class, () -> new MarkdownTemplate.Limits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownTemplate.Limits(1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownTemplate.Limits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MarkdownTemplate.Limits(1, 1, 1, 0));
    }

    @Test void emptyDocumentsAndAllHeadingLevelsAreSupportedWithoutInventingBlocks() {
        assertTrue(compiler().compile("").render(Map.of()).blocks().isEmpty());
        assertTrue(compiler().compile(" \r\n\n\r").render(Map.of()).blocks().isEmpty());
        for (int level = 1; level <= 6; level++) {
            var heading = assertInstanceOf(Heading.class, compiler().compile("#".repeat(level) + " Title ###").render(Map.of()).blocks().getFirst());
            assertEquals(level, heading.level());
            assertEquals("Title", heading.text());
        }
    }

    @Test void bindingBudgetsAndMissingValuesRemainDistinctFromCompilationDiagnostics() {
        var limits = new DocumentTemplate.Limits(100, 1, 100, 100, 10);
        var template = MarkdownTemplate.builder().bindingLimits(limits).build().compile("A\n\nB");
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of())).code());
        var compiled = compiler().compile("${missing}");
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE,
                assertThrows(DocumentTemplate.BindingException.class, () -> compiled.render(Map.of())).code());
        var textLimited = MarkdownTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 100, 100, 5, 10)).build().compile("${x}");
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> textLimited.render(Map.of("x", "123456"))).code());
    }

    @Test void borrowedReadersStayOpenOnSuccessMalformedInputAndInputBudgetFailure() throws IOException {
        var successful = new TrackingReader("# Report\n\nOK");
        assertEquals(2, compiler().compile(successful).render(Map.of()).blocks().size());
        assertFalse(successful.closed);
        var malformed = new TrackingReader("[SECRET](path)");
        assertThrows(MarkdownTemplateException.class, () -> compiler().compile(malformed));
        assertFalse(malformed.closed);
        var oversized = new TrackingReader("X".repeat(10000));
        var compiler = MarkdownTemplate.builder().limits(new MarkdownTemplate.Limits(64, 10, 10, 10)).build();
        var failure = assertThrows(MarkdownTemplateException.class, () -> compiler.compile(oversized));
        assertEquals(MarkdownTemplateException.Code.BUDGET_EXCEEDED, failure.code());
        assertEquals(64, failure.offset());
        assertEquals(65, oversized.read);
        assertFalse(oversized.closed);
    }

    @Test void borrowedReaderIoErrorsPropagateAndZeroLengthReadsDoNotPreventProgress() throws IOException {
        var cause = new IOException("Synthetic reader error");
        var closed = new AtomicBoolean();
        Reader failing = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException { throw cause; }
            @Override public void close() { closed.set(true); }
        };
        assertSame(cause, assertThrows(IOException.class, () -> compiler().compile(failing)));
        assertFalse(closed.get());
        var backing = new StringReader("OK");
        Reader zeroReads = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) { return 0; }
            @Override public int read() throws IOException { return backing.read(); }
            @Override public void close() { throw new AssertionError("borrowed reader was closed"); }
        };
        assertEquals("OK", compiler().compile(zeroReads).render(Map.of()).paragraphs().getFirst().text());
    }

    @Test void cancellationWhileParsingLongRunsRestoresScopeAndCompilerRemainsReusable() {
        var checkpoints = new AtomicInteger();
        var compiler = compiler();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(
                ExecutionOptions.builder().cancelled(() -> checkpoints.incrementAndGet() > 30).build(),
                () -> compiler.compile("A".repeat(100_000))));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code());
        ExecutionContext.checkpoint();
        assertEquals("OK", compiler.compile("OK").render(Map.of()).paragraphs().getFirst().text());
    }

    @Test void cancellationRequestedByReaderIsObservedWithoutClosingIt() {
        var cancelled = new AtomicBoolean();
        var backing = new StringReader("OK");
        Reader triggering = new Reader() {
            @Override public int read(char[] buffer, int offset, int length) throws IOException {
                int count = backing.read(buffer, offset, length); cancelled.set(true); return count;
            }
            @Override public void close() { throw new AssertionError("borrowed reader was closed"); }
        };
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class,
                () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(cancelled::get).build(),
                        () -> compiler().compile(triggering))).code());
        ExecutionContext.checkpoint();
    }

    @Test void resourceRegistrationValidatesGeometryAndNamesAtThePublicBoundary() throws IOException {
        var resource = image();
        for (String name : List.of("", "logo.png", "../logo", "${logo}", "a".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> MarkdownTemplate.builder().image(name, resource, 12, 12));
        }
        assertThrows(IllegalArgumentException.class, () -> MarkdownTemplate.builder().image("logo", resource, Double.NaN, 12));
        assertThrows(IllegalArgumentException.class, () -> MarkdownTemplate.builder().image("logo", resource, 0, 12));
        assertThrows(IllegalArgumentException.class, () -> MarkdownTemplate.builder().image("logo", resource, 12, 14401));
    }

    private static MarkdownTemplate compiler() { return MarkdownTemplate.builder().build(); }
    private static MarkdownTemplateException assertCode(MarkdownTemplateException.Code expected, String source) {
        var failure = assertThrows(MarkdownTemplateException.class, () -> compiler().compile(source));
        assertEquals(expected, failure.code(), () -> "Unexpected diagnostic for fixture: " + source);
        return failure;
    }
    private static ImageResource image() throws IOException {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return ImageResource.load(bytes.toByteArray());
    }
    private static final class TrackingReader extends Reader {
        private final StringReader source;
        private int read;
        private boolean closed;
        private TrackingReader(String text) { source = new StringReader(text); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = source.read(buffer, offset, length); if (count > 0) { read += count; } return count;
        }
        @Override public void close() { closed = true; source.close(); }
    }
}
