// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;

class StreamingDocumentExportTest {
    @TempDir Path temporary;
    private static final PageSettings PAGE = PageSettings.builder().size(380, 180).margins(12).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(15).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = StreamingDocumentExportTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
    }

    @Test void singlePassMatchesFullMixedDocumentIncludingNumberingFieldsTablesAndTemplate() throws IOException {
        var table = Table.builder("table").cellFormat(CELL).headerCells(TableCell.mixed(List.of(
                text("HEADER "), field(InlineField.Kind.PAGE, ""), text(" / "), field(InlineField.Kind.PAGES, "")), CELL));
        for (int row = 0; row < 20; row++) { table.row("ROW-" + row); }
        List<Block> blocks = List.of(new Heading("heading", "Heading", 1, FORMAT),
                new NumberedItem("n1", "list", "First", 0, FORMAT), new NumberedItem("n2", "list", "Second", 0, FORMAT),
                new Caption("caption", "Figure", "Caption", FORMAT),
                Paragraph.mixed("lead", List.of(text("PAGE "), field(InlineField.Kind.PAGE, ""), text(" / "),
                        field(InlineField.Kind.PAGES, ""), new InlineText(" LINK", InlineStyle.of(10), "https://example.com/lead")), FORMAT),
                table.build(), new PageBreak("break"), new Paragraph("tail", "TAIL", FORMAT));
        var engine = DocumentEngine.builder(fonts).pageTemplate(template()).build();
        var full = engine.layout(new Document(DocumentMetadata.EMPTY, PAGE, blocks));
        var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
        sink.beforeFinish = () -> assertEquals(1, closed.get());
        var result = engine.exportSemantic(PAGE, factory(() -> blocks, opened, closed)).expectedPages(full.display().pages().size())
                .backend(sink.backend()).to(new ByteArrayOutputStream());
        assertEquals(1, opened.get()); assertEquals(1, closed.get()); assertEquals(1, sink.finishes);
        assertEquals(full.display().pages(), sink.pages);
        assertEquals(full.display().extras().externalLinks(), sink.links);
        assertEquals(full.display().pages().size(), result.pages()); assertEquals(0, result.contentsPages());
    }

    @Test void explicitReplayOpensAStableSnapshotTwiceAndInfersTotalPages() throws IOException {
        var blocks = twoPages();
        var engine = DocumentEngine.builder(fonts).pageTemplate(template()).build();
        var full = engine.layout(new Document(DocumentMetadata.EMPTY, PAGE, blocks));
        var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
        sink.beforeFinish = () -> assertEquals(2, closed.get());
        var result = engine.exportReplay(PAGE, factory(() -> blocks, opened, closed)).backend(sink.backend()).to(new ByteArrayOutputStream());
        assertEquals(2, result.pages()); assertEquals(2, opened.get()); assertEquals(2, closed.get());
        assertEquals(full.display().pages(), sink.pages); assertEquals(1, sink.finishes);
    }

    @Test void requiresKnownPositiveSinglePassTotalBeforeOpeningSourceOrBackend() {
        var opens = new AtomicInteger(); var closes = new AtomicInteger(); var sink = new Sink();
        var request = DocumentEngine.builder(fonts).build().exportSemantic(PAGE, factory(StreamingDocumentExportTest::twoPages, opens, closes))
                .backend(sink.backend());
        assertThrows(IllegalStateException.class, () -> request.to(new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class, () -> request.expectedPages(0));
        assertThrows(IllegalArgumentException.class, () -> request.expectedPages(-1));
        assertEquals(0, opens.get()); assertEquals(0, sink.opens);
    }

    @Test void wrongTotalsNeverFinishAndPreserveTheOldAtomicTarget() throws IOException {
        var target = temporary.resolve("total.bin");
        for (int expected : List.of(1, 3)) {
            Files.writeString(target, "old");
            var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
            assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build()
                    .exportSemantic(PAGE, factory(StreamingDocumentExportTest::twoPages, opened, closed))
                    .expectedPages(expected).backend(sink.backend()).to(target));
            assertEquals(1, opened.get()); assertEquals(1, closed.get()); assertEquals(0, sink.finishes);
            assertEquals(1, sink.closes); assertEquals("old", Files.readString(target));
        }
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void replayRejectsRawBreakAndUnpaintedStyleChangesDespiteEquivalentPages() throws IOException {
        var target = temporary.resolve("source.bin");
        for (String change : List.of("break", "style")) {
            Files.writeString(target, "old");
            var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
            Supplier<List<Block>> blocks = () -> change.equals("break")
                    ? List.of(new Paragraph("p", "A", FORMAT), new PageBreak(opened.get() == 1 ? "old-break" : "new-break"),
                            new Paragraph("q", "B", FORMAT))
                    : List.of(new Paragraph("p", "A", FORMAT.toBuilder().spaceAfter(opened.get()).build()));
            assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build()
                    .exportReplay(PAGE, factory(blocks, opened, closed)).backend(sink.backend()).to(target));
            assertEquals(2, opened.get()); assertEquals(2, closed.get()); assertEquals(0, sink.finishes);
            assertEquals(change.equals("break") ? 2 : 1, sink.pages.size());
            assertEquals("old", Files.readString(target));
        }
    }

    @Test void replayChecksMeasuredComponentDrawingAndAcceptsEquivalentFreshInstances() throws IOException {
        for (boolean changing : List.of(false, true)) {
            var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
            Supplier<List<Block>> blocks = () -> List.of(new ComponentBlock("component", context -> new ComponentLayout(20, 20, 0,
                    List.of(new DrawCommand.FillRect(new Rect(0, 0, 10, 10),
                            changing && opened.get() == 2 ? new RgbColor(1, 0, 0) : RgbColor.BLACK)), List.of())));
            var request = DocumentEngine.builder(fonts).build().exportReplay(PAGE, factory(blocks, opened, closed)).backend(sink.backend());
            if (changing) {
                assertThrows(IllegalArgumentException.class, () -> request.to(new ByteArrayOutputStream()));
                assertTrue(sink.pages.isEmpty()); assertEquals(0, sink.finishes);
            } else {
                assertEquals(1, request.to(new ByteArrayOutputStream()).pages()); assertEquals(1, sink.finishes);
            }
            assertEquals(2, opened.get()); assertEquals(2, closed.get());
        }
    }

    @Test void futureReferencesAndConfiguredGlobalDeclarationsFailExplicitly() throws IOException {
        var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
        List<Block> reference = List.of(Paragraph.mixed("reference", List.of(field(InlineField.Kind.REFERENCE_PAGE, "later")), FORMAT),
                new Paragraph("later", "TARGET", FORMAT));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build()
                .exportReplay(PAGE, factory(() -> reference, opened, closed)).backend(sink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(1, opened.get()); assertEquals(1, closed.get()); assertEquals(0, sink.opens);
        List<Block> blockReference = List.of(new CrossReference("reference", "See ", "later", CrossReference.Kind.PAGE, FORMAT));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build()
                .exportSemantic(PAGE, factory(() -> blockReference, opened, closed)).expectedPages(1).backend(sink.backend())
                .to(new ByteArrayOutputStream()));
        var configured = DocumentEngine.builder(fonts).navigation(Navigation.builder().bookmark("Target", "later", 1).build()).build();
        assertThrows(IllegalStateException.class, () -> configured.exportSemantic(PAGE, factory(() -> reference, opened, closed)));
        assertThrows(IllegalStateException.class, () -> configured.exportReplay(PAGE, factory(() -> reference, opened, closed)));
    }

    @Test void globalPlacementsAndExternalLinksAreNotResetForEachPage() throws IOException {
        var target = temporary.resolve("budget.bin");
        for (boolean placements : List.of(false, true)) {
            Files.writeString(target, "old");
            var opened = new AtomicInteger(); var closed = new AtomicInteger(); var sink = new Sink();
            List<Block> blocks = List.of(new Paragraph("a", List.of(new InlineText("LINK A", InlineStyle.of(10), "https://example.com/a")), FORMAT),
                    new PageBreak("break"), new Paragraph("b", List.of(new InlineText("LINK B", InlineStyle.of(10), "https://example.com/b")), FORMAT));
            var engine = DocumentEngine.builder(fonts).pageTemplate(placements ? template() : PageTemplate.EMPTY).build();
            assertThrows(IllegalArgumentException.class, () -> engine.exportSemantic(PAGE, factory(() -> blocks, opened, closed))
                    .expectedPages(2).componentLimits(new ComponentLimits(placements ? 1 : 100, 10000, placements ? 100 : 1))
                    .backend(sink.backend()).to(target));
            assertEquals(1, closed.get()); assertEquals(0, sink.finishes); assertEquals("old", Files.readString(target));
        }
    }

    @Test void sourceCloseFailurePrecedesFinishAndPreservesSuppressedWriterCleanup() {
        var sourceFailure = new IOException("source close"); var writerFailure = new IOException("writer close");
        var sink = new Sink(); sink.closeFailure = writerFailure;
        var borrowedClosed = new AtomicBoolean();
        var target = new ByteArrayOutputStream() { @Override public void close() { borrowedClosed.set(true); } };
        BlockSource.Factory source = () -> new BlockSource() {
            private boolean read;
            @Override public Optional<Block> next() {
                if (read) { return Optional.empty(); }
                read = true; return Optional.of(new Paragraph("p", "A", FORMAT));
            }
            @Override public void close() throws IOException { throw sourceFailure; }
        };
        var failure = assertThrows(IOException.class, () -> DocumentEngine.builder(fonts).build().exportSemantic(PAGE, source)
                .expectedPages(1).backend(sink.backend()).to(target));
        assertSame(sourceFailure, failure); assertEquals(List.of(writerFailure), List.of(failure.getSuppressed()));
        assertEquals(0, sink.finishes); assertEquals(1, sink.closes); assertFalse(borrowedClosed.get());
    }

    @Test void activeSnapshotSurvivesExecutionCallbacksChangingTheRequest() throws IOException {
        var first = new Sink(); var next = new Sink(); var opened = new AtomicInteger(); var closed = new AtomicInteger();
        var metadata = new DocumentMetadata("Initial", "Author");
        var request = DocumentEngine.builder(fonts).build()
                .exportSemantic(PAGE, factory(() -> List.of(new Paragraph("p", "A", FORMAT)), opened, closed))
                .expectedPages(1).metadata(metadata).backend(first.backend());
        request.options(options -> options.progress(progress -> request.backend(next.backend()).expectedPages(2)
                .metadata(DocumentMetadata.EMPTY).limits(new DocumentLayoutLimits(1, 1, 1, 1))));
        assertEquals(1, request.to(new ByteArrayOutputStream()).pages());
        assertEquals(1, first.opens); assertEquals(0, next.opens); assertEquals(metadata, first.metadata);
    }

    @Test void sourceCloseCallbacksCannotChangeTheActiveTotalOrBackendSnapshot() throws IOException {
        var active = new AtomicReference<StreamingDocumentRequest>();
        var first = new Sink(); var next = new Sink(); var metadata = new DocumentMetadata("Initial", "Author");
        BlockSource.Factory source = () -> new BlockSource() {
            private boolean read;
            @Override public Optional<Block> next() {
                if (read) { return Optional.empty(); }
                read = true; return Optional.of(new Paragraph("p", "TEXT", FORMAT));
            }
            @Override public void close() {
                active.get().expectedPages(2).backend(next.backend()).metadata(DocumentMetadata.EMPTY)
                        .limits(new DocumentLayoutLimits(1, 1, 1, 1));
            }
        };
        var request = DocumentEngine.builder(fonts).build().exportSemantic(PAGE, source)
                .expectedPages(1).metadata(metadata).backend(first.backend());
        active.set(request);
        assertEquals(1, request.to(new ByteArrayOutputStream()).pages());
        assertEquals(1, first.opens); assertEquals(1, first.finishes); assertEquals(0, next.opens);
        assertEquals(metadata, first.metadata);
    }

    @Test void cancellationAndByteLimitsCloseTheSingleSourceAndKeepOldTarget() throws IOException {
        var target = temporary.resolve("execution.bin");
        for (boolean cancel : List.of(false, true)) {
            Files.writeString(target, "old");
            var opened = new AtomicInteger(); var closed = new AtomicInteger(); var cancelled = new AtomicBoolean(); var sink = new Sink();
            if (cancel) { sink.afterPage = () -> cancelled.set(true); }
            var failure = assertThrows(ExecutionException.class, () -> DocumentEngine.builder(fonts).build()
                    .exportSemantic(PAGE, factory(StreamingDocumentExportTest::twoPages, opened, closed)).expectedPages(2)
                    .options(options -> options.cancelled(cancelled::get).maxOutputBytes(cancel ? 100 : 1))
                    .backend(sink.backend()).to(target));
            if (cancel) { assertEquals(ExecutionException.Code.CANCELLED, failure.code()); }
            assertEquals(1, opened.get()); assertEquals(1, closed.get()); assertEquals(0, sink.finishes);
            assertEquals(1, sink.closes); assertEquals("old", Files.readString(target));
        }
    }

    private static List<Block> twoPages() {
        return List.of(Paragraph.mixed("a", List.of(text("PAGE "), field(InlineField.Kind.PAGE, ""), text(" / "),
                field(InlineField.Kind.PAGES, "")), FORMAT), new PageBreak("break"), new Paragraph("b", "TAIL", FORMAT));
    }
    private static PageTemplate template() {
        return PageTemplate.builder().footer(PageBand.builder().height(16).gap(4)
                .content(PageNumberComponent.builder("Page {page} / {pages}").fontSize(10).lineHeight(15).build()).build()).build();
    }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 24, InlineStyle.of(10)); }
    private static BlockSource.Factory factory(Supplier<List<Block>> blocks, AtomicInteger opens, AtomicInteger closes) {
        return () -> {
            opens.incrementAndGet();
            var values = blocks.get().iterator();
            return new BlockSource() {
                @Override public Optional<Block> next() { return values.hasNext() ? Optional.of(values.next()) : Optional.empty(); }
                @Override public void close() { closes.incrementAndGet(); }
            };
        };
    }

    private static final class Sink implements PageWriter {
        private final List<DisplayPage> pages = new ArrayList<>();
        private final List<DocumentExtras.ExternalLink> links = new ArrayList<>();
        private OutputStream output;
        private DocumentMetadata metadata;
        private int opens;
        private int finishes;
        private int closes;
        private IOException closeFailure;
        private Runnable beforeFinish = () -> { };
        private Runnable afterPage = () -> { };

        PageBackend backend() {
            return (value, target) -> { metadata = value; output = target; opens++; return this; };
        }
        @Override public void configureNavigation(PageNavigation navigation) { }
        @Override public void accept(DisplayPage page) throws IOException { pages.add(page); output.write(7); afterPage.run(); }
        @Override public void accept(DisplayPage page, List<DocumentExtras.ExternalLink> external) throws IOException {
            links.addAll(external); accept(page);
        }
        @Override public void finish() throws IOException { beforeFinish.run(); finishes++; output.write(9); }
        @Override public void close() throws IOException { closes++; output.close(); if (closeFailure != null) { throw closeFailure; } }
    }
}
