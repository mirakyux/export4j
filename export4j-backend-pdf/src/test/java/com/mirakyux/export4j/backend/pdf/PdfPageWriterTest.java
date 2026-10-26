// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.PathBuilder;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.GlyphRun;
import com.mirakyux.export4j.text.SimpleTextShaper;

class PdfPageWriterTest {
    @Test void passivePageLinksUseTopLeftCoordinatesAndCumulativeBudgets() throws IOException {
        var bytes = new ByteArrayOutputStream();
        var target = new com.mirakyux.export4j.model.ExternalDestination("https://example.com/link");
        try (var writer = new PdfBackend().open(DocumentMetadata.EMPTY, bytes)) {
            for (int page = 0; page < 2; page++) {
                writer.accept(blank(), List.of(new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(page,
                        new Rect(10, 20, 30, 12), target)));
            }
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(bytes.toByteArray())) {
            for (int index = 0; index < 2; index++) {
                var link = (org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink) pdf.getPage(index).getAnnotations().getFirst();
                assertEquals(10, link.getRectangle().getLowerLeftX());
                assertEquals(168, link.getRectangle().getLowerLeftY());
                assertEquals(target.uri(), ((org.apache.pdfbox.pdmodel.interactive.action.PDActionURI) link.getAction()).getURI());
            }
        }
        for (var limits : List.of(PdfLimits.DEFAULT.toBuilder().maxInteractions(1).build(),
                PdfLimits.DEFAULT.toBuilder().maxInteractionCodePoints(target.uri().length()).build())) {
            try (var writer = new PdfBackend(limits).open(DocumentMetadata.EMPTY, new ByteArrayOutputStream())) {
                writer.accept(blank(), List.of(new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(0, new Rect(10, 20, 30, 12), target)));
                var failure = assertThrows(PdfRenderException.class, () -> writer.accept(blank(), List.of(
                        new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(1, new Rect(10, 20, 30, 12), target))));
                assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
                assertThrows(IllegalStateException.class, writer::finish);
            }
        }
    }

    @Test void invalidPageLinkIndexAndBoundsTerminateWriterBeforeSaving() throws IOException {
        var target = new com.mirakyux.export4j.model.ExternalDestination("https://example.com/");
        for (var link : List.of(new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(1, new Rect(10, 20, 30, 12), target),
                new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(0, new Rect(190, 20, 30, 12), target))) {
            var bytes = new ByteArrayOutputStream();
            try (var writer = new PdfBackend().open(DocumentMetadata.EMPTY, bytes)) {
                assertEquals(PdfErrorCode.INVALID_GEOMETRY, assertThrows(PdfRenderException.class,
                        () -> writer.accept(blank(), List.of(link))).code());
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertEquals(0, bytes.size());
        }
    }
    private static final PageSize SIZE = new PageSize(200, 200);
    private static final TextStyle STYLE = new TextStyle(FontFamily.SANS_SERIF, 12, RgbColor.BLACK);
    private static final DocumentMetadata METADATA = new DocumentMetadata("Paged output", "Export4j test");

    @ParameterizedTest
    @MethodSource("storageOptions")
    void finishedPagesMatchCompleteDocumentTextGeometryAndPixels(PdfOptions options) throws IOException {
        var first = new DisplayListBuilder(SIZE).drawText("PAGE-ONE", new Point(20, 40), STYLE)
                .fillRect(new Rect(20, 60, 30, 20), new RgbColor(1, 0, 0))
                .drawImage(image(), new Rect(70, 60, 20, 40)).build();
        var second = new DisplayListBuilder(PageSize.LETTER).drawText("PAGE-TWO", new Point(30, 50), STYLE)
                .fillPath(path(), RgbColor.BLACK).build();
        var output = new TrackingOutput();
        var backend = new PdfBackend(PdfLimits.DEFAULT, options);
        PageBackend pageBackend = backend;
        try (var writer = pageBackend.open(METADATA, output)) {
            writer.accept(first);
            writer.accept(second);
            assertEquals(0, output.size(), "accept converts pages without implicitly saving the PDF");
            writer.finish();
            assertThrows(IllegalStateException.class, () -> writer.accept(first));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertFalse(output.closed);
        var complete = new ByteArrayOutputStream();
        backend.write(new DisplayDocument(METADATA, List.of(first, second)), complete);
        try (var paged = Loader.loadPDF(output.toByteArray()); var original = Loader.loadPDF(complete.toByteArray())) {
            assertEquals(2, paged.getNumberOfPages());
            assertEquals(METADATA.title(), paged.getDocumentInformation().getTitle());
            assertEquals(METADATA.author(), paged.getDocumentInformation().getAuthor());
            assertEquals(new PDFTextStripper().getText(original), new PDFTextStripper().getText(paged));
            for (int page = 0; page < 2; page++) {
                assertEquals(original.getPage(page).getMediaBox().getWidth(), paged.getPage(page).getMediaBox().getWidth());
                assertEquals(original.getPage(page).getMediaBox().getHeight(), paged.getPage(page).getMediaBox().getHeight());
                var expected = new PDFRenderer(original).renderImageWithDPI(page, 72);
                var actual = new PDFRenderer(paged).renderImageWithDPI(page, 72);
                assertEquals(expected.getWidth(), actual.getWidth());
                assertEquals(expected.getHeight(), actual.getHeight());
                int width = expected.getWidth(), height = expected.getHeight();
                assertArrayEquals(expected.getRGB(0, 0, width, height, null, 0, width),
                        actual.getRGB(0, 0, width, height, null, 0, width), "page pixels differ");
            }
        }
    }

    static Stream<PdfOptions> storageOptions() {
        return Stream.of(PdfOptions.DEFAULT, PdfOptions.storage(PdfOptions.Storage.TEMP_FILE));
    }

    @Test
    void closingWithoutFinishDiscardsOutputAndIsIdempotent() throws IOException {
        var output = new TrackingOutput();
        var writer = new PdfBackend().open(METADATA, output);
        try (writer) { writer.accept(text("UNFINISHED")); }
        writer.close();
        assertEquals(0, output.size());
        assertFalse(output.closed);
        assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
        assertThrows(IllegalStateException.class, writer::finish);
    }

    @Test
    void finishWithoutPagesFailsAndTerminatesTheSession() throws IOException {
        var output = new TrackingOutput();
        try (var writer = new PdfBackend().open(METADATA, output)) {
            assertThrows(IllegalStateException.class, writer::finish);
            assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    @ParameterizedTest
    @MethodSource("cumulativeBudgets")
    void rejectsCumulativeBudgetsAtTheActualSecondPageAndSharesPreflightWithCompleteWrite(
            PdfLimits limits, DisplayPage first, DisplayPage second) throws IOException {
        var output = new TrackingOutput();
        var backend = new PdfBackend(limits);
        try (var writer = backend.open(METADATA, output)) {
            writer.accept(first);
            var failure = assertThrows(PdfRenderException.class, () -> writer.accept(second));
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
            assertEquals(1, failure.pageIndex());
            assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertEquals(0, output.size());
        assertFalse(output.closed);
        var failure = assertThrows(PdfRenderException.class,
                () -> backend.write(new DisplayDocument(METADATA, List.of(first, second)), output));
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
        assertEquals(0, output.size());
    }

    static Stream<Arguments> cumulativeBudgets() {
        var rectangle = new DisplayListBuilder(SIZE).fillRect(new Rect(0, 0, 10, 10), RgbColor.BLACK).build();
        var picture = new DisplayListBuilder(SIZE).drawImage(image(), new Rect(0, 0, 10, 10)).build();
        var fill = new DisplayListBuilder(SIZE).fillPath(path(), RgbColor.BLACK).build();
        var stroke = new DisplayListBuilder(SIZE).strokePath(path(), RgbColor.BLACK, 1).build();
        var outline = new DisplayPage(SIZE, List.of(new DrawCommand.OutlineText("AB", List.of(path()), RgbColor.BLACK)));
        var shallow = new DisplayListBuilder(SIZE).save().restore().build();
        var nested = new DisplayListBuilder(SIZE).save().save().restore().restore().build();
        return Stream.of(
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxPages(1).build(), blank(), blank()),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxCommands(1).build(), rectangle, rectangle),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxImagePixels(3).build(), picture, picture),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxTextCodePoints(3).build(), text("AB"), text("CD")),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxTextCodePoints(3).build(), text("AB"), outline),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxPathSegments(7).build(), fill, stroke),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxPathSegments(7).build(), fill, outline),
                Arguments.of(PdfLimits.DEFAULT.toBuilder().maxStateDepth(1).build(), shallow, nested));
    }

    @Test
    void embeddedGlyphAndUniqueFontBudgetsAccumulateAcrossPages() throws IOException {
        var regular = font("LiberationSans-Regular.ttf");
        var bold = font("LiberationSans-Bold.ttf");
        var first = glyphPage(regular, "AB");
        var second = glyphPage(bold, "CD");
        for (var limits : List.of(
                PdfLimits.DEFAULT.toBuilder().maxTextCodePoints(3).build(),
                PdfLimits.DEFAULT.toBuilder().maxFontBytes((long) regular.data().byteSize() + bold.data().byteSize() - 1).build())) {
            var output = new TrackingOutput();
            try (var writer = new PdfBackend(limits).open(METADATA, output)) {
                writer.accept(first);
                var failure = assertThrows(PdfRenderException.class, () -> writer.accept(second));
                assertEquals(PdfErrorCode.BUDGET_EXCEEDED, failure.code());
                assertEquals(1, failure.pageIndex());
                assertEquals(0, failure.commandIndex());
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertEquals(0, output.size());
            assertFalse(output.closed);
        }
        var exact = PdfLimits.DEFAULT.toBuilder().maxTextCodePoints(4).maxFontBytes(regular.data().byteSize()).build();
        var output = new TrackingOutput();
        try (var writer = new PdfBackend(exact).open(METADATA, output)) {
            writer.accept(first);
            writer.accept(first);
            writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals("AB\nAB", new PDFTextStripper().getText(pdf).strip().replace("\r\n", "\n"));
            var firstResources = pdf.getPage(0).getResources();
            var secondResources = pdf.getPage(1).getResources();
            assertSame(firstResources.getFont(firstResources.getFontNames().iterator().next()).getCOSObject(),
                    secondResources.getFont(secondResources.getFontNames().iterator().next()).getCOSObject());
        }
    }

    @Test
    void lateInvalidTextAndGeometryFailDuringAcceptWithoutLeakingText() throws IOException {
        var invalidGeometry = new DisplayListBuilder(SIZE)
                .fillRect(new Rect(Double.MAX_VALUE, 0, 10, 10), RgbColor.BLACK).build();
        for (var page : List.of(text("PRIVATE-机密😀"), invalidGeometry)) {
            var output = new TrackingOutput();
            try (var writer = new PdfBackend().open(METADATA, output)) {
                writer.accept(text("VALID"));
                var failure = assertThrows(PdfRenderException.class, () -> writer.accept(page));
                assertEquals(1, failure.pageIndex());
                assertEquals(0, failure.commandIndex());
                assertFalse(failure.getMessage().contains("PRIVATE"));
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
            }
            assertEquals(0, output.size());
            assertFalse(output.closed);
        }
    }

    @Test
    void nullPageAlsoTerminatesTheSession() throws IOException {
        try (var writer = new PdfBackend().open(METADATA, new TrackingOutput())) {
            assertThrows(NullPointerException.class, () -> writer.accept(null));
            assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
            assertThrows(IllegalStateException.class, writer::finish);
        }
    }

    @Test
    void finishPreservesOutputFailureAndCloseNeverTouchesBorrowedOutput() throws IOException {
        var output = new FailingOutput();
        var writer = new PdfBackend().open(METADATA, output);
        try (writer) {
            writer.accept(text("VALID"));
            var failure = assertThrows(PdfRenderException.class, writer::finish);
            assertEquals(PdfErrorCode.OUTPUT_FAILED, failure.code());
            assertSame(output.failure, failure.getCause());
            assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
            assertThrows(IllegalStateException.class, writer::finish);
        }
        writer.close();
        assertFalse(output.closed);
    }

    @Test
    void pageOutputAppliesAes256AtFinishAndRetainsPassiveLinksAndBorrowedOutput() throws IOException {
        var encrypted = PdfOptions.DEFAULT.withEncryption(new PdfEncryption("owner".toCharArray(), "reader".toCharArray(),
                PdfEncryption.Permissions.READ_ONLY));
        var output = new TrackingOutput();
        try (var writer = new PdfBackend(PdfLimits.DEFAULT, encrypted).open(METADATA, output)) {
            writer.accept(text("FIRST"), List.of(new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(0,
                    new Rect(10, 20, 30, 12), new com.mirakyux.export4j.model.ExternalDestination("https://example.com/"))));
            writer.accept(text("SECOND"));
            assertEquals(0, output.size());
            writer.finish();
        }
        assertFalse(output.closed);
        assertThrows(org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException.class, () -> Loader.loadPDF(output.toByteArray()));
        try (var pdf = Loader.loadPDF(output.toByteArray(), "reader")) {
            assertEquals(256, pdf.getEncryption().getLength()); assertEquals(2, pdf.getNumberOfPages());
            assertFalse(pdf.getCurrentAccessPermission().canPrint()); assertFalse(pdf.getCurrentAccessPermission().canModify());
            assertFalse(pdf.getCurrentAccessPermission().canExtractContent());
            assertEquals(1, pdf.getPage(0).getAnnotations().size());
            assertTrue(new PDFTextStripper().getText(pdf).contains("SECOND"));
        }
        try (var pdf = Loader.loadPDF(output.toByteArray(), "owner")) { assertTrue(pdf.getCurrentAccessPermission().isOwnerPermission()); }
    }

    @Test
    void staticArchivalPageOutputEmbedsFontsAndPreservesItsMetadata() throws IOException {
        var archival = new PdfArchival(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), font("LiberationSans-Regular.ttf").data());
        var output = new TrackingOutput();
        try (var writer = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)).open(METADATA, output)) {
            writer.accept(glyphPage(font("LiberationSans-Regular.ttf"), "ARCHIVAL")); writer.finish();
        }
        assertFalse(output.closed);
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertFalse(pdf.isEncrypted()); assertEquals(1, pdf.getNumberOfPages());
            assertEquals(METADATA.title(), pdf.getDocumentInformation().getTitle());
            assertEquals(1, pdf.getDocumentCatalog().getOutputIntents().size());
            assertTrue(new String(pdf.getDocumentCatalog().getMetadata().toByteArray(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("pdfaid:conformance=\"B\""));
            assertTrue(new PDFTextStripper().getText(pdf).contains("ARCHIVAL"));
        }
    }

    @Test
    void archivalPageLinksAndStandardFontsFailAndTerminateWithoutSaving() throws IOException {
        var archival = new PdfArchival(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), font("LiberationSans-Regular.ttf").data());
        for (boolean link : List.of(false, true)) {
            var output = new TrackingOutput();
            try (var writer = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)).open(METADATA, output)) {
                if (link) {
                    assertThrows(IOException.class, () -> writer.accept(blank(), List.of(
                            new com.mirakyux.export4j.graphics.DocumentExtras.ExternalLink(0, new Rect(10, 20, 30, 12),
                                    new com.mirakyux.export4j.model.ExternalDestination("https://example.com/")))));
                } else { assertThrows(PdfRenderException.class, () -> writer.accept(text("UNEMBEDDED"))); }
                assertThrows(IllegalStateException.class, writer::finish);
            }
            assertEquals(0, output.size());
            assertFalse(output.closed);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outlineAnchorCountsTowardFontBytesAndSharesTheEmbeddedFontIdentity(boolean archival) throws IOException {
        var face = font("LiberationSans-Regular.ttf");
        var options = archival ? PdfOptions.DEFAULT.withArchival(new PdfArchival(ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData(), face.data())) : PdfOptions.DEFAULT;
        var outline = new DisplayPage(SIZE, List.of(new DrawCommand.OutlineText("AB", List.of(path()), RgbColor.BLACK)));
        var small = PdfLimits.DEFAULT.toBuilder().maxFontBytes(face.data().byteSize() - 1).build();
        var output = new TrackingOutput();
        var backend = new PdfBackend(small, options);
        assertEquals(PdfErrorCode.BUDGET_EXCEEDED, assertThrows(PdfRenderException.class,
                () -> backend.write(new DisplayDocument(METADATA, List.of(outline)), output)).code());
        try (var writer = backend.open(METADATA, output)) {
            assertEquals(PdfErrorCode.BUDGET_EXCEEDED, assertThrows(PdfRenderException.class, () -> writer.accept(outline)).code());
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertEquals(0, output.size()); assertFalse(output.closed);
        var exact = PdfLimits.DEFAULT.toBuilder().maxFontBytes(face.data().byteSize()).build();
        try (var writer = new PdfBackend(exact, options).open(METADATA, output)) {
            writer.accept(glyphPage(face, "SHARED FONT")); writer.accept(outline); writer.finish();
        }
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            assertEquals(2, pdf.getNumberOfPages());
            var resources = pdf.getPage(1).getResources();
            var anchor = resources.getFont(resources.getFontNames().iterator().next());
            assertTrue(anchor.isEmbedded());
            assertSame(anchor.getCOSObject(), pdf.getPage(0).getResources().getFont(
                    pdf.getPage(0).getResources().getFontNames().iterator().next()).getCOSObject());
        }
    }

    @Test
    void defaultAnchorsAreEmbeddedOnImageOnlyPagesInCompleteAndStreamingOutput() throws IOException {
        var page = new DisplayListBuilder(SIZE).drawImage(image(), new Rect(20, 20, 10, 10))
                .drawOutlinedText("\uFFFC", List.of(), RgbColor.BLACK, new Rect(20, 20, 10, 10)).build();
        for (boolean streaming : List.of(false, true)) {
            var output = new TrackingOutput(); var backend = new PdfBackend();
            if (streaming) {
                try (var writer = backend.open(METADATA, output)) { writer.accept(page); writer.accept(page); writer.finish(); }
            } else { backend.write(new DisplayDocument(METADATA, List.of(page, page)), output); }
            assertFalse(output.closed);
            try (var pdf = Loader.loadPDF(output.toByteArray())) {
                assertEquals(2, pdf.getNumberOfPages());
                for (var emitted : pdf.getPages()) {
                    var resources = emitted.getResources(); int count = 0;
                    for (var name : resources.getFontNames()) {
                        var anchor = resources.getFont(name); assertTrue(anchor.isEmbedded(), "invisible image anchor must be embedded");
                        assertTrue(anchor.getFontDescriptor().getFontFile2().getLength() > 0); count++;
                    }
                    assertEquals(1, count);
                    assertTrue(resources.getPropertiesNames().iterator().hasNext(), "logical image marker must remain in marked content");
                }
            }
        }
    }

    @Test
    void sessionsRejectOtherThreadsAndCanStillBeClosedByTheirOwner() throws Exception {
        var output = new TrackingOutput();
        try (var writer = new PdfBackend().open(METADATA, output); var executor = Executors.newSingleThreadExecutor()) {
            assertTrue(executor.submit(() -> {
                assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
                assertThrows(IllegalStateException.class, writer::finish);
                assertThrows(IllegalStateException.class, writer::close);
                return true;
            }).get());
            assertThrows(IllegalStateException.class, () -> writer.accept(blank()));
        }
        assertEquals(0, output.size());
        assertFalse(output.closed);
    }

    @Test
    void cancellationAtUnknownTotalProgressTerminatesOnlyThatSessionAndStillAllowsCleanup() throws IOException {
        var cancelled = new AtomicBoolean();
        var events = new ArrayList<ExecutionOptions.Progress>();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            events.add(progress);
            if (progress.stage().equals("PDF_PAGE")) { cancelled.set(true); }
        }).build();
        var output = new TrackingOutput();
        try (var writer = new PdfBackend().open(METADATA, output)) {
            var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
                writer.accept(blank());
                return null;
            }));
            assertEquals(ExecutionException.Code.CANCELLED, failure.code());
            assertEquals(1, events.size());
            assertEquals(-1, events.getFirst().total());
            assertThrows(IllegalStateException.class, writer::finish);
        }
        assertEquals(0, output.size());
        try (var writer = new PdfBackend().open(METADATA, output)) {
            writer.accept(blank());
            writer.finish();
        }
        assertTrue(output.size() > 0, "the cancelled execution context must have been restored");
        assertFalse(output.closed);
    }

    @Test
    void finishObservesExecutionOutputLimitAndLeavesDestinationOpen() throws IOException {
        var output = new TrackingOutput();
        var options = ExecutionOptions.builder().maxOutputBytes(1).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> {
            try (var writer = new PdfBackend().open(METADATA, ExecutionContext.output(output))) {
                writer.accept(blank());
                var limited = assertThrows(ExecutionException.class, writer::finish);
                assertThrows(IllegalStateException.class, writer::finish);
                throw limited;
            }
        }));
        assertEquals(ExecutionException.Code.OUTPUT_LIMIT, failure.code());
        assertTrue(output.size() <= 1);
        assertFalse(output.closed);
    }

    private static DisplayPage blank() { return new DisplayListBuilder(SIZE).build(); }

    private static DisplayPage text(String value) {
        return new DisplayListBuilder(SIZE).drawText(value, new Point(20, 40), STYLE).build();
    }

    private static RgbImage image() {
        return new RgbImage(1, 2, new byte[]{(byte) 255, 0, 0, 0, 0, (byte) 255});
    }

    private static VectorPath path() {
        return new PathBuilder().moveTo(20, 60).lineTo(40, 60).lineTo(20, 80).close().build();
    }

    private static FontFace font(String name) throws IOException {
        try (var input = PdfPageWriterTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }

    private static DisplayPage glyphPage(FontFace font, String text) {
        var run = new GlyphRun(font.data(), 12, new SimpleTextShaper().shape(text, List.of(font), 12)
                .glyphs().stream().map(glyph -> glyph.glyph()).toList());
        return new DisplayListBuilder(SIZE).drawGlyphRun(run, new Point(20, 40), RgbColor.BLACK).build();
    }

    private static final class TrackingOutput extends ByteArrayOutputStream {
        private boolean closed;
        @Override public void close() { closed = true; }
    }

    private static final class FailingOutput extends OutputStream {
        private final IOException failure = new IOException("simulated output failure");
        private boolean closed;
        @Override public void write(int value) throws IOException { throw failure; }
        @Override public void close() throws IOException {
            closed = true;
            throw new IOException("borrowed close must not be called");
        }
    }
}
