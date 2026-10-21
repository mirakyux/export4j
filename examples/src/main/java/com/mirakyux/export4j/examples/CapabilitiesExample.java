// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfLimits;
import com.mirakyux.export4j.backend.pdf.PdfOptions;
import com.mirakyux.export4j.backend.pdf.PdfEncryption;
import com.mirakyux.export4j.backend.pdf.PdfSigner;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.MultiColumn;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.SectionTemplate;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;

/** Fixed local fixtures for shared shaping, template binding, hyperlinks, CFF, balancing and encryption. */
public final class CapabilitiesExample {
    private static final Path OUTPUT = Path.of("examples/build/capabilities");
    private static final PageSettings PAGE = new PageSettings(new PageSize(500, 640), PageMargins.uniform(28));
    private static final DocumentMetadata META = new DocumentMetadata("Capabilities verification", "mirakyux");
    private static final ParagraphFormat FORMAT = new ParagraphFormat(12, 28, 0, 0, com.mirakyux.export4j.model.ParagraphAlignment.LEFT, 0, 8, false);
    private CapabilitiesExample() { }
    private static FontFace font(String name) throws IOException {
        try (var input = CapabilitiesExample.class.getResourceAsStream("/fonts/" + name)) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.strict(List.of(font("LiberationSans-Regular.ttf"), font("NotoNaskhArabic-Regular.ttf"), font("LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        var link = new InlineText("Documentation / 外部文档", new InlineStyle("", 12, 0x245ba2, 0, true, false), "https://example.com/docs?a=1&b=2");
        var row = new TableRow(List.of(new TableCell("CELL-01", new CellFormat(12, 28, com.mirakyux.export4j.model.ParagraphAlignment.LEFT)),
                TableCell.rich(List.of(new InlineText("CELL-02 a\u0301 العربية لا 123", InlineStyle.of(12))), new CellFormat(12, 28, com.mirakyux.export4j.model.ParagraphAlignment.LEFT))));
        var table = new Table("table", List.of(new ColumnWidth.Auto(30, 160), new ColumnWidth.Fraction(1)), List.of(row), 0, TableFormat.DEFAULT);
        var document = new Document(META, PAGE, List.of(new Heading("heading", "Shared typography / 统一文本排版", 1, FORMAT),
                new Paragraph("mixed", "BODY-01 a\u0301 e\u0308 English العربية لا 123 中文。", FORMAT), new Paragraph("link", List.of(link), FORMAT), table,
                new Paragraph("anchor", "NOTE-ANCHOR 普通语义段落关联脚注。", FORMAT)));
        var header = new TextComponent("HEADER-01 a\u0301 العربية", new ParagraphStyle(10, 24, 0, 0, TextAlignment.LEFT), RgbColor.BLACK);
        var template = new PageTemplate(new PageBand(24, 6, List.of(new Placement(header, new Position.Relative(Position.Anchor.TOP_LEFT, PAGE.contentWidth(), 24, 0, 0)))), PageBand.EMPTY, List.of(), List.of());
        var layout = engine.layout(new SectionedDocument(META, List.of(new Section("main", PAGE, document.blocks(), 0))),
                List.of(SectionTemplate.uniform(template)), Navigation.EMPTY, List.of(new Note("note", "anchor", "NOTE-01 a\u0301 العربية لا 中文脚注", Note.Kind.FOOTNOTE)));
        try (var output = Files.newOutputStream(OUTPUT.resolve("shared.pdf"))) { new PdfBackend().write(layout.display(), output); }
        try (var output = Files.newOutputStream(OUTPUT.resolve("shared.docx"))) { new DocxAdapter().write(document, output); }
        String columnText = java.util.stream.IntStream.rangeClosed(1, 11).mapToObj(i -> "BAL-" + String.format(java.util.Locale.ROOT, "%02d", i) + "  a\u0301 العربية 中文").collect(java.util.stream.Collectors.joining("\n"));
        var balanced = new Document(META, PAGE, List.of(new ComponentBlock("balanced", new MultiColumn(new FlowText(columnText,
                new ParagraphStyle(12, 28, 0, 0, TextAlignment.LEFT), RgbColor.BLACK, PaginationPolicy.DEFAULT), 2, 18, true), 0, 0),
                new Paragraph("after", "AFTER-COLUMNS 位于平衡多栏下方。", FORMAT)));
        engine.writeAtomically(balanced, new PdfBackend(), OUTPUT.resolve("balanced.pdf"), ExecutionOptions.DEFAULT);
        var binding = new DocumentTemplate(new DocumentMetadata("Invoice ${customer.name}", "mirakyux"), PAGE, List.of(
                new DocumentTemplate.ValueBlock(new Heading("title", "客户：${customer.name}", 1, FORMAT)),
                new DocumentTemplate.When("approved", List.of(new DocumentTemplate.ValueBlock(new Paragraph("approved", "状态：已确认", FORMAT))), List.of()),
                new DocumentTemplate.Rows("items", "items", "item", List.of(new ColumnWidth.Auto(40, 180), new ColumnWidth.Fraction(1)), List.of(),
                        new TableRow(List.of(new TableCell("${item.sku}", CellFormat.of(12)), new TableCell("${item.name}", CellFormat.of(12)))), TableFormat.DEFAULT)), DocumentTemplate.Limits.DEFAULT);
        var invoice = binding.render(Map.of("customer", Map.of("name", "示例客户"), "approved", true, "items", List.of(Map.of("sku", "SKU-001", "name", "图文报告"), Map.of("sku", "SKU-002", "name", "可填写表单"))));
        // Latin/Chinese font group keeps the invoice's ordinary compact line boxes.
        var invoiceEngine = DocumentEngine.builder(FontGroup.strict(List.of(font("LiberationSans-Regular.ttf"), font("LXGWWenKaiTC-Regular.ttf")))).build();
        invoiceEngine.writeAtomically(invoice, new PdfBackend(), OUTPUT.resolve("invoice.pdf"), ExecutionOptions.DEFAULT);
        var archival = new com.mirakyux.export4j.backend.pdf.PdfArchival(java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_sRGB).getData(), font("LiberationSans-Regular.ttf").data());
        var archiveDocument = new Document(META, PAGE, List.of(new Paragraph("archive", "ARCHIVE-01 中文 a\u0301 العربية لا", FORMAT), table));
        engine.writeAtomically(archiveDocument, new PdfBackend(PdfLimits.DEFAULT, PdfOptions.DEFAULT.withArchival(archival)), OUTPUT.resolve("archive.pdf"), ExecutionOptions.DEFAULT);
        var secured = new PdfBackend(PdfLimits.DEFAULT, PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)
                .withEncryption(new PdfEncryption("fixture-owner".toCharArray(), "fixture-reader".toCharArray(), PdfEncryption.Permissions.READ_ONLY)));
        invoiceEngine.writeAtomically(invoice, secured, OUTPUT.resolve("encrypted.pdf"), ExecutionOptions.DEFAULT);
        var cffEngine = DocumentEngine.builder(FontGroup.strict(List.of(font("SourceSans3-Regular.otf")))).build();
        cffEngine.writeAtomically(new Document(META, PAGE, List.of(new Paragraph("cff", "CFF-01 Source Sans 3: AVATAR, office ffi, a\u0301.", ParagraphFormat.of(18)))), new PdfBackend(), OUTPUT.resolve("cff.pdf"), ExecutionOptions.DEFAULT);
        if (args.length == 3) { sign(Path.of(args[0]), Path.of(args[1]), Path.of(args[2])); }
        System.out.println("Generated capability fixtures in " + OUTPUT);
    }

    /** Optional, explicit development-only OpenSSL signer. No certificate/key is bundled or generated by the library. */
    private static void sign(Path openssl, Path certificate, Path key) throws IOException {
        var options = new PdfSigner.Options("Local verification certificate", "Detached CMS verification", java.time.Instant.parse("2026-10-14T00:00:00Z"), 16384);
        try (var output = Files.newOutputStream(OUTPUT.resolve("signed.pdf"))) {
            PdfSigner.sign(OUTPUT.resolve("invoice.pdf"), output, options, content -> {
                Path data = Files.createTempFile(OUTPUT, "sign-content-", ".bin");
                Path cms = Files.createTempFile(OUTPUT, "sign-cms-", ".der");
                Path log = Files.createTempFile(OUTPUT, "sign-log-", ".txt");
                try {
                    Files.copy(content, data, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    var process = new ProcessBuilder(openssl.toString(), "cms", "-sign", "-binary", "-in", data.toString(), "-signer", certificate.toString(),
                            "-inkey", key.toString(), "-outform", "DER", "-out", cms.toString(), "-md", "sha256").redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    try {
                        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("development signer timed out"); }
                        if (process.exitValue() != 0) { throw new IOException("development signer failed; inspect its local configuration"); }
                    } catch (InterruptedException failure) { process.destroyForcibly(); Thread.currentThread().interrupt(); throw new IOException("development signer interrupted", failure); }
                    return Files.readAllBytes(cms);
                } finally { Files.deleteIfExists(data); Files.deleteIfExists(cms); Files.deleteIfExists(log); }
            });
        }
    }
}
