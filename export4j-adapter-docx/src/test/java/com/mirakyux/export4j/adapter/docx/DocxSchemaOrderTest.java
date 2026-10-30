// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class DocxSchemaOrderTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    @Test void propertySequencesConformToOoxmlInsteadOfRelyingOnReaderRepair() throws Exception {
        var format = ParagraphFormat.of(12).toBuilder().keepTogether(true)
                .pagination(new PaginationPolicy(true, true, 1, 1)).lineBreakPolicy(LineBreakPolicy.CJK_STRICT).build();
        var style = new InlineStyle("bold", 12, 0x224488, 1, true, true);
        var item = NumberedItem.rich("item", "list", List.of(new InlineText("Styled item", style)), 1, format, 1);
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(new TableCell("Header", CellFormat.of(12)))),
                        new TableRow(List.of(new TableCell("Body", CellFormat.of(12))))), 1, TableFormat.DEFAULT);
        var field = Paragraph.mixed("field", List.of(new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(12))), format);
        var regions = new PageRegions(PageRegion.EMPTY, PageRegion.EMPTY,
                Map.of(PageRegions.Variant.EVEN, PageRegions.Bands.EMPTY));
        var source = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("section", PageSettings.A4, List.of(item, table, field), 1, regions)));
        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        var main = part(output.toByteArray(), "word/document.xml");
        assertEquals(List.of("keepNext", "keepLines", "pageBreakBefore", "numPr", "kinsoku", "spacing", "ind", "jc", "rPr"),
                children((Element) main.getElementsByTagNameNS(W, "pPr").item(0)));
        assertEquals(List.of("rStyle", "strike", "color", "position", "sz", "u"),
                children((Element) main.getElementsByTagNameNS(W, "rPr").item(1)));
        assertEquals(List.of("tblW", "tblBorders", "tblLayout", "tblCellMar"), children((Element) main.getElementsByTagNameNS(W, "tblPr").item(0)));
        assertEquals(List.of("top", "left", "bottom", "right"), children((Element) main.getElementsByTagNameNS(W, "tblCellMar").item(0)));
        assertEquals(List.of("cantSplit", "tblHeader"), children((Element) main.getElementsByTagNameNS(W, "trPr").item(0)));
        assertEquals(List.of("evenAndOddHeaders", "updateFields", "compat"), children(part(output.toByteArray(), "word/settings.xml")));
    }

    @Test void freshPlainAndNativeDocumentsDeclareModernWordLayoutWithoutFieldsOrHeaders() throws Exception {
        var source = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("section", PageSettings.A4,
                        List.of(new Paragraph("body", "Modern Word layout", ParagraphFormat.of(12))), 1)));
        var adapter = new DocxAdapter();
        for (boolean nativeSemantics : List.of(false, true)) {
            var output = new ByteArrayOutputStream();
            if (nativeSemantics) {
                adapter.writeSemantic(new DocxSemanticDocument(source), output);
            } else {
                adapter.write(source, output);
            }
            byte[] bytes = output.toByteArray();
            var settings = part(bytes, "word/settings.xml");
            assertEquals(List.of("compat"), children(settings));
            var declarations = settings.getElementsByTagNameNS(W, "compatSetting");
            assertEquals(1, declarations.getLength());
            var mode = (Element) declarations.item(0);
            assertEquals("compatibilityMode", mode.getAttributeNS(W, "name"));
            assertEquals("http://schemas.microsoft.com/office/word", mode.getAttributeNS(W, "uri"));
            assertEquals("15", mode.getAttributeNS(W, "val"));
            var relationships = part(bytes, "word/_rels/document.xml.rels");
            var settingsRelationships = childrenElements(relationships).stream()
                    .filter(e -> e.getAttribute("Type").endsWith("/settings")).toList();
            assertEquals(1, settingsRelationships.size());
            assertEquals("settings.xml", settingsRelationships.getFirst().getAttribute("Target"));
            var types = childrenElements(part(bytes, "[Content_Types].xml")).stream()
                    .filter(e -> e.getAttribute("PartName").equals("/word/settings.xml")).toList();
            assertEquals(1, types.size());
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml",
                    types.getFirst().getAttribute("ContentType"));
        }
    }

    private static List<Element> childrenElements(Element element) {
        var result = new ArrayList<Element>();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) { result.add(child); }
        }
        return result;
    }

    private static Element part(byte[] bytes, String name) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals(name)) { continue; }
                var factory = DocumentBuilderFactory.newDefaultNSInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                return factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes())).getDocumentElement();
            }
        }
        throw new AssertionError("Missing OOXML part: " + name);
    }

    private static List<String> children(Element element) {
        var names = new ArrayList<String>();
        for (var child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element value) { names.add(value.getLocalName()); }
        }
        return names;
    }
}
