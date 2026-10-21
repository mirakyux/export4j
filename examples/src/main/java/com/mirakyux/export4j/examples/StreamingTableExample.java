// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Lazy, synthetic table rows; no body collection, implicit font lookup or external data access. */
public final class StreamingTableExample {
    private static final Path OUTPUT = Path.of("examples/build/streaming-table");
    private static final CellFormat FORMAT = CellFormat.builder().fontSize(10).lineHeight(16).build();

    private StreamingTableExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf"),
                AuthoringExample.face("LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        write(engine, "streaming", 55, true);
        write(engine, "empty-header", 0, true);
        write(engine, "empty-plain", 0, false);
    }

    private static void write(DocumentEngine engine, String name, int rowCount, boolean header) throws IOException {
        var specification = StreamingTable.builder(name).columns(72, 300, 120)
                .metadata(new DocumentMetadata("Lazy table / " + name, "Export4j synthetic fixture"))
                .pageSettings(PageSettings.builder().size(540, 420).margins(24).build())
                .padding(PageMargins.uniform(3)).cellFormat(FORMAT);
        if (header) { specification.header("ROW-ID", "DESCRIPTION", "VALUE"); }
        var observations = new SourceObservations();
        var target = OUTPUT.resolve(name + ".pdf");
        var summary = engine.export(specification.build(), () -> new Rows(rowCount, observations))
                .backend(new PdfBackend())
                .diagnostics(context -> {
                    var diagnostic = context.diagnostic();
                    int offset = diagnostic.sourceStart();
                    // Liberation Sans is intentionally first. Each of the 14 non-ASCII scalars
                    // in a description uses the explicitly supplied second font and reports INFO.
                    if (diagnostic.code() != TextDiagnostic.Code.FONT_FALLBACK
                            || diagnostic.severity() != TextDiagnostic.Severity.INFO
                            || !context.blockId().equals(name) || context.columnIndex() != 1
                            || context.rowIndex() < 1 || context.rowIndex() > rowCount
                            || context.pageIndex() != (context.rowIndex() - 1) / 14
                            || offset >= 19 || "记录00001：逐行生成，完整行跨页。".charAt(offset) <= 127
                            || diagnostic.sourceEnd() != offset + 1) {
                        throw new IllegalStateException("Unexpected streaming fixture diagnostic");
                    }
                    observations.fontFallbacks++;
                })
                .to(target);
        if (summary.bodyRows() != rowCount || summary.diagnostics() != 14L * rowCount
                || observations.fontFallbacks != 14 * rowCount || observations.opens != 1
                || observations.closes != 1 || observations.calls != rowCount + 1
                || summary.outputBytes() != Files.size(target)) {
            throw new IllegalStateException("Unexpected streaming fixture counters or source ownership");
        }
        Files.writeString(OUTPUT.resolve(name + ".json"), "{\"bodyRows\":" + summary.bodyRows()
                + ",\"pages\":" + summary.pages() + ",\"sourceCells\":" + summary.sourceCells()
                + ",\"sourceCodePoints\":" + summary.sourceCodePoints() + ",\"outputLines\":" + summary.outputLines()
                + ",\"commands\":" + summary.commands() + ",\"diagnostics\":" + summary.diagnostics()
                + ",\"fontFallbackInfoDiagnostics\":" + observations.fontFallbacks
                + ",\"outputBytes\":" + summary.outputBytes() + ",\"sourceOpens\":" + observations.opens
                + ",\"sourceCloses\":" + observations.closes + ",\"sourceNextCalls\":" + observations.calls + "}\n");
        System.out.println(name + ": " + summary.bodyRows() + " rows, " + summary.pages() + " pages");
    }

    private static final class SourceObservations {
        private int opens;
        private int calls;
        private int closes;
        private int fontFallbacks;
    }

    /** Produces just the requested row; the example never constructs a complete body list. */
    private static final class Rows implements TableRowSource {
        private final int rowCount;
        private final SourceObservations observations;
        private int index;

        private Rows(int rowCount, SourceObservations observations) {
            this.rowCount = rowCount;
            this.observations = observations;
            observations.opens++;
        }

        @Override public Optional<TableRow> next() {
            observations.calls++;
            if (index == rowCount) { return Optional.empty(); }
            String number = String.format(Locale.ROOT, "%05d", ++index);
            String detail = "记录" + number + "：逐行生成，完整行跨页。";
            if (index % 7 == 0) { detail += "\nDETAIL-" + number; }
            return Optional.of(new TableRow(List.of(new TableCell("ROW-" + number, FORMAT),
                    new TableCell(detail, FORMAT), new TableCell("VALUE-" + number, FORMAT))));
        }

        @Override public void close() { observations.closes++; }
    }
}
