// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PdfCffFontTest {
    @ParameterizedTest
    @CsvSource({"SourceSans3-Regular.otf,CFF native café Ω Ж €,Type1C", "Export4j-CID-Test.otf,AB中😀,CIDFontType0C"})
    void embedsCorrectCffProgramAndKeepsUnicodeMapping(String file, String source, String subtype) throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/fonts/" + file)) { bytes = java.util.Objects.requireNonNull(input).readAllBytes(); }
        var face = TrueTypeFontLoader.load(bytes);
        var paragraph = new ParagraphLayouter().layout(source, List.of(face), 500, ParagraphStyle.of(18));
        assertTrue(paragraph.lines().getFirst().outlines().isEmpty());
        var commands = paragraph.lines().getFirst().runs().stream().map(run -> (DrawCommand) new DrawCommand.GlyphText(run, new Point(20, 50), RgbColor.BLACK)).toList();
        var display = new DisplayDocument(DocumentMetadata.EMPTY, List.of(new DisplayPage(new PageSize(550, 100), commands)));
        var output = new ByteArrayOutputStream(); new PdfBackend().write(display, output);
        try (var document = Loader.loadPDF(output.toByteArray())) {
            assertEquals(source.replace(" ", ""), new PDFTextStripper().getText(document).replaceAll("\\s", ""));
            var page = document.getPage(0);
            org.apache.pdfbox.cos.COSStream shared = null;
            for (var name : page.getResources().getFontNames()) {
                var font = page.getResources().getFont(name);
                assertTrue(font.isEmbedded());
                var program = font.getFontDescriptor().getFontFile3().getCOSObject();
                assertEquals(subtype, program.getNameAsString(COSName.SUBTYPE));
                if (subtype.equals("CIDFontType0C")) {
                    var dictionary = font.getCOSObject();
                    var encoding = (org.apache.pdfbox.cos.COSStream) dictionary.getDictionaryObject(COSName.ENCODING);
                    var descendant = ((org.apache.pdfbox.pdmodel.font.PDType0Font) font).getDescendantFont().getCOSObject();
                    var expected = (org.apache.pdfbox.cos.COSDictionary) descendant.getDictionaryObject(COSName.CIDSYSTEMINFO);
                    var actual = (org.apache.pdfbox.cos.COSDictionary) encoding.getDictionaryObject(COSName.CIDSYSTEMINFO);
                    assertEquals(expected.getString(COSName.REGISTRY), actual.getString(COSName.REGISTRY));
                    assertEquals(expected.getString(COSName.ORDERING), actual.getString(COSName.ORDERING));
                    assertEquals(COSName.CMAP, encoding.getCOSName(COSName.TYPE));
                }
                if (shared == null) { shared = program; } else { assertTrue(shared == program); }
            }
        }
    }
}
