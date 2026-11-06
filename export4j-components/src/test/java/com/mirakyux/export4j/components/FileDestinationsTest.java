// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;

class FileDestinationsTest {
    enum Target { STRING, FILE }
    private static DocumentEngine engine;
    @TempDir Path directory;
    private static final Document BODY = Document.builder().paragraph("Body").build();
    private static final DocumentBackend TEXT = (document, output) -> {
        for (var page : document.pages()) {
            for (var command : page.commands()) {
                if (command instanceof DrawCommand.GlyphText text) {
                    output.write((text.run().text() + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    };

    @BeforeAll static void fonts() throws IOException {
        try (var input = FileDestinationsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(input)))).build();
        }
    }

    private static PageDocument pageDocument() {
        return new PageDocument(BODY, new PageRegions(
                new PageRegion(24, 2, List.of(new Paragraph("header", "Header", ParagraphFormat.of(12)))), PageRegion.EMPTY));
    }

    @ParameterizedTest @EnumSource(Target.class)
    void pageDocumentConvenienceWritePreservesItsHeaderAndBody(Target kind) throws IOException {
        var target = directory.resolve("报告 sample.txt");
        var document = pageDocument();
        var result = kind == Target.STRING
                ? engine.write(document, TEXT, target.toString(), ExecutionOptions.DEFAULT)
                : engine.write(document, TEXT, target.toFile(), ExecutionOptions.DEFAULT);
        assertEquals(1, result.decorations().size());
        assertTrue(Files.readString(target).contains("Header\n"));
        assertTrue(Files.readString(target).contains("Body\n"));
        var request = engine.export(document).backend(TEXT);
        if (kind == Target.STRING) { request.to(target.toString()); } else { request.to(target.toFile()); }
        assertTrue(Files.readString(target).contains("Header\n"));
    }

    @ParameterizedTest @EnumSource(Target.class)
    void fileConvenienceKeepsOldTargetsOnFailureAndCancellation(Target kind) throws IOException {
        var target = directory.resolve("protected.txt"); Files.writeString(target, "old");
        var rejected = new IOException("injected backend failure");
        DocumentBackend broken = (document, output) -> { output.write(42); throw rejected; };
        var request = engine.export(BODY).backend(broken);
        assertSame(rejected, assertThrows(IOException.class, () -> {
            if (kind == Target.STRING) { request.to(target.toString()); } else { request.to(target.toFile()); }
        }));
        assertEquals("old", Files.readString(target));
        var cancelled = ExecutionOptions.builder().cancelled(() -> true).build();
        assertEquals(ExecutionException.Code.CANCELLED, assertThrows(ExecutionException.class, () -> {
            if (kind == Target.STRING) { engine.write(pageDocument(), TEXT, target.toString(), cancelled); }
            else { engine.write(pageDocument(), TEXT, target.toFile(), cancelled); }
        }).code());
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @ParameterizedTest @EnumSource(Target.class)
    void streamingTargetsKeepSinglePassAndExplicitReplaySourceOwnership(Target kind) throws IOException {
        var reads = new AtomicInteger(); var closes = new AtomicInteger();
        var spec = StreamingTable.builder("stream").columns(120).header("ID").build();
        TableRowSource.Factory rows = () -> new TableRowSource() {
            private boolean emitted;
            @Override public Optional<TableRow> next() {
                reads.incrementAndGet();
                if (emitted) { return Optional.empty(); }
                emitted = true;
                return Optional.of(new TableRow(List.of(new TableCell("ONE", CellFormat.of(12)))));
            }
            @Override public void close() { closes.incrementAndGet(); }
        };
        PageBackend backend = (metadata, output) -> new PageWriter() {
            @Override public void accept(DisplayPage page) throws IOException { output.write(42); }
            @Override public void finish() { }
            @Override public void close() { }
        };
        var target = directory.resolve("rows.bin");
        var single = engine.export(spec, rows).backend(backend);
        if (kind == Target.STRING) { single.to(target.toString()); } else { single.to(target.toFile()); }
        assertEquals(2, reads.get()); assertEquals(1, closes.get());
        var replay = engine.exportReplay(spec, rows).backend(backend);
        if (kind == Target.STRING) { replay.to(target.toString()); } else { replay.to(target.toFile()); }
        assertEquals(6, reads.get()); assertEquals(3, closes.get());
        var semantic = engine.exportSemantic(spec, rows).backend(backend);
        if (kind == Target.STRING) { semantic.to(target.toString()); } else { semantic.to(target.toFile()); }
        assertEquals(8, reads.get()); assertEquals(4, closes.get());
        assertEquals(1, Files.size(target));
    }

    @Test void pageDocumentStreamWriteBorrowsItsDestination() throws IOException {
        var output = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("borrowed stream closed"); }
            @Override public void flush() { throw new AssertionError("borrowed stream flushed"); }
        };
        engine.write(pageDocument(), TEXT, output);
        assertFalse(output.toString(StandardCharsets.UTF_8).isBlank());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Header\n"));
    }
}
