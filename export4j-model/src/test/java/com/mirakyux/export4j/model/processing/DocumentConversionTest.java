// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.processing;

import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentConversionTest {
    @TempDir Path directory;
    private static final ProcessingReport EMPTY = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());
    private static Document source() { return Document.builder().paragraph("p", "OLD").build(); }
    private static DocumentConversion.Target text() {
        return new DocumentConversion.Target("TEXT", (document, notes, policy, output) -> {
            output.write(((com.mirakyux.export4j.model.Paragraph) document.sections().getFirst().blocks().getFirst()).text().getBytes(StandardCharsets.UTF_8)); return EMPTY;
        });
    }
    @Test void stagesExecuteOnceAndPreparedBytesIgnoreLaterPlanChanges() throws Exception {
        var reads = new AtomicInteger(); var edits = new AtomicInteger(); var writes = new AtomicInteger();
        var request = DocumentConversion.from("TEXT", policy -> { reads.incrementAndGet(); return DocumentConversion.Input.of(source(), EMPTY); })
                .edit(editor -> { edits.incrementAndGet(); editor.replaceText("OLD", "READY"); });
        var target = new DocumentConversion.Target("TEXT", (document, notes, policy, output) -> { writes.incrementAndGet(); return text().writer().write(document, notes, policy, output); });
        var prepared = request.prepare(target); request.edit(editor -> editor.remove("p"));
        var first = new ByteArrayOutputStream(); var result = prepared.to(first);
        var second = new ByteArrayOutputStream(); prepared.to(second);
        assertEquals("READY", first.toString(StandardCharsets.UTF_8)); assertEquals(first.toString(), second.toString());
        assertEquals(1, reads.get()); assertEquals(1, edits.get()); assertEquals(1, writes.get());
        assertEquals(prepared.preview().issues(), result.report().issues()); assertEquals(5, result.bytesWritten());
    }
    @Test void atomicPublicationReplacesOnlyAfterSuccessfulPreparation() throws Exception {
        Path target = directory.resolve("target.txt"); Files.writeString(target, "previous");
        assertThrows(IOException.class, () -> DocumentConversion.from("TEXT", policy -> { throw new IOException("input failure"); }).prepare(text()));
        assertEquals("previous", Files.readString(target));
        var prepared = DocumentConversion.from(source()).prepare(text()); prepared.to(target);
        assertEquals("OLD", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }
    @Test void cancellationBeforeCommitPreservesTargetAndRemovesTemporary() throws Exception {
        var armed = new AtomicBoolean(); var checks = new AtomicInteger();
        var execution = ExecutionOptions.builder().cancelled(() -> armed.get() && checks.incrementAndGet() >= 4).build();
        var prepared = DocumentConversion.from(source()).execution(execution).prepare(text());
        Path target = directory.resolve("target.txt"); Files.writeString(target, "previous"); armed.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target));
        assertEquals("previous", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(List.of(target), files.toList()); }
    }
    @Test void outputFailureDoesNotCloseBorrowedStreamOrReturnSuccess() throws Exception {
        var closed = new AtomicBoolean(); var prepared = DocumentConversion.from(source()).prepare(text());
        var output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("write failed"); }
            @Override public void close() { closed.set(true); }
        };
        assertThrows(IOException.class, () -> prepared.to(output)); assertFalse(closed.get());
    }
    @Test void encodingAndAggregateReportBudgetsCannotBeIgnored() throws Exception {
        var limits = new DocumentConversion.Limits(1, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source()).limits(limits).prepare(text()));
        var issue = new ProcessingIssue("IMPORT", "TEXT", "MODEL", "UNKNOWN", "IMPORT",
                new ProcessingIssue.Location("", "BODY", "p", List.of(), -1, -1), "source simplified", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE, List.of("STYLE_REMOVED"));
        var report = new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of(issue, issue));
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from("TEXT", ignored -> DocumentConversion.Input.of(source(), report)).limits(limits).prepare(text()));
        assertThrows(IllegalArgumentException.class, () -> DocumentConversion.from(source()).limits(limits).edit(editor -> { }).edit(editor -> { }));
    }
    @Test void sourceAndTargetFailuresRestoreSynchronousContext() throws Exception {
        var target = new DocumentConversion.Target("TEXT", (document, notes, policy, output) -> { throw new IOException("target failed"); });
        assertThrows(IOException.class, () -> DocumentConversion.from(source()).prepare(target));
        assertThrows(ExecutionException.class, () -> DocumentConversion.from(source()).execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare(text()));
        assertTrue(DocumentConversion.from(source()).prepare(text()).preview().losses().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new DocumentConversion.Input(
                DocumentConversion.Input.of(source(), EMPTY).document(), List.of(), new ProcessingReport(ProcessingReport.Phase.PREVIEW, List.of())));
    }
}
