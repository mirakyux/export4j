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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfGraphicsStateImportTest {
    @FunctionalInterface
    private interface StateChange {
        void apply(PDDocument document, PDType0Font font, COSDictionary state) throws Exception;
    }

    @Test void opaqueGraphicsStateAcrossContentStreamsPreservesTextColorsAndSources() throws Exception {
        var adapter = new PdfAdapter();
        var plain = adapter.importDocument(adapter.input(source((document, font, state) -> { }, "", false), ""))
                .policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        for (String blend : List.of("Normal", "Compatible")) {
            var prepared = adapter.importDocument(adapter.input(source((document, font, state) -> state.setName(COSName.BM, blend), "/GS gs", true), ""))
                    .policy(policy(ProcessingPolicy.Action.WARN)).prepare();
            var blocks = prepared.document().sections().getFirst().blocks();
            assertEquals(List.of("BEFORE", "RED", "AFTER"), blocks.stream().map(block -> ((Paragraph) block).text()).toList());
            assertEquals(List.of(0x000000, 0xFF0000, 0x000000), blocks.stream().map(block -> ((Paragraph) block).inlines().getFirst().style().rgb()).toList());
            assertEquals(plain.sources(), prepared.sources());
            assertEquals(plain.preview().issues(), prepared.preview().issues());
            assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
            assertEquals("CONTROL", ((Paragraph) prepared.document().sections().getLast().blocks().getFirst()).text());
        }
    }

    @Test void validButUnsupportedStatesUseTheWholePagePolicyWithoutRecoveringItsText() throws Exception {
        List<StateChange> changes = List.of(
                (document, font, state) -> state.setFloat(COSName.CA, .5f),
                (document, font, state) -> state.setFloat(COSName.getPDFName("ca"), 0),
                (document, font, state) -> state.setName(COSName.BM, "Multiply"),
                (document, font, state) -> { var modes = new COSArray(); modes.add(COSName.NORMAL); state.setItem(COSName.BM, modes); },
                (document, font, state) -> { var setting = new COSArray(); setting.add(font.getCOSObject()); setting.add(new COSFloat(12)); state.setItem(COSName.FONT, setting); },
                (document, font, state) -> {
                    var group = new PDTransparencyGroup(document); group.setBBox(new PDRectangle(20, 20)); group.setResources(new PDResources());
                    var attributes = new COSDictionary(); attributes.setName(COSName.S, "Transparency"); group.getCOSObject().setItem(COSName.GROUP, attributes);
                    var mask = new COSDictionary(); mask.setName(COSName.S, "Alpha"); mask.setItem(COSName.G, group); state.setItem(COSName.SMASK, mask);
                },
                (document, font, state) -> state.setBoolean(COSName.getPDFName("TK"), true),
                (document, font, state) -> state.setBoolean(COSName.getPDFName("PrivateState"), true));
        var adapter = new PdfAdapter();
        for (var change : changes) {
            var input = adapter.input(source(change, "/GS gs", false), "");
            var failure = assertThrows(ProcessingException.class, () -> adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.ERROR)).prepare());
            assertEquals("PDF_STRUCTURE", failure.issue().feature());
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = adapter.importDocument(input).policy(policy(action)).prepare();
                assertEquals(1, prepared.document().sections().size());
                assertEquals("CONTROL", ((Paragraph) prepared.document().sections().getFirst().blocks().getFirst()).text());
                var issue = prepared.preview().issues().stream().filter(value -> value.feature().equals("PDF_STRUCTURE")).findFirst().orElseThrow();
                assertEquals(action, issue.action()); assertEquals(ProcessingPolicy.Treatment.SKIP_BLOCK, issue.treatment());
                assertEquals("PDF_PAGE_0", issue.location().region());
                assertEquals(List.of("PAGE_NOT_RECOVERED"), issue.effects());
                assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
                assertEquals(action == ProcessingPolicy.Action.IGNORE, prepared.preview().warnings().isEmpty());
            }
        }
    }

    @Test void malformedGraphicsStateValuesCannotBecomePolicySkippedPages() throws Exception {
        List<StateChange> changes = List.of(
                (document, font, state) -> state.setItem(COSName.CA, new COSString("1")),
                (document, font, state) -> state.setFloat(COSName.getPDFName("ca"), 1.01f),
                (document, font, state) -> state.setFloat(COSName.LW, -1),
                (document, font, state) -> state.setFloat(COSName.LC, 1.5f),
                (document, font, state) -> state.setInt(COSName.LJ, 3),
                (document, font, state) -> state.setFloat(COSName.ML, .5f),
                (document, font, state) -> state.setItem(COSName.D, new COSString("[] 0")),
                (document, font, state) -> { var dash = new COSArray(); var lengths = new COSArray(); lengths.add(COSInteger.ZERO); dash.add(lengths); dash.add(COSInteger.ZERO); state.setItem(COSName.D, dash); },
                (document, font, state) -> state.setItem(COSName.RI, new COSString("Perceptual")),
                (document, font, state) -> state.setInt(COSName.SA, 1),
                (document, font, state) -> state.setInt(COSName.BM, 1),
                (document, font, state) -> state.setName(COSName.SMASK, "BadMask"),
                (document, font, state) -> { state.setFloat(COSName.CA, .5f); state.setName(COSName.SMASK, "BadMask"); },
                (document, font, state) -> state.setName(COSName.TYPE, "Other"));
        var adapter = new PdfAdapter();
        for (var change : changes) {
            var input = adapter.input(source(change, "/GS gs", false), "");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                assertEquals(PdfOperationException.Code.INVALID_PDF,
                        assertThrows(PdfOperationException.class, () -> adapter.importDocument(input).policy(policy(action)).prepare()).code());
            }
        }
    }

    @Test void missingResourcesAndMalformedGsOperandsFailEvenAfterAnUnsupportedState() throws Exception {
        var adapter = new PdfAdapter();
        for (String command : List.of("/Missing gs", "gs", "1 gs", "/GS 1 gs", "/GS gs /Missing gs")) {
            var input = adapter.input(source((document, font, state) -> state.setFloat(COSName.CA, .5f), command, false), "");
            assertEquals(PdfOperationException.Code.INVALID_PDF,
                    assertThrows(PdfOperationException.class, () -> adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        }
        for (boolean wrongGroup : List.of(false, true)) {
            byte[] bytes;
            try (var document = org.apache.pdfbox.Loader.loadPDF(source((pdf, font, state) -> { }, "/GS gs", false))) {
                var resources = document.getPage(0).getResources().getCOSObject();
                if (wrongGroup) { resources.setItem(COSName.EXT_G_STATE, new COSString("invalid")); }
                else { resources.getCOSDictionary(COSName.EXT_G_STATE).setItem(COSName.getPDFName("GS"), new COSString("invalid")); }
                var output = new ByteArrayOutputStream(); document.save(output); bytes = output.toByteArray();
            }
            var input = adapter.input(bytes, "");
            assertEquals(PdfOperationException.Code.INVALID_PDF,
                    assertThrows(PdfOperationException.class, () -> adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        }
    }

    @Test void explicitClippingRestrictionRemainsAndCannotProduceFalseRecoveredText() throws Exception {
        var adapter = new PdfAdapter();
        byte[] bytes = source((document, font, state) -> { }, "/GS gs 0 0 5 5 re W n", false);
        var result = adapter.importDocument(adapter.input(bytes, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(1, result.document().sections().size());
        assertEquals("CONTROL", ((Paragraph) result.document().sections().getFirst().blocks().getFirst()).text());
        assertTrue(result.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
    }

    @Test void graphicsStateValidationIsBoundedAndCancellationDoesNotReturnAModel() throws Exception {
        var input = new PdfAdapter().input(source((document, font, state) -> {
            var dash = new COSArray(); var lengths = new COSArray();
            for (int i = 0; i < 200; i++) { lengths.add(COSInteger.ONE); }
            dash.add(lengths); dash.add(COSInteger.ZERO); state.setItem(COSName.D, dash);
        }, "/GS gs", false), "");
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(100).build()).build();
        assertEquals(PdfOperationException.Code.OPERATOR_LIMIT,
                assertThrows(PdfOperationException.class, () -> bounded.importDocument(input).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        var cancelled = new AtomicBoolean(true);
        assertThrows(ExecutionException.class, () -> new PdfAdapter().importDocument(input).policy(policy(ProcessingPolicy.Action.WARN))
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare());
        cancelled.set(false);
        var prepared = new PdfAdapter().importDocument(input).policy(policy(ProcessingPolicy.Action.WARN))
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        cancelled.set(true); assertThrows(ExecutionException.class, prepared::execute);
        assertFalse(prepared.document().sections().isEmpty());
    }

    @Test void repeatedUseOfOneLargeValidStateStaysWithinTheSharedValidationBudget() throws Exception {
        var bytes = source((document, font, state) -> {
            var dash = new COSArray(); var lengths = new COSArray();
            for (int i = 0; i < 200; i++) { lengths.add(COSInteger.ONE); }
            dash.add(lengths); dash.add(COSInteger.ZERO); state.setItem(COSName.D, dash);
        }, "/GS gs\n".repeat(300), false);
        var adapter = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(1_500).build()).build();
        var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(2, prepared.document().sections().size());
        assertEquals(List.of("BEFORE", "RED", "AFTER"), prepared.document().sections().getFirst().blocks().stream().map(block -> ((Paragraph) block).text()).toList());
        assertTrue(prepared.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
    }

    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        var diagnostics = action == ProcessingPolicy.Action.ERROR ? ProcessingPolicy.Action.WARN : action;
        return ProcessingPolicy.builder().defaultRule(diagnostics, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LAYOUT", diagnostics, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_FONT_MAPPING", diagnostics, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", action, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    }

    private static byte[] source(StateChange change, String command, boolean split) throws Exception {
        try (var document = new PDDocument()) {
            var font = PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))), false);
            for (int pageIndex = 0; pageIndex < 2; pageIndex++) {
                var page = new PDPage(new PDRectangle(300, 320)); document.addPage(page);
                var resources = new PDResources(); page.setResources(resources); var fontName = resources.add(font);
                var streams = new ArrayList<PDStream>();
                if (pageIndex == 0) {
                    var state = new COSDictionary(); state.setItem(COSName.TYPE, COSName.EXT_G_STATE);
                    state.setFloat(COSName.LW, 0); state.setInt(COSName.LC, 2); state.setInt(COSName.LJ, 0); state.setFloat(COSName.ML, 1);
                    var dash = new COSArray(); var lengths = new COSArray(); lengths.add(COSInteger.ONE); lengths.add(COSInteger.ZERO);
                    dash.add(lengths); dash.add(new COSFloat(.5f)); state.setItem(COSName.D, dash);
                    state.setName(COSName.RI, "RelativeColorimetric"); state.setItem(COSName.SA, COSBoolean.TRUE);
                    state.setFloat(COSName.CA, 1); state.setFloat(COSName.getPDFName("ca"), 1); state.setName(COSName.BM, "Normal"); state.setName(COSName.SMASK, "None");
                    change.apply(document, font, state);
                    var states = new COSDictionary(); states.setItem(COSName.getPDFName("GS"), state); resources.getCOSObject().setItem(COSName.EXT_G_STATE, states);
                    String prefix = text(fontName, font, 280, "BEFORE") + "q\n";
                    String suffix = "1 0 0 rg\n" + text(fontName, font, 250, "RED") + "Q\n" + text(fontName, font, 220, "AFTER");
                    if (split) {
                        streams.add(stream(document, prefix + "/GS ")); streams.add(stream(document, "gs\n" + suffix));
                    } else { streams.add(stream(document, prefix + command + "\n" + suffix)); }
                } else { streams.add(stream(document, text(fontName, font, 280, "CONTROL"))); }
                page.setContents(streams);
            }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }

    private static PDStream stream(PDDocument document, String content) throws Exception {
        var stream = new PDStream(document);
        try (var output = stream.createOutputStream()) { output.write(content.getBytes(StandardCharsets.US_ASCII)); }
        return stream;
    }

    private static String text(COSName name, PDType0Font font, int y, String text) throws Exception {
        return "BT /" + name.getName() + " 12 Tf 20 " + y + " Td <" + HexFormat.of().formatHex(font.encode(text)) + "> Tj ET\n";
    }
}
