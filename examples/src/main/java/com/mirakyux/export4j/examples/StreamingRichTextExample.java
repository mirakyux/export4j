// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageWriter;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Finite, self-authored rich streaming fixtures; body rows are opened and produced lazily. */
public final class StreamingRichTextExample {
    private static final Path OUTPUT = Path.of("examples/build/streaming-rich-text");
    private static final PageSettings PAGE = PageSettings.builder().size(420, 250).margins(20).build();
    private static final CellFormat STYLED = CellFormat.builder().fontSize(10).lineHeight(18).build();
    private static final CellFormat COMPLEX = CellFormat.builder().fontSize(12).lineHeight(24).build();
    private static final int BLUE = 0x224488;
    private static final int RED = 0x884422;
    private StreamingRichTextExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var faces = new LinkedHashMap<String, FontFace>();
        for (String name : List.of("LiberationSans-Regular.ttf", "LiberationSans-Bold.ttf", "LiberationSans-Italic.ttf",
                "NotoNaskhArabic-Regular.ttf", "NotoSansDevanagari-Regular.ttf")) { faces.put(name, AuthoringExample.face(name)); }
        var regular = FontGroup.strict(List.of(faces.get("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(regular)
                .fontRole("bold", FontGroup.strict(List.of(faces.get("LiberationSans-Bold.ttf"))))
                .fontRole("accent", FontGroup.strict(List.of(faces.get("LiberationSans-Italic.ttf"))))
                .fontRole("arabic", FontGroup.strict(List.of(faces.get("NotoNaskhArabic-Regular.ttf"))))
                .fontRole("indic", FontGroup.strict(List.of(faces.get("NotoSansDevanagari-Regular.ttf"))))
                .fontRole("missing", FontGroup.placeholder(regular.fonts(), '#')).build();
        write(engine, "styled", 7, new int[] {3, 3, 1}, STYLED);
        write(engine, "complex", 2, new int[] {2}, COMPLEX);
        Files.writeString(OUTPUT.resolve("fonts.json"), "{\"fonts\":[" + faces.entrySet().stream()
                .map(entry -> "{\"resource\":" + quote(entry.getKey()) + ",\"sha256\":" + quote(entry.getValue().data().id())
                        + ",\"bytes\":" + entry.getValue().data().byteSize() + "}")
                .collect(java.util.stream.Collectors.joining(",")) + "]}\n", StandardCharsets.UTF_8);
    }

    private static void write(DocumentEngine engine, String name, int rowCount, int[] perPage, CellFormat format) throws IOException {
        var header = new TableRow(List.of(cell(format, ink("ROW-ID", "bold", format.fontSize(), BLUE, 0, false, false)),
                cell(format, ink(name.equals("styled") ? "STYLED CONTENT" : "COMPLEX CONTENT", "accent",
                        format.fontSize(), RED, 0, true, false))));
        var table = StreamingTable.builder(name).pageSettings(PAGE).columns(90, 290).padding(3).header(header).build();
        var seen = new Observations();
        seen.sourceCodePoints = header.cells().stream().mapToLong(c -> c.text().codePointCount(0, c.text().length())).sum();
        seen.sourceInlines = 2;
        var request = engine.export(table, () -> new Rows(name, rowCount, format, seen))
                .limits(limits -> limits.maxCellInlines(16).maxRowInlines(20))
                .backend(observedBackend(name, rowCount, perPage, seen))
                .diagnostics(context -> {
                    var value = context.diagnostic();
                    if (value.code() != TextDiagnostic.Code.MISSING_GLYPH_REPLACED
                            || value.severity() != TextDiagnostic.Severity.WARNING || !name.equals("complex")
                            || value.sourceCodePoint() != 0x1f600 || value.replacementCodePoint() != '#'
                            || context.columnIndex() != 1 || context.rowIndex() < 1 || context.rowIndex() > 2) {
                        throw new IllegalStateException("Unexpected rich streaming diagnostic");
                    }
                    seen.diagnostics.add("{\"row\":" + context.rowIndex() + ",\"column\":" + context.columnIndex()
                            + ",\"page\":" + context.pageIndex() + ",\"region\":" + quote(context.region())
                            + ",\"start\":" + value.sourceStart() + ",\"end\":" + value.sourceEnd()
                            + ",\"sourceCodePoint\":" + value.sourceCodePoint() + ",\"replacementCodePoint\":" + value.replacementCodePoint()
                            + ",\"fontId\":" + quote(value.fontId()) + "}");
                });
        if (seen.opens != 0) { throw new IllegalStateException("source opened before export"); }
        var target = OUTPUT.resolve(name + ".pdf");
        var result = request.to(target);
        if (result.bodyRows() != rowCount || result.pages() != perPage.length || seen.opens != 1 || seen.closes != 1
                || seen.nextCalls != rowCount + 1 || seen.finished != 1 || seen.writerCloses != 1
                || result.sourceCodePoints() != seen.sourceCodePoints || result.sourceCells() != 2L * (rowCount + 1)
                || result.diagnostics() != (name.equals("complex") ? 2 : 0) || result.outputBytes() != Files.size(target)) {
            throw new IllegalStateException("rich streaming fixture counters or ownership differ");
        }
        Files.writeString(OUTPUT.resolve(name + "-sources.json"), "{\"rows\":[" + String.join(",", seen.sources) + "]}\n", StandardCharsets.UTF_8);
        Files.writeString(OUTPUT.resolve(name + "-results.json"), "{\"pages\":" + result.pages() + ",\"bodyRows\":" + result.bodyRows()
                + ",\"sourceCells\":" + result.sourceCells() + ",\"sourceCodePoints\":" + result.sourceCodePoints()
                + ",\"sourceInlineRuns\":" + seen.sourceInlines + ",\"outputLines\":" + result.outputLines()
                + ",\"commands\":" + result.commands() + ",\"outputBytes\":" + result.outputBytes()
                + ",\"opens\":" + seen.opens + ",\"closes\":" + seen.closes + ",\"nextCalls\":" + seen.nextCalls
                + ",\"nextCallsAtPages\":" + seen.callsAtPages + ",\"pendingAtPages\":" + seen.pendingAtPages
                + ",\"finished\":" + seen.finished + ",\"writerCloses\":" + seen.writerCloses
                + ",\"diagnostics\":[" + String.join(",", seen.diagnostics) + "]}\n", StandardCharsets.UTF_8);
        System.out.println("Rich streaming " + name + ": " + result.bodyRows() + " rows, " + result.pages() + " pages");
    }

    private static PageBackend observedBackend(String name, int rowCount, int[] perPage, Observations seen) {
        return (metadata, output) -> {
            var writer = new PdfBackend().open(metadata, output);
            return new PageWriter() {
                private int pageIndex;
                private int deliveredRows;
                @Override public void accept(DisplayPage page) throws IOException {
                    deliveredRows += perPage[pageIndex];
                    int pending = Math.min(rowCount, seen.nextCalls) - deliveredRows;
                    if (seen.closes != 0 || pending != (pageIndex + 1 < perPage.length ? 1 : 0)) {
                        throw new IllegalStateException("more than one source row was read ahead");
                    }
                    seen.callsAtPages.add(seen.nextCalls); seen.pendingAtPages.add(pending);
                    try (var preview = Files.newOutputStream(OUTPUT.resolve(name + "-page-" + (pageIndex + 1) + ".svg"))) {
                        new SvgBackend().write(new DisplayDocument(metadata, List.of(page)), preview);
                    }
                    writer.accept(page); pageIndex++;
                }
                @Override public void finish() throws IOException {
                    if (seen.closes != 1 || pageIndex != perPage.length) { throw new IllegalStateException("source must close before finish"); }
                    writer.finish(); seen.finished++;
                }
                @Override public void close() throws IOException { writer.close(); seen.writerCloses++; }
            };
        };
    }

    private static TableCell cell(CellFormat format, InlineText... values) {
        return TableCell.builder().format(format).content(List.of(values)).build();
    }

    private static InlineText ink(String value, String role, double size, int color, double shift, boolean underline, boolean strike) {
        return new InlineText(value, new InlineStyle(role, size, color, shift, underline, strike));
    }

    private static final class Rows implements TableRowSource {
        private final String name;
        private final int count;
        private final CellFormat format;
        private final Observations seen;
        private int index;
        private Rows(String name, int count, CellFormat format, Observations seen) {
            this.name = name; this.count = count; this.format = format; this.seen = seen; seen.opens++;
        }
        @Override public Optional<TableRow> next() {
            seen.nextCalls++;
            if (index == count) { return Optional.empty(); }
            String number = String.format(Locale.ROOT, "%02d", ++index);
            var identifier = cell(format, ink("ROW-" + number, "bold", format.fontSize(), BLUE, 0, false, false));
            TableCell detail;
            if (name.equals("styled")) {
                detail = cell(format, ink("WRAP-" + number + " ", "bold", 10, BLUE, 0, false, false),
                        ink("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau ", "", 10, 0, 0, false, false),
                        ink("ITALIC\n", "accent", 10, RED, 0, false, false), ink("H", "", 10, 0, 0, false, false),
                        ink("2", "", 8, BLUE, 2, false, false), ink("O x", "", 10, 0, 0, false, false),
                        ink("2", "", 8, RED, -3, false, false), ink(" OLD", "accent", 10, RED, 0, false, true),
                        ink(" END-" + number, "bold", 10, BLUE, 0, true, false));
            } else {
                detail = cell(format, ink("AR-" + number + " ", "", 12, 0, 0, false, false),
                        ink("العربية لا 123", "arabic", 12, BLUE, 0, false, false),
                        ink("\nDEV-" + number + " ", "", 12, 0, 0, false, false), ink("नमस्ते", "indic", 12, RED, 0, false, false),
                        ink("\nMISS-" + number + " ", "", 12, 0, 0, false, false), ink("😀 END", "missing", 12, 0, 0, false, false));
            }
            var row = new TableRow(List.of(identifier, detail));
            seen.sourceCodePoints += row.cells().stream().mapToLong(c -> c.text().codePointCount(0, c.text().length())).sum();
            seen.sourceInlines += row.cells().stream().mapToLong(c -> c.inlines().size()).sum();
            seen.sources.add("{\"row\":" + index + ",\"texts\":[" + quote(identifier.text()) + "," + quote(detail.text())
                    + "],\"utf16Length\":" + detail.text().length() + "}");
            return Optional.of(row);
        }
        @Override public void close() { seen.closes++; }
    }

    private static final class Observations {
        private int opens;
        private int closes;
        private int nextCalls;
        private int finished;
        private int writerCloses;
        private long sourceCodePoints;
        private long sourceInlines;
        private final List<Integer> callsAtPages = new ArrayList<>();
        private final List<Integer> pendingAtPages = new ArrayList<>();
        private final List<String> sources = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
