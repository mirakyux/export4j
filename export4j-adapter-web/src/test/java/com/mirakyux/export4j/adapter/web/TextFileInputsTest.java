// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TextFileInputsTest {
    @TempDir Path directory;

    @Test void textFileInputsUseUtf8AndKeepStringContentSemantics() throws IOException {
        var html = new HtmlImporter(); var markdown = new MarkdownImporter();
        var file = directory.resolve("正文 sample.txt"); String source = "正文 😀";
        Files.writeString(file, source);
        assertEquals(html.read(source).execute().document(), html.read(file).execute().document());
        assertEquals(html.read(source).execute().document(), html.readFile(file.toString()).execute().document());
        assertEquals(markdown.read(source).execute().document(), markdown.read(file.toFile()).execute().document());
        assertEquals(markdown.read(source).execute().document(), markdown.readFile(file.toString()).execute().document());
        var input = new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() { throw new AssertionError("borrowed input closed"); }
        };
        assertEquals(html.read(source).execute().document(), html.read(input).execute().document());
        Files.move(file, directory.resolve("released.txt"));
    }

    @Test void malformedUtf8AndExcessiveInputAreRejectedWithoutClosingBorrowedInput() {
        var malformed = new ByteArrayInputStream(new byte[] {(byte) 0xc3, 0x28}) {
            @Override public void close() { throw new AssertionError("borrowed input closed"); }
        };
        assertThrows(IOException.class, () -> new HtmlImporter().read(malformed));
        var defaults = ImportLimits.DEFAULT;
        var limits = new ImportLimits(3, defaults.maxLineUtf16(), defaults.maxNodes(), defaults.maxDepth(),
                defaults.maxTextUtf16(), defaults.maxTableSlots(), defaults.maxIssues(), defaults.maxWork());
        var importer = MarkdownImporter.builder().limits(limits).build();
        assertThrows(IllegalArgumentException.class, () -> importer.read(new ByteArrayInputStream("FOUR".getBytes(StandardCharsets.UTF_8))));
    }

    @Test void conversionFileInputsAreDeferredAndObserveCancellation() throws IOException {
        var file = directory.resolve("created-later.html"); var importer = new HtmlImporter();
        var request = importer.conversion(file); Files.writeString(file, "<p>Deferred body</p>");
        var output = directory.resolve("converted.md");
        var result = request.convertTo(new MarkdownAdapter().conversionTarget(), output.toString());
        String encoded = Files.readString(output);
        assertEquals("Deferred&#32;body", encoded.strip()); assertEquals(Files.size(output), result.bytesWritten());
        var untouched = new ByteArrayInputStream(new byte[] {1}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) { throw new AssertionError("cancelled source read"); }
            @Override public int read() { throw new AssertionError("cancelled source read"); }
            @Override public void close() { throw new AssertionError("borrowed source closed"); }
        };
        assertThrows(ExecutionException.class, () -> importer.conversion(untouched)
                .execution(ExecutionOptions.builder().cancelled(() -> true).build())
                .convertTo(new MarkdownAdapter().conversionTarget(), directory.resolve("cancelled.md")));
    }

    @Test void utf8ReadAheadIsBoundedAndSupplementaryTextUsesUtf16Units() throws IOException {
        var defaults = ImportLimits.DEFAULT;
        var limits = new ImportLimits(2, defaults.maxLineUtf16(), defaults.maxNodes(), defaults.maxDepth(),
                defaults.maxTextUtf16(), defaults.maxTableSlots(), defaults.maxIssues(), defaults.maxWork());
        var importer = MarkdownImporter.builder().limits(limits).build();
        var result = importer.read(new ByteArrayInputStream("😀".getBytes(StandardCharsets.UTF_8))).execute();
        assertEquals("😀", ((Paragraph) result.document().blocks().getFirst()).text());
        var excessive = new ByteArrayInputStream(new byte[100]);
        assertThrows(IllegalArgumentException.class, () -> importer.read(excessive));
        assertEquals(93, excessive.available());
    }
}
