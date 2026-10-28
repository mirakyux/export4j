// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for instance execution, resource scopes and invisible Form content. */
class PdfFormImportTest {
    @TempDir Path temporary;

    @FunctionalInterface private interface PageContent {
        String create(PDDocument document, PDPage page, PDType0Font regular, PDType0Font bold) throws Exception;
    }
    @FunctionalInterface private interface FormChange { void apply(PDFormXObject form) throws Exception; }

    @Test void repeatedInstancesApplyFormMatrixFontSizeColorAndRestoreTheCallerState() throws Exception {
        byte[] bytes = source((document, page, regular, bold) -> {
            var form = form(document, regular, new PDRectangle(100, 60), "1 0 0 rg\n" + text(regular, 10, 20, "FORM"));
            form.getCOSObject().setItem(COSName.MATRIX, numbers(1.5f, 0, 0, 1.5f, 4, 6));
            page.getResources().put(name("Body"), form);
            return "q 1 0 0 1 30 100 cm /Body Do Q\nq 1 0 0 1 160 160 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
        var prepared = adapter.importDocument(input).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var blocks = prepared.document().sections().getFirst().blocks();
        assertEquals(List.of("BEFORE", "FORM", "FORM", "AFTER"), blocks.stream().map(PdfFormImportTest::textOf).toList());
        assertEquals(List.of(0x000000, 0xFF0000, 0xFF0000, 0x000000), blocks.stream().map(block -> runs(block).getFirst().style().rgb()).toList());
        assertEquals(18, runs(blocks.get(1)).getFirst().style().fontSize(), .0001);
        assertEquals(18, runs(blocks.get(2)).getFirst().style().fontSize(), .0001);
        // PDF coordinates become top-left physical pt. These positions are direct affine arithmetic.
        var secondInvocation = sourceOf(prepared, blocks.get(1));
        var firstInvocation = sourceOf(prepared, blocks.get(2));
        assertEquals(179, secondInvocation.x(), .001); assertEquals(204, secondInvocation.y() + secondInvocation.height(), .001);
        assertEquals(49, firstInvocation.x(), .001); assertEquals(264, firstInvocation.y() + firstInvocation.height(), .001);
        assertFalse(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
        assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
        assertArrayEquals(bytes, input.bytes(), "recovering Form instances must not modify the source snapshot");
    }

    @Test void nestedFormsUseTheirLocalFontOrInheritTheInvokingResourceScope() throws Exception {
        for (boolean localResources : List.of(false, true)) {
            byte[] bytes = source((document, page, regular, bold) -> {
                var inner = form(document, localResources ? bold : null, new PDRectangle(100, 60), "1 0 0 rg\n" + text(localResources ? bold : regular, 10, 20, "INNER"));
                var outer = form(document, regular, new PDRectangle(160, 100), "0 0 1 rg\n" + text(regular, 20, 60, "OUTER") + "q 1 0 0 1 10 10 cm /Child Do Q\n");
                outer.getResources().put(name("Child"), inner); page.getResources().put(name("Parent"), outer);
                return "q 1 0 0 1 20 100 cm /Parent Do Q\n";
            });
            var adapter = new PdfAdapter(); String regularName; String boldName;
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(bytes)) {
                regularName = pdf.getPage(0).getResources().getFont(name("F1")).getName();
                var outer = (PDFormXObject) pdf.getPage(0).getResources().getXObject(name("Parent"));
                var inner = (PDFormXObject) outer.getResources().getXObject(name("Child"));
                boldName = inner.getResources() == null ? regularName : inner.getResources().getFont(name("F1")).getName();
            }
            var request = adapter.importDocument(adapter.input(bytes, "")).fontRole(regularName, "regular");
            if (localResources) { request.fontRole(boldName, "bold"); }
            var prepared = request.policy(policy(ProcessingPolicy.Action.WARN)).prepare();
            var blocks = prepared.document().sections().getFirst().blocks();
            assertEquals(List.of("BEFORE", "OUTER", "INNER", "AFTER"), blocks.stream().map(PdfFormImportTest::textOf).toList());
            assertEquals(List.of("regular", "regular", localResources ? "bold" : "regular", "regular"),
                    blocks.stream().map(block -> runs(block).getFirst().style().fontRole()).toList());
            assertEquals(List.of(0, 0x0000FF, 0xFF0000, 0), blocks.stream().map(block -> runs(block).getFirst().style().rgb()).toList());
            var innerSource = sourceOf(prepared, blocks.get(2));
            assertEquals(40, innerSource.x(), .001); assertEquals(270, innerSource.y() + innerSource.height(), .001);
            assertTrue(prepared.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_FONT_MAPPING") || issue.feature().equals("PDF_STRUCTURE")));
        }
    }

    @Test void repeatedImageInstancesKeepTheirPixelsAndComposedDisplayGeometry() throws Exception {
        byte[] bytes = source((document, page, regular, bold) -> {
            var form = form(document, null, new PDRectangle(60, 60), "q 20 0 0 10 10 20 cm /Picture Do Q\n");
            form.setResources(new PDResources()); form.getResources().put(name("Picture"), image(document));
            form.getCOSObject().setItem(COSName.MATRIX, numbers(1.5f, 0, 0, 1.5f, 5, 7)); page.getResources().put(name("Body"), form);
            return "q 1 0 0 1 30 100 cm /Body Do Q\nq 1 0 0 1 160 160 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter(); var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var images = prepared.document().sections().getFirst().blocks().stream().filter(block -> block instanceof Paragraph p && !p.images().isEmpty()).map(block -> (Paragraph) block).toList();
        assertEquals(2, images.size());
        for (var paragraph : images) {
            var placed = paragraph.images().getFirst().image();
            assertEquals(30, placed.width(), .001); assertEquals(15, placed.height(), .001);
            var pixels = javax.imageio.ImageIO.read(new ByteArrayInputStream(placed.resource().bytes()));
            try { assertEquals(0xFF2266AA, pixels.getRGB(0, 0)); assertEquals(0xFFAA6622, pixels.getRGB(1, 1)); }
            finally { pixels.flush(); }
        }
        var second = sourceOf(prepared, images.get(0)); var first = sourceOf(prepared, images.get(1));
        assertEquals(180, second.x(), .001); assertEquals(188, second.y(), .001);
        assertEquals(50, first.x(), .001); assertEquals(248, first.y(), .001);
    }

    @Test void uninvokedFormResourcesDoNotCauseAVisiblePageToBeSkipped() throws Exception {
        var bytes = source((document, page, regular, bold) -> {
            var unused = form(document, regular, new PDRectangle(10, 10), "0 0 1 1 re W n\n" + text(regular, 50, 20, "UNUSED"));
            page.getResources().put(name("Unused"), unused); return "";
        });
        var adapter = new PdfAdapter(); var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        assertEquals(2, prepared.document().sections().size());
        assertEquals(List.of("BEFORE", "AFTER"), prepared.document().sections().getFirst().blocks().stream().map(PdfFormImportTest::textOf).toList());
        assertTrue(prepared.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
    }

    @Test void whollyClippedTextAndImagesAreExcludedWithExplicitLossesUnderWarnAndIgnore() throws Exception {
        byte[] bytes = source((document, page, regular, bold) -> {
            var form = form(document, regular, new PDRectangle(100, 60), text(regular, 10, 20, "VISIBLE") + text(regular, 110, 20, "SECRET") + "q 20 0 0 10 110 20 cm /Picture Do Q\n");
            form.getResources().put(name("Picture"), image(document)); page.getResources().put(name("Body"), form);
            return "q 1 0 0 1 20 100 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(action)).prepare();
            assertEquals(List.of("BEFORE", "VISIBLE", "AFTER"), prepared.document().sections().getFirst().blocks().stream().map(PdfFormImportTest::textOf).toList());
            assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_HIDDEN_TEXT") && issue.treatment() == ProcessingPolicy.Treatment.DISCARD && issue.effects().contains("HIDDEN_BY_FORM_BBOX")));
            assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_IMAGE_TRANSFORM") && issue.treatment() == ProcessingPolicy.Treatment.SKIP_MATCH && issue.effects().contains("HIDDEN_BY_FORM_BBOX")));
            assertTrue(prepared.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
            assertEquals(action == ProcessingPolicy.Action.IGNORE, prepared.preview().warnings().isEmpty());
            assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
        }
    }

    @Test void invisibleTextCrossingTheFormBoxDoesNotDiscardItsVisiblePage() throws Exception {
        byte[] bytes = source((document, page, regular, bold) -> {
            var form = form(document, regular, new PDRectangle(100, 60), text(regular, 10, 20, "VISIBLE")
                    + "3 Tr\n" + text(regular, 95, 40, "INVISIBLE") + "0 Tr\n");
            page.getResources().put(name("Body"), form); return "q 1 0 0 1 20 100 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter();
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(action)).prepare();
            assertEquals(2, prepared.document().sections().size());
            assertEquals(List.of("BEFORE", "VISIBLE", "AFTER"), prepared.document().sections().getFirst().blocks().stream().map(PdfFormImportTest::textOf).toList());
            assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_HIDDEN_TEXT")
                    && issue.treatment() == ProcessingPolicy.Treatment.DISCARD
                    && issue.effects().contains("INVISIBLE_TEXT_NOT_RECOVERED")));
            assertTrue(prepared.preview().issues().stream().noneMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
            assertEquals(action == ProcessingPolicy.Action.IGNORE, prepared.preview().warnings().isEmpty());
            assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
        }
    }

    @Test void partialGlyphOrImageClippingSkipsTheWholePageAndErrorProtectsTheOldTarget() throws Exception {
        for (boolean picture : List.of(false, true)) {
            byte[] bytes = source((document, page, regular, bold) -> {
                var form = form(document, regular, new PDRectangle(picture ? 60 : 15, 60), picture ? "q 20 0 0 10 50 20 cm /Picture Do Q\n" : text(regular, 10, 20, "CROSSED"));
                if (picture) { form.getResources().put(name("Picture"), image(document)); }
                page.getResources().put(name("Body"), form); return "q 1 0 0 1 20 100 cm /Body Do Q\n";
            });
            var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var prepared = adapter.importDocument(input).policy(policy(action)).prepare();
                assertEquals(1, prepared.document().sections().size());
                assertEquals("CONTROL", textOf(prepared.document().sections().getFirst().blocks().getFirst()));
                var issues = prepared.preview().issues().stream().filter(issue -> issue.location().region().equals("PDF_PAGE_0")).toList();
                assertEquals(1, issues.size()); assertEquals("PDF_STRUCTURE", issues.getFirst().feature());
                assertEquals(ProcessingPolicy.Treatment.SKIP_BLOCK, issues.getFirst().treatment());
                assertEquals(prepared.preview().issues(), prepared.execute().report().issues());
            }
            Path target = temporary.resolve(picture ? "image.txt" : "text.txt"); byte[] sentinel = "old target".getBytes(StandardCharsets.UTF_8); Files.write(target, sentinel);
            var error = assertThrows(ProcessingException.class, () -> adapter.importDocument(input).conversion().policy(structureError())
                    .prepare(new DocumentConversion.Target("TEXT", (document, notes, policy, output) -> {
                        output.write("unexpected".getBytes(StandardCharsets.UTF_8)); return new ProcessingReport(ProcessingReport.Phase.ACTUAL, List.of());
                    })).to(target));
            assertEquals("PDF_STRUCTURE", error.issue().feature()); assertArrayEquals(sentinel, Files.readAllBytes(target));
        }
    }

    @Test void unsupportedFormTransformAndExplicitClippingCannotLeakFalseRecoveredContent() throws Exception {
        for (String commands : List.of("0 0 5 5 re W n\n", "")) {
            var bytes = source((document, page, regular, bold) -> {
                var form = form(document, regular, new PDRectangle(100, 60), commands + text(regular, 10, 20, "FORM"));
                if (commands.isEmpty()) { form.getCOSObject().setItem(COSName.MATRIX, numbers(0, 1, -1, 0, 100, 0)); }
                page.getResources().put(name("Body"), form); return "q 1 0 0 1 20 100 cm /Body Do Q\n";
            });
            var adapter = new PdfAdapter(); var prepared = adapter.importDocument(adapter.input(bytes, "")).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
            assertEquals(1, prepared.document().sections().size()); assertEquals("CONTROL", textOf(prepared.document().sections().getFirst().blocks().getFirst()));
            assertTrue(prepared.preview().issues().stream().anyMatch(issue -> issue.feature().equals("PDF_STRUCTURE")));
        }
    }

    @Test void malformedFormGeometryAndMissingDoResourcesRemainInvalidUnderLossPolicies() throws Exception {
        List<FormChange> changes = List.of(
                form -> form.getCOSObject().setItem(COSName.MATRIX, new COSString("1 0 0 1 0 0")),
                form -> form.getCOSObject().setItem(COSName.MATRIX, numbers(1, 0, 0, 1, 0)),
                form -> form.getCOSObject().removeItem(COSName.BBOX),
                form -> form.getCOSObject().setItem(COSName.BBOX, new COSString("0 0 100 60")),
                form -> form.getCOSObject().setItem(COSName.BBOX, numbers(0, 0, 0, 60)));
        for (var change : changes) {
            byte[] bytes = source((document, page, regular, bold) -> {
                var form = form(document, regular, new PDRectangle(100, 60), text(regular, 10, 20, "FORM")); change.apply(form);
                page.getResources().put(name("Body"), form); return "/Body Do\n";
            });
            invalidForBothActions(bytes);
        }
        for (String command : List.of("Do", "1 Do", "/Missing Do", "/Body 1 Do", "0 0 5 5 re W n /Missing Do")) {
            invalidForBothActions(source((document, page, regular, bold) -> {
                page.getResources().put(name("Body"), form(document, regular, new PDRectangle(100, 60), text(regular, 10, 20, "FORM")));
                return command + "\n";
            }));
        }
        // An explicit resource dictionary shadows the caller; a missing entry must not fall back to its F1.
        invalidForBothActions(source((document, page, regular, bold) -> {
            var form = form(document, null, new PDRectangle(100, 60), text(regular, 10, 20, "MISSING"));
            form.setResources(new PDResources()); page.getResources().put(name("Body"), form); return "/Body Do\n";
        }));
    }

    @Test void recursiveFormsAndRepeatedExecutionCannotBypassObjectOrWorkBudgets() throws Exception {
        byte[] recursive = source((document, page, regular, bold) -> {
            var form = form(document, regular, new PDRectangle(100, 60), "/Self Do\n"); form.getResources().put(name("Self"), form);
            page.getResources().put(name("Body"), form); return "/Body Do\n";
        });
        invalidForBothActions(recursive);
        // The stream is stored once but executed 20 times: unique-object inspection cannot bound this work.
        byte[] operators = source((document, page, regular, bold) -> {
            var form = form(document, regular, new PDRectangle(100, 60), "q Q\n".repeat(80));
            page.getResources().put(name("Body"), form); return "/Body Do\n".repeat(20);
        });
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(1_000).build()).build();
        assertEquals(PdfOperationException.Code.OPERATOR_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.importDocument(bounded.input(operators, "")).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        byte[] text = source((document, page, regular, bold) -> {
            page.getResources().put(name("Body"), form(document, regular, new PDRectangle(100, 60), text(regular, 10, 20, "REPEAT")));
            return "/Body Do\n".repeat(20);
        });
        var textBounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(50).build()).build();
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class, () -> textBounded.importDocument(textBounded.input(text, "")).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        byte[] pictures = source((document, page, regular, bold) -> {
            var form = form(document, null, new PDRectangle(100, 60), "q 20 0 0 10 10 20 cm /Picture Do Q\n");
            form.setResources(new PDResources()); form.getResources().put(name("Picture"), image(document)); page.getResources().put(name("Body"), form);
            return "/Body Do\n".repeat(10);
        });
        var imageBounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxImagePixels(20).build()).build();
        assertEquals(PdfOperationException.Code.IMAGE_LIMIT, assertThrows(PdfOperationException.class, () -> imageBounded.importDocument(imageBounded.input(pictures, "")).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
    }

    private static void invalidForBothActions(byte[] bytes) throws Exception {
        var adapter = new PdfAdapter(); var input = adapter.input(bytes, "");
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class,
                    () -> adapter.importDocument(input).policy(policy(action)).prepare()).code());
        }
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LAYOUT", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_FONT_MAPPING", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", action, ProcessingPolicy.Treatment.SKIP_BLOCK)
                .feature("PDF_IMAGE_TRANSFORM", action, ProcessingPolicy.Treatment.SKIP_MATCH).build();
    }
    private static ProcessingPolicy structureError() {
        return ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
                .feature("PDF_LAYOUT", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_FONT_MAPPING", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
                .feature("PDF_STRUCTURE", ProcessingPolicy.Action.ERROR, ProcessingPolicy.Treatment.SKIP_BLOCK).build();
    }
    private static PreparedPdfImport.Source sourceOf(PreparedPdfImport prepared, Block block) {
        return prepared.sources().stream().filter(source -> source.blockId().equals(block.id())).findFirst().orElseThrow();
    }
    private static String textOf(Block block) {
        return switch (block) { case Paragraph p -> p.text(); case Heading h -> h.text(); default -> throw new AssertionError("unexpected block"); };
    }
    private static List<InlineText> runs(Block block) {
        return switch (block) { case Paragraph p -> p.inlines(); case Heading h -> h.inlines(); default -> throw new AssertionError("unexpected block"); };
    }
    private static byte[] source(PageContent content) throws Exception {
        try (var document = new PDDocument()) {
            var regular = font(document, "LiberationSans-Regular.ttf"); var bold = font(document, "LiberationSans-Bold.ttf");
            var page = new PDPage(new PDRectangle(400, 400)); document.addPage(page); page.setResources(new PDResources()); page.getResources().put(name("F1"), regular);
            setContents(document, page, text(regular, 20, 360, "BEFORE") + content.create(document, page, regular, bold) + text(regular, 20, 60, "AFTER"));
            var control = new PDPage(new PDRectangle(400, 400)); document.addPage(control); control.setResources(new PDResources()); control.getResources().put(name("F1"), regular);
            setContents(document, control, text(regular, 20, 360, "CONTROL"));
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static PDType0Font font(PDDocument document, String file) throws Exception {
        return PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts").resolve(file))), false);
    }
    private static PDFormXObject form(PDDocument document, PDType0Font font, PDRectangle box, String content) throws Exception {
        var form = new PDFormXObject(document); form.setBBox(box);
        if (font != null) { form.setResources(new PDResources()); form.getResources().put(name("F1"), font); }
        try (var output = form.getCOSObject().createOutputStream()) { output.write(content.getBytes(StandardCharsets.US_ASCII)); }
        return form;
    }
    private static org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image(PDDocument document) throws Exception {
        var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try { pixels.setRGB(0, 0, 0x2266AA); pixels.setRGB(1, 1, 0xAA6622); return LosslessFactory.createFromImage(document, pixels); }
        finally { pixels.flush(); }
    }
    private static void setContents(PDDocument document, PDPage page, String commands) throws Exception {
        var stream = new PDStream(document); try (var output = stream.createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); } page.setContents(stream);
    }
    private static String text(PDType0Font font, int x, int y, String value) throws Exception {
        return "BT /F1 12 Tf " + x + " " + y + " Td <" + HexFormat.of().formatHex(font.encode(value)) + "> Tj ET\n";
    }
    private static COSName name(String value) { return COSName.getPDFName(value); }
    private static COSArray numbers(float... values) {
        var result = new COSArray(); for (float value : values) { result.add(new COSFloat(value)); } return result;
    }
}
