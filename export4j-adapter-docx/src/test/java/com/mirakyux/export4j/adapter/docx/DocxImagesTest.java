// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;

class DocxImagesTest {
    private static Document source() throws IOException {
        var data = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", data);
        var image = new InlineImage(ImageResource.load(data.toByteArray()), 24, 18, 2, "Alt <&> 中文", "https://example.com/picture?a=1&b=2");
        var content = List.of(new InlineText("before ", InlineStyle.of(12)), image, new InlineText(" after", InlineStyle.of(12)), image);
        var paragraph = Paragraph.mixed("p", content, ParagraphFormat.of(12));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(TableCell.mixed(content, CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(paragraph, table));
    }
    private static byte[] write(Document source) throws IOException {
        var bytes = new ByteArrayOutputStream(); new DocxAdapter().write(source, bytes); return bytes.toByteArray();
    }
    private static Map<String, byte[]> parts(byte[] zipBytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (var part = zip.getNextEntry(); part != null; part = zip.getNextEntry()) { result.put(part.getName(), zip.readAllBytes()); }
        }
        return result;
    }
    private static byte[] pack(Map<String, byte[]> parts) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var part : parts.entrySet()) { zip.putNextEntry(new ZipEntry(part.getKey())); zip.write(part.getValue()); zip.closeEntry(); }
        }
        return bytes.toByteArray();
    }
    private static byte[] change(byte[] bytes, String part, UnaryOperator<String> mutation) throws IOException {
        var parts = parts(bytes); parts.compute(part, (key, value) -> mutation.apply(new String(value, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
        return pack(parts);
    }
    @Test void standardDrawingMlRoundTripsWithMediaDeduplicationAltTextLinksAndCells() throws IOException {
        var source = source(); byte[] bytes = write(source); var parts = parts(bytes);
        assertEquals(1, parts.keySet().stream().filter(p -> p.startsWith("word/media/")).count());
        assertTrue(new String(parts.get("word/document.xml"), StandardCharsets.UTF_8).contains("<wp:inline"));
        var result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
        assertTrue(result.diagnostics().isEmpty()); assertEquals(source.blocks(), result.document().sections().getFirst().blocks());
    }
    @Test void standardDrawingImportNeedsNoPrivateMetadataAndKeepsObjectsWithinOneRun() throws IOException {
        byte[] bytes = change(write(source()), "word/document.xml", xml -> xml.replaceAll(" od:[a-zA-Z]+=\"[^\"]*\"", "")
                .replaceAll("</?w:hyperlink[^>]*>", "")
                .replace("</w:r><w:r><w:rPr><w:position w:val=\"-4\"/></w:rPr>", ""));
        var imported = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty());
        var paragraph = (Paragraph) imported.document().sections().getFirst().blocks().getFirst();
        assertEquals("before \ufffc after\ufffc", paragraph.text());
        assertEquals(List.of(7, 14), paragraph.images().stream().map(Paragraph.ImageSpan::sourceIndex).toList());
    }
    @Test void unsupportedImageTransformsAndExternalImagesFailStrictlyOrReturnExplicitLoss() throws IOException {
        byte[] source = write(source());
        var transformed = change(source, "word/document.xml", xml -> xml.replace("<a:xfrm>", "<a:xfrm rot=\"60000\">"));
        var external = change(source, "word/_rels/document.xml.rels", xml -> xml.replace("Target=\"media/", "TargetMode=\"External\" Target=\"https://example.com/"));
        for (var bytes : List.of(transformed, external)) {
            assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT));
            var result = new DocxAdapter().read(new ByteArrayInputStream(bytes), DocxAdapter.Policy.ALLOW_LOSS);
            assertFalse(result.diagnostics().isEmpty());
            assertTrue(((Paragraph) result.document().sections().getFirst().blocks().getFirst()).images().isEmpty());
        }
    }
    @Test void unsafeMissingAndDuplicateImageRelationshipsAreRejected() throws IOException {
        byte[] source = write(source());
        for (var target : List.of("../media/", "media/%2e%2e/", "media/%5c", "media/missing/")) {
            byte[] invalid = change(source, "word/_rels/document.xml.rels", xml -> xml.replace("media/", target));
            assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(invalid), DocxAdapter.Policy.STRICT));
        }
        byte[] duplicate = change(source, "word/_rels/document.xml.rels", xml -> xml.replace("Id=\"img-", "Id=\"rId1\" Original=\"img-"));
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(duplicate), DocxAdapter.Policy.ALLOW_LOSS));
    }
    @Test void resourcesCountTowardExportEntryBudgetBeforeOutputAndStreamsStayOpen() throws IOException {
        var source = source(); var output = new TrackingOutput(); new DocxAdapter().write(source, output); assertFalse(output.closed);
        var input = new TrackingInput(output.toByteArray()); new DocxAdapter().read(input, DocxAdapter.Policy.STRICT); assertFalse(input.closed);
        var limited = new DocxAdapter(new DocxLimits(1_000_000, 1_000_000, 10_000_000, 7, 100));
        var rejected = new ByteArrayOutputStream(); assertThrows(IOException.class, () -> limited.write(source, rejected)); assertEquals(0, rejected.size());
    }
    @Test void lossPolicyCannotBypassAggregateImagePixelBudget() throws IOException {
        var raster = new BufferedImage(3500, 4000, BufferedImage.TYPE_BYTE_GRAY);
        var data = new ByteArrayOutputStream(); ImageIO.write(raster, "png", data); raster.flush();
        var parts = parts(write(source()));
        String relations = new String(parts.get("word/_rels/document.xml.rels"), StandardCharsets.UTF_8);
        String main = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) {
            parts.put("word/media/large" + i + ".png", data.toByteArray());
            relations = relations.replace("</Relationships>", "<Relationship Id=\"budget" + i
                    + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/large" + i + ".png\"/></Relationships>");
            main = main.replaceFirst("r:embed=\"img-[^\"]*\"", "r:embed=\"budget" + i + "\"");
        }
        parts.put("word/_rels/document.xml.rels", relations.getBytes(StandardCharsets.UTF_8));
        parts.put("word/document.xml", main.getBytes(StandardCharsets.UTF_8));
        byte[] invalid = pack(parts);
        for (var policy : DocxAdapter.Policy.values()) {
            assertThrows(DocxImages.LimitException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(invalid), policy));
        }
    }
    private static final class TrackingOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
    private static final class TrackingInput extends ByteArrayInputStream { private boolean closed; TrackingInput(byte[] bytes) { super(bytes); } @Override public void close() { closed = true; } }
}
