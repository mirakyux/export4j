// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfComplexRecoveryTest {
    @Test void explicitRasterDegradationPreservesTheVisiblePageAndDeclaresLostEditability() throws Exception {
        var adapter = new PdfAdapter();
        for (String kind : List.of("clip", "alpha", "blend", "text-clip")) {
            var input = adapter.input(source(kind), "");
            var raster = adapter.render(input.pages(0)).dpi(144).prepare().images().getFirst();
            var warning = adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)).prepare();
            var ignored = adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare();
            var block = (Paragraph) warning.document().sections().getFirst().blocks().getFirst();
            assertEquals(1, block.images().size());
            var image = block.images().getFirst().image();
            assertArrayEquals(raster.bytes(), image.resource().bytes(), kind);
            assertEquals(warning.document(), ignored.document());
            assertEquals(300, warning.sources().getFirst().width()); assertEquals(240, warning.sources().getFirst().height());
            assertEquals("CONTROL", ((Paragraph) warning.document().sections().getLast().blocks().getFirst()).text());
            var losses = warning.preview().issues().stream().filter(issue -> issue.feature().equals("PDF_STRUCTURE")).toList();
            assertEquals(1, losses.size()); assertEquals("PDF_PAGE_0", losses.getFirst().location().region());
            assertTrue(losses.getFirst().effects().getFirst().contains("TEXT_VECTORS_AND_STRUCTURE_NOT_EDITABLE"));
            assertFalse(warning.preview().warnings().isEmpty()); assertTrue(ignored.preview().warnings().isEmpty());
            assertEquals(warning.preview().issues(), warning.execute().report().issues());
            // Provisional semantic losses from a rejected page must not survive its raster replacement.
            assertEquals(1, warning.preview().issues().stream().filter(issue -> issue.feature().equals("PDF_FONT_MAPPING")).count());
        }
    }

    @Test void defaultRejectionAndExplicitPageSkipRemainDistinctFromRasterDegradation() throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(source("clip"), "");
        var failure = assertThrows(ProcessingException.class, () -> adapter.importDocument(input)
                .policy(policy(ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.DEGRADE)).prepare());
        assertEquals("PDF_STRUCTURE", failure.issue().feature());
        var skipped = adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.SKIP_BLOCK)).prepare();
        assertEquals(1, skipped.document().sections().size());
        assertEquals("CONTROL", ((Paragraph) skipped.document().sections().getFirst().blocks().getFirst()).text());
        assertEquals(1, skipped.sources().getFirst().page());
    }

    @Test void malformedOrUnimplementedPaintStateCannotBeMadeRenderableByIgnore() throws Exception {
        var adapter = new PdfAdapter();
        for (String kind : List.of("invalid-alpha", "missing-state", "unknown-state", "overprint")) {
            var input = adapter.input(source(kind), "");
            var failure = assertThrows(PdfOperationException.class, () -> adapter.importDocument(input)
                    .policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare(), kind);
            assertEquals(kind.startsWith("invalid") || kind.startsWith("missing")
                    ? PdfOperationException.Code.INVALID_PDF : PdfOperationException.Code.UNSUPPORTED_FEATURE, failure.code());
        }
    }

    @Test void rasterRecoveryRespectsPixelsOutputAndCancellationWithoutReturningPartialModels() throws Exception {
        var input = new PdfAdapter().input(source("alpha"), "");
        for (var limits : List.of(PdfLimits.DEFAULT.toBuilder().maxImagePixels(300L * 240 * 4 - 1).build(),
                PdfLimits.DEFAULT.toBuilder().maxOutputBytes(20).build())) {
            var adapter = PdfAdapter.builder().limits(limits).build();
            var failure = assertThrows(PdfOperationException.class, () -> adapter.importDocument(input)
                    .policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare());
            assertEquals(limits.maxOutputBytes() == 20 ? PdfOperationException.Code.OUTPUT_LIMIT : PdfOperationException.Code.IMAGE_LIMIT, failure.code());
        }
        assertThrows(ExecutionException.class, () -> new PdfAdapter().importDocument(input)
                .policy(policy(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE))
                .execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare());
        var request = new PdfAdapter().importDocument(input).rasterDpi(72)
                .policy(policy(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE));
        var prepared = request.prepare(); request.rasterDpi(144);
        var image = ((Paragraph) prepared.document().sections().getFirst().blocks().getFirst()).images().getFirst().image();
        assertEquals(300, image.resource().pixelWidth()); assertEquals(240, image.resource().pixelHeight());
    }

    private static ProcessingPolicy policy(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) {
        return ProcessingPolicy.builder().feature("PDF_LAYOUT", action == ProcessingPolicy.Action.ERROR ? ProcessingPolicy.Action.WARN : action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_FONT_MAPPING", action == ProcessingPolicy.Action.ERROR ? ProcessingPolicy.Action.WARN : action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", action, treatment).build();
    }

    private static byte[] source(String kind) throws Exception {
        try (var document = new PDDocument()) {
            var font = PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false);
            for (int index = 0; index < 2; index++) {
                var page = new PDPage(new PDRectangle(300, 240)); document.addPage(page);
                var resources = new PDResources(); page.setResources(resources); var name = resources.add(font);
                String text = "BT /" + name.getName() + " 16 Tf 20 120 Td <" + HexFormat.of().formatHex(font.encode(index == 0 ? "SOURCE CONTENT" : "CONTROL")) + "> Tj ET";
                String prefix = "";
                if (index == 0) {
                    var state = new COSDictionary();
                    if (kind.contains("alpha")) { state.setFloat(COSName.getPDFName("ca"), kind.equals("invalid-alpha") ? 2 : .4f); }
                    if (kind.equals("blend")) { state.setName(COSName.BM, "Multiply"); }
                    if (kind.equals("unknown-state")) { state.setBoolean(COSName.getPDFName("PrivateState"), true); }
                    if (kind.equals("overprint")) { state.setBoolean(COSName.OP, true); }
                    var group = new COSDictionary(); group.setItem(COSName.getPDFName("GS"), state); resources.getCOSObject().setItem(COSName.EXT_G_STATE, group);
                    prefix = switch (kind) {
                        case "clip" -> "0 0 60 240 re W n ";
                        case "text-clip" -> "BT /" + name.getName() + " 24 Tf 20 120 Td 7 Tr <" + HexFormat.of().formatHex(font.encode("MASK")) + "> Tj ET 1 0 0 rg 0 0 300 240 re f ";
                        case "missing-state" -> "/Missing gs ";
                        default -> "/GS gs 0.2 0.4 0.8 rg 10 90 190 60 re f 0 0 0 rg ";
                    };
                }
                var stream = new PDStream(document);
                try (var output = stream.createOutputStream()) { output.write((prefix + text).getBytes(StandardCharsets.US_ASCII)); }
                page.setContents(stream);
            }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
}
