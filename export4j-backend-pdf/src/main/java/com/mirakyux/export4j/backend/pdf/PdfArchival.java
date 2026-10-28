// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.backend.pdf;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.List;
import java.time.Instant;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.model.DocumentMetadata;

/** Static PDF/A output configuration with an explicit edition, RGB ICC profile and embeddable TrueType anchor font.
 * Unsupported interactions/standard-font commands are rejected. Conformance must be checked on final output.
 */
public final class PdfArchival {
    /** Selected ISO 19005 editions: visual level B for parts 1/2/3, PDF/A-4f for part 4. */
    public enum Profile {
        A1B(1, "B", 1.4f), A2B(2, "B", 1.7f), A3B(3, "B", 1.7f), A4F(4, "F", 2.0f);
        final int part; final String conformance; final float version;
        Profile(int part, String conformance, float version) { this.part = part; this.conformance = conformance; this.version = version; }
    }
    /** Explicit associated-file bytes. No URI is read and all returned bytes are independent copies. */
    public record AssociatedFile(String name, String mimeType, String description, String relationship, Instant modified, byte[] bytes) {
        public AssociatedFile {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(mimeType, "mimeType"); Objects.requireNonNull(description, "description"); Objects.requireNonNull(modified, "modified"); bytes = bytes.clone();
            if (name.isBlank() || name.length() > 256 || name.contains("/") || name.contains("\\") || name.codePoints().anyMatch(Character::isISOControl)
                    || !mimeType.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+") || description.length() > 4096
                    || !List.of("Source", "Data", "Alternative", "Supplement", "Unspecified").contains(relationship) || bytes.length == 0 || bytes.length > 16 * 1024 * 1024) { throw new IllegalArgumentException("invalid archival associated file"); }
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    private final byte[] profile;
    private final FontData anchor;
    private final Profile target;
    private final List<AssociatedFile> files;

    public PdfArchival(byte[] rgbIccProfile, FontData anchorFont) {
        this(Profile.A2B, rgbIccProfile, anchorFont, List.of());
    }
    public PdfArchival(Profile target, byte[] rgbIccProfile, FontData anchorFont) { this(target, rgbIccProfile, anchorFont, List.of()); }
    public PdfArchival(Profile target, byte[] rgbIccProfile, FontData anchorFont, List<AssociatedFile> files) {
        this.target = Objects.requireNonNull(target, "target"); this.files = List.copyOf(files);
        if (!files.isEmpty() && target != Profile.A3B && target != Profile.A4F || target == Profile.A4F && files.isEmpty() || files.size() > 100
                || files.stream().map(AssociatedFile::name).distinct().count() != files.size()
                || files.stream().mapToLong(file -> file.bytes.length).sum() > 32L * 1024 * 1024) { throw new IllegalArgumentException("associated files require PDF/A-3b or PDF/A-4f and finite unique resources"); }
        profile = Objects.requireNonNull(rgbIccProfile, "rgbIccProfile").clone();
        anchor = Objects.requireNonNull(anchorFont, "anchorFont");
        if (profile.length < 128 || profile.length > 4 * 1024 * 1024 || ICC_Profile.getInstance(profile).getColorSpaceType() != ColorSpace.TYPE_RGB
                || anchor.cffOutlines() || target == Profile.A1B && ICC_Profile.getInstance(profile).getMajorVersion() != 2) { throw new IllegalArgumentException("PDF/A requires an RGB ICC profile and TrueType anchor font; part 1 requires ICC v2"); }
        try {
            if (com.mirakyux.export4j.font.TrueTypeFontLoader.load(anchor.bytes()).glyph('x').isEmpty()) { throw new IllegalArgumentException("anchor font must support x"); }
        } catch (IOException failure) { throw new IllegalArgumentException("invalid anchor font", failure); }
    }
    public Profile target() { return target; }
    public List<AssociatedFile> associatedFiles() { return files; }

    FontData anchor() { return anchor; }

    void configure(PDDocument pdf, DocumentMetadata metadata, DocumentExtras extras) throws IOException {
        if (!extras.equals(DocumentExtras.EMPTY)) { throw new IOException("PDF/A static profile does not support interactive extras"); }
        pdf.setVersion(target.version);
        pdf.getDocument().setVersion(target.version);
        String producer = "Export4j / PDFBox";
        pdf.getDocumentInformation().setProducer(producer);
        var intent = new PDOutputIntent(pdf, new ByteArrayInputStream(profile));
        intent.setInfo("Explicit RGB output profile"); intent.setOutputConditionIdentifier("Explicit RGB");
        intent.setRegistryName("http://www.color.org"); pdf.getDocumentCatalog().addOutputIntent(intent);
        String xmp = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">"
                + "<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\" pdfaid:part=\"" + target.part + "\" pdfaid:conformance=\"" + target.conformance + "\"" + (target.part == 4 ? " pdfaid:rev=\"2020\"" : "") + "/>"
                + "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">"
                + "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">" + escape(metadata.title()) + "</rdf:li></rdf:Alt></dc:title>"
                + "<dc:creator><rdf:Seq><rdf:li>" + escape(metadata.author()) + "</rdf:li></rdf:Seq></dc:creator>"
                + "<xmp:CreatorTool>Export4j</xmp:CreatorTool><pdf:Producer>" + producer + "</pdf:Producer></rdf:Description>"
                + "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";
        var xmpMetadata = new PDMetadata(pdf); xmpMetadata.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
        pdf.getDocumentCatalog().setMetadata(xmpMetadata);
        if (!files.isEmpty()) { associated(pdf); }
        if (target.part == 4) { pdf.getDocument().getTrailer().removeItem(org.apache.pdfbox.cos.COSName.INFO); }
    }
    private void associated(PDDocument pdf) throws IOException {
        var names = new org.apache.pdfbox.pdmodel.PDDocumentNameDictionary(pdf.getDocumentCatalog());
        var tree = new org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode();
        var entries = new java.util.TreeMap<String, org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification>();
        var associations = new org.apache.pdfbox.cos.COSArray();
        for (var file : files) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var specification = new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification();
            specification.setFile(file.name()); specification.setFileUnicode(file.name()); specification.setFileDescription(file.description());
            specification.getCOSObject().setName(org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship"), file.relationship());
            var embedded = new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(pdf, new ByteArrayInputStream(file.bytes));
            embedded.setSubtype(file.mimeType()); embedded.setSize(file.bytes.length);
            var calendar = java.util.GregorianCalendar.from(file.modified().atZone(java.time.ZoneOffset.UTC)); embedded.setModDate(calendar); embedded.setCreationDate(calendar);
            specification.setEmbeddedFile(embedded); specification.setEmbeddedFileUnicode(embedded); entries.put(file.name(), specification); associations.add(specification);
        }
        tree.setNames(entries); names.setEmbeddedFiles(tree); pdf.getDocumentCatalog().setNames(names);
        pdf.getDocumentCatalog().getCOSObject().setItem(org.apache.pdfbox.cos.COSName.getPDFName("AF"), associations);
    }
    private static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != '\t' && cp != '\n' && cp != '\r'
                || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) {
            throw new IllegalArgumentException("metadata contains invalid XML scalar");
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
