// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for the actual consumer workarounds: reopening ZIPs to inject fonts or prevent
 * image clipping, and promoting inline mathematical header cells into forbidden block cells. */
class DocxOutputContractTest {
    private static final String W = DocxSemanticXml.W;
    private static final String OD = DocxSemanticXml.OD;

    @Test void explicitScriptFamiliesAndCustomRolesWriteStandardFontsAndReadStrictly() throws IOException {
        var adapter = DocxAdapter.builder().defaultFonts(new DocxFontFamilies("Latin Body", "CJK Body"))
                .fontFamily(new DocxFontFamilies("Latin Accent", "CJK Accent"),
                        new DocxAdapter.FontRoles("accent", "accent-bold", "accent-italic", "accent-both")).build();
        var paragraph = new Paragraph("p", List.of(new InlineText("Western 中文 ", InlineStyle.of(12)),
                new InlineText("accent", InlineStyle.of(12).toBuilder().fontRole("accent").build()),
                new InlineText(" bold", InlineStyle.of(12).toBuilder().fontRole("bold").build())), ParagraphFormat.of(12));
        var source = document(List.of(paragraph)); byte[] encoded = write(adapter, source);
        var parts = adapter.unzip(new ByteArrayInputStream(encoded));
        String styles = new String(parts.get("word/styles.xml"), StandardCharsets.UTF_8);
        assertTrue(styles.contains("w:ascii=\"Latin Body\"")); assertTrue(styles.contains("w:eastAsia=\"CJK Body\""));
        assertTrue(styles.contains("w:ascii=\"Latin Accent\""));
        var imported = adapter.read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        assertTrue(imported.diagnostics().isEmpty()); assertEquals(source.blocks(), imported.document().sections().getFirst().blocks());
        assertThrows(IOException.class, () -> new DocxAdapter().read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT));
        var target = adapter.conversionTarget();
        assertTrue(com.mirakyux.export4j.model.processing.DocumentConversion.from(source).prepare(target).preview().issues().isEmpty());
        var root = DocxAdapter.parse(parts.get("word/styles.xml"));
        for (var style : DocxSemanticXml.all(root, W, "style")) {
            if (style.getAttributeNS(W, "styleId").equals("bold")) { DocxAdapter.child(DocxAdapter.child(style, "rPr"), "b").setAttributeNS(W, "w:val", "0"); }
        }
        parts.put("word/styles.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        byte[] changedStyle = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
        assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(changedStyle), DocxAdapter.Policy.STRICT));
    }

    @Test void changingDefaultFamiliesRetainsPriorAdapterSnapshot() throws IOException {
        var builder = DocxAdapter.builder().defaultFonts(new DocxFontFamilies("Old Latin", "Old CJK"));
        var original = builder.build(); var changed = builder.defaultFonts(new DocxFontFamilies("New Latin", "New CJK")).build();
        var source = document(List.of(new Paragraph("p", "text", ParagraphFormat.of(12))));
        String oldStyles = new String(original.unzip(new ByteArrayInputStream(write(original, source))).get("word/styles.xml"), StandardCharsets.UTF_8);
        String newStyles = new String(changed.unzip(new ByteArrayInputStream(write(changed, source))).get("word/styles.xml"), StandardCharsets.UTF_8);
        assertTrue(oldStyles.contains("Old Latin")); assertFalse(oldStyles.contains("New Latin"));
        assertTrue(newStyles.contains("New Latin")); assertFalse(newStyles.contains("Old Latin"));
    }

    @Test void objectSpacingExpandsImagesButRetainsOrdinaryAndLegacyLineBoxes() throws IOException {
        var source = document(List.of(new Paragraph("text", "before", ParagraphFormat.of(12)),
                Paragraph.mixed("image", List.of(image()), ParagraphFormat.of(12))));
        var adapter = DocxAdapter.builder().lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        byte[] encoded = write(adapter, source); var xml = main(adapter, encoded);
        assertEquals("exact", spacing(xml, "text").getAttributeNS(W, "lineRule"));
        assertEquals("atLeast", spacing(xml, "image").getAttributeNS(W, "lineRule"));
        assertEquals("360", spacing(xml, "image").getAttributeNS(W, "line"));
        var restored = adapter.read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT).document().sections().getFirst().blocks();
        for (int index = 0; index < source.blocks().size(); index++) {
            var expected = (Paragraph) source.blocks().get(index); var actual = (Paragraph) restored.get(index);
            assertEquals(expected.id(), actual.id()); assertEquals(expected.format(), actual.format()); assertEquals(expected.content(), actual.content());
        }
        assertEquals("exact", spacing(main(new DocxAdapter(), write(new DocxAdapter(), source)), "image").getAttributeNS(W, "lineRule"));
    }

    @Test void imagesRemainInsideHeadingAndNumberedItemOnStrictRoundTrip() throws IOException {
        var content = List.of(new InlineText("label ", InlineStyle.of(12)), image(), new com.mirakyux.export4j.model.InlineField(
                com.mirakyux.export4j.model.InlineField.Kind.PAGE, "", 24, InlineStyle.of(12)));
        var source = document(List.of(Heading.mixed("h", content, 2, ParagraphFormat.of(12)),
                NumberedItem.mixed("n", "list", content, 0, ParagraphFormat.of(12), 1),
                com.mirakyux.export4j.model.BulletItem.mixed("b", content, "•", 1, ParagraphFormat.of(12))));
        var adapter = DocxAdapter.builder().lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        var imported = adapter.read(new ByteArrayInputStream(write(adapter, source)), DocxAdapter.Policy.STRICT);
        assertEquals(source.blocks(), imported.document().sections().getFirst().blocks()); assertTrue(imported.diagnostics().isEmpty());
    }

    @Test void nativeEquationsUseActualInlineMergedCellAnchorsIncludingRepeatedHeaders() throws IOException {
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(new TableCell("header \ufffc", CellFormat.of(12)).toBuilder().columnSpan(2).build())),
                new TableRow(List.of(new TableCell("left", CellFormat.of(12)), new TableCell("\ufffc right", CellFormat.of(12))))), 1, TableFormat.DEFAULT);
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(table), 0)));
        var first = new DocxMathDocument.Equation("head", new DocxSemanticDocument.Point(DocxSemanticDocument.cellParagraphId("table", 0, 0), 7),
                DocxMathDocument.Placement.INLINE, new DocxMath.Fraction(new DocxMath.Run("a"), new DocxMath.Run("b")));
        var second = new DocxMathDocument.Equation("body", new DocxSemanticDocument.Point(DocxSemanticDocument.cellParagraphId("table", 1, 1), 0),
                DocxMathDocument.Placement.INLINE, new DocxMath.Run("x"));
        var source = new DocxMathDocument(body, List.of(first, second));
        var adapter = DocxAdapter.builder().lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        var bytes = new ByteArrayOutputStream(); adapter.writeMath(source, bytes);
        var xml = main(adapter, bytes.toByteArray()); assertEquals(2, xml.getElementsByTagNameNS(DocxMathXml.M, "oMath").getLength());
        assertEquals("atLeast", spacing(xml, first.anchor().paragraphId()).getAttributeNS(W, "lineRule"));
        var imported = adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT);
        assertEquals(source.equations(), imported.document().equations()); assertTrue(imported.diagnostics().isEmpty());
        var reopened = (Table) imported.document().semantic().body().sections().getFirst().blocks().getFirst();
        assertEquals(1, reopened.headerRows()); assertFalse(reopened.hasBlockCells()); assertEquals(table, reopened);
        // Standard OOXML does not carry Export4j's inline-cell metadata. Temporary math anchors
        // must still retain the ordinary inline branch and repeatable header semantics.
        var standard = adapter.unzip(new ByteArrayInputStream(bytes.toByteArray()));
        var standardXml = DocxAdapter.parse(standard.get("word/document.xml"));
        for (var p : DocxSemanticXml.all(standardXml, W, "p")) { p.removeAttributeNS(OD, "id"); p.removeAttributeNS(OD, "inlineCell"); }
        for (var t : DocxSemanticXml.all(standardXml, W, "tbl")) { t.removeAttributeNS(OD, "id"); }
        standard.put("word/document.xml", DocxSemanticXml.encode(standardXml, DocxLimits.DEFAULT.maxEntryBytes()));
        var external = adapter.readMath(new ByteArrayInputStream(DocxSemanticXml.zip(standard, DocxLimits.DEFAULT)), DocxAdapter.Policy.STRICT).document();
        var externalTable = (Table) external.semantic().body().sections().getFirst().blocks().getFirst();
        assertEquals(2, external.equations().size()); assertEquals(1, externalTable.headerRows()); assertFalse(externalTable.hasBlockCells());
    }

    @Test void forgedInlineCellIdentityCannotChangeItsBranchOrAnchor() throws IOException {
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(new TableCell("value", CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var adapter = new DocxAdapter(); var parts = adapter.unzip(new ByteArrayInputStream(write(adapter, document(List.of(table)))));
        var root = DocxAdapter.parse(parts.get("word/document.xml"));
        var paragraph = (Element) root.getElementsByTagNameNS(W, "p").item(0); paragraph.setAttributeNS(OD, "od:id", "wrong-cell");
        parts.put("word/document.xml", DocxSemanticXml.encode(root, DocxLimits.DEFAULT.maxEntryBytes()));
        byte[] forged = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
        for (var policy : DocxAdapter.Policy.values()) { assertThrows(IOException.class, () -> adapter.read(new ByteArrayInputStream(forged), policy)); }
    }

    @Test void nativeMarkerRetainsSizeColorRoleAndHyperlink() throws IOException {
        var appearance = InlineStyle.of(17).toBuilder().fontRole("bold").rgb(0x2457a4).build();
        var paragraph = new Paragraph("p", List.of(new InlineText("before ", InlineStyle.of(12)),
                new InlineText("\ufffc", appearance, "https://example.org/formula"), new InlineText(" after", InlineStyle.of(12))), ParagraphFormat.of(12));
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(paragraph), 0)));
        var source = new DocxMathDocument(body, List.of(new DocxMathDocument.Equation("eq", new DocxSemanticDocument.Point("p", 7),
                DocxMathDocument.Placement.INLINE, new DocxMath.Fraction(new DocxMath.Run("a"), new DocxMath.Run("b")))));
        var adapter = DocxAdapter.builder().lineSpacing(DocxAdapter.LineSpacing.OBJECTS_AT_LEAST).build();
        var bytes = new ByteArrayOutputStream(); adapter.writeMath(source, bytes);
        var xml = main(adapter, bytes.toByteArray()); var formula = (Element) xml.getElementsByTagNameNS(DocxMathXml.M, "oMath").item(0);
        assertTrue(DocxSemanticXml.is((Element) formula.getParentNode(), "hyperlink"));
        assertEquals("34", ((Element) formula.getElementsByTagNameNS(W, "sz").item(0)).getAttributeNS(W, "val"));
        assertEquals("2457A4", ((Element) formula.getElementsByTagNameNS(W, "color").item(0)).getAttributeNS(W, "val"));
        var imported = adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT);
        assertEquals(source.semantic().body().sections().getFirst().blocks(), imported.document().semantic().body().sections().getFirst().blocks());
        assertEquals(source.equations(), imported.document().equations());
        assertTrue(imported.diagnostics().isEmpty());
        var parts = adapter.unzip(new ByteArrayInputStream(bytes.toByteArray())); formula.removeAttributeNS(OD, "markerSize");
        parts.put("word/document.xml", DocxSemanticXml.encode(xml, DocxLimits.DEFAULT.maxEntryBytes()));
        byte[] malformed = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
        assertThrows(IOException.class, () -> adapter.readMath(new ByteArrayInputStream(malformed), DocxAdapter.Policy.ALLOW_LOSS));
    }

    @Test void unsupportedNativeMarkerDecorationsFailAtTheModelBoundary() {
        for (var style : List.of(InlineStyle.of(12).toBuilder().underline(true).build(),
                InlineStyle.of(12).toBuilder().strikeThrough(true).build(), InlineStyle.of(12).toBuilder().baselineShift(2).build())) {
            var p = new Paragraph("p", List.of(new InlineText("\ufffc", style)), ParagraphFormat.of(12));
            var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(p), 0)));
            assertThrows(IllegalArgumentException.class, () -> new DocxMathDocument(body, List.of(new DocxMathDocument.Equation("eq",
                    new DocxSemanticDocument.Point("p", 0), DocxMathDocument.Placement.INLINE, new DocxMath.Run("x")))));
        }
    }

    @Test void nativeMathWritesEffectiveSizesForBodyHeadingsListsAndPhysicalCells() throws IOException {
        var bodyParagraph = new Paragraph("body", "\ufffc", ParagraphFormat.of(11));
        var heading = Heading.mixed("heading", List.of(new InlineText("\ufffc", InlineStyle.of(16))), 2, ParagraphFormat.of(16));
        var item = NumberedItem.mixed("list", "numbering", List.of(new InlineText("\ufffc", InlineStyle.of(14))), 0, ParagraphFormat.of(14), 1);
        var cell = new TableCell("\ufffc", CellFormat.of(8));
        var table = new Table("table", List.of(new ColumnWidth.Fixed(80)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var inline = new Paragraph("inline", List.of(new InlineText("\ufffc", InlineStyle.of(18))), ParagraphFormat.of(11));
        var blocks = List.<Block>of(bodyParagraph, heading, item, table, inline);
        var sectioned = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, blocks, 0)));
        var anchors = List.of("body", "heading", "list", DocxSemanticDocument.cellParagraphId("table", 0, 0), "inline");
        var equations = new java.util.ArrayList<DocxMathDocument.Equation>();
        for (var anchor : anchors) {
            var expression = new DocxMath.Fraction(new DocxMath.Run("x"),
                    new DocxMath.Superscript(new DocxMath.Run("y"), new DocxMath.Run("2", DocxMath.Style.PLAIN, 9.5, 0)));
            equations.add(new DocxMathDocument.Equation("equation-" + equations.size(),
                    new DocxSemanticDocument.Point(anchor, 0), DocxMathDocument.Placement.INLINE, expression));
        }
        var source = new DocxMathDocument(sectioned, equations);
        var adapter = new DocxAdapter(); var bytes = new ByteArrayOutputStream(); adapter.writeMath(source, bytes);
        var xml = main(adapter, bytes.toByteArray());
        var expectedHalfPoints = List.of("22", "32", "28", "16", "36");
        var formulas = xml.getElementsByTagNameNS(DocxMathXml.M, "oMath");
        assertEquals(expectedHalfPoints.size(), formulas.getLength());
        for (int index = 0; index < formulas.getLength(); index++) {
            var runs = ((Element) formulas.item(index)).getElementsByTagNameNS(DocxMathXml.M, "r");
            assertEquals(3, runs.getLength());
            for (int runIndex = 0; runIndex < runs.getLength(); runIndex++) {
                var properties = DocxAdapter.child((Element) runs.item(runIndex), "rPr");
                assertEquals(W, properties.getNamespaceURI());
                assertEquals(runIndex == 2 ? "19" : expectedHalfPoints.get(index),
                        DocxAdapter.child(properties, "sz").getAttributeNS(W, "val"));
            }
        }
        // The model has no independent structural-control appearance. An external ctrlPr
        // changing that appearance must fail, rather than being silently discarded.
        var fraction = (Element) xml.getElementsByTagNameNS(DocxMathXml.M, "f").item(0);
        var fPr = DocxMathXml.math(fraction, "fPr"); var control = DocxMathXml.math(fraction, "ctrlPr");
        var controlRun = DocxSemanticXml.word(fraction, "rPr");
        controlRun.appendChild(DocxSemanticXml.property(fraction, "sz", "48")); control.appendChild(controlRun); fPr.appendChild(control);
        fraction.insertBefore(fPr, fraction.getFirstChild());
        var parts = adapter.unzip(new ByteArrayInputStream(bytes.toByteArray()));
        parts.put("word/document.xml", DocxSemanticXml.encode(xml, DocxLimits.DEFAULT.maxEntryBytes()));
        byte[] unsupportedControl = DocxSemanticXml.zip(parts, DocxLimits.DEFAULT);
        assertThrows(IOException.class, () -> adapter.readMath(new ByteArrayInputStream(unsupportedControl), DocxAdapter.Policy.STRICT));
        assertEquals(source.equations(), adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document().equations());
    }

    @Test void hostBoldRoleCannotTurnExplicitNormalTextIntoAMathematicalRun() throws IOException {
        var paragraph = new Paragraph("p", List.of(new InlineText("\ufffc", InlineStyle.of(12).toBuilder().fontRole("bold").build())), ParagraphFormat.of(12));
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(paragraph), 0)));
        var source = new DocxMathDocument(body, List.of(new DocxMathDocument.Equation("eq", new DocxSemanticDocument.Point("p", 0),
                DocxMathDocument.Placement.INLINE, new DocxMath.Run("rate", DocxMath.Style.NORMAL_TEXT))));
        assertEquals(DocxMath.Style.NORMAL_TEXT, ((DocxMath.Run) source.equations().getFirst().expression()).style());
        var adapter = new DocxAdapter(); var bytes = new ByteArrayOutputStream(); adapter.writeMath(source, bytes);
        var xml = main(adapter, bytes.toByteArray());
        assertEquals(1, xml.getElementsByTagNameNS(DocxMathXml.M, "nor").getLength());
        assertEquals(source.equations(), adapter.readMath(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT).document().equations());
    }

    @Test void generatedCellIdentityCannotAliasAnAuthoredBlock() {
        String generated = DocxSemanticDocument.cellParagraphId("table", 0, 0);
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(new TableCell("value", CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var source = document(List.of(new Paragraph(generated, "authored", ParagraphFormat.of(12)), table));
        assertThrows(IOException.class, () -> write(new DocxAdapter(), source));
    }

    @Test void contentsUsesMixedHeadingReadingTextAndRejectsUnresolvedFields() throws IOException {
        var heading = Heading.mixed("heading", List.of(new InlineText("Label ", InlineStyle.of(12)), image()), 1, ParagraphFormat.of(12));
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(heading, new Paragraph("toc", "old", ParagraphFormat.of(12))), 0)));
        var toc = new DocxSemanticDocument.Contents("contents", DocxSemanticDocument.Range.of("toc", 0, 3), 1, 3, true, false, true);
        var source = new DocxSemanticDocument(body, List.of(), List.of(), List.of(), List.of(), List.of(toc));
        var updated = source.edit().updateContents("contents", java.util.Map.of()).execute().document();
        assertEquals("Label figure", ((Paragraph) updated.body().sections().getFirst().blocks().get(1)).text());
        var fieldHeading = Heading.mixed("heading", List.of(new com.mirakyux.export4j.model.InlineField(
                com.mirakyux.export4j.model.InlineField.Kind.PAGE, "", 24, InlineStyle.of(12))), 1, ParagraphFormat.of(12));
        var fieldBody = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(fieldHeading, new Paragraph("toc", "old", ParagraphFormat.of(12))), 0)));
        var unresolved = new DocxSemanticDocument(fieldBody, List.of(), List.of(), List.of(), List.of(), List.of(toc));
        assertThrows(IllegalArgumentException.class, () -> unresolved.edit().updateContents("contents", java.util.Map.of()).execute());
    }

    @Test void portableCellProjectionRetainsTextboxesAnchoredAtItsVirtualParagraph() throws IOException {
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)), List.of(new TableRow(List.of(new TableCell("Cell", CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4, List.of(table), 0)));
        var box = new DocxSemanticDocument.TextBox(List.of(new Paragraph("box-content", "Explanation", ParagraphFormat.of(12))),
                160, 72, 2, 2, 2, 2, DocxSemanticDocument.VerticalAlignment.TOP, null, null, 0, DocxSemanticDocument.Transform.NONE);
        var drawing = new DocxSemanticDocument.Drawing("box", new DocxSemanticDocument.Point(DocxSemanticDocument.cellParagraphId("table", 0, 0), 0),
                DocxSemanticDocument.Placement.inline(), box);
        var source = new DocxSemanticDocument(body, List.of(drawing), List.of(), List.of(), List.of(), List.of());
        var policy = com.mirakyux.export4j.model.processing.ProcessingPolicy.builder().defaultRule(
                com.mirakyux.export4j.model.processing.ProcessingPolicy.Action.WARN,
                com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment.DEGRADE).build();
        var result = new DocxAdapter().project(source, policy);
        var cell = ((Table) result.document().sections().getFirst().blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertTrue(cell.hasBlocks()); assertEquals(List.of("Cell", "Explanation"), cell.blocks().stream().map(b -> ((Paragraph) b).text()).toList());
        assertEquals(1, result.report().issues().stream().filter(issue -> issue.feature().equals("TEXTBOX")).count());
    }

    private static Document document(List<? extends Block> blocks) { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.copyOf(blocks)); }
    private static byte[] write(DocxAdapter adapter, Document document) throws IOException { var bytes = new ByteArrayOutputStream(); adapter.write(document, bytes); return bytes.toByteArray(); }
    private static Element main(DocxAdapter adapter, byte[] encoded) throws IOException { return DocxAdapter.parse(adapter.unzip(new ByteArrayInputStream(encoded)).get("word/document.xml")); }
    private static Element spacing(Element root, String id) {
        for (var paragraph : DocxSemanticXml.all(root, W, "p")) { if (paragraph.getAttributeNS(OD, "id").equals(id)) { return DocxAdapter.child(DocxAdapter.child(paragraph, "pPr"), "spacing"); } }
        throw new AssertionError("paragraph missing: " + id);
    }
    private static InlineImage image() throws IOException {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 24, 30, 0, "figure");
    }
}
