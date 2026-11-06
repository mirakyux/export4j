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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.FontFamily;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.RgbImage;
import com.mirakyux.export4j.graphics.TextStyle;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.text.ParagraphStyle;

class PagedDocumentExportTest {
    @TempDir Path temporary;
    private static final PageSettings PAGE = PageSettings.builder().size(380, 180).margins(12).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();
    private static final CellFormat CELL = CellFormat.builder().fontSize(10).lineHeight(15).build();
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = PagedDocumentExportTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
    }

    @Test void matchesFullOutputWithSectionsContentsFutureFieldsRepeatedHeadersAndContinuedFootnotes() throws IOException {
        var table = Table.builder("table").cellFormat(CELL).headerCells(TableCell.mixed(List.of(
                text("HEADER "), field(InlineField.Kind.PAGE, ""), text(" / "), field(InlineField.Kind.PAGES, "")), CELL));
        for (int index = 0; index < 15; index++) { table.row("ROW-" + index); }
        var lead = Paragraph.mixed("lead", List.of(text("PAGE "), field(InlineField.Kind.PAGE, ""), text(" / "),
                field(InlineField.Kind.PAGES, ""), text(" TARGET "), field(InlineField.Kind.REFERENCE_PAGE, "target"),
                new InlineText(" LINK", InlineStyle.of(10), "https://example.com/report")), FORMAT);
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new Heading("heading", "Heading", 1, FORMAT), lead, table.build()), 0),
                new Section("second", PAGE, List.of(new Caption("target", "Figure", "Target", FORMAT)), 7)));
        var navigation = Navigation.builder().bookmark("Heading", "heading", 1).bookmark("Target", "target", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        var notes = List.of(new Note("note", "lead", "continued note line\n".repeat(20), Note.Kind.FOOTNOTE));
        var header = PageBand.builder().height(16).gap(4)
                .content(PageNumberComponent.builder("P={page} T={pages}").fontSize(10).lineHeight(15).build()).build();
        var engine = DocumentEngine.builder(fonts).pageTemplate(PageTemplate.builder().header(header).build())
                .navigation(navigation).notes(notes).build();
        var full = engine.layout(document);
        var sink = new Sink();
        var diagnostics = new ArrayList<ContextualDiagnostic>();
        var summary = engine.exportPaged(document).backend(sink.backend()).diagnostics(diagnostics::add).to(new ByteArrayOutputStream());
        assertTrue(summary.pages() > 5);
        assertEquals(full.display().pages().size(), summary.pages());
        assertEquals(full.body().pages().size(), summary.bodyPages());
        assertEquals(full.bodyPageOffset(), summary.contentsPages());
        assertEquals(full.display().extras().bookmarks(), sink.navigation.bookmarks());
        assertEquals(full.display().extras().links(), sink.navigation.links());
        assertEquals(full.display().extras().externalLinks(), sink.externalLinks);
        for (int index = 0; index < sink.pages.size(); index++) {
            assertEquals(full.display().pages().get(index).commands(), sink.pages.get(index).commands(), "physical page " + index);
        }
        assertEquals(full.contextualDiagnostics(), diagnostics);
        assertEquals(diagnostics.size(), summary.diagnostics());
        assertEquals(3, summary.outputBytes());
        assertEquals(1, sink.finishes); assertEquals(1, sink.closes);
    }

    @Test void semanticTablesInContinuousColumnsPreserveFutureLinksAndSourceBudgets() throws IOException {
        var table = Table.builder("semantic").cellFormat(CELL).headerCells(TableCell.mixed(List.of(text("REF "),
                field(InlineField.Kind.REFERENCE_PAGE, "target")), CELL));
        for (int index = 0; index < 24; index++) { table.row("R" + index); }
        var columns = ContinuousColumns.builder(FlowColumn.builder().semanticTable(table.build()).build()).gap(12).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new ComponentBlock("host", columns)), 0),
                new Section("last", PAGE, List.of(new Paragraph("target", "TARGET", FORMAT)), 11)));
        var engine = DocumentEngine.builder(fonts).build();
        var full = engine.layout(source);
        var sink = new Sink();
        var summary = engine.exportPaged(source).backend(sink.backend()).to(new ByteArrayOutputStream());
        assertEquals(full.display().pages().size(), summary.pages());
        assertEquals(full.display().extras().links(), sink.navigation.links());
        for (int page = 0; page < sink.pages.size(); page++) {
            assertEquals(full.display().pages().get(page).commands(), sink.pages.get(page).commands());
        }
    }

    @Test void refusesUnsupportedInteractionsAndUnknownFutureTargetsBeforeOpeningWriter() {
        var document = document(new Paragraph("p", "TEXT", FORMAT));
        var sink = new Sink();
        var navigation = Navigation.builder().comment(new Navigation.BlockTarget("p", new Rect(0, 0, 10, 10)), "author", "comment").build();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).navigation(navigation).build()
                .exportPaged(document).backend(sink.backend()).to(new ByteArrayOutputStream()));
        var unknown = document(Paragraph.mixed("ref", List.of(field(InlineField.Kind.REFERENCE_PAGE, "missing")), FORMAT));
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build()
                .exportPaged(unknown).backend(sink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, sink.opens);
    }

    @Test void sharedOutputLineAndPlacementBudgetsAreEnforcedAcrossSectionsBeforeWriterOpens() {
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("a", PAGE, List.of(new Paragraph("a1", "A\nB\nC", FORMAT)), 0),
                new Section("b", PAGE, List.of(new Paragraph("b1", "D\nE\nF", FORMAT)), 0)));
        var sink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                .limits(new DocumentLayoutLimits(10, 1000, 5, 10)).backend(sink.backend()).to(new ByteArrayOutputStream()));
        var header = PageBand.builder().height(16).content(PageNumberComponent.builder().fontSize(10).lineHeight(15).build()).build();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts)
                .pageTemplate(PageTemplate.builder().header(header).build()).build().exportPaged(source)
                .componentLimits(new ComponentLimits(1, 1000, 100)).backend(sink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, sink.opens);
    }

    @Test void fieldAndExternalInteractionsShareOneBudgetAcrossPages() {
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("a", PAGE, List.of(Paragraph.mixed("ref", List.of(field(InlineField.Kind.REFERENCE_PAGE, "target")), FORMAT)), 0),
                new Section("b", PAGE, List.of(new Paragraph("target", List.of(new InlineText("LINK", InlineStyle.of(10), "https://example.com")), FORMAT)), 0)));
        var sink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                .componentLimits(new ComponentLimits(100, 1000, 1)).backend(sink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, sink.opens);
    }

    @Test void changingGeometryDuringPlanningFailsBeforeOpeningOutputAndKeepsTarget() throws IOException {
        var calls = new AtomicInteger();
        var source = document(new ComponentBlock("dynamic", context -> new ComponentLayout(20, 10 + calls.incrementAndGet(), 0, List.of(), List.of())));
        var target = temporary.resolve("changing.bin"); Files.writeString(target, "old");
        var sink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source).backend(sink.backend()).to(target));
        assertEquals(0, sink.opens); assertEquals("old", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void changedGeometryDrawingAndSemanticSourcesAfterWriterOpenNeverFinishOrReplaceOldTarget() throws IOException {
        var target = temporary.resolve("replay.bin");
        for (String change : List.of("geometry", "drawing", "source")) {
            Files.writeString(target, "old");
            var opened = new AtomicBoolean();
            var sink = new Sink();
            var source = document(new ComponentBlock("dynamic", context -> {
                if (change.equals("source")) {
                    var table = Table.builder("nested").cellFormat(CELL).row(opened.get() ? "NEW" : "OLD").build();
                    return new SemanticTable(table).layout(context);
                }
                return new ComponentLayout(20, change.equals("geometry") && opened.get() ? 30 : 20, 0,
                        List.of(new DrawCommand.FillRect(new Rect(0, 0, 10, 10),
                                change.equals("drawing") && opened.get() ? new RgbColor(1, 0, 0) : RgbColor.BLACK)), List.of());
            }));
            assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                    .backend((metadata, output) -> { opened.set(true); return sink.open(output); }).to(target));
            assertTrue(sink.pages.isEmpty()); assertEquals(0, sink.finishes); assertEquals(1, sink.closes);
            assertEquals("old", Files.readString(target));
        }
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void equivalentFreshRasterInstancesAreDeterministicAcrossReplay() throws IOException {
        var source = document(new ComponentBlock("image", context -> new ComponentLayout(10, 10, 0,
                List.of(new DrawCommand.Image(new RgbImage(1, 1, new byte[]{1, 2, 3}), new Rect(0, 0, 10, 10))), List.of())));
        var sink = new Sink();
        assertEquals(1, DocumentEngine.builder(fonts).build().exportPaged(source).backend(sink.backend()).to(new ByteArrayOutputStream()).pages());
        assertEquals(1, sink.finishes);
    }

    @Test void differentTextWithTheSameJavaHashCannotBypassReplayVerification() throws IOException {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        var opened = new AtomicBoolean();
        var source = document(new ComponentBlock("collision", context -> new ComponentLayout(30, 20, 1,
                List.of(new DrawCommand.Text(opened.get() ? "BB" : "Aa", new Point(0, 12),
                        new TextStyle(FontFamily.SANS_SERIF, 10, RgbColor.BLACK))), List.of())));
        var target = temporary.resolve("collision.bin"); Files.writeString(target, "old");
        var sink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                .backend((metadata, output) -> { opened.set(true); return sink.open(output); }).to(target));
        assertTrue(sink.pages.isEmpty()); assertEquals(0, sink.finishes); assertEquals(1, sink.closes);
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void manyIndependentHyperlinkedTablesRetainOnlyABoundedNumberOfParsedGrids() throws IOException {
        var tables = new ArrayList<Block>();
        for (int index = 0; index < 80; index++) {
            tables.add(Table.builder("table-" + index).cellFormat(CELL).row(TableCell.mixed(List.of(
                    new InlineText("LINK-" + index, InlineStyle.of(10), "https://example.com/" + index)), CELL)).build());
        }
        var links = new InlineHyperlinks.Session(tables, 0, 1000);
        int[] count = {0};
        new DocumentLayouter().layoutPages(new Document(DocumentMetadata.EMPTY, PAGE, tables), fonts, Map.of(), page -> {
            count[0] += links.collect(page).size();
            assertTrue(links.cachedTables() <= 16);
        });
        assertEquals(80, count[0]);
        assertEquals(16, links.cachedTables());
    }

    @Test void borrowedOutputRemainsOpenAndCleanupFailureIsSuppressedOnAcceptFailure() throws IOException {
        var closed = new AtomicBoolean();
        var bytes = new ByteArrayOutputStream() { @Override public void close() { closed.set(true); } };
        var source = document(new Paragraph("p", "TEXT", FORMAT));
        var sink = new Sink();
        DocumentEngine.builder(fonts).build().exportPaged(source).backend(sink.backend()).to(bytes);
        assertFalse(closed.get()); assertEquals(3, bytes.size());
        var accept = new IOException("accept"); var cleanup = new IOException("cleanup");
        var failed = new Sink(); failed.acceptFailure = accept; failed.closeFailure = cleanup;
        assertSame(accept, assertThrows(IOException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                .backend(failed.backend()).to(new ByteArrayOutputStream())));
        assertEquals(List.of(cleanup), List.of(accept.getSuppressed()));
        assertEquals(0, failed.finishes); assertEquals(1, failed.closes);
    }

    @Test void cancellationAndOutputBudgetFailuresCloseWriterAndKeepOldPath() throws IOException {
        var source = document(new Paragraph("p", "LINE\n".repeat(30), FORMAT));
        var target = temporary.resolve("cancelled.bin");
        for (boolean cancel : List.of(false, true)) {
            Files.writeString(target, "old");
            var cancelled = new AtomicBoolean();
            var sink = new Sink(); sink.afterPage = () -> { if (cancel) { cancelled.set(true); } };
            var failure = assertThrows(ExecutionException.class, () -> DocumentEngine.builder(fonts).build().exportPaged(source)
                    .backend(sink.backend()).options(options -> options.cancelled(cancelled::get).maxOutputBytes(cancel ? 100 : 2)).to(target));
            if (cancel) { assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals(1, sink.pages.size()); assertEquals(0, sink.finishes); }
            assertEquals(1, sink.closes); assertEquals("old", Files.readString(target));
        }
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test void executionCallbacksCannotChangeTheActiveRequestSnapshot() throws IOException {
        var first = new Sink(); var next = new Sink();
        var request = DocumentEngine.builder(fonts).build().exportPaged(document(new Paragraph("p", "TEXT", FORMAT))).backend(first.backend());
        request.options(options -> options.progress(progress -> {
            request.backend(next.backend()).limits(new DocumentLayoutLimits(1, 1, 1, 1));
        }));
        assertEquals(1, request.to(new ByteArrayOutputStream()).pages());
        assertEquals(1, first.opens); assertEquals(0, next.opens);
    }

    private static Document document(Block... blocks) { return new Document(DocumentMetadata.EMPTY, PAGE, List.of(blocks)); }
    private static InlineText text(String value) { return new InlineText(value, InlineStyle.of(10)); }
    private static InlineField field(InlineField.Kind kind, String target) { return new InlineField(kind, target, 24, InlineStyle.of(10)); }

    private static final class Sink implements PageWriter {
        private final List<DisplayPage> pages = new ArrayList<>();
        private final List<DocumentExtras.ExternalLink> externalLinks = new ArrayList<>();
        private PageNavigation navigation;
        private OutputStream output;
        private int opens;
        private int finishes;
        private int closes;
        private IOException acceptFailure;
        private IOException closeFailure;
        private Runnable afterPage = () -> { };
        PageBackend backend() { return (metadata, target) -> open(target); }
        Sink open(OutputStream target) { opens++; output = target; return this; }
        @Override public void configureNavigation(PageNavigation value) { navigation = value; }
        @Override public void accept(DisplayPage page) throws IOException {
            pages.add(page); afterPage.run(); if (acceptFailure != null) { throw acceptFailure; }
        }
        @Override public void accept(DisplayPage page, List<DocumentExtras.ExternalLink> links) throws IOException {
            externalLinks.addAll(links); accept(page);
        }
        @Override public void finish() throws IOException { finishes++; output.write(new byte[]{1, 2, 3}); }
        @Override public void close() throws IOException { closes++; output.close(); if (closeFailure != null) { throw closeFailure; } }
    }
}
