// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxFileInputsTest {
    @TempDir Path directory;
    private final DocxAdapter adapter = new DocxAdapter();
    private final Document source = Document.builder().add(Paragraph.mixed("p",
            List.of(new InlineText("Body preserved", InlineStyle.of(12))), ParagraphFormat.of(12))).build();

    @Test void directFileReadWritePreservesDocumentsAndPageRegions() throws IOException {
        var file = directory.resolve("报告 sample.docx");
        adapter.write(source, file.toString());
        for (var result : List.of(adapter.read(file), adapter.read(file.toFile()), adapter.read(file.toString(), DocxAdapter.Policy.STRICT))) {
            assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
        }
        var page = new PageDocument(source, new PageRegions(new PageRegion(24, 2,
                List.of(new Paragraph("header", "Header preserved", ParagraphFormat.of(12)))), PageRegion.EMPTY));
        adapter.write(page, file.toFile(), ExecutionOptions.DEFAULT);
        try (var archive = new ZipFile(file.toFile())) {
            var header = archive.stream().filter(entry -> entry.getName().startsWith("word/header") && entry.getName().endsWith(".xml")).findFirst().orElseThrow();
            try (var input = archive.getInputStream(header)) {
                assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("Header preserved"));
            }
        }
        var sections = new SectionedDocument(source.metadata(), List.of(new Section("first", source.pageSettings(), source.blocks(), 0)));
        adapter.write(sections, file, ExecutionOptions.DEFAULT);
        assertEquals(source.blocks(), adapter.read(file).document().sections().getFirst().blocks());
        Files.move(file, directory.resolve("released.docx"));
    }

    @Test void directAtomicOutputKeepsOldFileOnBudgetAndCancellation() throws IOException {
        var file = directory.resolve("protected.docx"); Files.writeString(file, "old");
        assertThrows(ExecutionException.class, () -> adapter.write(source, file,
                ExecutionOptions.builder().maxOutputBytes(1).build()));
        assertEquals("old", Files.readString(file));
        assertThrows(ExecutionException.class, () -> adapter.write(source, file.toString(),
                ExecutionOptions.builder().cancelled(() -> true).build()));
        assertEquals("old", Files.readString(file));
        try (var files = Files.list(directory)) { assertEquals(List.of(file), files.toList()); }
    }

    @Test void defaultReadAndControlledWriteBorrowTheirStreams() throws IOException {
        var output = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("borrowed output closed"); }
        };
        adapter.write(source, output, ExecutionOptions.DEFAULT);
        var input = new ByteArrayInputStream(output.toByteArray()) {
            @Override public void close() { throw new AssertionError("borrowed input closed"); }
        };
        assertEquals(source.blocks(), adapter.read(input).document().sections().getFirst().blocks());
    }

    @Test void conversionReadsTheFileAtPreparationAndReturnsCompleteResult() throws IOException {
        var input = directory.resolve("created-later.docx"); var output = directory.resolve("converted.docx");
        var request = adapter.conversion(input);
        assertFalse(Files.exists(input)); adapter.write(source, input);
        var result = request.convertTo(adapter.conversionTarget(), output.toFile());
        assertEquals(Files.size(output), result.bytesWritten());
        assertEquals(source.blocks(), adapter.read(output).document().sections().getFirst().blocks());
        assertTrue(result.report().losses().isEmpty());
        var limited = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxInputBytes(16).build());
        assertThrows(IOException.class, () -> limited.conversion(input).prepare(limited.conversionTarget()));
    }

    @Test void streamConversionKeepsBothBorrowedStreamsOpen() throws IOException {
        var encoded = new ByteArrayOutputStream(); adapter.write(source, encoded);
        var input = new ByteArrayInputStream(encoded.toByteArray()) {
            @Override public void close() { throw new AssertionError("borrowed conversion source closed"); }
        };
        var output = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("borrowed conversion output closed"); }
            @Override public void flush() { throw new AssertionError("borrowed conversion output flushed"); }
        };
        var result = adapter.conversion(input).convertTo(adapter.conversionTarget(), output);
        assertEquals(output.size(), result.bytesWritten());
        assertEquals(source.blocks(), adapter.read(new ByteArrayInputStream(output.toByteArray())).document().sections().getFirst().blocks());
    }
}
