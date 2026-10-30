// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfApplicationMetadataTest {
    private static final COSName PIECE_INFO = COSName.getPDFName("PieceInfo");
    private static final COSName APPLICATION = COSName.getPDFName("DocumentEditor");

    @Test void readerApplicationMetadataSurvivesFieldReadAndEdit() throws Exception {
        byte[] payload = "opaque application metadata".getBytes(StandardCharsets.UTF_8);
        byte[] source;
        try (var pdf = Loader.loadPDF(PdfObjectEditorTest.source())) {
            var stream = pdf.getDocument().createCOSStream();
            try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) { output.write(payload); }
            application(pdf).setItem(COSName.getPDFName("Private"), stream);
            source = save(pdf);
        }
        var adapter = new PdfAdapter();
        assertEquals("OLD", adapter.readObjects(adapter.input(source, "")).fields().getFirst().value());
        var output = new ByteArrayOutputStream();
        adapter.editObjects(adapter.input(source, "")).fillText("text", "READY", PdfObjectEditorTest.font(), 10).prepare().to(output);
        assertEquals("READY", adapter.readObjects(adapter.input(output.toByteArray(), "")).fields().getFirst().value());
        try (var pdf = Loader.loadPDF(output.toByteArray())) {
            var metadata = pdf.getDocumentCatalog().getCOSObject().getCOSDictionary(PIECE_INFO).getCOSDictionary(APPLICATION);
            var stream = metadata.getCOSStream(COSName.getPDFName("Private"));
            try (var input = stream.createInputStream()) { assertArrayEquals(payload, input.readAllBytes()); }
        }
    }

    @Test void applicationMetadataDoesNotHideActiveActionsOrUnknownCatalogEntries() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            var action = new COSDictionary();
            action.setName(COSName.S, "JavaScript"); action.setString(COSName.JS, "untrusted");
            application(pdf).setItem(COSName.getPDFName("Private"), action);
            var adapter = new PdfAdapter();
            byte[] active = save(pdf);
            assertEquals(PdfOperationException.Code.UNSUPPORTED_FEATURE,
                    assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(active, ""))).code());
            pdf.getDocumentCatalog().getCOSObject().removeItem(PIECE_INFO);
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("UnknownCatalogEntry"), new COSDictionary());
            byte[] unknown = save(pdf);
            assertEquals(PdfOperationException.Code.UNSUPPORTED_FEATURE,
                    assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(unknown, ""))).code());
        }
    }

    @Test void applicationMetadataStreamsRemainWithinDecodedBudgets() throws Exception {
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            var stream = pdf.getDocument().createCOSStream();
            try (var output = stream.createOutputStream(COSName.FLATE_DECODE)) { output.write(new byte[4096]); }
            application(pdf).setItem(COSName.getPDFName("Private"), stream);
            byte[] source = save(pdf);
            var adapter = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxDecodedStreamBytes(1024).build()).build();
            assertEquals(PdfOperationException.Code.DECODED_STREAM_LIMIT,
                    assertThrows(PdfOperationException.class, () -> adapter.readObjects(adapter.input(source, ""))).code());
        }
    }

    private static COSDictionary application(PDDocument pdf) {
        var application = new COSDictionary();
        application.setString(COSName.getPDFName("LastModified"), "D:20260101000000Z");
        var pieces = new COSDictionary(); pieces.setItem(APPLICATION, application);
        pdf.getDocumentCatalog().getCOSObject().setItem(PIECE_INFO, pieces);
        return application;
    }

    private static byte[] save(PDDocument pdf) throws Exception {
        var output = new ByteArrayOutputStream(); pdf.save(output, CompressParameters.NO_COMPRESSION);
        return output.toByteArray();
    }
}
