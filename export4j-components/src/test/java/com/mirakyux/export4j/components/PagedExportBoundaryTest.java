// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentLimits;
import com.mirakyux.export4j.layout.core.DocumentLayoutLimits;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
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
import com.mirakyux.export4j.text.ParagraphStyle;

class PagedExportBoundaryTest {
    private static final PageSettings PAGE = PageSettings.builder().size(380, 180).margins(12).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(10).lineHeight(15).spaceAfter(0).build();
    private static final Rect MARKER = new Rect(0, 0, 20, 10);
    private static final Component BOX = context -> new ComponentLayout(20, 10, 0,
            List.of(new DrawCommand.FillRect(MARKER, RgbColor.BLACK)), List.of());
    private static FontGroup fonts;
    @TempDir Path temporary;

    @BeforeAll
    static void fonts() throws IOException {
        try (var input = PagedExportBoundaryTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES));
        }
    }

    @Test
    void modelPageRegionsResolvePageAndFinalPagesOnEveryExportedPage() throws IOException {
        var body = document(new Paragraph("one", "BODY-ONE", FORMAT), new PageBreak("break-one"),
                new Paragraph("two", "BODY-TWO", FORMAT), new PageBreak("break-two"), new Paragraph("three", "BODY-THREE", FORMAT));
        var source = new PageDocument(body, new PageRegions(numberedRegion("header", "H-"), numberedRegion("footer", "F-")));
        var sink = new Sink();
        var summary = DocumentEngine.builder(fonts).build().exportPaged(source).backend(sink.backend()).to(new ByteArrayOutputStream());
        assertEquals(3, summary.pages());
        assertEquals(3, sink.navigation.pages());
        for (int page = 0; page < sink.pages.size(); page++) {
            String text = text(sink.pages.get(page));
            assertTrue(text.contains("H-" + (page + 1) + "/3"), text);
            assertTrue(text.contains("F-" + (page + 1) + "/3"), text);
        }
        assertEquals(1, sink.finishes);
        assertEquals(1, sink.closes);
    }

    @Test
    void configuredJavaTemplateCannotSilentlyReplaceModelPageRegions() {
        var source = new PageDocument(document(new Paragraph("body", "BODY", FORMAT)),
                new PageRegions(numberedRegion("header", "H-"), PageRegion.EMPTY));
        var engine = DocumentEngine.builder(fonts).pageTemplate(PageTemplate.builder()
                .header(PageBand.builder().height(15).build()).build()).build();
        var failure = assertThrows(IllegalStateException.class, () -> engine.exportPaged(source));
        assertTrue(failure.getMessage().contains("conflicts"));
    }

    @Test
    void everyPagedFactoryInheritsEngineBudgetsAndRequestOverridesStayLocal() throws IOException {
        var body = document(new Paragraph("first", "A", FORMAT), new Paragraph("second", "B", FORMAT));
        var sectioned = new SectionedDocument(body.metadata(), List.of(new Section("section", PAGE, body.blocks(), 0)));
        for (boolean structural : List.of(true, false)) {
            var builder = DocumentEngine.builder(fonts);
            if (structural) { builder.layoutLimits(limits -> limits.maxCodePoints(1)); }
            else { builder.componentLimits(limits -> limits.maxCommands(1)); }
            var engine = builder.build();
            List<Supplier<PagedExportRequest>> factories = List.of(() -> engine.exportPaged(body),
                    () -> engine.exportPaged(new PageDocument(body, PageRegions.EMPTY)),
                    () -> engine.exportPaged(sectioned),
                    () -> engine.exportPaged(sectioned, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, List.of()));
            for (var factory : factories) {
                var rejected = new Sink();
                assertThrows(IllegalArgumentException.class,
                        () -> factory.get().backend(rejected.backend()).to(new ByteArrayOutputStream()));
                assertEquals(0, rejected.opens);
                var accepted = new Sink();
                assertEquals(1, factory.get().limits(DocumentLayoutLimits.DEFAULT).componentLimits(ComponentLimits.DEFAULT)
                        .backend(accepted.backend()).to(new ByteArrayOutputStream()).pages());
                assertEquals("AB", text(accepted.pages.getFirst()));
                assertEquals(1, accepted.finishes);
                assertThrows(IllegalArgumentException.class,
                        () -> factory.get().backend(new Sink().backend()).to(new ByteArrayOutputStream()));
            }
        }
    }

    @Test
    void successfulFinishFollowedByCloseFailurePreservesOldPathAndRemovesTemporaryOutput() throws IOException {
        var target = temporary.resolve("report.bin");
        Files.writeString(target, "old destination");
        var sink = new Sink();
        var cleanup = new IOException("backend close failed");
        sink.closeFailure = cleanup;
        assertSame(cleanup, assertThrows(IOException.class, () -> DocumentEngine.builder(fonts).build()
                .exportPaged(document(new Paragraph("body", "BODY", FORMAT))).backend(sink.backend()).to(target)));
        assertEquals(1, sink.pages.size());
        assertEquals(1, sink.finishes);
        assertEquals(1, sink.closes);
        assertEquals("old destination", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test
    void navigationConfigurationFailureClosesWithoutAcceptingOrFinishingAndPreservesPrimaryFailure() {
        var sink = new Sink();
        var configuration = new IOException("navigation rejected");
        var cleanup = new IOException("cleanup failed");
        sink.navigationFailure = configuration;
        sink.closeFailure = cleanup;
        var output = new ByteArrayOutputStream();
        assertSame(configuration, assertThrows(IOException.class, () -> DocumentEngine.builder(fonts).build()
                .exportPaged(document(new Paragraph("body", "BODY", FORMAT))).backend(sink.backend()).to(output)));
        assertEquals(List.of(cleanup), List.of(configuration.getSuppressed()));
        assertEquals(1, sink.opens);
        assertEquals(1, sink.configurations);
        assertTrue(sink.pages.isEmpty());
        assertEquals(0, sink.finishes);
        assertEquals(1, sink.closes);
        assertEquals(0, output.size());
    }

    @Test
    void completedSemanticSourcesCannotReuseDescendantIdsInAnotherSection() throws IOException {
        var engine = DocumentEngine.builder(fonts).build();
        var valid = semanticSections("leaf-a", "leaf-b");
        var validSink = new Sink();
        assertEquals(2, engine.exportPaged(valid).backend(validSink.backend()).to(new ByteArrayOutputStream()).pages());
        var rejected = new Sink();
        var failure = assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(semanticSections("shared-leaf", "shared-leaf"))
                .backend(rejected.backend()).to(new ByteArrayOutputStream()));
        assertTrue(failure.getMessage().contains("descendant ID"));
        assertEquals(0, rejected.opens);
    }

    @Test
    void finalDecorationCommandsShareOneBudgetAcrossAllPages() throws IOException {
        var source = twoSections();
        var engine = DocumentEngine.builder(fonts).pageTemplate(overlay(0, false)).build();
        var baseline = new Sink();
        engine.exportPaged(source).backend(baseline.backend()).to(new ByteArrayOutputStream());
        int totalCommands = baseline.pages.stream().mapToInt(page -> page.commands().size()).sum();
        int perPageMaximum = baseline.pages.stream().mapToInt(page -> page.commands().size()).max().orElseThrow();
        assertTrue(perPageMaximum < totalCommands - 1);
        var rejected = new Sink();
        assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(source)
                .componentLimits(new ComponentLimits(100, totalCommands - 1, 100))
                .backend(rejected.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, rejected.opens);
    }

    @Test
    void finalHeaderLinesCountWithBodyLinesAcrossPagesAtTheExactSharedBoundary() throws IOException {
        var header = PageBand.builder().height(15).content(PageNumberComponent.builder("HEADER {page}/{pages}")
                .fontSize(10).lineHeight(15).build()).build();
        var engine = DocumentEngine.builder(fonts).pageTemplate(PageTemplate.builder().header(header).build()).build();
        var accepted = new Sink();
        assertEquals(2, engine.exportPaged(twoSections()).limits(new DocumentLayoutLimits(10, 1000, 4, 10))
                .backend(accepted.backend()).to(new ByteArrayOutputStream()).pages());
        assertTrue(text(accepted.pages.getFirst()).contains("HEADER 1/2"));
        assertTrue(text(accepted.pages.getLast()).contains("HEADER 2/2"));
        var rejected = new Sink();
        assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(twoSections())
                .limits(new DocumentLayoutLimits(10, 1000, 3, 10)).backend(rejected.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, rejected.opens);
    }

    @Test
    void physicalSelectorCanExtendToThePageBudgetButCannotExceedIt() throws IOException {
        var engine = DocumentEngine.builder(fonts).pageTemplate(overlay(3, false)).build();
        var source = document(new Paragraph("body", "BODY", FORMAT));
        var accepted = new Sink();
        assertEquals(3, engine.exportPaged(source).limits(new DocumentLayoutLimits(10, 1000, 100, 3))
                .backend(accepted.backend()).to(new ByteArrayOutputStream()).pages());
        assertTrue(accepted.pages.get(1).commands().isEmpty());
        assertEquals(0, markerCount(accepted.pages.getFirst()));
        assertEquals(1, markerCount(accepted.pages.getLast()));
        var rejected = new Sink();
        assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(source)
                .limits(new DocumentLayoutLimits(10, 1000, 100, 2)).backend(rejected.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, rejected.opens);
    }

    @Test
    void physicalSelectorMustBelongToItsDeclaringSectionAndSelectedVariant() {
        var engine = DocumentEngine.builder(fonts).build();
        var shared = SectionTemplate.uniform(overlay(1, false));
        var sectionSink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(twoSections(), List.of(shared, shared), Navigation.EMPTY, List.of())
                .backend(sectionSink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, sectionSink.opens);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("only", PAGE,
                List.of(new Paragraph("body", "BODY", FORMAT)), 0)));
        var firstPageOnly = new SectionTemplate(overlay(2, false), PageTemplate.EMPTY, PageTemplate.EMPTY);
        var variantSink = new Sink();
        assertThrows(IllegalArgumentException.class, () -> engine.exportPaged(source, List.of(firstPageOnly), Navigation.EMPTY, List.of())
                .backend(variantSink.backend()).to(new ByteArrayOutputStream()));
        assertEquals(0, variantSink.opens);
    }

    @Test
    void bodyAvoidanceReservesHeightAndBothAvoidanceAndPhysicalSelectorsRejectGeneratedContents() throws IOException {
        var source = document(new Paragraph("body", "LINE\n".repeat(9) + "LAST", FORMAT));
        var plain = new Sink();
        assertEquals(1, DocumentEngine.builder(fonts).build().exportPaged(source).backend(plain.backend()).to(new ByteArrayOutputStream()).pages());
        var avoiding = PageTemplate.builder().foreground(new Placement(context -> new ComponentLayout(20, 30, 0,
                List.of(new DrawCommand.FillRect(new Rect(0, 0, 20, 30), RgbColor.BLACK)), List.of()),
                new Position.Absolute(new Rect(20, 60, 20, 30)), 0, true)).build();
        var avoided = new Sink();
        assertEquals(2, DocumentEngine.builder(fonts).pageTemplate(avoiding).build().exportPaged(source)
                .backend(avoided.backend()).to(new ByteArrayOutputStream()).pages());

        var navigable = document(new Heading("heading", "Heading", 1, FORMAT), new Paragraph("text", "BODY", FORMAT));
        var navigation = Navigation.builder().bookmark("Heading", "heading", 1)
                .contents(new TableOfContents(true, "Contents", ParagraphStyle.of(10))).build();
        for (var template : List.of(overlay(1, false), overlay(0, true))) {
            var sink = new Sink();
            var failure = assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts)
                    .pageTemplate(template).navigation(navigation).build().exportPaged(navigable)
                    .backend(sink.backend()).to(new ByteArrayOutputStream()));
            assertTrue(failure.getMessage().contains("generated contents"));
            assertEquals(0, sink.opens);
        }
    }

    private static PageRegion numberedRegion(String id, String prefix) {
        return new PageRegion(15, 2, List.of(Paragraph.mixed(id, List.of(new InlineText(prefix, InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(10)), new InlineText("/", InlineStyle.of(10)),
                new InlineField(InlineField.Kind.PAGES, "", 24, InlineStyle.of(10))), FORMAT)));
    }

    private static SectionedDocument semanticSections(String firstLeaf, String secondLeaf) {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(semanticHost("first-host", "first-source", firstLeaf)), 0),
                new Section("second", PAGE, List.of(semanticHost("second-host", "second-source", secondLeaf)), 0)));
    }

    private static ComponentBlock semanticHost(String host, String root, String leaf) {
        var source = Table.builder(root).row(TableCell.builder().blocks(List.of(new Paragraph(leaf, "LEAF", FORMAT))).build()).build();
        return new ComponentBlock(host, new SemanticTable(source));
    }

    private static SectionedDocument twoSections() {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new Paragraph("first-body", "FIRST", FORMAT)), 0),
                new Section("second", PAGE, List.of(new Paragraph("second-body", "SECOND", FORMAT)), 0)));
    }

    private static PageTemplate overlay(int page, boolean avoid) {
        return PageTemplate.builder().foreground(new Placement(BOX,
                new Position.Absolute(new Rect(20, 60, 20, 10)), page, avoid)).build();
    }

    private static Document document(Block... blocks) { return new Document(DocumentMetadata.EMPTY, PAGE, List.of(blocks)); }

    private static String text(DisplayPage page) {
        return page.commands().stream().map(command -> switch (command) {
            case DrawCommand.GlyphText glyphs -> glyphs.run().text();
            case DrawCommand.OutlineText outlines -> outlines.logicalText();
            default -> "";
        }).collect(java.util.stream.Collectors.joining());
    }

    private static long markerCount(DisplayPage page) {
        return page.commands().stream().filter(command -> command.equals(new DrawCommand.FillRect(MARKER, RgbColor.BLACK))).count();
    }

    private static final class Sink implements PageWriter {
        private final List<DisplayPage> pages = new ArrayList<>();
        private PageNavigation navigation;
        private OutputStream output;
        private int opens;
        private int configurations;
        private int finishes;
        private int closes;
        private IOException navigationFailure;
        private IOException closeFailure;

        private PageBackend backend() {
            return (metadata, target) -> { opens++; output = target; return this; };
        }

        @Override public void configureNavigation(PageNavigation value) throws IOException {
            configurations++;
            navigation = value;
            if (navigationFailure != null) { throw navigationFailure; }
        }

        @Override public void accept(DisplayPage page) { pages.add(page); }
        @Override public void finish() throws IOException { finishes++; output.write(new byte[]{1, 2, 3}); }
        @Override public void close() throws IOException { closes++; if (closeFailure != null) { throw closeFailure; } }
    }
}
