// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real shared streams distinguish invocation edits from accidental shared-resource mutation. */
class PdfFormTextEditingTest {
    @TempDir Path directory;
    private record Position(String text, float x, float y, String font) { }
    @FunctionalInterface private interface Fixture { String create(PDDocument document, PDPage first, PDPage second, PDType0Font regular, PDType0Font bold) throws Exception; }

    @Test void repeatedSharedFormsAreClonedPerInvocationAndAnUnselectedPageKeepsItsSource() throws Exception {
        byte[] source = shared(false, false);
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var adapter = new PdfAdapter(); var input = adapter.input(source, "");
            var prepared = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(policy(action)).prepare();
            var output = new ByteArrayOutputStream(); var result = prepared.to(output); byte[] bytes = output.toByteArray();
            assertEquals(2, result.changes()); assertEquals(prepared.preview().issues(), result.report().issues());
            assertEquals(action == ProcessingPolicy.Action.IGNORE, result.report().warnings().isEmpty());
            assertFalse(text(bytes, 0).contains("SECRET")); assertEquals(2, occurrences(text(bytes, 0), "HI"));
            assertTrue(text(bytes, 1).contains("SECRET KEEP")); assertArrayEquals(source, input.bytes());
            assertEquals(positions(source, 1), positions(bytes, 1));
            assertUnmatchedCoordinates(source, bytes);
            try (var before = Loader.loadPDF(source); var after = Loader.loadPDF(bytes)) {
                assertArrayEquals(contents(before.getPage(1)), contents(after.getPage(1)));
                var original = formAt(before.getPage(1), "Body"); var preserved = formAt(after.getPage(1), "Body");
                assertArrayEquals(contents(original), contents(preserved));
                var actual = invocations(after.getPage(0)); assertEquals(2, actual.size());
                assertNotSame(actual.get(0).getCOSObject(), actual.get(1).getCOSObject());
                for (var edited : actual) {
                    assertEquals(1.25, edited.getMatrix().getScaleX(), .0001);
                    assertEquals(7, edited.getMatrix().getTranslateX(), .0001);
                    assertEquals(original.getBBox().getWidth(), edited.getBBox().getWidth());
                    assertFalse(java.util.Arrays.equals(contents(original), contents(edited)));
                }
            }
        }
    }

    @Test void nestedLocalAndInheritedResourcesPreserveUnmatchedFontAndPosition() throws Exception {
        for (boolean local : List.of(false, true)) {
            byte[] source = shared(true, local); var adapter = new PdfAdapter();
            var prepared = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
            var output = new ByteArrayOutputStream(); assertEquals(2, prepared.to(output).changes()); byte[] bytes = output.toByteArray();
            assertFalse(text(bytes, 0).contains("SECRET")); assertTrue(text(bytes, 1).contains("SECRET"));
            assertUnmatchedCoordinates(source, bytes);
            try (var before = Loader.loadPDF(source); var after = Loader.loadPDF(bytes)) {
                assertArrayEquals(contents(formAt(before.getPage(1), "Body")), contents(formAt(after.getPage(1), "Body")));
                for (var outer : invocations(after.getPage(0))) { assertEquals(1, invocations(outer).size()); }
            }
        }
    }

    @Test void inheritedFontObjectSurvivesALocallyShadowedResourceName() throws Exception {
        byte[] source = fixture((document, first, second, regular, bold) -> {
            var body = form(document, bold, "BT 10 20 Td " + shown(regular, "SECRET KEEP") + " ET\n");
            first.getResources().put(name("Body"), body);
            return "BT /F1 14 Tf ET q 1 0 0 1 20 100 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter(); var candidate = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var output = new ByteArrayOutputStream(); assertEquals(1, candidate.to(output).changes());
        assertUnmatchedCoordinates(source, output.toByteArray());
        // PDFTextStripper may infer another space from the deliberately retained old advance.
        assertEquals("HI KEEP", positions(output.toByteArray(), 0).stream().map(Position::text).collect(java.util.stream.Collectors.joining()));
    }

    @Test void qAndQKeepInheritedSpacingLocalToTheActualInvocation() throws Exception {
        byte[] source = fixture((document, first, second, regular, bold) -> {
            var body = form(document, regular, "BT /F1 14 Tf 10 20 Td " + shown(regular, "SECRET KEEP") + " ET\n"); first.getResources().put(name("Body"), body);
            return "q 1 Tc 1 0 0 1 20 100 cm /Body Do Q q 1 0 0 1 20 200 cm /Body Do Q\n";
        });
        var adapter = new PdfAdapter(); var candidate = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var output = new ByteArrayOutputStream(); assertEquals(1, candidate.to(output).changes());
        assertTrue(text(output.toByteArray(), 0).contains("SECRET")); assertTrue(text(output.toByteArray(), 0).contains("HI"));
        assertTrue(candidate.preview().issues().stream().anyMatch(issue -> issue.feature().equals("TEXT_SHAPING")));
    }

    @Test void ordinaryGraphicsStateIsPreservedButTransparencyUsesTheDeclaredSkipPolicy() throws Exception {
        for (boolean transparent : List.of(false, true)) {
            byte[] source = fixture((document, first, second, regular, bold) -> {
                var body = form(document, regular, "/State gs 0.2 0.4 0.6 rg BT /F1 14 Tf 10 20 Td " + shown(regular, "SECRET KEEP") + " ET\n");
                var state = new COSDictionary(); state.setFloat(COSName.CA, 1); state.setFloat(COSName.CA_NS, transparent ? .5f : 1); state.setItem(COSName.BM, COSName.NORMAL);
                var states = new COSDictionary(); states.setItem(name("State"), state); body.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
                first.getResources().put(name("Body"), body); return "/Body Do\n";
            });
            var adapter = new PdfAdapter(); var candidate = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
            var output = new ByteArrayOutputStream(); assertEquals(transparent ? 0 : 1, candidate.to(output).changes());
            assertEquals(transparent, text(output.toByteArray(), 0).contains("SECRET"));
            assertEquals(transparent, candidate.preview().issues().stream().anyMatch(issue -> issue.feature().equals("TEXT_STRUCTURE")));
        }
    }

    @Test void aValidFontSettingGraphicsStateUsesTheUnsupportedStructurePolicy() throws Exception {
        byte[] source = fixture((document, first, second, regular, bold) -> {
            var body = form(document, regular, "/State gs BT 10 20 Td " + shown(regular, "SECRET KEEP") + " ET\n");
            var setting = new COSArray(); setting.add(regular.getCOSObject()); setting.add(new COSFloat(14));
            var state = new COSDictionary(); state.setItem(COSName.FONT, setting);
            var states = new COSDictionary(); states.setItem(name("State"), state); body.getResources().getCOSObject().setItem(COSName.EXT_G_STATE, states);
            first.getResources().put(name("Body"), body); return "/Body Do\n";
        });
        var adapter = new PdfAdapter(); var input = adapter.input(source, "");
        assertEquals("TEXT_STRUCTURE", assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "SECRET", "HI", font()).prepare()).issue().feature());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var candidate = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(policy(action)).prepare();
            var output = new ByteArrayOutputStream(); assertEquals(0, candidate.to(output).changes());
            assertEquals(1, candidate.preview().issues().size()); assertEquals("TEXT_STRUCTURE", candidate.preview().issues().getFirst().feature());
            assertTrue(text(output.toByteArray(), 0).contains("SECRET"));
        }
    }

    @Test void laterReplacementsCanReadEmbeddedProgramsInPreviouslyClonedForms() throws Exception {
        byte[] source = shared(false, false); var adapter = new PdfAdapter();
        var candidate = adapter.editText(adapter.input(source, "")).replace(0, "SECRET", "PUBLIC", font())
                .replace(0, "PUBLIC", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN)).prepare();
        var output = new ByteArrayOutputStream(); assertEquals(4, candidate.to(output).changes());
        assertFalse(text(output.toByteArray(), 0).contains("SECRET")); assertFalse(text(output.toByteArray(), 0).contains("PUBLIC"));
        assertEquals(2, occurrences(text(output.toByteArray(), 0), "HI")); assertTrue(text(output.toByteArray(), 1).contains("SECRET"));
    }

    @Test void nonmatchesKeepPageAndFormStreamsAndStrictErrorsProtectExistingTargets() throws Exception {
        byte[] source = shared(false, false); var adapter = new PdfAdapter(); var input = adapter.input(source, "");
        var unchanged = adapter.editText(input).replace(0, "ABSENT", "HI", font()).prepare();
        var output = new ByteArrayOutputStream(); assertEquals(0, unchanged.to(output).changes()); assertTrue(unchanged.preview().issues().isEmpty());
        try (var before = Loader.loadPDF(source); var after = Loader.loadPDF(output.toByteArray())) {
            for (int i = 0; i < 2; i++) { assertArrayEquals(contents(before.getPage(i)), contents(after.getPage(i))); assertArrayEquals(contents(formAt(before.getPage(i), "Body")), contents(formAt(after.getPage(i), "Body"))); }
        }
        Path target = directory.resolve("strict.pdf"); Files.writeString(target, "OLD");
        assertEquals("TEXT_FONT", assertThrows(ProcessingException.class, () -> adapter.editText(input).replace(0, "SECRET", "HI", font()).prepare().to(target)).issue().feature());
        assertEquals("OLD", Files.readString(target));
        var cancelled = new AtomicBoolean(); var candidate = adapter.editText(input).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.WARN))
                .execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare(); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> candidate.to(target)); assertEquals("OLD", Files.readString(target));
    }

    @Test void actualRepeatedExecutionAndMalformedFormsCannotBypassLimitsOrPolicies() throws Exception {
        byte[] source = fixture((document, first, second, regular, bold) -> {
            var body = form(document, regular, "q Q\n".repeat(80)); first.getResources().put(name("Body"), body); return "/Body Do\n".repeat(20);
        });
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxContentOperators(1_000).build()).build();
        assertEquals(PdfOperationException.Code.OPERATOR_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.editText(bounded.input(source, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        var textBounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTextCharacters(25).build()).build();
        byte[] repeated = shared(false, false);
        assertEquals(PdfOperationException.Code.TEXT_LIMIT, assertThrows(PdfOperationException.class, () -> textBounded.editText(textBounded.input(repeated, "")).replace(0, "SECRET", "HI", font()).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        for (boolean matrix : List.of(false, true)) {
            byte[] malformed = fixture((document, first, second, regular, bold) -> {
                var body = form(document, regular, "BT /F1 14 Tf 10 20 Td " + shown(regular, "SECRET") + " ET\n");
                if (matrix) { body.getCOSObject().setItem(COSName.MATRIX, new COSString("wrong")); }
                else { body.setResources(new PDResources()); }
                first.getResources().put(name("Body"), body); return "/Body Do\n";
            });
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var adapter = new PdfAdapter(); assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, () -> adapter.editText(adapter.input(malformed, "")).replace(0, "SECRET", "HI", font()).policy(policy(action)).prepare()).code());
            }
        }
    }

    @Test void sourcePdfAndActuallyLoadedReplacementFontShareTheInputBudget() throws Exception {
        byte[] source = shared(false, false); var replacement = font();
        long exact = source.length + (long) replacement.bytes().length;
        var bounded = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes(exact - 1).build()).build();
        assertEquals(PdfOperationException.Code.INPUT_LIMIT, assertThrows(PdfOperationException.class, () -> bounded.editText(bounded.input(source, ""))
                .replace(0, "SECRET", "HI", replacement).policy(policy(ProcessingPolicy.Action.IGNORE)).prepare()).code());
        var accepted = PdfAdapter.builder().limits(PdfLimits.DEFAULT.toBuilder().maxTotalInputBytes(exact).build()).build();
        assertEquals(2, accepted.editText(accepted.input(source, "")).replace(0, "SECRET", "HI", replacement)
                .policy(policy(ProcessingPolicy.Action.WARN)).prepare().to(new ByteArrayOutputStream()).changes());
    }

    private static byte[] shared(boolean nested, boolean local) throws Exception {
        return fixture((document, first, second, regular, bold) -> {
            var selected = local ? bold : regular;
            var body = form(document, nested ? regular : selected, nested ? "q 1 0 0 1 3 4 cm /Child Do Q\n"
                    : "BT /F1 14 Tf 10 20 Td <" + HexFormat.of().formatHex(selected.encode("SEC")) + "> Tj [<" + HexFormat.of().formatHex(selected.encode("RET KEEP")) + ">] TJ ET\n");
            if (nested) { var inner = form(document, local ? bold : null, "BT /F1 14 Tf 10 20 Td " + shown(selected, "SECRET KEEP") + " ET\n"); body.getResources().put(name("Child"), inner); }
            body.getCOSObject().setItem(COSName.MATRIX, numbers(1.25f, 0, 0, 1.25f, 7, 5));
            first.getResources().put(name("Body"), body); second.getResources().put(name("Body"), body);
            setContents(document, second, "q 1 0 0 1 20 100 cm /Body Do Q\n");
            return "q 1 0 0 1 20 100 cm /Body Do Q q 1 0 0 1 20 200 cm /Body Do Q\n";
        });
    }
    private static byte[] fixture(Fixture fixture) throws Exception {
        try (var document = new PDDocument()) {
            var regular = load(document, "LiberationSans-Regular.ttf"); var bold = load(document, "LiberationSans-Bold.ttf");
            var first = page(document, regular); var second = page(document, regular);
            String content = fixture.create(document, first, second, regular, bold); setContents(document, first, content);
            if (!second.hasContents()) { setContents(document, second, "BT /F1 14 Tf 20 300 Td " + shown(regular, "CONTROL") + " ET\n"); }
            var output = new ByteArrayOutputStream(); document.save(output); return output.toByteArray();
        }
    }
    private static PDPage page(PDDocument document, PDType0Font regular) { var page = new PDPage(new PDRectangle(400, 400)); document.addPage(page); page.setResources(new PDResources()); page.getResources().put(name("F1"), regular); return page; }
    private static PDType0Font load(PDDocument document, String file) throws Exception { return PDType0Font.load(document, new ByteArrayInputStream(Files.readAllBytes(Path.of("../assets/fonts").resolve(file))), false); }
    private static PdfFont font() throws Exception { return PdfFont.of(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf"))); }
    private static PDFormXObject form(PDDocument document, PDType0Font font, String commands) throws Exception {
        var form = new PDFormXObject(document); form.setBBox(new PDRectangle(200, 70));
        if (font != null) { form.setResources(new PDResources()); form.getResources().put(name("F1"), font); }
        try (var output = form.getCOSObject().createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); } return form;
    }
    private static void setContents(PDDocument document, PDPage page, String commands) throws Exception { var stream = new PDStream(document); try (var output = stream.createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); } page.setContents(stream); }
    private static String shown(PDType0Font font, String text) throws Exception { return "<" + HexFormat.of().formatHex(font.encode(text)) + "> Tj"; }
    private static COSName name(String value) { return COSName.getPDFName(value); }
    private static COSArray numbers(float... values) { var result = new COSArray(); for (float value : values) { result.add(new COSFloat(value)); } return result; }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) { return ProcessingPolicy.builder().feature("TEXT_FONT", action, ProcessingPolicy.Treatment.DEGRADE).feature("TEXT_SHAPING", action, ProcessingPolicy.Treatment.SKIP_MATCH).feature("TEXT_STRUCTURE", action, ProcessingPolicy.Treatment.SKIP_BLOCK).build(); }
    private static int occurrences(String text, String value) { return (text.length() - text.replace(value, "").length()) / value.length(); }
    private static String text(byte[] bytes, int page) throws Exception { try (var document = Loader.loadPDF(bytes)) { var stripper = new PDFTextStripper(); stripper.setStartPage(page + 1); stripper.setEndPage(page + 1); return stripper.getText(document); } }
    private static List<Position> positions(byte[] bytes, int page) throws Exception {
        var result = new ArrayList<Position>(); var stripper = new PDFTextStripper() {
            @Override protected void processTextPosition(TextPosition position) { result.add(new Position(position.getUnicode(), position.getXDirAdj(), position.getYDirAdj(), position.getFont().getName())); }
        };
        stripper.setStartPage(page + 1); stripper.setEndPage(page + 1); try (var document = Loader.loadPDF(bytes)) { stripper.getText(document); } return result;
    }
    private static void assertUnmatchedCoordinates(byte[] source, byte[] output) throws Exception {
        var before = positions(source, 0).stream().filter(value -> value.text().equals("K") || value.text().equals("P")).toList();
        var after = positions(output, 0).stream().filter(value -> value.text().equals("K") || value.text().equals("P")).toList();
        assertEquals(before.size(), after.size());
        for (int index = 0; index < before.size(); index++) {
            var original = before.get(index); var actual = after.get(index);
            assertEquals(original.text(), actual.text()); assertEquals(original.font(), actual.font());
            // The source advances six glyphs; replacement advances two plus TJ. PDFBox's float
            // arithmetic differs by at most two ULPs in these controls (under 0.00004 pt).
            assertEquals(original.x(), actual.x(), Math.ulp(original.x()) * 2);
            assertEquals(original.y(), actual.y(), Math.ulp(original.y()) * 2);
        }
    }
    private static byte[] contents(PDPage page) throws Exception { try (var input = page.getContents()) { return input.readAllBytes(); } }
    private static byte[] contents(PDFormXObject form) throws Exception { try (var input = form.getContents()) { return input.readAllBytes(); } }
    private static PDFormXObject formAt(PDPage page, String name) throws Exception { return (PDFormXObject) page.getResources().getXObject(name(name)); }
    private static List<PDFormXObject> invocations(PDPage page) throws Exception { return invocations(contents(page), page.getResources()); }
    private static List<PDFormXObject> invocations(PDFormXObject form) throws Exception { return invocations(contents(form), form.getResources()); }
    private static List<PDFormXObject> invocations(byte[] bytes, PDResources resources) throws Exception {
        var parser = new PDFStreamParser(bytes); List<Object> tokens; try { tokens = parser.parse(); } finally { parser.close(); }
        var result = new ArrayList<PDFormXObject>(); Object previous = null;
        for (var token : tokens) { if (token instanceof Operator operator && operator.getName().equals("Do")) { result.add((PDFormXObject) resources.getXObject((COSName) previous)); } previous = token; } return result;
    }
}
