// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Static PDF/A-2b output configuration with explicit RGB ICC profile and an embeddable TrueType anchor font.
 * Unsupported interactions/standard-font commands are rejected. Conformance must be checked on final output.
 */
public final class PdfArchival {
    private final byte[] profile;
    private final FontData anchor;

    public PdfArchival(byte[] rgbIccProfile, FontData anchorFont) {
        profile = Objects.requireNonNull(rgbIccProfile, "rgbIccProfile").clone();
        anchor = Objects.requireNonNull(anchorFont, "anchorFont");
        if (profile.length < 128 || profile.length > 4 * 1024 * 1024 || ICC_Profile.getInstance(profile).getColorSpaceType() != ColorSpace.TYPE_RGB
                || anchor.cffOutlines()) { throw new IllegalArgumentException("PDF/A requires an RGB ICC profile and TrueType anchor font"); }
        try {
            if (com.mirakyux.export4j.font.TrueTypeFontLoader.load(anchor.bytes()).glyph('x').isEmpty()) { throw new IllegalArgumentException("anchor font must support x"); }
        } catch (IOException failure) { throw new IllegalArgumentException("invalid anchor font", failure); }
    }

    FontData anchor() { return anchor; }

    void configure(PDDocument pdf, DocumentMetadata metadata, DocumentExtras extras) throws IOException {
        if (!extras.equals(DocumentExtras.EMPTY)) { throw new IOException("PDF/A static profile does not support interactive extras"); }
        pdf.setVersion(1.7f);
        String producer = "Export4j / PDFBox";
        pdf.getDocumentInformation().setProducer(producer);
        var intent = new PDOutputIntent(pdf, new ByteArrayInputStream(profile));
        intent.setInfo("Explicit RGB output profile"); intent.setOutputConditionIdentifier("Explicit RGB");
        intent.setRegistryName("http://www.color.org"); pdf.getDocumentCatalog().addOutputIntent(intent);
        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" pdfaid:part=\"2\" pdfaid:conformance=\"B\"/>"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + escape(metadata.title()) + "</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>" + escape(metadata.author()) + "</rdf:li></rdf:Seq></dc:creator>"
                + "<xmp:CreatorTool>Export4j</xmp:CreatorTool><pdf:Producer>" + producer + "</pdf:Producer></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        var xmpMetadata = new PDMetadata(pdf); xmpMetadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        pdf.getDocumentCatalog().setMetadata(xmpMetadata);
    }
    private static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != '\t' && cp != '\n' && cp != '\r'
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("metadata contains invalid XML scalar");
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
