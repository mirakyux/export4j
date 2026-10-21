// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphStyle;

class ExecutionAndLayoutTest {
    @Test void shortPathOutputAndRichListsPreserveStylesLinksAndOriginalIds() throws IOException {
        var fonts = fonts();
        var engine = DocumentEngine.builder(fonts).fontRole("bold", fonts).build();
        var run = new com.mirakyux.export4j.model.InlineText("Linked", new com.mirakyux.export4j.model.InlineStyle("bold", 12, 0x224488, 0, true, false), "https://example.com");
        var doc = Document.builder().heading(List.of(run), 1).bullet(List.of(run)).numbered("list", List.of(run)).build();
        var result = engine.write(doc, (d, output) -> output.write(42), directory.resolve("short.bin"));
        assertEquals(42, Files.readAllBytes(directory.resolve("short.bin"))[0]);
        assertEquals(3, result.display().extras().externalLinks().size());
        assertEquals("1", SemanticBlocks.compile(new com.mirakyux.export4j.model.SectionedDocument(doc.metadata(),
                List.of(new com.mirakyux.export4j.model.Section("s", doc.pageSettings(), doc.blocks(), 0))), List.of()).labels().get("auto-3"));
        var fragments = result.body().pages().getFirst().fragments();
        assertEquals(List.of("auto-1", "auto-2", "auto-3"), fragments.stream().map(com.mirakyux.export4j.layout.ParagraphFragment::paragraphId).toList());
        assertTrue(fragments.getLast().layout().lines().getFirst().runs().stream().anyMatch(r -> r.rgb() == 0x224488));
        assertEquals("1. Linked", fragments.getLast().layout().lines().getFirst().runs().stream().map(com.mirakyux.export4j.text.GlyphRun::text).collect(java.util.stream.Collectors.joining()));
        var sectioned = new com.mirakyux.export4j.model.SectionedDocument(doc.metadata(), List.of(
                new com.mirakyux.export4j.model.Section("one", doc.pageSettings(), doc.blocks(), 0)));
        var sectionedResult = engine.write(sectioned, (d, output) -> output.write(43), directory.resolve("sections.bin"));
        assertEquals(result.display(), sectionedResult.display());
        assertEquals(43, Files.readAllBytes(directory.resolve("sections.bin"))[0]);
    }

    @TempDir Path directory;
    private static FontGroup fonts() throws IOException {
        try (var input = ExecutionAndLayoutTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static Document document() { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(new Paragraph("body", "Text", ParagraphFormat.of(12)))); }

    @Test void cancellationDeadlineByteBudgetAndBackendFailurePreserveTargetAndReleaseScope() throws IOException {
        var engine = DocumentEngine.builder(fonts()).build();
        var target = directory.resolve("report.bin"); Files.writeString(target, "original");
        var cancelled = new AtomicBoolean();
        List<ExecutionOptions.Progress> progress = new ArrayList<>();
        var options = new ExecutionOptions(Duration.ofSeconds(30), cancelled::get, p -> {
            progress.add(p); if (p.stage().equals("BODY")) { cancelled.set(true); }
        }, 1000);
        var failure = assertThrows(ExecutionException.class, () -> engine.writeAtomically(document(), (d, out) -> out.write(1), target, options));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals("body", failure.context().blockId());
        assertEquals("original", Files.readString(target)); assertTrue(progress.stream().anyMatch(p -> p.stage().equals("BODY")));
        assertEquals(ExecutionException.Code.TIMEOUT, assertThrows(ExecutionException.class, () -> engine.write(document(), (d, out) -> out.write(1),
                new ByteArrayOutputStream(), new ExecutionOptions(Duration.ofNanos(1), () -> false, p -> { }, 100))).code());
        assertEquals(ExecutionException.Code.OUTPUT_LIMIT, assertThrows(ExecutionException.class, () -> engine.writeAtomically(document(),
                (d, out) -> out.write(new byte[20]), target, new ExecutionOptions(Duration.ZERO, () -> false, p -> { }, 10))).code());
        assertThrows(IOException.class, () -> engine.writeAtomically(document(), (d, out) -> { out.write(1); throw new IOException("failed"); }, target, ExecutionOptions.DEFAULT));
        assertEquals("original", Files.readString(target));
        ExecutionContext.checkpoint();
        engine.writeAtomically(document(), (d, out) -> out.write("complete".getBytes(java.nio.charset.StandardCharsets.UTF_8)), target, ExecutionOptions.DEFAULT);
        assertEquals("complete", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void autoColumnsPreferContentAndBalancedColumnsRetainEveryLine() throws IOException {
        var fonts = fonts();
        var table = new Table("table", List.of(new ColumnWidth.Auto(20, 200), new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(
                new TableCell("ID", CellFormat.of(12)), new TableCell("Description", CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var layout = DocumentEngine.builder(fonts).build().layout(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(table)));
        var fragment = (TableFragment) layout.body().pages().getFirst().elements().getFirst();
        assertTrue(fragment.rows().getFirst().cells().getFirst().bounds().width() < 40);
        assertEquals(PageSettings.A4.contentWidth(), fragment.width(), 1e-8);
        var text = new FlowText("A\nB\nC\nD\nE\nF\nG", ParagraphStyle.of(12), RgbColor.BLACK, PaginationPolicy.DEFAULT);
        var parts = new MultiColumn(text, 2, 12, true).layoutParts(new ComponentContext(300, 400, fonts, 0, 0, 1));
        assertEquals(1, parts.size()); assertEquals(7, parts.getFirst().textLines());
        assertTrue(parts.getFirst().height() < 100);
    }
}
