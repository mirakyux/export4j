// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlPageSettingsIntegrationTest {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final PageSettings JAVA_PAGE = new PageSettings(new PageSize(400, 300), new PageMargins(11, 12, 13, 14));
    private static final ParagraphFormat BODY = ParagraphFormat.builder().fontSize(10).lineHeight(20).spaceAfter(0).build();
    private static FontGroup fonts;

    @BeforeAll static void loadFont() throws IOException {
        try (var input = XmlPageSettingsIntegrationTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("missing test font"); }
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test void physicalPageAndAsymmetricMarginsReachEveryLayoutAndDisplayPage() {
        var source = document("<page width='5in' height='4in' margin='18' margin-top='24' margin-left='30'/>"
                + "<p id='first'>First</p><page-break id='break'/><p id='last'>Last</p>");
        var result = DocumentEngine.builder(fonts).build().layout(source);
        assertEquals(new PageSettings(new PageSize(360, 288), new PageMargins(24, 18, 18, 30)), source.pageSettings());
        assertEquals(2, result.body().pages().size());
        assertEquals(2, result.display().pages().size());
        for (int index = 0; index < 2; index++) {
            var page = result.body().pages().get(index);
            assertEquals(new PageSize(360, 288), page.size());
            assertEquals(new Rect(30, 24, 312, 246), page.contentBounds());
            assertEquals(page.size(), result.display().pages().get(index).size());
            var fragment = page.fragments().getFirst();
            assertEquals(index == 0 ? "first" : "last", fragment.blockId());
            assertEquals(index == 0 ? 0 : 2, fragment.blockIndex());
            assertEquals(30, fragment.origin().x());
            assertEquals(24, fragment.origin().y());
        }
        assertTrue(result.contextualDiagnostics().isEmpty());
    }

    @Test void customPageContentHeightActuallyChangesPaginationWithoutLosingOrRepeatingSourceLines() {
        String paragraph = "<p id='body'>A\nB\nC\nD\nE\nF\nG</p>";
        var engine = DocumentEngine.builder(fonts).build();
        var legacy = engine.layout(document(paragraph));
        var small = engine.layout(document("<page width='120' height='80' margin='10'/>" + paragraph));
        assertEquals(1, legacy.body().pages().size());
        assertEquals(3, small.body().pages().size());
        var fragments = small.body().pages().stream().flatMap(page -> page.fragments().stream()).toList();
        assertEquals(List.of(0, 3, 6), fragments.stream().map(fragment -> fragment.firstLineIndex()).toList());
        assertEquals(List.of(3, 3, 1), fragments.stream().map(fragment -> fragment.layout().lines().size()).toList());
        assertTrue(fragments.stream().allMatch(fragment -> fragment.blockId().equals("body") && fragment.blockIndex() == 0));
        var lines = fragments.stream().flatMap(fragment -> fragment.layout().lines().stream()).toList();
        assertEquals("ABCDEFG", lines.stream().flatMap(line -> line.runs().stream()).map(run -> run.text()).collect(Collectors.joining()));
        assertEquals(List.of(0, 2, 4, 6, 8, 10, 12), lines.stream().map(line -> line.sourceStart()).toList());
        assertEquals(13, lines.getLast().sourceEnd());
        for (var page : small.body().pages()) {
            assertEquals(new Rect(10, 10, 100, 60), page.contentBounds());
            assertEquals(new PageSize(120, 80), page.size());
        }
        assertTrue(small.contextualDiagnostics().isEmpty());
    }

    @Test void emptyPageDeclarationKeepsLegacyLayoutDisplaySourcesAndDiagnosticsExactly() {
        String content = "<p id='body'>A\nB</p><page-break id='break'/><p id='tail'>Tail</p>";
        var engine = DocumentEngine.builder(fonts).build();
        var legacy = engine.layout(document(content));
        var declared = engine.layout(document("<page/>" + content));
        assertEquals(legacy.body(), declared.body());
        assertEquals(legacy.display().pages(), declared.display().pages());
        assertEquals(legacy.display().extras(), declared.display().extras());
        assertEquals(legacy.contextualDiagnostics(), declared.contextualDiagnostics());
    }

    @Test void landscapeAndAsymmetricMarginsUseNativeDocxPageSettingsAndStrictModelRoundTrip() throws Exception {
        var source = document("<page size='LETTER' orientation='LANDSCAPE' margin='18' margin-top='24' margin-left='30'/>"
                + "<p id='body'>Report</p>");
        byte[] encoded = docx(source);
        var main = mainPart(encoded);
        assertAttributes(main, "pgSz", Map.of("w", "15840", "h", "12240"));
        assertAttributes(main, "pgMar", Map.of("top", "480", "right", "360", "bottom", "360", "left", "600"));
        var restored = new DocxAdapter().read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        assertEquals(1, restored.document().sections().size());
        assertEquals(source.pageSettings(), restored.document().sections().getFirst().pageSettings());
        assertEquals(source.metadata(), restored.document().metadata());
        var before = source.paragraphs().getFirst();
        var after = assertInstanceOf(Paragraph.class, restored.document().sections().getFirst().blocks().getFirst());
        assertEquals(before.id(), after.id());
        assertEquals(before.text(), after.text());
        assertEquals(before.format(), after.format());
    }

    @Test void fractionalPhysicalLengthsKeepExactNativeTwipQuantizationOnDocxRoundTrip() throws Exception {
        var source = document("<page size='A4' margin='18mm' margin-top='20mm' margin-left='19mm'/><p id='body'>Report</p>");
        byte[] encoded = docx(source);
        var main = mainPart(encoded);
        assertAttributes(main, "pgSz", Map.of("w", "11906", "h", "16838"));
        assertAttributes(main, "pgMar", Map.of("top", "1134", "right", "1020", "bottom", "1020", "left", "1077"));
        var restored = new DocxAdapter().read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        assertEquals(new PageSettings(new PageSize(595.3, 841.9), new PageMargins(56.7, 51, 51, 53.85)),
                restored.document().sections().getFirst().pageSettings());
        assertEquals(PageSize.A4, source.pageSettings().size());
        assertEquals(18 * 72 / 25.4, source.pageSettings().margins().right(), 1.0e-12);
    }

    @Test void zeroMarginsRemainZeroThroughNativeDocxRatherThanBeingReplacedByJavaDefaults() throws Exception {
        var source = document("<page size='LETTER' margin='0'/><p id='body'>Report</p>");
        byte[] encoded = docx(source);
        assertAttributes(mainPart(encoded), "pgMar", Map.of("top", "0", "right", "0", "bottom", "0", "left", "0"));
        var restored = new DocxAdapter().read(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        assertEquals(new PageSettings(PageSize.LETTER, PageMargins.uniform(0)), restored.document().sections().getFirst().pageSettings());
    }

    private static Document document(String content) {
        return XmlTemplate.builder().pageSettings(JAVA_PAGE).bodyFormat(BODY).build()
                .compile("<document version='1'>" + content + "</document>").render(Map.of());
    }

    private static byte[] docx(Document source) throws IOException {
        var output = new ByteArrayOutputStream();
        new DocxAdapter().write(source, output);
        return output.toByteArray();
    }

    private static org.w3c.dom.Document mainPart(byte[] archive) throws Exception {
        try (var input = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) {
                    var factory = DocumentBuilderFactory.newDefaultInstance();
                    factory.setNamespaceAware(true);
                    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(input.readAllBytes()));
                }
            }
        }
        throw new IOException("generated DOCX has no main document part");
    }

    private static void assertAttributes(org.w3c.dom.Document document, String name, Map<String, String> expected) {
        var elements = document.getElementsByTagNameNS(W, name);
        assertEquals(1, elements.getLength());
        var element = assertInstanceOf(Element.class, elements.item(0));
        expected.forEach((attribute, value) -> assertEquals(value, element.getAttributeNS(W, attribute)));
    }
}
