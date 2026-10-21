// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;

/** Bounded mixed-report workload; independent verification and Windows process sampling are external. */
public final class ReportStressExample {
    private static final Path OUTPUT = Path.of("examples/build/report-stress");
    private static final int GROUPS = 40;
    private static final int EXPORTS = 6;
    private static final int CONCURRENCY = 2;
    private static final ExecutionOptions CONTROL = new ExecutionOptions(Duration.ofSeconds(120), () -> false, p -> { }, 64L * 1024 * 1024);
    private ReportStressExample() { }

    private static Document report(int index, InlineImage image) {
        String token = "REQUEST-" + index + "-END";
        var builder = Document.builder(new DocumentMetadata(token, "stress fixture"), AuthoringExample.PAGE).bodyFormat(AuthoringExample.BODY);
        for (int group = 0; group < GROUPS; group++) {
            String marker = String.format(Locale.ROOT, "GROUP-%03d-END", group);
            if (group % 10 == 0) { builder.heading(List.of(AuthoringExample.accent("Section " + group), AuthoringExample.text(" / Report")), 1); }
            builder.paragraph(token + " " + marker).paragraph(AuthoringExample.CHINESE).paragraph(AuthoringExample.LATIN)
                    .bullet(List.of(AuthoringExample.accent("Styled / "), AuthoringExample.text("中文列表")))
                    .numbered("steps", "Report step " + group);
            if (group % 4 == 0) {
                builder.paragraph("AVATAR a\u0301 العربية لا 123 " + AuthoringExample.LATIN)
                        .paragraph(List.of(AuthoringExample.text("PAGE="), new InlineField(InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)),
                                AuthoringExample.text(" ICON="), image, AuthoringExample.text(" " + AuthoringExample.LATIN)));
            }
            if (group % 3 == 0) {
                builder.table(List.of("Key", "Value"), List.of(List.of("KEY-" + group, "120"), List.of("中文", "稳定输出")));
            }
        }
        return builder.build();
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        String run = System.getProperty("export4j.stress.run", "manual");
        if (!run.matches("[A-Za-z0-9-]{1,64}")) { throw new IllegalArgumentException("invalid stress run ID"); }
        long started = System.nanoTime();
        Files.writeString(OUTPUT.resolve("running.json"), "{\"run\":\"" + run + "\",\"pid\":" + ProcessHandle.current().pid() + "}");
        var engine = AuthoringExample.engine(); var backend = new PdfBackend(); var image = AuthoringExample.image();
        // Recover on the same caller thread and target before exercising shared instances concurrently.
        Path recovery = OUTPUT.resolve("recovery.pdf"); Files.writeString(recovery, "original");
        expectFailure(engine, backend, report(99, image), recovery,
                new ExecutionOptions(Duration.ZERO, () -> true, p -> { }, Long.MAX_VALUE), ExecutionException.Code.CANCELLED);
        expectFailure(engine, backend, Document.builder().bodyFormat(AuthoringExample.BODY).paragraph("failure").build(), recovery,
                new ExecutionOptions(Duration.ofSeconds(30), () -> false, p -> { }, 16), ExecutionException.Code.OUTPUT_LIMIT);
        engine.write(Document.builder().bodyFormat(AuthoringExample.BODY).paragraph("RECOVERY-OK").build(), backend, recovery, CONTROL);
        var results = new ArrayList<String>();
        try (var executor = Executors.newFixedThreadPool(CONCURRENCY)) {
            for (int round = 0; round < EXPORTS / CONCURRENCY; round++) {
                var calls = new ArrayList<Callable<String>>();
                for (int worker = 0; worker < CONCURRENCY; worker++) {
                    int index = round * CONCURRENCY + worker;
                    calls.add(() -> {
                        long before = System.nanoTime(); Path target = OUTPUT.resolve("report-" + index + ".pdf");
                        var result = engine.write(report(index, image), backend, target, CONTROL);
                        return "{\"index\":" + index + ",\"pages\":" + result.display().pages().size() + ",\"bytes\":" + Files.size(target)
                                + ",\"milliseconds\":" + (System.nanoTime() - before) / 1_000_000 + "}";
                    });
                }
                for (var result : executor.invokeAll(calls)) { results.add(result.get()); }
                System.out.println("Stress round " + (round + 1) + " complete");
            }
        }
        try (var files = Files.list(OUTPUT)) {
            if (files.anyMatch(path -> path.getFileName().toString().startsWith(".export4j-"))) { throw new IOException("temporary export file was not released"); }
        }
        Files.writeString(OUTPUT.resolve("report.json"), "{\"run\":\"" + run + "\",\"groupsPerReport\":" + GROUPS + ",\"concurrency\":" + CONCURRENCY
                + ",\"maxHeapBytes\":" + Runtime.getRuntime().maxMemory() + ",\"java\":\"" + Runtime.version() + "\",\"elapsedMilliseconds\":"
                + (System.nanoTime() - started) / 1_000_000 + ",\"recovery\":true,\"exports\":[" + String.join(",", results) + "]}");
    }

    private static void expectFailure(DocumentEngine engine, PdfBackend backend, Document document, Path target,
            ExecutionOptions options, ExecutionException.Code expected) throws IOException {
        try { engine.write(document, backend, target, options); throw new IllegalStateException("export should have failed"); }
        catch (ExecutionException failure) { if (failure.code() != expected) { throw failure; } }
        if (!Files.readString(target).equals("original")) { throw new IOException("failed atomic export modified the target"); }
    }
}
