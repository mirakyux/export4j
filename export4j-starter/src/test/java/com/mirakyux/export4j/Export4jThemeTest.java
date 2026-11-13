// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.DocumentTheme;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Configuration once must preserve semantic theme domains through every source entry point. */
class Export4jThemeTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String MARKDOWN = "# Heading\n\nBody\n\n| Header |\n|---|\n| Cell |";
    private static final String HTML = "<h1>Heading</h1><p>Body</p><table><tr><td>Cell</td></tr></table>";
    private static final String XML = """
            <document version="1" content-format="HTML">
              <content id="source"><![CDATA[<h1>Heading</h1><p>Body</p><table><tr><td>Cell</td></tr></table>]]></content>
            </document>
            """;

    @Test void allSourceFormatsRetainDistinctThemeDomainsInPdfAndDocx() throws Exception {
        try (var exporter = Export4j.builder(fonts()).theme(theme()).build()) {
            for (String format : List.of("markdown", "html", "xml")) {
                var word = new ByteArrayOutputStream();
                request(exporter, format).docx(word);
                assertRun(word.toByteArray(), "Heading", 18, 0xAABBCC);
                assertRun(word.toByteArray(), "Body", 10, 0x223344);
                assertRun(word.toByteArray(), "Cell", 9, 0x445566);

                var pdf = new ByteArrayOutputStream();
                request(exporter, format).pdf(pdf);
                var glyphs = pdfGlyphs(pdf.toByteArray());
                assertGlyphs(glyphs, "Heading", 18, 0xAABBCC);
                assertGlyphs(glyphs, "Body", 10, 0x223344);
                assertGlyphs(glyphs, "Cell", 9, 0x445566);
            }
        }
    }

    @Test void sparseGlobalAndRequestDeclarationsDoNotFlattenUnchangedDomains() throws Exception {
        var defaults = ContentOptions.builder().spaceAfter(0).build();
        try (var exporter = Export4j.builder(fonts()).theme(theme()).defaults(defaults).build()) {
            var changed = new ByteArrayOutputStream();
            exporter.markdown(MARKDOWN).options(ContentOptions.builder().rgb(0).fontSize(10).build()).docx(changed);
            // Size ten equals the body baseline, but still explicitly overrides heading size.
            assertRun(changed.toByteArray(), "Heading", 10, 0);
            assertRun(changed.toByteArray(), "Body", 10, 0);
            assertRun(changed.toByteArray(), "Cell", 9, 0);
            var inherited = new ByteArrayOutputStream();
            exporter.markdown(MARKDOWN).docx(inherited);
            assertRun(inherited.toByteArray(), "Heading", 18, 0xAABBCC);
            assertRun(inherited.toByteArray(), "Cell", 9, 0x445566);
        }
    }

    @Test void xmlRequestRootScopeAndBlockDeclarationsPreserveSiblingThemeDomains() throws Exception {
        var xml = """
                <document version="1" content-format="HTML">
                  <scope color="#000000">
                    <content id="inside" size="10"><![CDATA[<h1>Scoped</h1><table><tr><td>Local</td></tr></table>]]></content>
                  </scope>
                  <content id="after"><![CDATA[<h1>Restored</h1><table><tr><td>Sibling</td></tr></table>]]></content>
                </document>
                """;
        try (var exporter = Export4j.builder(fonts()).theme(theme())
                .defaults(ContentOptions.builder().spaceAfter(0).build()).build()) {
            var output = new ByteArrayOutputStream();
            exporter.xml(xml, Map.of()).options(ContentOptions.builder().lineHeight(32).build()).docx(output);
            assertRun(output.toByteArray(), "Scoped", 10, 0);
            assertRun(output.toByteArray(), "Local", 9, 0);
            assertRun(output.toByteArray(), "Restored", 18, 0xAABBCC);
            assertRun(output.toByteArray(), "Sibling", 9, 0x445566);
        }
    }

    private static Export4j.Request request(Export4j exporter, String format) {
        return switch (format) {
            case "markdown" -> exporter.markdown(MARKDOWN);
            case "html" -> exporter.html(HTML);
            case "xml" -> exporter.xml(XML, Map.of());
            default -> throw new IllegalArgumentException("unknown test source format");
        };
    }

    private static FontFamily fonts() {
        return FontFamily.builder("Liberation Sans", source("LiberationSans-Regular.ttf"))
                .bold(source("LiberationSans-Bold.ttf")).italic(source("LiberationSans-Italic.ttf"))
                .boldItalic(source("LiberationSans-BoldItalic.ttf")).build();
    }

    private static FontSource source(String name) {
        return FontSource.resource(Export4jThemeTest.class, "/fonts/" + name);
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyRgb(0x223344).headingRgb(1, 0xAABBCC).cellRgb(0x445566)
                .bodyFormat(p -> p.fontSize(10).lineHeight(22))
                .headingFormat(1, p -> p.fontSize(18).lineHeight(28))
                .cellFormat(c -> c.fontSize(9).lineHeight(20)).build();
    }

    private record Glyph(String text, float size, int rgb) { }

    private static List<Glyph> pdfGlyphs(byte[] bytes) throws IOException {
        var glyphs = new ArrayList<Glyph>();
        try (var pdf = Loader.loadPDF(bytes)) {
            var stripper = new PDFTextStripper() {
                {
                    addOperator(new SetNonStrokingDeviceRGBColor(this));
                    addOperator(new SetNonStrokingDeviceGrayColor(this));
                    addOperator(new SetNonStrokingDeviceCMYKColor(this));
                }
                @Override protected void processTextPosition(TextPosition position) {
                    try {
                        glyphs.add(new Glyph(position.getUnicode(), position.getFontSizeInPt(),
                                getGraphicsState().getNonStrokingColor().toRGB()));
                    } catch (IOException failure) { throw new IllegalStateException(failure); }
                    super.processTextPosition(position);
                }
            };
            stripper.getText(pdf);
        }
        return glyphs;
    }

    private static void assertGlyphs(List<Glyph> glyphs, String text, int size, int rgb) {
        String all = glyphs.stream().map(Glyph::text).collect(java.util.stream.Collectors.joining());
        int start = all.indexOf(text);
        assertTrue(start >= 0, () -> "PDF text missing: " + text);
        int offset = 0;
        for (var glyph : glyphs) {
            if (offset >= start && offset < start + text.length()) {
                assertEquals(size, glyph.size(), 0.001);
                assertEquals(rgb, glyph.rgb());
            }
            offset += glyph.text().length();
        }
    }

    private static void assertRun(byte[] bytes, String text, int size, int rgb) throws Exception {
        byte[] xml = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) { xml = zip.readAllBytes(); break; }
            }
        }
        assertTrue(xml != null);
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var runs = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml)).getElementsByTagNameNS(W, "r");
        for (int index = 0; index < runs.getLength(); index++) {
            var run = (Element) runs.item(index);
            if (!run.getTextContent().equals(text)) { continue; }
            var color = (Element) run.getElementsByTagNameNS(W, "color").item(0);
            var fontSize = (Element) run.getElementsByTagNameNS(W, "sz").item(0);
            assertEquals(String.format(java.util.Locale.ROOT, "%06X", rgb), color.getAttributeNS(W, "val"));
            assertEquals(Integer.toString(size * 2), fontSize.getAttributeNS(W, "val"));
            return;
        }
        throw new AssertionError("DOCX text missing: " + text);
    }
}
