// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.CheckMark;
import com.mirakyux.export4j.components.ChoiceGroup;
import com.mirakyux.export4j.components.ContinuousColumns;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FormText;
import com.mirakyux.export4j.components.Spacer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.text.ParagraphStyle;

/** Self-authored static-form composition and continuation fixture; all values are synthetic. */
public final class StaticFormsExample {
    private static final Path OUTPUT = Path.of("examples/build/static-forms");
    private static final ParagraphStyle STYLE = ParagraphStyle.builder().fontSize(10).lineHeight(18).build();
    private StaticFormsExample() { }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.builder().font(AuthoringExample.face("LiberationSans-Regular.ttf"))
                .font(AuthoringExample.face("NotoNaskhArabic-Regular.ttf")).build();
        var engine = DocumentEngine.builder(fonts).build();
        String source = IntStream.rangeClosed(1, 26).mapToObj(i -> String.format(Locale.ROOT,
                "ITEM%03d %s Yes %s No", i, i % 2 == 0 ? "☑" : "□", i % 2 == 0 ? "□" : "☑")).collect(Collectors.joining("\n"));
        var choices = FlowColumn.builder().gap(6).formText("INLINE ☑ Yes □ No ● First ○ Second", text -> text.style(STYLE))
                .choices(group -> group.style(STYLE).option("Approved", true).option("Deferred", false))
                .choices(group -> group.kind(ChoiceGroup.Kind.RADIO).style(STYLE).option("Single", true).option("Multiple", false))
                .formText("BIDI ☑ العربية a\u0301", text -> text.style(STYLE.toBuilder().lineHeight(24).build()))
                .labelValue("Case", "DEMO-FORM-001", field -> field.style(STYLE).labelWidth(42).gap(6))
                .fillLine(line -> line.height(18).bottomInset(4))
                .add(CheckMark.builder().selected(true).size(9).build()).build();
        var document = Documents.builder().title("Static form components")
                .bodyFormat(format -> format.fontSize(10).lineHeight(24))
                .pageSettings(page -> page.size(380, 300).margins(20))
                .component(choices).pageBreak()
                .grid(grid -> grid.columns(1, 3).headerRows(1).allowRowSplit(true)
                        .defaults(cell -> cell.padding(3).textStyle(STYLE))
                        .row(row -> row.cells("LABEL", "OPTIONS"))
                        .row(row -> row.cell("ONCE", cell -> cell.rowSpan(2))
                                .cell(FormText.builder(source).style(STYLE).build(), cell -> cell.rowSpan(2)))
                        .row(row -> { }))
                .pageBreak().component(new Spacer(120))
                .component(ContinuousColumns.builder(FlowColumn.builder().gap(4)
                        .choices(group -> {
                            group.style(STYLE).vertical(true);
                            for (int i = 1; i <= 32; i++) { group.option(String.format(Locale.ROOT, "COL%03d", i), i % 3 == 0); }
                        }).build()).columns(2).gap(20).build())
                .paragraph("TAIL-STATIC-FORMS").build();
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve("static-forms.pdf"));
        try (var output = Files.newOutputStream(OUTPUT.resolve("static-forms.svg"))) { new SvgBackend().write(result.display(), output); }
        if (result.diagnostics().stream().anyMatch(d -> d.severity() == com.mirakyux.export4j.text.TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("static form fixture has unexpected warnings");
        }
        Files.writeString(OUTPUT.resolve("results.json"), "{\"pages\":" + result.display().pages().size() + ",\"items\":26,\"columnOptions\":32}\n");
        System.out.println("Static form components: " + result.display().pages().size() + " pages");
    }
}
