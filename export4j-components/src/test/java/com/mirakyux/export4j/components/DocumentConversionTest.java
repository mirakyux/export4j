// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DocumentBackend;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConversionTest {
    private static FontGroup fonts() throws IOException {
        try (var input = DocumentConversionTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            return FontGroup.placeholder(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)), '?');
        }
    }

    @Test void configuredGlyphReplacementIsStillAnActualLossAndStrictBlocksPublication() throws Exception {
        var document = Document.builder().paragraph("source", "A\uDBFF\uDFFFB").build();
        var writes = new AtomicInteger();
        DocumentBackend backend = (display, output) -> { writes.incrementAndGet(); output.write("encoded".getBytes(StandardCharsets.UTF_8)); };
        var target = DocumentEngine.builder(fonts()).build().conversionTarget("TEST", backend);
        assertThrows(ProcessingException.class, () -> DocumentConversion.from(document).prepare(target));
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        var prepared = DocumentConversion.from(document).policy(policy).prepare(target);
        var output = new ByteArrayOutputStream(); var actual = prepared.to(output);
        assertEquals("encoded", output.toString(StandardCharsets.UTF_8)); assertEquals(2, writes.get());
        assertEquals(prepared.preview().issues(), actual.report().issues());
        var issue = actual.report().losses().getFirst();
        assertEquals("source", issue.location().blockId()); assertEquals(1, issue.location().startUtf16()); assertEquals(3, issue.location().endUtf16());
        assertTrue(actual.report().warnings().isEmpty());
    }

    @Test void sourceNotesReachActualLayoutAndHiddenEngineNotesAreRejected() throws Exception {
        var source = Document.builder().paragraph("anchor", "Anchor").build();
        var note = new Note("note", "anchor", "Actual note", Note.Kind.FOOTNOTE);
        var received = new AtomicInteger();
        var target = DocumentEngine.builder(fonts()).build().conversionTarget("TEST", (display, output) -> {
            assertEquals(1, display.pages().size());
            String text = display.pages().getFirst().commands().stream().map(command -> switch (command) {
                case DrawCommand.Text value -> value.text();
                case DrawCommand.GlyphText value -> value.run().text();
                case DrawCommand.OutlineText value -> value.logicalText();
                default -> "";
            }).collect(java.util.stream.Collectors.joining());
            assertTrue(text.contains("Actual note"), text);
            received.incrementAndGet();
        });
        var sectioned = DocumentConversion.Input.of(source, new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of())).document();
        var prepared = DocumentConversion.from(sectioned, List.of(note)).prepare(target);
        assertEquals(List.of(note), prepared.to(new ByteArrayOutputStream()).notes()); assertEquals(1, received.get());
        assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(fonts()).notes(List.of(note)).build().conversionTarget("TEST", (d, o) -> { }));
    }

    @Test void backendFailurePropagatesWithoutPublishingPreparedOutput() throws Exception {
        var failure = new IOException("encoding failed");
        var target = DocumentEngine.builder(fonts()).build().conversionTarget("TEST", (document, output) -> { output.write(7); throw failure; });
        assertSame(failure, assertThrows(IOException.class, () -> DocumentConversion.from(Document.builder().paragraph("A").build()).prepare(target)));
    }
}
