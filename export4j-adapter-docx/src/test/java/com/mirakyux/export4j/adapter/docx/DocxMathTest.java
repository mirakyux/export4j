// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.VectorResource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javax.xml.XMLConstants;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocxMathTest {
    private static final DocxAdapter ADAPTER = new DocxAdapter();
    private static final String M = DocxMathXml.M;

    @Test void writesEditableStandardObjectsAndReadsTypedMathematics() throws IOException {
        var source = source(); byte[] encoded = write(source); var root = xml(encoded);
        assertEquals(8, root.getElementsByTagNameNS(M, "oMath").getLength());
        assertEquals(8, root.getElementsByTagNameNS(M, "oMathPara").getLength());
        assertEquals(2, root.getElementsByTagNameNS(M, "m").getLength());
        assertTrue(root.getElementsByTagNameNS(M, "f").getLength() >= 3);
        assertTrue(root.getElementsByTagNameNS(M, "rad").getLength() > 0);
        assertTrue(root.getElementsByTagNameNS(M, "sSubSup").getLength() > 0);
        assertTrue(root.getElementsByTagNameNS(M, "nary").getLength() >= 3);
        assertTrue(root.getElementsByTagNameNS(M, "func").getLength() > 0);
        assertTrue(root.getElementsByTagNameNS(M, "limLow").getLength() > 0);
        assertEquals(0, root.getElementsByTagNameNS(DocxSemanticXml.W, "drawing").getLength());
        var imported = read(encoded);
        assertTrue(imported.diagnostics().isEmpty());
        assertEquals(source.equations(), imported.document().equations());
        assertEquals(source.settings(), imported.document().settings());
        assertEquals(texts(source), texts(imported.document()));
    }

    @Test void standardInputWithoutOwnMetadataSurvivesThreeRoundsAndARealFormulaEdit() throws IOException {
        var original = source(); byte[] encoded = stripMetadata(write(original)); var current = read(encoded).document();
        var expected = original.equations().stream().map(DocxMathDocument.Equation::expression).toList();
        for (int round = 0; round < 3; round++) {
            assertEquals(expected, current.equations().stream().map(DocxMathDocument.Equation::expression).toList());
            assertEquals(texts(original), texts(current));
            assertEquals(original.settings(), current.settings());
            byte[] written = stripMetadata(write(current));
            assertEquals(8, xml(written).getElementsByTagNameNS(M, "oMath").getLength());
            current = read(written).document();
        }
        var replacement = new DocxMath.Fraction(run("p"), run("q"));
        var changed = current.withEquation(current.equations().getFirst().id(), replacement);
        var reopened = read(stripMetadata(write(changed))).document();
        var resolvedReplacement = new DocxMath.Fraction(
                new DocxMath.Run("p", DocxMath.Style.ITALIC, 18, 0),
                new DocxMath.Run("q", DocxMath.Style.ITALIC, 18, 0));
        assertEquals(resolvedReplacement, changed.equations().getFirst().expression());
        assertEquals(resolvedReplacement, reopened.equations().getFirst().expression());
        assertEquals(expected.subList(1, 8), reopened.equations().subList(1, 8).stream().map(DocxMathDocument.Equation::expression).toList());
        assertEquals(expected.getFirst(), current.equations().getFirst().expression());
        assertEquals(texts(current), texts(reopened));
    }

    @Test void inlineEquationsKeepTheirUtf16PositionsAmongRealText() throws IOException {
        var paragraph = new Paragraph("inline", "A😀 \ufffc B \ufffc C", ParagraphFormat.of(18));
        var source = document(List.of(paragraph), List.of(
                equation("one", "inline", 4, DocxMathDocument.Placement.INLINE, new DocxMath.Superscript(run("x"), run("2"))),
                equation("two", "inline", 8, DocxMathDocument.Placement.INLINE, new DocxMath.Fraction(run("a"), run("b")))));
        var imported = read(write(source)).document();
        assertEquals(source.equations(), imported.equations());
        assertEquals(texts(source), texts(imported));
        assertEquals(0, xml(write(source)).getElementsByTagNameNS(M, "oMathPara").getLength());
        assertThrows(IllegalArgumentException.class, () -> document(List.of(paragraph), List.of(
                equation("bad", "inline", 4, DocxMathDocument.Placement.DISPLAY_CENTER, run("x")))));
    }

    @Test void bodyCellEquationsRetainTheirAnchorsWithoutPromotingOrdinaryRepeatedHeaders() throws IOException {
        var paragraph = new Paragraph("cell-math", "A \ufffc B", ParagraphFormat.of(12));
        var source = document(List.of(headerTable(TableCell.builder().blocks(List.of(paragraph)).build())),
                List.of(equation("cell-equation", paragraph.id(), 2, DocxMathDocument.Placement.INLINE,
                        new DocxMath.Fraction(run("a"), run("b")))));
        byte[] encoded = write(source);
        for (var input : List.of(encoded, stripMetadata(encoded))) {
            var current = read(input).document();
            for (int round = 0; round < 3; round++) {
                var table = (Table) current.semantic().body().sections().getFirst().blocks().getFirst();
                assertEquals(1, table.headerRows());
                assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
                assertEquals("Header", table.rows().getFirst().cells().getFirst().text());
                var bodyCell = table.rows().get(1).cells().getFirst();
                if (input == encoded) { assertTrue(bodyCell.hasBlocks()); }
                var retained = bodyCell.hasBlocks() ? (Paragraph) bodyCell.blocks().getFirst()
                        : DocxSemanticSupport.cellParagraph(table, table.grid().anchorRows().get(1).getFirst());
                assertEquals(paragraph.text(), retained.text());
                var actual = current.equations().getFirst();
                assertEquals(retained.id(), actual.anchor().paragraphId());
                assertEquals(2, actual.anchor().utf16());
                assertEquals(source.equations().getFirst().expression(), actual.expression());
                var restored = read(write(current)).document();
                assertEquals(current.equations(), restored.equations());
                current = restored;
            }
        }
    }

    @Test void plainHeaderTableSemanticRoundtripRetainsInlineCellsAndItsTableReference() throws IOException {
        var table = headerTable(new TableCell("Body", CellFormat.of(12)));
        var references = Paragraph.mixed("refs", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE,
                table.id(), 24, InlineStyle.of(12))), ParagraphFormat.of(12));
        var source = new DocxSemanticDocument(document(List.of(table, references), List.of()).semantic().body());
        for (int round = 0; round < 3; round++) {
            var encoded = new ByteArrayOutputStream();
            ADAPTER.writeSemantic(source, encoded);
            var result = ADAPTER.readSemantic(new ByteArrayInputStream(encoded.toByteArray()), DocxAdapter.Policy.STRICT);
            assertTrue(result.diagnostics().isEmpty());
            var retained = (Table) result.document().body().sections().getFirst().blocks().getFirst();
            assertEquals(table.id(), retained.id());
            assertEquals(1, retained.headerRows());
            assertFalse(retained.hasBlockCells());
            assertEquals("Header", retained.rows().getFirst().cells().getFirst().text());
            assertEquals("Body", retained.rows().get(1).cells().getFirst().text());
            var reference = (Paragraph) result.document().body().sections().getFirst().blocks().get(1);
            assertEquals(retained.id(), reference.fields().getFirst().field().targetId());
            source = result.document();
        }
    }

    @Test void genuineBlockHeaderRemainsRejectedForWriteAndReadUnderEveryPolicy() throws IOException {
        var header = TableCell.builder().blocks(List.of(new Paragraph("header-block", "Header", ParagraphFormat.of(12)))).build();
        var source = new Table("table", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(header)), new TableRow(List.of(new TableCell("Body", CellFormat.of(12))))),
                1, TableFormat.DEFAULT);
        var output = new ByteArrayOutputStream();
        var failure = assertThrows(IOException.class, () -> ADAPTER.writeSemantic(
                new DocxSemanticDocument(document(List.of(source), List.of()).semantic().body()), output));
        assertTrue(failure.getMessage().contains("repeated parent table headers"));
        assertEquals(0, output.size());
        byte[] encoded = write(document(List.of(headerTable(new TableCell("Body", CellFormat.of(12)))), List.of()));
        byte[] malformed = mutate(encoded, root -> {
            var cell = (Element) root.getElementsByTagNameNS(DocxSemanticXml.W, "tc").item(0);
            cell.setAttributeNS(DocxSemanticXml.OD, "od:content", "blocks");
            DocxAdapter.child(cell, "p").removeAttributeNS(DocxSemanticXml.OD, "inlineCell");
            DocxAdapter.child(cell, "p").setAttributeNS(DocxSemanticXml.OD, "od:id", "genuine-header");
        });
        for (var policy : DocxAdapter.Policy.values()) {
            var rejected = assertThrows(IOException.class,
                    () -> ADAPTER.readSemantic(new ByteArrayInputStream(malformed), policy));
            assertTrue(rejected.getMessage().contains("repeated parent table headers"));
        }
    }

    @Test void metadataFreeNativeRevisionKeepsTheGeneratedBodyCellParagraphAnchor() throws IOException {
        var paragraph = new Paragraph("cell-revision", "AB", ParagraphFormat.of(12));
        var body = document(List.of(headerTable(TableCell.builder().blocks(List.of(paragraph)).build())), List.of()).semantic().body();
        var revision = new DocxSemanticDocument.Revision("inserted", DocxSemanticDocument.RevisionKind.INSERT,
                DocxSemanticDocument.Range.of(paragraph.id(), 0, 1), "Author", null, "", null, null);
        var semantic = new DocxSemanticDocument(body, List.of(), List.of(), List.of(revision), List.of(), List.of());
        var output = new ByteArrayOutputStream();
        ADAPTER.writeSemantic(semantic, output);
        var result = ADAPTER.readSemantic(new ByteArrayInputStream(stripMetadata(output.toByteArray())),
                DocxAdapter.Policy.STRICT).document();
        var table = (Table) result.body().sections().getFirst().blocks().getFirst();
        assertFalse(table.rows().getFirst().cells().getFirst().hasBlocks());
        var bodyCell = table.rows().get(1).cells().getFirst();
        assertTrue(bodyCell.hasBlocks());
        var retained = (Paragraph) bodyCell.blocks().getFirst();
        assertEquals("AB", retained.text());
        assertEquals(DocxSemanticDocument.Range.of(retained.id(), 0, 1), result.revisions().getFirst().range());
    }

    @Test void nativeEquationsCannotClaimVectorMarkersAndWriteRejectsTheirGeometry() {
        var resource = VectorResource.of(10, 8, 2, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -8.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(10.0, 2.0))));
        var vector = new InlineVector(resource, "VECTOR");
        var paragraph = Paragraph.mixed("vector-and-math", List.of(vector,
                new InlineText(" \ufffc", InlineStyle.of(18))), ParagraphFormat.of(18));
        var collision = assertThrows(IllegalArgumentException.class, () -> document(List.of(paragraph), List.of(
                equation("collision", paragraph.id(), 0, DocxMathDocument.Placement.INLINE, run("x")))));
        assertEquals("equation must own a unique ordinary object marker", collision.getMessage());
        var source = document(List.of(paragraph), List.of(
                equation("actual-math", paragraph.id(), 2, DocxMathDocument.Placement.INLINE, run("x"))));
        var output = new ByteArrayOutputStream();
        var failure = assertThrows(IOException.class, () -> ADAPTER.writeMath(source, output));
        assertTrue(failure.getMessage().contains("inline vectors require an explicit conversion loss policy"));
        assertEquals(0, output.size());
        assertEquals(vector, ((Paragraph) source.semantic().body().sections().getFirst().blocks().getFirst())
                .vectors().getFirst().vector());
    }

    @Test void nativeSettingsRunStylesAndNthRootsRemainTyped() throws IOException {
        var defaults = DocxMathSettings.DEFAULT;
        var settings = new DocxMathSettings("Cambria Math", DocxMathSettings.BinaryBreak.AFTER,
                DocxMathSettings.BinarySubtraction.PLUS_MINUS, true, false, 20, 40,
                DocxMathSettings.Justification.RIGHT, 10, 20, 30, 40, 720, true,
                DocxMath.LimitLocation.UNDER_OVER, DocxMath.LimitLocation.SUB_SUP);
        var source = document(List.of(new Paragraph("p", "\ufffc", ParagraphFormat.of(18))), List.of(
                equation("styled", "p", 0, DocxMathDocument.Placement.DISPLAY_RIGHT, DocxMath.sequence(
                        new DocxMath.Run("normal text", DocxMath.Style.NORMAL_TEXT, 14.5, 0x284460),
                        new DocxMath.Radical(run("x"), Optional.of(run("3"))), new DocxMath.UpperLimit(run("lim"), run("x"))))));
        source = new DocxMathDocument(source.semantic(), source.equations(), settings);
        var imported = read(stripMetadata(write(source))).document();
        assertEquals(settings, imported.settings());
        assertEquals(source.equations().getFirst().expression(), imported.equations().getFirst().expression());
        assertFalse(defaults.equals(settings));
    }

    @Test void wordControlFontOnlyNormalizesTheExplicitMathDefault() throws IOException {
        byte[] encoded = mutate(write(source()), root -> {
            var fraction = (Element) root.getElementsByTagNameNS(M, "f").item(0);
            var properties = DocxMathXml.math(fraction, "fPr");
            var control = DocxMathXml.math(fraction, "ctrlPr");
            var run = DocxSemanticXml.word(fraction, "rPr");
            var font = DocxSemanticXml.word(fraction, "rFonts");
            font.setAttributeNS(DocxSemanticXml.W, "w:ascii", "Cambria Math");
            font.setAttributeNS(DocxSemanticXml.W, "w:hAnsi", "Cambria Math");
            run.appendChild(font); control.appendChild(run); properties.appendChild(control);
            fraction.insertBefore(properties, fraction.getFirstChild());
        });
        assertEquals(source().equations(), read(encoded).document().equations());
        var invalid = List.<Consumer<Element>>of(
                root -> ((Element) root.getElementsByTagNameNS(DocxSemanticXml.W, "rFonts").item(0)).setAttributeNS(DocxSemanticXml.W, "w:ascii", "Other Math"),
                root -> { var control = (Element) root.getElementsByTagNameNS(M, "ctrlPr").item(0); control.getFirstChild().appendChild(DocxSemanticXml.word(control, "b")); });
        for (var mutation : invalid) {
            byte[] changed = mutate(encoded, mutation);
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(changed), policy)); }
        }
    }

    @Test void omittedStandardDelimiterGrowRemainsEnabled() throws IOException {
        byte[] encoded = mutate(write(source()), root -> {
            var properties = root.getElementsByTagNameNS(M, "dPr");
            for (int index = 0; index < properties.getLength(); index++) {
                var parent = (Element) properties.item(index);
                for (var child : DocxAdapter.children(parent)) { if (DocxMathXml.is(child, "grow")) { parent.removeChild(child); } }
            }
        });
        assertEquals(source().equations(), read(encoded).document().equations());
    }

    @Test void complexFieldCacheBeforeInlineMathDoesNotShiftTheUtf16Anchor() throws IOException {
        var style = InlineStyle.of(18);
        var paragraph = Paragraph.mixed("inline-field", List.of(new InlineText("A", style),
                new InlineField(InlineField.Kind.PAGE, "", 33, style), new InlineText(" B\ufffc", style)), ParagraphFormat.of(18));
        var source = document(List.of(paragraph), List.of(equation("formula", paragraph.id(), 4,
                DocxMathDocument.Placement.INLINE, new DocxMath.Fraction(run("a"), run("b")))));
        byte[] encoded = mutate(write(source), root -> {
            var field = (Element) root.getElementsByTagNameNS(DocxSemanticXml.W, "fldSimple").item(0);
            var parent = field.getParentNode();
            for (String kind : List.of("begin", "separate", "end")) {
                if (kind.equals("separate")) {
                    var run = DocxSemanticXml.word(field, "r"); var instruction = DocxSemanticXml.word(field, "instrText");
                    instruction.setTextContent(" PAGE "); run.appendChild(instruction); parent.insertBefore(run, field);
                }
                var run = DocxSemanticXml.word(field, "r"); var control = DocxSemanticXml.word(field, "fldChar");
                control.setAttributeNS(DocxSemanticXml.W, "w:fldCharType", kind);
                if (kind.equals("begin")) { control.setAttributeNS(DocxSemanticXml.OD, "od:width", "33"); }
                run.appendChild(control); parent.insertBefore(run, field);
                if (kind.equals("separate")) { parent.insertBefore(DocxSemanticXml.textRun(field, "999"), field); }
            }
            parent.removeChild(field);
        });
        var imported = read(encoded).document();
        assertEquals(source.equations(), imported.equations());
        assertEquals(texts(source), texts(imported));
        assertEquals(1, ((Paragraph) imported.semantic().body().sections().getFirst().blocks().getFirst()).fields().getFirst().sourceIndex());
    }

    @Test void existingImportEntrypointsCannotSilentlyDiscardEquations() throws IOException {
        byte[] encoded = write(source());
        for (var policy : DocxAdapter.Policy.values()) {
            assertThrows(IOException.class, () -> ADAPTER.read(new ByteArrayInputStream(encoded), policy));
            assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(encoded), policy));
        }
    }

    @Test void ordinaryReadersRejectMathBeforeStyleMaterializationAndInsideSettings() throws IOException {
        byte[] ordinary = write(document(List.of(new Paragraph("ordinary", "Ordinary text", ParagraphFormat.of(12))), List.of()));
        assertTrue(ADAPTER.read(new ByteArrayInputStream(ordinary), DocxAdapter.Policy.STRICT).diagnostics().isEmpty());
        assertTrue(ADAPTER.readSemantic(new ByteArrayInputStream(ordinary), DocxAdapter.Policy.STRICT).diagnostics().isEmpty());
        for (String part : List.of("word/styles.xml", "word/settings.xml")) {
            for (String name : List.of("oMath", "box")) {
                var parts = ADAPTER.unzip(new ByteArrayInputStream(ordinary)); var root = DocxAdapter.parse(parts.get(part));
                root.appendChild(DocxMathXml.math(root, name));
                parts.put(part, DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
                byte[] changed = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
                for (var policy : DocxAdapter.Policy.values()) {
                    assertThrows(IOException.class, () -> ADAPTER.read(new ByteArrayInputStream(changed), policy));
                    assertThrows(IOException.class, () -> ADAPTER.readSemantic(new ByteArrayInputStream(changed), policy));
                }
            }
        }
    }

    @Test void unknownMalformedAndHiddenContentFailUnderEveryPolicy() throws IOException {
        byte[] encoded = write(source());
        var mutations = List.<Consumer<Element>>of(
                root -> { var first = (Element) root.getElementsByTagNameNS(M, "oMath").item(0); first.appendChild(DocxMathXml.math(first, "box")); },
                root -> ((Element) root.getElementsByTagNameNS(M, "r").item(0)).setAttribute("unknown", "true"),
                root -> { var first = (Element) root.getElementsByTagNameNS(M, "f").item(0); var pr = DocxMathXml.math(first, "fPr"); pr.appendChild(DocxMathXml.property(first, "type", "lin")); first.insertBefore(pr, first.getFirstChild()); },
                root -> { var first = (Element) root.getElementsByTagNameNS(M, "naryPr").item(0); for (var e : DocxAdapter.children(first)) { if (DocxMathXml.is(e, "subHide")) { e.setAttributeNS(M, "m:val", "1"); } } },
                root -> { var first = (Element) root.getElementsByTagNameNS(M, "mr").item(0); first.removeChild(first.getLastChild()); });
        for (var mutation : mutations) {
            byte[] changed = mutate(encoded, mutation);
            for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(changed), policy)); }
        }
    }

    @Test void absentRootDegreesAndNaryLimitsUseTheSameModelBudgetInBothDirections() throws IOException {
        var source = document(List.of(new Paragraph("root", "\ufffc", ParagraphFormat.of(12)),
                new Paragraph("sum", "\ufffc", ParagraphFormat.of(12))), List.of(
                equation("root-math", "root", 0, DocxMathDocument.Placement.DISPLAY_CENTER,
                        new DocxMath.Radical(run("x"))),
                equation("sum-math", "sum", 0, DocxMathDocument.Placement.DISPLAY_CENTER,
                        new DocxMath.Nary(DocxMath.Operator.SUM, Optional.empty(), Optional.empty(),
                                run("x"), DocxMath.LimitLocation.UNDER_OVER, true))));
        // Two roots plus two runs: hidden deg/sub/sup containers are not model nodes.
        var exact = new DocxMathLimits(2, 4, 2, 1, 2);
        var output = new ByteArrayOutputStream(); ADAPTER.writeMath(source, output, exact);
        byte[] encoded = output.toByteArray();
        for (var policy : DocxAdapter.Policy.values()) {
            assertEquals(source.equations(), ADAPTER.readMath(new ByteArrayInputStream(encoded), policy, exact).document().equations());
        }
        for (var tooSmall : List.of(new DocxMathLimits(2, 3, 2, 1, 2),
                new DocxMathLimits(2, 4, 1, 1, 2))) {
            var untouched = new ByteArrayOutputStream(); untouched.write(7);
            assertThrows(IOException.class, () -> ADAPTER.writeMath(source, untouched, tooSmall));
            assertEquals(List.of((byte) 7), bytes(untouched));
            for (var policy : DocxAdapter.Policy.values()) {
                assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(encoded), policy, tooSmall));
            }
        }
    }

    @Test void visibleEmptyDegreesStillConsumeAModelNode() throws IOException {
        var source = document(List.of(new Paragraph("p", "\ufffc", ParagraphFormat.of(12))), List.of(
                equation("root", "p", 0, DocxMathDocument.Placement.INLINE,
                        new DocxMath.Radical(run("x"), Optional.of(new DocxMath.Sequence(List.of()))))));
        var exact = new DocxMathLimits(1, 3, 2, 1, 1);
        var output = new ByteArrayOutputStream(); ADAPTER.writeMath(source, output, exact);
        assertEquals(source.equations(), ADAPTER.readMath(new ByteArrayInputStream(output.toByteArray()),
                DocxAdapter.Policy.STRICT, exact).document().equations());
        var tooSmall = new DocxMathLimits(1, 2, 2, 1, 1);
        assertThrows(IOException.class, () -> ADAPTER.writeMath(source, new ByteArrayOutputStream(), tooSmall));
        assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(output.toByteArray()),
                DocxAdapter.Policy.STRICT, tooSmall));
    }

    @Test void hiddenOptionalArgumentsStillRejectContentAndMalformedContainers() throws IOException {
        var expressions = List.of(new DocxMath.Radical(run("x")),
                new DocxMath.Nary(DocxMath.Operator.SUM, Optional.empty(), Optional.empty(),
                        run("x"), DocxMath.LimitLocation.UNDER_OVER, true));
        for (var expression : expressions) {
            var source = document(List.of(new Paragraph("p", "\ufffc", ParagraphFormat.of(12))), List.of(
                    equation("math", "p", 0, DocxMathDocument.Placement.INLINE, expression)));
            byte[] encoded = write(source);
            var argumentNames = expression instanceof DocxMath.Radical ? List.of("deg") : List.of("sub", "sup");
            for (var argumentName : argumentNames) {
                var mutations = List.<Consumer<Element>>of(
                        argument -> DocxMathXml.append(argument, run("hidden")),
                        argument -> argument.setTextContent("hidden"),
                        argument -> argument.setAttribute("unknown", "true"),
                        argument -> argument.appendChild(DocxMathXml.math(argument, "unknown")));
                for (var mutation : mutations) {
                    byte[] changed = mutate(encoded, root -> mutation.accept((Element) root.getElementsByTagNameNS(M, argumentName).item(0)));
                    for (var policy : DocxAdapter.Policy.values()) {
                        assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(changed), policy));
                    }
                }
            }
        }
    }

    @Test void cumulativeMathAndSharedSemanticBudgetsApplyBeforeOutput() throws IOException {
        var source = source(); byte[] encoded = write(source());
        for (var limits : List.of(new DocxMathLimits(7, 100_000, 32, 100_000, 4_000_000),
                new DocxMathLimits(8, 2, 32, 100_000, 4_000_000), new DocxMathLimits(8, 100_000, 2, 100_000, 4_000_000),
                new DocxMathLimits(8, 100_000, 32, 3, 4_000_000), new DocxMathLimits(8, 100_000, 32, 100_000, 2))) {
            var target = new ByteArrayOutputStream(); target.write(7);
            assertThrows(IOException.class, () -> ADAPTER.writeMath(source, target, limits)); assertEquals(List.of((byte) 7), bytes(target));
            assertThrows(IOException.class, () -> ADAPTER.readMath(new ByteArrayInputStream(encoded), DocxAdapter.Policy.ALLOW_LOSS, limits));
        }
        var tiny = document(List.of(new Paragraph("p", "\ufffc", ParagraphFormat.of(12))),
                List.of(equation("math", "p", 0, DocxMathDocument.Placement.DISPLAY_CENTER, run("ab"))));
        var sharedText = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxSourceCodePoints(2).build());
        assertThrows(IOException.class, () -> sharedText.writeMath(tiny, new ByteArrayOutputStream()));
        var sharedNodes = new DocxAdapter(DocxLimits.DEFAULT.toBuilder().maxBlocks(1).build());
        assertThrows(IOException.class, () -> sharedNodes.writeMath(tiny, new ByteArrayOutputStream()));
    }

    @Test void invalidAnchorsAndDeepModelsAreRejectedAndListsAreImmutable() {
        var source = source(); assertThrows(UnsupportedOperationException.class, () -> source.equations().clear());
        assertThrows(IllegalArgumentException.class, () -> source.withEquation("missing", run("x")));
        assertThrows(IllegalArgumentException.class, () -> document(List.of(new Paragraph("p", "A", ParagraphFormat.of(12))),
                List.of(equation("bad", "p", 0, DocxMathDocument.Placement.INLINE, run("x")))));
        assertThrows(IllegalArgumentException.class, () -> new DocxMath.Matrix(List.of(List.of(run("a"))), List.of(DocxMath.ColumnAlignment.LEFT, DocxMath.ColumnAlignment.CENTER), DocxMath.BaseAlignment.CENTER));
        DocxMath deep = run("x"); for (int index = 0; index < 40; index++) { deep = new DocxMath.Superscript(deep, run("2")); }
        DocxMath finalDeep = deep;
        assertThrows(IllegalArgumentException.class, () -> document(List.of(new Paragraph("p", "\ufffc", ParagraphFormat.of(12))),
                List.of(equation("deep", "p", 0, DocxMathDocument.Placement.INLINE, finalDeep))));
    }

    @Test void borrowsStreamsAndObservesCancellationWithoutWriting() throws IOException {
        var source = source(); var target = new BorrowedOutput(); ADAPTER.writeMath(source, target); assertFalse(target.closed);
        var input = new BorrowedInput(target.toByteArray()); ADAPTER.readMath(input, DocxAdapter.Policy.STRICT); assertFalse(input.closed);
        var cancelled = new ByteArrayOutputStream();
        assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(),
                () -> { ADAPTER.writeMath(source, cancelled); return null; })); assertEquals(0, cancelled.size());
    }

    private static DocxMathDocument source() {
        var matrix = new DocxMath.Matrix(List.of(List.of(run("a"), run("b")), List.of(run("c"), run("d"))),
                List.of(DocxMath.ColumnAlignment.CENTER, DocxMath.ColumnAlignment.CENTER), DocxMath.BaseAlignment.CENTER);
        var cases = new DocxMath.Matrix(List.of(List.of(new DocxMath.Superscript(run("x"), run("2")), plain("x ≥ 0")), List.of(run("−x"), plain("x < 0"))),
                List.of(DocxMath.ColumnAlignment.LEFT, DocxMath.ColumnAlignment.LEFT), DocxMath.BaseAlignment.CENTER);
        var expressions = List.of(
                DocxMath.sequence(plain("x = "), new DocxMath.Fraction(DocxMath.sequence(run("−b"), plain(" ± "), new DocxMath.Radical(DocxMath.sequence(new DocxMath.Superscript(run("b"), run("2")), plain("−4ac")))), run("2a"))),
                DocxMath.sequence(new DocxMath.SubSuperscript(run("x"), run("1"), run("2")), plain(" + "), new DocxMath.Superscript(new DocxMath.Subscript(run("x"), run("2")), run("2")), plain(" = "), new DocxMath.Superscript(run("r"), run("2"))),
                DocxMath.sequence(new DocxMath.Nary(DocxMath.Operator.INTEGRAL, Optional.of(run("0")), Optional.of(run("1")), DocxMath.sequence(new DocxMath.Superscript(run("x"), run("2")), plain(" dx")), DocxMath.LimitLocation.SUB_SUP, true), plain(" = "), new DocxMath.Fraction(run("1"), run("3"))),
                DocxMath.sequence(new DocxMath.Nary(DocxMath.Operator.SUM, Optional.of(plain("i=1")), Optional.of(run("n")), run("i"), DocxMath.LimitLocation.UNDER_OVER, true), plain(" = "), new DocxMath.Fraction(run("n(n+1)"), run("2"))),
                DocxMath.sequence(plain("A = "), new DocxMath.Delimiter("(", ")", matrix)),
                DocxMath.sequence(plain("f(x) = "), new DocxMath.Delimiter("{", "", cases)),
                DocxMath.sequence(new DocxMath.Function(new DocxMath.Superscript(plain("sin"), run("2")), run("θ")), plain(" + "), new DocxMath.Function(new DocxMath.Superscript(plain("cos"), run("2")), run("θ")), plain(" = 1    "), run("α + β = γ")),
                DocxMath.sequence(new DocxMath.LowerLimit(plain("lim"), plain("n→∞")), new DocxMath.Fraction(run("1"), run("n")), plain(" = 0    "), new DocxMath.Nary(DocxMath.Operator.PRODUCT, Optional.of(plain("i=1")), Optional.of(run("3")), run("i"), DocxMath.LimitLocation.UNDER_OVER, true), plain(" = 6")));
        var paragraphs = new ArrayList<Block>(); var equations = new ArrayList<DocxMathDocument.Equation>();
        for (int index = 0; index < expressions.size(); index++) {
            String id = "equation-" + index; paragraphs.add(new Paragraph("label-" + index, "MATH-" + index, ParagraphFormat.of(10)));
            paragraphs.add(new Paragraph(id, "\ufffc", ParagraphFormat.of(18)));
            equations.add(equation(id, id, 0, DocxMathDocument.Placement.DISPLAY_CENTER, expressions.get(index)));
        }
        return document(paragraphs, equations);
    }
    private static DocxMath run(String value) { return new DocxMath.Run(value); }
    private static Table headerTable(TableCell body) {
        return new Table("table", List.of(new ColumnWidth.Fixed(200)),
                List.of(new TableRow(List.of(new TableCell("Header", CellFormat.of(12)))), new TableRow(List.of(body))),
                1, TableFormat.DEFAULT);
    }
    private static DocxMath plain(String value) { return new DocxMath.Run(value, DocxMath.Style.PLAIN); }
    private static DocxMathDocument.Equation equation(String id, String paragraph, int position, DocxMathDocument.Placement placement, DocxMath value) { return new DocxMathDocument.Equation(id, new DocxSemanticDocument.Point(paragraph, position), placement, value); }
    private static DocxMathDocument document(List<? extends Block> blocks, List<DocxMathDocument.Equation> equations) { return new DocxMathDocument(new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("main", PageSettings.A4, List.copyOf(blocks), 0))), equations); }
    private static byte[] write(DocxMathDocument document) throws IOException { var output = new ByteArrayOutputStream(); ADAPTER.writeMath(document, output); return output.toByteArray(); }
    private static DocxAdapter.MathReadResult read(byte[] bytes) throws IOException { return ADAPTER.readMath(new ByteArrayInputStream(bytes), DocxAdapter.Policy.STRICT); }
    private static Element xml(byte[] bytes) throws IOException { return DocxAdapter.parse(ADAPTER.unzip(new ByteArrayInputStream(bytes)).get("word/document.xml")); }
    private static List<String> texts(DocxMathDocument document) { return document.semantic().body().sections().getFirst().blocks().stream().map(block -> ((Paragraph) block).text()).toList(); }
    private static List<Byte> bytes(ByteArrayOutputStream output) { var values = new ArrayList<Byte>(); for (byte value : output.toByteArray()) { values.add(value); } return values; }
    private static byte[] mutate(byte[] encoded, Consumer<Element> action) throws IOException {
        var parts = ADAPTER.unzip(new ByteArrayInputStream(encoded)); var root = DocxAdapter.parse(parts.get("word/document.xml")); action.accept(root); parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes())); return DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
    }
    private static byte[] stripMetadata(byte[] encoded) throws IOException {
        var parts = ADAPTER.unzip(new ByteArrayInputStream(encoded));
        for (var entry : parts.entrySet()) {
            if (!entry.getKey().endsWith(".xml") && !entry.getKey().endsWith(".rels")) { continue; }
            var root = DocxAdapter.parse(entry.getValue()); var all = new ArrayList<Element>(); all.add(root);
            var descendants = root.getElementsByTagName("*"); for (int index = 0; index < descendants.getLength(); index++) { all.add((Element) descendants.item(index)); }
            for (var element : all) {
                var attributes = element.getAttributes();
                for (int index = attributes.getLength() - 1; index >= 0; index--) {
                    var attribute = attributes.item(index);
                    if (DocxSemanticXml.OD.equals(attribute.getNamespaceURI()) || XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI()) && DocxSemanticXml.OD.equals(attribute.getNodeValue())) { element.removeAttributeNode((org.w3c.dom.Attr) attribute); }
                }
                String mc = "http://schemas.openxmlformats.org/markup-compatibility/2006";
                if (element.hasAttributeNS(mc, "Ignorable")) { String value = String.join(" ", List.of(element.getAttributeNS(mc, "Ignorable").split("\\s+")).stream().filter(prefix -> !prefix.equals("od")).toList()); if (value.isEmpty()) { element.removeAttributeNS(mc, "Ignorable"); } else { element.setAttributeNS(mc, "mc:Ignorable", value); } }
            }
            entry.setValue(DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        }
        return DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
    }
    private static final class BorrowedOutput extends ByteArrayOutputStream { private boolean closed; @Override public void close() { closed = true; } }
    private static final class BorrowedInput extends ByteArrayInputStream { private boolean closed; private BorrowedInput(byte[] value) { super(value); } @Override public void close() { closed = true; } }
}
