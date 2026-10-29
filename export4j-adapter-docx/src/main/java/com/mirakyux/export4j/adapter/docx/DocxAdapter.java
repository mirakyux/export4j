// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableGrid;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.processing.DocumentConversion;

/** Offline OOXML semantic subset. Handles paragraphs/runs, headings, lists, rectangular merged tables and page sections.
 * STRICT rejects unsupported markup; ALLOW_LOSS returns diagnostics. No embedded code, links or external resources execute.
 * XML depth is capped at 128. Streams remain caller-owned. Instances are immutable and reusable concurrently.
 */
public final class DocxAdapter {
    public enum Policy { STRICT, ALLOW_LOSS }
    public record Diagnostic(String part, String element, String message) { }
    public record ReadResult(SectionedDocument document, List<Diagnostic> diagnostics) {
        public ReadResult { Objects.requireNonNull(document, "document"); diagnostics = List.copyOf(diagnostics); }
    }
    /** Typed native Word objects, independent of the original package representation. */
    public record SemanticReadResult(DocxSemanticDocument document, List<Diagnostic> diagnostics) {
        public SemanticReadResult { Objects.requireNonNull(document, "document"); diagnostics = List.copyOf(diagnostics); }
    }
    public SemanticReadResult readSemantic(InputStream input, Policy policy) throws IOException {
        return DocxSemanticReader.read(this, limits, Objects.requireNonNull(input, "input"), Objects.requireNonNull(policy, "policy"));
    }
    public DocxSemanticConversion.Request semanticConversion(byte[] encoded) { return DocxSemanticConversion.request(this, limits, encoded); }
    /** Explicit projection; strict policy rejects any native behavior lacking a portable mapping. */
    public DocumentConversion.Input project(DocxSemanticDocument document, com.mirakyux.export4j.model.processing.ProcessingPolicy policy) throws IOException {
        return DocxSemanticProjection.project(Objects.requireNonNull(document, "document"), Objects.requireNonNull(policy, "policy"));
    }
    /** Rebuilds standard Word objects. Output is touched only after complete package validation. */
    public void writeSemantic(DocxSemanticDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output"); byte[] bytes = DocxSemanticWriter.write(this, limits, Objects.requireNonNull(document, "document")); output.write(bytes);
    }
    boolean materializeSemanticStyles(Map<String, byte[]> parts, Policy policy, List<Diagnostic> diagnostics) throws IOException {
        return DocxStyles.materialize(parts, fontFamilies, policy, diagnostics, limits);
    }
    ReadResult readSemanticBody(InputStream input, Policy policy, boolean stylesMaterialized) throws IOException {
        try { return readPackage(input, policy, 0, stylesMaterialized); }
        catch (IllegalArgumentException | ArithmeticException failure) { throw new IOException("invalid imported DOCX semantics", failure); }
    }
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String OD = "urn:com.mirakyux.export4j:semantic:1";
    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = " xmlns:r=\"" + R + "\" xmlns:w=\"" + W + "\" xmlns:od=\"" + OD
            + "\" xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" mc:Ignorable=\"od\"";
    private static final Set<String> MARKUP = Set.of("document", "body", "p", "pPr", "pStyle", "r", "rPr", "rStyle", "t", "br", "tab", "b", "i", "u", "strike", "sz", "color", "position",
            "jc", "kinsoku", "spacing", "ind", "keepLines", "keepNext", "pageBreakBefore", "numPr", "ilvl", "numId", "sectPr", "type", "pgSz", "pgMar", "pgNumType",
            "tbl", "tblPr", "tblCellMar", "tblGrid", "gridCol", "tr", "trPr", "tblHeader", "cantSplit", "tc", "tcPr", "gridSpan", "vMerge", "top", "right", "bottom", "left", "proofErr", "hyperlink", "drawing",
            "fldSimple", "fldChar", "instrText", "bookmarkStart", "bookmarkEnd", "hdr", "ftr", "headerReference", "footerReference", "titlePg", "tblBorders", "insideH", "insideV");
    private static final Set<String> EMPTY_CONTINUATION_MARKUP = Set.of("p", "pPr", "pStyle", "r", "rPr", "rStyle", "t",
            "b", "i", "u", "strike", "sz", "color", "position", "jc", "kinsoku", "spacing", "ind",
            "keepLines", "keepNext", "pageBreakBefore", "proofErr");
    private record RegionKey(PageRegion region, double tableWidth) { }
    private static RegionKey regionKey(PageRegion region, double width) {
        return new RegionKey(region, region.blocks().stream().anyMatch(Table.class::isInstance) ? width : 0);
    }
    private final DocxLimits limits;
    private final RegionGeometry regionGeometry;
    private final Map<String, FontRoles> fontFamilies;

    /** Four caller-owned physical font-role keys for one explicit OOXML font family. Empty regular
     * selects the configured default font group. No installed-font discovery or synthetic variants.
     */
    public record FontRoles(String regular, String bold, String italic, String boldItalic) {
        public FontRoles {
            Objects.requireNonNull(regular, "regular"); Objects.requireNonNull(bold, "bold"); Objects.requireNonNull(italic, "italic"); Objects.requireNonNull(boldItalic, "boldItalic");
            if (!regular.isEmpty() && regular.isBlank() || bold.isBlank() || italic.isBlank() || boldItalic.isBlank()) { throw new IllegalArgumentException("font variants require explicit role keys"); }
        }
    }

    /** Explicit fallback geometry in points for standard Word bands without Export4j fixed-height metadata.
     * Word does not encode fixed band heights; no content-based height guessing occurs. */
    public record RegionGeometry(double headerHeight, double headerGap, double footerHeight, double footerGap) {
        public RegionGeometry {
            new PageRegion(headerHeight, headerGap, List.of());
            new PageRegion(footerHeight, footerGap, List.of());
        }
    }

    /** Task-confined configuration builder. Adapter remains offline and borrows caller streams. */
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private DocxLimits limits = DocxLimits.DEFAULT;
        private RegionGeometry regionGeometry;
        private final Map<String, FontRoles> fontFamilies = new HashMap<>();
        private Builder() { }
        public Builder limits(DocxLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder regionGeometry(RegionGeometry value) { regionGeometry = Objects.requireNonNull(value, "regionGeometry"); return this; }
        public Builder fontFamily(String name, FontRoles roles) {
            if (Objects.requireNonNull(name, "name").isBlank()) { throw new IllegalArgumentException("empty font family"); }
            if (fontFamilies.putIfAbsent(name, Objects.requireNonNull(roles, "roles")) != null) { throw new IllegalArgumentException("duplicate font family"); } return this;
        }
        public DocxAdapter build() { return new DocxAdapter(limits, regionGeometry, fontFamilies); }
    }

    public DocxAdapter() { this(DocxLimits.DEFAULT); }
    /** Snapshots explicit package bytes for a shared-policy import/edit/export plan. Macros and unsafe
     * packages remain unconditionally rejected; unsupported markup produces queryable actual losses.
     */
    public DocumentConversion.Request conversion(byte[] encoded) {
        return DocxConversions.source(this, encoded);
    }
    /** Shared-policy semantic target. Unsupported source font roles require explicit degradation;
     * structures without an implemented target mapping still fail before final output.
     */
    public DocumentConversion.Target conversionTarget() {
        return DocxConversions.target(this);
    }
    public DocxAdapter(DocxLimits limits) { this(limits, null, Map.of()); }
    /** Same-format package-preserving literal text edits. Untouched parts retain exact expanded bytes;
     * only explicitly supported text nodes change. No semantic import, field update or external access.
     * Macro/active, signed and protected packages are rejected independently of loss policy.
     */
    public DocxPackageEditor.Request editPackage(byte[] encoded) {
        return DocxPackageEditor.request(this, limits, encoded);
    }
    private DocxAdapter(DocxLimits limits, RegionGeometry regionGeometry, Map<String, FontRoles> fontFamilies) {
        this.limits = Objects.requireNonNull(limits, "limits"); this.regionGeometry = regionGeometry; this.fontFamilies = Map.copyOf(fontFamilies);
    }

    /** Exports body and fixed repeating bands using standard OOXML parts and section references. */
    public void write(PageDocument document, OutputStream output) throws IOException {
        var body = document.document();
        write(new SectionedDocument(body.metadata(), List.of(new Section("document", body.pageSettings(), body.blocks(), 0, document.pageRegions()))), output);
    }

    public void write(Document document, OutputStream output) throws IOException {
        write(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))), output);
    }

    public void write(SectionedDocument document, OutputStream output) throws IOException {
        Objects.requireNonNull(document, "document");
        if (!document.navigation().equals(com.mirakyux.export4j.model.DocumentNavigation.NONE)) {
            throw new IOException("DOCX navigation requires an explicit conversion loss policy");
        }
        Objects.requireNonNull(output, "output");
        if (document.sections().stream().anyMatch(section -> !section.pageRegions().background().isEmpty()
                || !section.pageRegions().foreground().isEmpty())) {
            throw new IOException("DOCX page overlays require an explicit conversion loss policy");
        }
        var semanticBudget = new DocxCells.Budget(limits);
        List<SemanticTree.Visit> visits = new ArrayList<>();
        var uniqueBands = new HashSet<PageRegion>();
        for (var section : document.sections()) {
            SemanticTree.walk(section.blocks(), visits::add);
            for (var band : java.util.stream.Stream.concat(section.pageRegions().headers().stream(), section.pageRegions().footers().stream()).toList()) {
                if (uniqueBands.add(band)) { SemanticTree.walk(band.blocks(), visits::add); }
            }
        }
        long textBytes = 0;
        for (var visit : visits) {
            semanticBudget.node();
            var block = visit.block();
            if (block instanceof Table table) {
                checkBlockTable(table);
                semanticBudget.slots(table.rows().size(), table.columns().size());
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        if (cell.hasBlocks()) { continue; }
                        semanticBudget.text(cell.text()); textBytes += escapedBytes(cell.text());
                    }
                }
            } else {
                var source = paragraph(block).text(); semanticBudget.text(source); textBytes += escapedBytes(source);
            }
            if (textBytes > limits.maxEntryBytes()) { throw new IOException("DOCX source text exceeds entry byte budget"); }
        }
        var fields = new DocxFields.Writer(document);
        var main = new StringBuilder(XML + "<w:document" + NS + "><w:body>");
        var numbering = new StringBuilder(XML + "<w:numbering" + NS + ">");
        var roles = new HashSet<String>();
        var counters = new HashMap<String, int[]>();
        Map<String, String> hyperlinks = new LinkedHashMap<>();
        var images = new DocxImages.Writer(limits.maxEntryBytes());
        var regionParts = new LinkedHashMap<String, String>();
        var headerNames = new LinkedHashMap<RegionKey, String>();
        var footerNames = new LinkedHashMap<RegionKey, String>();
        boolean hasRegions = document.sections().stream().anyMatch(section -> !section.pageRegions().equals(PageRegions.EMPTY));
        boolean hasEvenVariants = document.sections().stream().anyMatch(section -> section.pageRegions().variants().containsKey(PageRegions.Variant.EVEN));
        if (hasRegions) {
            for (var section : document.sections()) {
                for (boolean header : List.of(true, false)) {
                    for (var band : header ? section.pageRegions().headers() : section.pageRegions().footers()) {
                        var names = header ? headerNames : footerNames;
                        var key = regionKey(band, section.pageSettings().contentWidth());
                        if (!names.containsKey(key)) {
                            String name = (header ? "header" : "footer") + (names.size() + 1);
                            names.put(key, name);
                            var bandLinks = new LinkedHashMap<String, String>();
                            var xml = new StringBuilder(XML + "<w:" + (header ? "hdr" : "ftr") + NS
                                    + " od:height=\"" + band.height() + "\" od:gap=\"" + band.gap() + "\">");
                            for (var block : band.blocks()) {
                                if (block instanceof Table table) {
                                    table(xml, table, section.pageSettings().contentWidth(), bandLinks, images, fields, roles, List.of());
                                } else {
                                    var paragraph = (Paragraph) block;
                                    paragraph.inlines().forEach(run -> { if (!run.style().fontRole().isEmpty()) { roles.add(run.style().fontRole()); } });
                                    xml.append("<w:p od:id=\"").append(escape(paragraph.id())).append("\">");
                                    properties(xml, paragraph.format(), 0, 0, 1);
                                    runs(xml, paragraph, bandLinks, images, fields);
                                    xml.append("</w:p>");
                                }
                                if (xml.length() > limits.maxEntryBytes()) { throw new IOException("DOCX region markup budget exceeded"); }
                            }
                            if (band.blocks().isEmpty()) { xml.append("<w:p od:emptyBand=\"true\"/>"); }
                            xml.append("</w:").append(header ? "hdr" : "ftr").append('>');
                            regionParts.put("word/" + name + ".xml", xml.toString());
                            regionParts.put("word/_rels/" + name + ".xml.rels", XML
                                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                    + images.relationships() + "</Relationships>");
                        }
                    }
                }
            }
        }
        int numberId = 1;
        for (int sectionIndex = 0; sectionIndex < document.sections().size(); sectionIndex++) {
            var section = document.sections().get(sectionIndex);
            for (var block : section.blocks()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                if (block instanceof Table table) { table(main, table, section.pageSettings().contentWidth(), hyperlinks, images, fields, roles, List.of());
                } else {
                    var p = paragraph(block);
                    p.inlines().forEach(inline -> { if (!inline.style().fontRole().isEmpty()) { roles.add(inline.style().fontRole()); } });
                    main.append("<w:p od:id=\"").append(escape(block.id())).append("\"");
                    String kind = block instanceof Heading ? "heading" : block instanceof NumberedItem ? "number" : block instanceof BulletItem ? "bullet" : block instanceof PageBreak ? "break" : "paragraph";
                    main.append(" od:kind=\"").append(kind).append("\"");
                    int listId = 0;
                    int level = 1;
                    if (block instanceof NumberedItem item) {
                        level = item.level();
                        var values = counters.computeIfAbsent(item.listId(), ignored -> new int[9]);
                        for (int i = 0; i < level - 1; i++) { if (values[i] == 0) { throw new IOException("numbered list skips levels"); } }
                        values[level - 1] = item.restartAt() == 0 ? Math.addExact(values[level - 1], 1) : item.restartAt();
                        java.util.Arrays.fill(values, level, 9, 0);
                        listId = numberId++;
                        numbering(numbering, listId, values, "", item.markerStyle());
                        if (!item.markerStyle().fontRole().isEmpty()) { roles.add(item.markerStyle().fontRole()); }
                        main.append(" od:listId=\"").append(escape(item.listId())).append("\" od:restart=\"").append(item.restartAt()).append("\"");
                    } else if (block instanceof BulletItem item) {
                        level = item.level(); listId = numberId++;
                        numbering(numbering, listId, new int[9], item.marker(), item.markerStyle());
                        if (!item.markerStyle().fontRole().isEmpty()) { roles.add(item.markerStyle().fontRole()); }
                        main.append(" od:marker=\"").append(escape(item.marker())).append("\"");
                    }
                    main.append('>');
                    properties(main, p.format(), block instanceof Heading h ? h.level() : 0, listId, level);
                    fields.start(main, block.id());
                    if (block instanceof PageBreak) { main.append("<w:r><w:br w:type=\"page\"/></w:r>"); }
                    else { runs(main, p, hyperlinks, images, fields); }
                    fields.end(main, block.id());
                    main.append("</w:p>");
                }
                if (main.length() > limits.maxEntryBytes() || numbering.length() > limits.maxEntryBytes()) {
                    throw new IOException("DOCX document or numbering markup budget exceeded");
                }
            }
            if (sectionIndex + 1 < document.sections().size()) { main.append("<w:p od:boundary=\"true\"><w:pPr>"); }
            section(main, section, headerNames, footerNames, hasRegions, hasEvenVariants);
            if (sectionIndex + 1 < document.sections().size()) { main.append("</w:pPr></w:p>"); }
        }
        main.append("</w:body></w:document>");
        numbering.append("</w:numbering>");
        var styles = new StringBuilder(XML + "<w:styles" + NS + ">");
        for (int level = 1; level <= 6; level++) {
            styles.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level).append("\"><w:name w:val=\"heading ")
                    .append(level).append("\"/><w:pPr><w:outlineLvl w:val=\"").append(level - 1).append("\"/></w:pPr></w:style>");
        }
        roles.stream().sorted().forEach(role -> styles.append("<w:style w:type=\"character\" w:styleId=\"").append(escape(role))
                .append("\"><w:name w:val=\"").append(escape(role)).append("\"/>").append(roleProperties(role)).append("</w:style>"));
        styles.append("</w:styles>");
        String contentTypes = XML + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/></Types>";
        if (hasRegions) {
            for (var name : headerNames.values()) { contentTypes = contentTypes.replace("</Types>", regionContentType(name, "header") + "</Types>"); }
            for (var name : footerNames.values()) { contentTypes = contentTypes.replace("</Types>", regionContentType(name, "footer") + "</Types>"); }
        }
        String relns = "http://schemas.openxmlformats.org/package/2006/relationships";
        String reltype = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
        String rootRels = XML + "<Relationships xmlns=\"" + relns + "\"><Relationship Id=\"rId1\" Type=\"" + reltype + "officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/></Relationships>";
        String wordRels = XML + "<Relationships xmlns=\"" + relns + "\"><Relationship Id=\"rId1\" Type=\"" + reltype + "styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + reltype + "numbering\" Target=\"numbering.xml\"/></Relationships>";
        if (hasRegions) {
            for (var name : headerNames.values()) { wordRels = wordRels.replace("</Relationships>", regionRelationship(name, "header") + "</Relationships>"); }
            for (var name : footerNames.values()) { wordRels = wordRels.replace("</Relationships>", regionRelationship(name, "footer") + "</Relationships>"); }
        }
        StringBuilder linkRels = new StringBuilder();
        hyperlinks.forEach((target, id) -> linkRels.append("<Relationship Id=\"").append(id).append("\" Type=\"").append(R)
                .append("/hyperlink\" Target=\"").append(escape(target)).append("\" TargetMode=\"External\"/>"));
        wordRels = wordRels.replace("</Relationships>", linkRels + images.relationships() + "</Relationships>");
        String core = XML + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>" + escape(document.metadata().title()) + "</dc:title><dc:creator>" + escape(document.metadata().author()) + "</dc:creator></cp:coreProperties>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", contentTypes); parts.put("_rels/.rels", rootRels); parts.put("docProps/core.xml", core);
        parts.put("word/document.xml", main.toString()); parts.put("word/styles.xml", styles.toString());
        parts.put("word/numbering.xml", numbering.toString()); parts.put("word/_rels/document.xml.rels", wordRels);
        if (fields.present() || hasEvenVariants) {
            parts.put("word/settings.xml", XML + "<w:settings" + NS + ">"
                    + (hasEvenVariants ? "<w:evenAndOddHeaders/>" : "")
                    + (fields.present() ? "<w:updateFields w:val=\"true\"/>" : "") + "</w:settings>");
            parts.put("[Content_Types].xml", contentTypes.replace("</Types>", "<Override PartName=\"/word/settings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml\"/></Types>"));
            parts.put("word/_rels/document.xml.rels", wordRels.replace("</Relationships>", "<Relationship Id=\"rSettings\" Type=\"" + reltype + "settings\" Target=\"settings.xml\"/></Relationships>"));
        }
        parts.putAll(regionParts);
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (var entry : parts.entrySet()) {
            bytes.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        bytes.putAll(images.parts());
        long total = 0;
        if (bytes.size() > limits.maxEntries()) { throw new IOException("DOCX output entry budget exceeded"); }
        for (var data : bytes.values()) {
            total += data.length;
            if (data.length > limits.maxEntryBytes() || total > limits.maxExpandedBytes()) { throw new IOException("DOCX expanded output budget exceeded"); }
        }
        try (var zip = new ZipOutputStream(new BorrowedOutput(output))) {
            for (var entry : bytes.entrySet()) {
                var target = new ZipEntry(entry.getKey()); target.setTime(0); zip.putNextEntry(target); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
    }

    private static Paragraph paragraph(Block block) throws IOException {
        return switch (block) {
            case Paragraph p -> p;
            case Heading h -> h.paragraph();
            case NumberedItem n -> n.paragraph();
            case BulletItem b -> b.paragraph();
            case PageBreak b -> new Paragraph(b.id(), "", ParagraphFormat.of(12));
            default -> throw new IOException("unsupported DOCX block type: " + block.getClass().getSimpleName());
        };
    }

    private static void properties(StringBuilder xml, ParagraphFormat f, int heading, int number, int level) {
        xml.append("<w:pPr od:minBefore=\"").append(f.pagination().minLinesBeforeBreak()).append("\" od:minAfter=\"").append(f.pagination().minLinesAfterBreak()).append("\">");
        if (heading > 0) { xml.append("<w:pStyle w:val=\"Heading").append(heading).append("\"/>"); }
        if (f.pagination().keepWithNext()) { xml.append("<w:keepNext/>"); }
        if (f.keepTogether()) { xml.append("<w:keepLines/>"); }
        if (f.pagination().pageBreakBefore()) { xml.append("<w:pageBreakBefore/>"); }
        if (number > 0) { xml.append("<w:numPr><w:ilvl w:val=\"").append(level - 1).append("\"/><w:numId w:val=\"").append(number).append("\"/></w:numPr>"); }
        if (f.lineBreakPolicy() == com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT) {
            xml.append("<w:kinsoku od:policy=\"CJK_STRICT\"/>");
        }
        xml.append("<w:spacing w:lineRule=\"exact\" w:line=\"").append(twips(f.lineHeight())).append("\" w:before=\"").append(twips(f.spaceBefore()))
                .append("\" w:after=\"").append(twips(f.spaceAfter())).append("\"/>");
        xml.append("<w:ind w:left=\"").append(twips(f.subsequentLineIndent())).append("\"");
        if (f.firstLineIndent() >= f.subsequentLineIndent()) { xml.append(" w:firstLine=\"").append(twips(f.firstLineIndent() - f.subsequentLineIndent())); }
        else { xml.append(" w:hanging=\"").append(twips(f.subsequentLineIndent() - f.firstLineIndent())); }
        xml.append("\"/>");
        xml.append("<w:jc w:val=\"").append(f.alignment() == ParagraphAlignment.JUSTIFY ? "both" : f.alignment().name().toLowerCase(Locale.ROOT)).append("\"/>");
        xml.append("<w:rPr><w:sz w:val=\"").append(Math.round(f.fontSize() * 2)).append("\"/></w:rPr>");
        xml.append("</w:pPr>");
    }

    private static void runs(StringBuilder xml, Paragraph paragraph, Map<String, String> hyperlinks, DocxImages.Writer images, DocxFields.Writer fields) throws IOException {
        for (var content : paragraph.content()) {
            if (content instanceof InlineField field) { fields.write(xml, field); continue; }
            String target = content instanceof InlineImage image ? image.hyperlink() : ((InlineText) content).hyperlink();
            String id = target.isEmpty() ? "" : hyperlinks.computeIfAbsent(target, ignored -> "rId" + (hyperlinks.size() + 3));
            if (content instanceof InlineImage image) {
                xml.append("<w:r><w:rPr><w:position w:val=\"").append(Math.round(-image.baselineShift() * 2))
                        .append("\"/></w:rPr>").append(images.drawing(image, id)).append("</w:r>");
                continue;
            }
            if (!target.isEmpty()) { xml.append("<w:hyperlink r:id=\"").append(id).append("\">"); }
            var inline = (InlineText) content;
            xml.append("<w:r>");
            runProperties(xml, inline.style());
            String normalized = inline.text().replace("\r\n", "\n").replace('\r', '\n');
            String[] lines = normalized.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (i > 0) { xml.append("<w:br/>"); }
                String[] tabs = lines[i].split("\t", -1);
                for (int tab = 0; tab < tabs.length; tab++) {
                    if (tab > 0) { xml.append("<w:tab/>"); }
                    xml.append("<w:t xml:space=\"preserve\">").append(escape(tabs[tab])).append("</w:t>");
                }
            }
            xml.append("</w:r>");
            if (!inline.hyperlink().isEmpty()) { xml.append("</w:hyperlink>"); }
        }
    }

    static void runProperties(StringBuilder xml, InlineStyle s) {
        xml.append("<w:rPr>");
        if (!s.fontRole().isEmpty()) { xml.append("<w:rStyle w:val=\"").append(escape(s.fontRole())).append("\"/>"); }
        if (s.strikeThrough()) { xml.append("<w:strike/>"); }
        xml.append("<w:color w:val=\"").append(String.format(Locale.ROOT, "%06X", s.rgb()))
                .append("\"/><w:position w:val=\"").append(Math.round(-s.baselineShift() * 2))
                .append("\"/><w:sz w:val=\"").append(Math.round(s.fontSize() * 2)).append("\"/>");
        if (s.underline()) { xml.append("<w:u w:val=\"single\"/>"); }
        xml.append("</w:rPr>");
    }

    private static String roleProperties(String role) {
        return switch (role) {
            case "bold" -> "<w:rPr><w:b/></w:rPr>";
            case "italic" -> "<w:rPr><w:i/></w:rPr>";
            case "boldItalic" -> "<w:rPr><w:b/><w:i/></w:rPr>";
            default -> "";
        };
    }

    private static void numbering(StringBuilder xml, int id, int[] numbers, String bullet, InlineStyle markerStyle) {
        xml.append("<w:abstractNum w:abstractNumId=\"").append(id).append("\"><w:multiLevelType w:val=\"multilevel\"/>");
        for (int level = 0; level < 9; level++) {
            String label = bullet;
            if (bullet.isEmpty()) {
                StringBuilder pattern = new StringBuilder();
                for (int i = 1; i <= level + 1; i++) { pattern.append('%').append(i).append('.'); }
                label = pattern.toString();
            }
            xml.append("<w:lvl w:ilvl=\"").append(level).append("\"><w:start w:val=\"1\"/><w:numFmt w:val=\"").append(bullet.isEmpty() ? "decimal" : "bullet")
                    .append("\"/><w:lvlText w:val=\"").append(escape(label)).append("\"/>");
            runProperties(xml, markerStyle);
            xml.append("</w:lvl>");
        }
        xml.append("</w:abstractNum><w:num w:numId=\"").append(id).append("\"><w:abstractNumId w:val=\"").append(id).append("\"/>");
        for (int level = 0; level < 9; level++) {
            if (numbers[level] > 0) { xml.append("<w:lvlOverride w:ilvl=\"").append(level).append("\"><w:startOverride w:val=\"").append(numbers[level]).append("\"/></w:lvlOverride>"); }
        }
        xml.append("</w:num>");
    }

    private static String regionContentType(String name, String kind) {
        return "<Override PartName=\"/word/" + name + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml." + kind + "+xml\"/>";
    }
    private static String regionRelationship(String name, String kind) {
        return "<Relationship Id=\"" + name + "\" Type=\"" + R + "/" + kind + "\" Target=\"" + name + ".xml\"/>";
    }
    private static void regionReferences(StringBuilder xml, String type, PageRegion header, PageRegion footer, double width,
            Map<RegionKey, String> headers, Map<RegionKey, String> footers) {
        xml.append("<w:headerReference w:type=\"").append(type).append("\" r:id=\"")
                .append(headers.get(regionKey(header, width))).append("\"/>")
                .append("<w:footerReference w:type=\"").append(type).append("\" r:id=\"")
                .append(footers.get(regionKey(footer, width))).append("\"/>");
    }
    private static void section(StringBuilder xml, Section section, Map<RegionKey, String> headers,
            Map<RegionKey, String> footers, boolean hasRegions, boolean hasEvenVariants) {
        var s = section.pageSettings(); var m = s.margins(); var regions = section.pageRegions();
        double top = regions.headers().stream().mapToDouble(band -> band.height() + band.gap()).max().orElse(0);
        double bottom = regions.footers().stream().mapToDouble(band -> band.height() + band.gap()).max().orElse(0);
        xml.append("<w:sectPr od:id=\"").append(escape(section.id())).append("\"");
        if (hasEvenVariants && !regions.variants().containsKey(PageRegions.Variant.EVEN)) { xml.append(" od:evenDefault=\"true\""); }
        xml.append('>');
        if (hasRegions) {
            regionReferences(xml, "default", regions.header(), regions.footer(), s.contentWidth(), headers, footers);
            var first = regions.variants().get(PageRegions.Variant.FIRST);
            if (first != null) { regionReferences(xml, "first", first.header(), first.footer(), s.contentWidth(), headers, footers); }
            if (hasEvenVariants) {
                var even = regions.variants().getOrDefault(PageRegions.Variant.EVEN, new PageRegions.Bands(regions.header(), regions.footer()));
                regionReferences(xml, "even", even.header(), even.footer(), s.contentWidth(), headers, footers);
            }
        }
        xml.append("<w:type w:val=\"nextPage\"/><w:pgSz w:w=\"").append(twips(s.size().width()))
                .append("\" w:h=\"").append(twips(s.size().height())).append("\"/><w:pgMar w:top=\"")
                .append(twips(m.top() + top)).append("\" w:right=\"").append(twips(m.right()))
                .append("\" w:bottom=\"").append(twips(m.bottom() + bottom)).append("\" w:left=\"").append(twips(m.left()));
        if (hasRegions) { xml.append("\" w:header=\"").append(twips(m.top())).append("\" w:footer=\"").append(twips(m.bottom())); }
        xml.append("\"/>");
        if (section.firstPageNumber() > 0) { xml.append("<w:pgNumType w:start=\"").append(section.firstPageNumber()).append("\"/>"); }
        if (regions.variants().containsKey(PageRegions.Variant.FIRST)) { xml.append("<w:titlePg/>"); }
        xml.append("</w:sectPr>");
    }

    private static void checkBlockTable(Table table) throws IOException {
        if (table.hasBlockCells()) {
            if (table.columns().stream().anyMatch(ColumnWidth.Auto.class::isInstance)) {
                throw new IOException("DOCX block-cell tables require fixed or fractional columns");
            }
        }
        for (int row = 0; row < table.headerRows(); row++) {
            if (table.rows().get(row).cells().stream().anyMatch(TableCell::hasBlocks)) {
                throw new IOException("DOCX repeated parent table headers cannot contain block cells");
            }
        }
    }

    private static void table(StringBuilder xml, Table table, double availableWidth, Map<String, String> hyperlinks,
            DocxImages.Writer images, DocxFields.Writer fields, Set<String> roles, List<String> ancestorAnchors) throws IOException {
        var padding = table.format().padding();
        xml.append("<w:tbl od:id=\"").append(escape(table.id())).append("\" od:split=\"").append(table.format().allowRowSplit()).append("\" od:before=\"")
                .append(table.format().spaceBefore()).append("\" od:after=\"").append(table.format().spaceAfter()).append("\"><w:tblPr>");
        xml.append("<w:tblBorders>");
        for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            xml.append("<w:").append(edge).append(" w:val=\"").append(table.format().drawBorders() ? "single" : "nil").append("\"/>");
        }
        xml.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"").append(twips(padding.top()))
                .append("\" w:type=\"dxa\"/><w:left w:w=\"").append(twips(padding.left()))
                .append("\" w:type=\"dxa\"/><w:bottom w:w=\"").append(twips(padding.bottom()))
                .append("\" w:type=\"dxa\"/><w:right w:w=\"").append(twips(padding.right()))
                .append("\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>");
        double fixed = table.columns().stream().filter(ColumnWidth.Fixed.class::isInstance).map(ColumnWidth.Fixed.class::cast).mapToDouble(ColumnWidth.Fixed::points).sum();
        double weights = table.columns().stream().mapToDouble(c -> c instanceof ColumnWidth.Fraction f ? f.weight() : c instanceof ColumnWidth.Auto ? 1 : 0).sum();
        if (!Double.isFinite(fixed) || !Double.isFinite(weights)) { throw new IOException("DOCX column width sum is not finite"); }
        if (fixed > availableWidth || weights > 0 && fixed >= availableWidth) { throw new IOException("DOCX table widths exceed the containing content width"); }
        double[] widths = new double[table.columns().size()];
        int columnIndex = 0;
        for (var column : table.columns()) {
            double width = column instanceof ColumnWidth.Fixed f ? f.points() : (availableWidth - fixed) * (column instanceof ColumnWidth.Fraction f ? f.weight() : 1) / weights;
            widths[columnIndex++] = width;
            xml.append("<w:gridCol w:w=\"").append(twips(width)).append("\" od:weight=\"").append(column instanceof ColumnWidth.Fraction f ? f.weight() : 0).append('"');
            if (column instanceof ColumnWidth.Auto auto) { xml.append(" od:autoMin=\"").append(auto.minPoints()).append("\" od:autoMax=\"").append(auto.maxPoints()).append('"'); }
            xml.append("/>");
        }
        xml.append("</w:tblGrid>");
        var tableAnchors = new ArrayList<>(ancestorAnchors); tableAnchors.add(table.id());
        var grid = table.grid();
        var active = new TreeMap<Integer, TableGrid.Anchor>();
        for (int row = 0; row < table.rows().size(); row++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            for (var iterator = active.values().iterator(); iterator.hasNext();) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var anchor = iterator.next();
                if (row - anchor.rowIndex() >= anchor.cell().rowSpan()) { iterator.remove(); }
            }
            for (var anchor : grid.anchorRows().get(row)) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                active.put(anchor.columnIndex(), anchor);
            }
            xml.append("<w:tr><w:trPr>");
            if (!table.format().allowRowSplit()) { xml.append("<w:cantSplit/>"); }
            if (row < table.headerRows()) { xml.append("<w:tblHeader/>"); }
            xml.append("</w:trPr>");
            boolean firstCell = row == 0;
            for (var anchor : active.values()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var cell = anchor.cell();
                boolean continuation = anchor.rowIndex() != row;
                xml.append("<w:tc");
                if (cell.hasBlocks() && !continuation) {
                    var format = cell.format();
                    xml.append(" od:content=\"blocks\" od:fontSize=\"").append(format.fontSize())
                            .append("\" od:lineHeight=\"").append(format.lineHeight()).append("\" od:alignment=\"")
                            .append(format.alignment()).append("\" od:lineBreak=\"").append(format.lineBreakPolicy()).append('"');
                }
                xml.append('>');
                if (cell.columnSpan() > 1 || cell.rowSpan() > 1) {
                    xml.append("<w:tcPr>");
                    if (cell.columnSpan() > 1) { xml.append("<w:gridSpan w:val=\"").append(cell.columnSpan()).append("\"/>"); }
                    if (cell.rowSpan() > 1) { xml.append("<w:vMerge w:val=\"").append(continuation ? "continue" : "restart").append("\"/>"); }
                    xml.append("</w:tcPr>");
                }
                if (continuation) { xml.append("<w:p/></w:tc>"); continue; }
                var anchors = firstCell ? tableAnchors : List.<String>of();
                if (cell.hasBlocks()) {
                    double cellWidth = 0;
                    for (int col = anchor.columnIndex(); col < anchor.columnIndex() + cell.columnSpan(); col++) { cellWidth += widths[col]; }
                    cellWidth -= padding.left() + padding.right();
                    if (!Double.isFinite(cellWidth) || cellWidth <= 0) { throw new IOException("DOCX block cell has no positive content width"); }
                    for (int childIndex = 0; childIndex < cell.blocks().size(); childIndex++) {
                        var block = cell.blocks().get(childIndex);
                        var pending = childIndex == 0 ? anchors : List.<String>of();
                        if (block instanceof Table nested) { table(xml, nested, cellWidth, hyperlinks, images, fields, roles, pending); }
                        else {
                            var paragraph = (Paragraph) block;
                            paragraph.inlines().forEach(inline -> { if (!inline.style().fontRole().isEmpty()) { roles.add(inline.style().fontRole()); } });
                            xml.append("<w:p od:id=\"").append(escape(paragraph.id())).append("\" od:kind=\"paragraph\">");
                            properties(xml, paragraph.format(), 0, 0, 1);
                            pending.forEach(id -> fields.start(xml, id)); fields.start(xml, paragraph.id());
                            runs(xml, paragraph, hyperlinks, images, fields);
                            fields.end(xml, paragraph.id());
                            for (int a = pending.size() - 1; a >= 0; a--) { fields.end(xml, pending.get(a)); }
                            xml.append("</w:p>");
                        }
                    }
                    if (cell.blocks().getLast() instanceof Table) { xml.append("<w:p od:cellTail=\"true\"/>"); }
                    xml.append("</w:tc>"); firstCell = false; continue;
                }
                xml.append("<w:p>");
                var f = cell.format();
                var format = new ParagraphFormat(f.fontSize(), f.lineHeight(), 0, 0, f.alignment(), 0, 0, false).withLineBreakPolicy(f.lineBreakPolicy());
                cell.inlines().forEach(inline -> { if (!inline.style().fontRole().isEmpty()) { roles.add(inline.style().fontRole()); } });
                properties(xml, format, 0, 0, 1);
                anchors.forEach(id -> fields.start(xml, id));
                runs(xml, new Paragraph("cell", cell.text(), format, cell.inlines(), cell.images(), cell.fields()), hyperlinks, images, fields);
                for (int a = anchors.size() - 1; a >= 0; a--) { fields.end(xml, anchors.get(a)); }
                firstCell = false;
                xml.append("</w:p></w:tc>");
            }
            xml.append("</w:tr>");
        }
        xml.append("</w:tbl>");
    }

    public ReadResult read(InputStream input, Policy policy) throws IOException {
        return readWithWidth(input, policy, 0);
    }

    /** Imports native fields with an explicit fallback reserved width in points when engine metadata is absent.
     * The width must be finite and positive. Present field metadata takes precedence and must be valid.
     * This does not constrain Word's own pagination. The input remains caller-owned.
     */
    public ReadResult read(InputStream input, Policy policy, double defaultFieldWidth) throws IOException {
        if (!Double.isFinite(defaultFieldWidth) || defaultFieldWidth <= 0 || defaultFieldWidth > Float.MAX_VALUE) {
            throw new IllegalArgumentException("default field width must be finite and positive");
        }
        return readWithWidth(input, policy, defaultFieldWidth);
    }

    private ReadResult readWithWidth(InputStream input, Policy policy, double defaultFieldWidth) throws IOException {
        Objects.requireNonNull(policy, "policy");
        try { return readPackage(Objects.requireNonNull(input, "input"), policy, defaultFieldWidth); }
        catch (IllegalArgumentException | ArithmeticException failure) { throw new IOException("invalid imported DOCX semantics", failure); }
    }

    private ReadResult readPackage(InputStream input, Policy policy, double defaultFieldWidth) throws IOException {
        return readPackage(input, policy, defaultFieldWidth, false);
    }
    private ReadResult readPackage(InputStream input, Policy policy, double defaultFieldWidth, boolean alreadyMaterialized) throws IOException {
        var parts = unzip(Objects.requireNonNull(input, "input"));
        if (!parts.containsKey("[Content_Types].xml") || !parts.containsKey("word/document.xml")) { throw new IOException("DOCX document parts are missing"); }
        List<Diagnostic> diagnostics = new ArrayList<>();
        boolean stylesMaterialized = alreadyMaterialized || DocxStyles.materialize(parts, fontFamilies, policy, diagnostics, limits);
        Element relations = parts.containsKey("word/_rels/document.xml.rels") ? parse(parts.get("word/_rels/document.xml.rels")) : null;
        var decoded = new HashMap<String, com.mirakyux.export4j.model.ImageResource>();
        var images = new DocxImages.Reader(parts, relations, decoded);
        var semanticBudget = new DocxCells.Budget(limits);
        var regionReader = new DocxPageRegions(parts, relations, regionGeometry, policy, diagnostics, semanticBudget, decoded, defaultFieldWidth);
        for (var entry : parts.entrySet()) {
            String part = entry.getKey();
            if (part.toLowerCase(Locale.ROOT).contains("vbaproject")) { throw new IOException("macro parts are unsupported"); }
            if (part.endsWith(".xml") || part.endsWith(".rels")) {
                var root = parse(entry.getValue());
                if (part.endsWith(".rels")) {
                    for (var relation : children(root)) {
                        if ("External".equals(relation.getAttribute("TargetMode"))) {
                            if ((part.equals("word/_rels/document.xml.rels") || regionReader.contains(part)) && relation.getAttribute("Type").equals(R + "/hyperlink")) {
                                new com.mirakyux.export4j.model.ExternalDestination(relation.getAttribute("Target"));
                            } else { loss(policy, diagnostics, part, "Relationship", "external relationship is not loaded"); }
                        }
                    }
                }
                if (part.equals("[Content_Types].xml")) {
                    for (var type : children(root)) {
                        String content = type.getAttribute("ContentType").toLowerCase(Locale.ROOT);
                        if (content.contains("macroenabled") || content.contains("vba")) { throw new IOException("macro-enabled DOCX is unsupported"); }
                    }
                }
                if (part.equals("word/styles.xml") && !stylesMaterialized) { validateStyles(root, policy, diagnostics); }
            }

            if (part.startsWith("word/") && !images.contains(part) && !regionReader.contains(part) && !Set.of("word/document.xml", "word/styles.xml", "word/numbering.xml", "word/settings.xml", "word/fontTable.xml", "word/webSettings.xml", "word/_rels/document.xml.rels").contains(part)) {
                loss(policy, diagnostics, part, "part", "unsupported document part");
            }
        }
        var root = parse(parts.get("word/document.xml"));
        if (!W.equals(root.getNamespaceURI()) || !"document".equals(root.getLocalName())) { throw new IOException("unsupported OOXML document namespace"); }
        validateMarkup(root, policy, diagnostics);
        var main = child(root, "body");
        if (main == null) { throw new IOException("DOCX body is missing"); }
        var ids = DocxCells.index(main, semanticBudget, regionReader.usedIds(), "");
        DocxNumbering.Index numbers = parts.containsKey("word/numbering.xml")
                ? DocxNumbering.read(parse(parts.get("word/numbering.xml")), limits, policy, diagnostics) : new DocxNumbering.Index(Map.of(), Set.of());
        var fields = new DocxFields.Reader(main, ids, policy, diagnostics, defaultFieldWidth, numbers.levels());
        Map<String, String> hyperlinks = new HashMap<>();
        if (parts.containsKey("word/_rels/document.xml.rels")) {
            for (var relation : children(parse(parts.get("word/_rels/document.xml.rels")))) {
                if (relation.getAttribute("Type").equals(R + "/hyperlink") && relation.getAttribute("TargetMode").equals("External")) {
                    String target = new com.mirakyux.export4j.model.ExternalDestination(relation.getAttribute("Target")).uri();
                    if (hyperlinks.putIfAbsent(relation.getAttribute("Id"), target) != null) { throw new IOException("duplicate DOCX relationship ID"); }
                }
            }
        }
        List<Section> sections = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        for (var element : children(main)) {
            if (!W.equals(element.getNamespaceURI())) { continue; }
            if (element.getLocalName().equals("sectPr")) {
                sections.add(regionReader.attach(readSection(element, sections.size(), blocks), element)); blocks = new ArrayList<>();
            } else if (element.getLocalName().equals("p")) {
                String id = ids.get(element);
                if (!element.getAttributeNS(OD, "boundary").equals("true")) {
                    blocks.add(readParagraph(element, id, numbers, hyperlinks, images, fields, policy, diagnostics, semanticBudget));
                }
                var sect = child(child(element, "pPr"), "sectPr");
                if (sect != null) { sections.add(regionReader.attach(readSection(sect, sections.size(), blocks), sect)); blocks = new ArrayList<>(); }
            } else if (element.getLocalName().equals("tbl")) {
                blocks.add(readTable(element, ids, numbers, hyperlinks, images, fields, policy, diagnostics, semanticBudget, 1));
            }
        }
        if (!blocks.isEmpty() || sections.isEmpty()) { sections.add(new Section("section-" + sections.size(), PageSettings.A4, blocks, 0)); }
        DocumentMetadata metadata = DocumentMetadata.EMPTY;
        if (parts.containsKey("docProps/core.xml")) {
            var core = parse(parts.get("docProps/core.xml"));
            metadata = new DocumentMetadata(descendantText(core, "http://purl.org/dc/elements/1.1/", "title"), descendantText(core, "http://purl.org/dc/elements/1.1/", "creator"));
        }
        try { return new ReadResult(new SectionedDocument(metadata, sections), diagnostics); }
        catch (IllegalArgumentException failure) { throw new IOException("invalid imported DOCX semantics", failure); }
    }

    static Block readParagraph(Element p, String id, DocxNumbering.Index numbers, Map<String, String> hyperlinks, DocxImages.Reader images, DocxFields.Reader fields, Policy policy, List<Diagnostic> diagnostics, DocxCells.Budget budget) throws IOException {
        fields.normalize(p);
        var pr = child(p, "pPr");
        var f = format(pr);
        List<InlineContent> contents = new ArrayList<>();
        boolean pageBreak = p.getAttributeNS(OD, "kind").equals("break");
        List<Element> runs = new ArrayList<>();
        Map<Element, String> destinations = new HashMap<>();
        for (var node : children(p)) {
            if (W.equals(node.getNamespaceURI()) && node.getLocalName().equals("hyperlink")) {
                String destination = hyperlinks.get(node.getAttributeNS(R, "id"));
                if (destination == null) { loss(policy, diagnostics, "word/document.xml", "hyperlink", "hyperlink destination is missing or unsupported"); destination = ""; }
                for (var run : children(node)) { runs.add(run); destinations.put(run, destination); }
            } else { runs.add(node); }
        }
        for (var run : runs) {
            if (W.equals(run.getNamespaceURI()) && run.getLocalName().equals("fldSimple")) {
                if (destinations.containsKey(run)) {
                    loss(policy, diagnostics, "word/document.xml", "hyperlink", "field inside external hyperlink loses its external destination");
                }
                contents.addAll(fields.read(run, f.fontSize()));
                continue;
            }
            if (!"r".equals(run.getLocalName()) || !W.equals(run.getNamespaceURI())) { continue; }
            var rpr = child(run, "rPr");
            var style = inline(rpr, f.fontSize());
            String destination = destinations.getOrDefault(run, "");
            var text = new StringBuilder();
            for (var content : children(run)) {
                if (!W.equals(content.getNamespaceURI())) { continue; }
                switch (content.getLocalName()) {
                    case "t" -> text.append(content.getTextContent());
                    case "tab" -> text.append('\t');
                    case "br" -> {
                        if ("page".equals(content.getAttributeNS(W, "type"))) { pageBreak = true; }
                        else { text.append('\n'); }
                    }
                    case "drawing" -> {
                        if (!text.isEmpty()) { contents.add(new InlineText(text.toString(), style, destination)); text.setLength(0); }
                        try {
                            for (var drawing : children(content)) { DocxImages.validate(drawing); }
                            contents.add(images.read(content, style.baselineShift(), destination));
                        } catch (IOException failure) {
                            if (failure instanceof DocxImages.LimitException) { throw failure; }
                            loss(policy, diagnostics, "word/document.xml", "drawing", "unsupported or invalid inline image");
                        }
                    }
                    default -> { }
                }
            }
            if (!text.isEmpty()) { contents.add(new InlineText(text.toString(), style, destination)); }
        }
        for (var content : contents) { budget.text(content instanceof InlineText text ? text.text() : "\uFFFC"); }
        var mixed = Paragraph.mixed(id, contents, f);
        var inlines = mixed.inlines();
        String text = inlines.stream().map(InlineText::text).collect(java.util.stream.Collectors.joining());
        if (pageBreak && !text.isEmpty()) { loss(policy, diagnostics, "word/document.xml", "br", "inline page break cannot be represented inside a paragraph"); }
        if (pageBreak && text.isEmpty()) { return new PageBreak(id); }
        String heading = value(child(pr, "pStyle"), "");
        if ((!mixed.images().isEmpty() || !mixed.fields().isEmpty()) && (heading.matches("(?i)Heading[1-6]") || child(pr, "numPr") != null)) {
            loss(policy, diagnostics, "word/document.xml", "pPr", "image or field heading or list is imported as a paragraph");
            return mixed;
        }
        if (heading.matches("(?i)Heading[1-6]")) {
            return new Heading(id, text, Integer.parseInt(heading.substring(7)), f, semanticRuns(inlines, f));
        }
        if (!heading.isEmpty()) { loss(policy, diagnostics, "word/document.xml", "pStyle", "paragraph style inheritance is unsupported"); }
        var num = child(pr, "numPr");
        if (num != null) {
            int level = integer(value(child(num, "ilvl"), "0")) + 1;
            String numId = Integer.toString(integer(value(child(num, "numId"), "")));
            var definition = numbers.levels().get(numId + ":" + (level - 1));
            var markerStyle = definition == null ? InlineStyle.of(f.fontSize()) : inline(child(definition, "rPr"), f.fontSize());
            if (definition != null && !p.hasAttributeNS(OD, "restart") && numbers.overrides().contains(numId)) {
                loss(policy, diagnostics, "word/numbering.xml", "lvlOverride", "external numbering overrides are unsupported");
            }
            String marker = p.getAttributeNS(OD, "marker");
            if (definition != null && value(child(definition, "numFmt"), "decimal").equals("bullet")) { marker = value(child(definition, "lvlText"), "•"); }
            if (!marker.isEmpty()) { return new BulletItem(id, text, marker, level, f, semanticRuns(inlines, f), markerStyle); }
            if (definition == null) { loss(policy, diagnostics, "word/numbering.xml", "numId", "numbering definition is missing"); }
            else if (!value(child(definition, "numFmt"), "decimal").equals("decimal")) { loss(policy, diagnostics, "word/numbering.xml", "numFmt", "only decimal and bullet numbering are supported"); }
            if (definition != null && !p.hasAttributeNS(OD, "restart")) {
                if (!value(child(definition, "start"), "1").equals("1")) { loss(policy, diagnostics, "word/numbering.xml", "start", "external numbering start is unsupported"); }
                StringBuilder label = new StringBuilder();
                for (int i = 1; i <= level; i++) { label.append('%').append(i).append('.'); }
                if (!value(child(definition, "lvlText"), label.toString()).equals(label.toString())) { loss(policy, diagnostics, "word/numbering.xml", "lvlText", "custom decimal label is unsupported"); }
            }
            return new NumberedItem(id, attribute(p, OD, "listId", "num-" + numId), text,
                    integer(attribute(p, OD, "restart", "0")), f, level, semanticRuns(inlines, f), markerStyle);
        }
        return mixed;
    }

    private static List<InlineText> semanticRuns(List<InlineText> runs, ParagraphFormat format) {
        return runs.stream().allMatch(run -> run.style().equals(InlineStyle.of(format.fontSize())) && run.hyperlink().isEmpty()) ? List.of() : runs;
    }

    static ParagraphFormat format(Element pr) throws IOException {
        double size = number(value(child(child(pr, "rPr"), "sz"), "24")) / 2;
        var spacing = child(pr, "spacing");
        double line = attributeNumber(spacing, W, "line", size * 30) / 20;
        if (!attribute(spacing, W, "lineRule", "auto").equals("exact")) { line = attributeNumber(spacing, W, "line", 240) / 240 * size * 1.5; }
        var ind = child(pr, "ind");
        double subsequent = attributeNumber(ind, W, "left", 0) / 20;
        double first = subsequent + (attributeNumber(ind, W, "firstLine", 0) - attributeNumber(ind, W, "hanging", 0)) / 20;
        ParagraphAlignment alignment = switch (value(child(pr, "jc"), "left")) { case "center" -> ParagraphAlignment.CENTER; case "right", "end" -> ParagraphAlignment.RIGHT; case "both" -> ParagraphAlignment.JUSTIFY; default -> ParagraphAlignment.LEFT; };
        return new ParagraphFormat(size, line, Math.max(0, first), Math.max(0, subsequent), alignment,
                attributeNumber(spacing, W, "before", 0) / 20, attributeNumber(spacing, W, "after", 0) / 20, enabled(child(pr, "keepLines")),
                new PaginationPolicy(enabled(child(pr, "keepNext")), enabled(child(pr, "pageBreakBefore")),
                        integer(attribute(pr, OD, "minBefore", "1")), integer(attribute(pr, OD, "minAfter", "1"))))
                .withLineBreakPolicy("CJK_STRICT".equals(attribute(child(pr, "kinsoku"), OD, "policy", ""))
                        ? com.mirakyux.export4j.model.LineBreakPolicy.CJK_STRICT : com.mirakyux.export4j.model.LineBreakPolicy.STANDARD);
    }

    static InlineStyle inline(Element pr, double defaultSize) throws IOException {
        String role = value(child(pr, "rStyle"), "");
        if (role.isEmpty()) {
            boolean bold = enabled(child(pr, "b")); boolean italic = enabled(child(pr, "i"));
            role = bold && italic ? "boldItalic" : bold ? "bold" : italic ? "italic" : "";
        }
        String color = value(child(pr, "color"), "000000");
        int rgb;
        try { rgb = color.equals("auto") ? 0 : Integer.parseInt(color, 16); } catch (NumberFormatException failure) { throw new IOException("invalid DOCX color", failure); }
        double position = number(value(child(pr, "position"), "0"));
        return new InlineStyle(role, number(value(child(pr, "sz"), Double.toString(defaultSize * 2))) / 2, rgb,
                position == 0 ? 0 : -position / 2, child(pr, "u") != null && !value(child(pr, "u"), "single").equals("none"), enabled(child(pr, "strike")));
    }

    static Table readTable(Element table, Map<Element, String> ids, DocxNumbering.Index numbers,
            Map<String, String> hyperlinks, DocxImages.Reader images, DocxFields.Reader fields,
            Policy policy, List<Diagnostic> diagnostics, DocxCells.Budget budget, int depth) throws IOException {
        if (depth > com.mirakyux.export4j.model.SemanticTree.MAX_TABLE_DEPTH) { throw new IOException("DOCX semantic table nesting budget exceeded"); }
        if (children(table).stream().filter(element -> W.equals(element.getNamespaceURI()) && element.getLocalName().equals("tblGrid")).count() > 1) {
            throw new IOException("DOCX table has multiple column grids");
        }
        List<ColumnWidth> columns = new ArrayList<>();
        for (var col : children(child(table, "tblGrid"))) {
            double weight = attributeNumber(col, OD, "weight", 0);
            columns.add(col.hasAttributeNS(OD, "autoMin") ? new ColumnWidth.Auto(attributeNumber(col, OD, "autoMin", 1), attributeNumber(col, OD, "autoMax", 14400))
                    : weight > 0 ? new ColumnWidth.Fraction(weight) : new ColumnWidth.Fixed(attributeNumber(col, W, "w", 1440) / 20));
        }
        var wordRows = children(table).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("tr")).toList();
        int headers = 0;
        for (int rowIndex = 0; rowIndex < wordRows.size(); rowIndex++) {
            var properties = children(wordRows.get(rowIndex)).stream().filter(element -> W.equals(element.getNamespaceURI())
                    && element.getLocalName().equals("trPr")).toList();
            if (properties.size() > 1) { throw new IOException("DOCX row has multiple property containers"); }
            var headerFlags = properties.isEmpty() ? List.<Element>of() : children(properties.getFirst()).stream()
                    .filter(element -> W.equals(element.getNamespaceURI()) && element.getLocalName().equals("tblHeader")).toList();
            if (headerFlags.size() > 1) { throw new IOException("DOCX row has multiple header flags"); }
            if (!properties.isEmpty() && (child(properties.getFirst(), "gridBefore") != null
                    || child(properties.getFirst(), "gridAfter") != null)) {
                throw new IOException("DOCX rows must cover the complete grid without omitted slots");
            }
            if (!headerFlags.isEmpty() && enabled(headerFlags.getFirst())) {
                if (rowIndex != headers) { throw new IOException("DOCX header rows must precede body rows"); }
                headers++;
            }
        }
        List<List<ReadAnchor>> anchors = new ArrayList<>();
        Map<Integer, ReadAnchor> active = Map.of();
        for (int rowIndex = 0; rowIndex < wordRows.size(); rowIndex++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            var row = wordRows.get(rowIndex);
            List<ReadAnchor> local = new ArrayList<>();
            Map<Integer, ReadAnchor> continued = new HashMap<>();
            int column = 0;
            for (var cell : children(row)) {
                if (!W.equals(cell.getNamespaceURI()) || !cell.getLocalName().equals("tc")) { continue; }
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                int columnSpan = readColumnSpan(cell);
                if (columnSpan > Integer.MAX_VALUE - column || !columns.isEmpty() && columnSpan > columns.size() - column) {
                    throw new IOException("DOCX cell coverage exceeds the table grid");
                }
                var merge = readVerticalMerge(cell);
                if (merge == VerticalMerge.CONTINUE) {
                    validateContinuationCell(cell);
                    // Proven-empty paragraphs still obey the same style/format loss boundary as real cells.
                    readTableCell(cell, columnSpan, ids, numbers, hyperlinks, images, fields, policy, diagnostics, budget, depth, true);
                    var anchor = active.get(column);
                    if (anchor == null || anchor.cell.columnSpan() != columnSpan) {
                        throw new IOException("DOCX vertical continuation has no matching complete interval");
                    }
                    if ((anchor.rowIndex < headers) != (rowIndex < headers)) {
                        throw new IOException("DOCX vertical merge crosses the header boundary");
                    }
                    anchor.rowSpan++;
                    continued.put(column, anchor);
                } else {
                    var anchor = new ReadAnchor(rowIndex, readTableCell(cell, columnSpan, ids, numbers, hyperlinks,
                            images, fields, policy, diagnostics, budget, depth, false));
                    local.add(anchor);
                    if (merge == VerticalMerge.RESTART) { continued.put(column, anchor); }
                }
                column += columnSpan;
            }
            if (columns.isEmpty()) {
                if (column == 0 || column > Table.MAX_INFERRED_COLUMNS) { throw new IOException("DOCX inferred column count is empty or exceeds limit"); }
                for (int col = 0; col < column; col++) { columns.add(new ColumnWidth.Fraction(1)); }
            }
            if (column != columns.size()) { throw new IOException("DOCX row does not cover the complete table grid"); }
            anchors.add(local);
            active = continued;
        }
        List<TableRow> rows = anchors.stream().map(local -> local.isEmpty() ? TableRow.covered()
                : new TableRow(local.stream().map(anchor -> anchor.cell.toBuilder().rowSpan(anchor.rowSpan).build()).toList())).toList();
        var margin = child(child(table, "tblPr"), "tblCellMar");
        var padding = new PageMargins(attributeNumber(child(margin, "top"), W, "w", 120) / 20, attributeNumber(child(margin, "right"), W, "w", 120) / 20,
                attributeNumber(child(margin, "bottom"), W, "w", 120) / 20, attributeNumber(child(margin, "left"), W, "w", 120) / 20);
        var result = new Table(ids.get(table), columns, rows, headers, new TableFormat(padding, attributeNumber(table, OD, "before", 0), attributeNumber(table, OD, "after", 6),
                attribute(table, OD, "split", "false").equals("true"), readBorders(table)));
        checkBlockTable(result);
        return result;
    }

    private enum VerticalMerge { NONE, RESTART, CONTINUE }

    private static boolean readBorders(Element table) throws IOException {
        var borders = child(child(table, "tblPr"), "tblBorders");
        if (borders == null) { return true; }
        Boolean drawn = null;
        for (String edge : List.of("top", "left", "bottom", "right", "insideH", "insideV")) {
            var border = child(borders, edge);
            if (border == null) { throw new IOException("DOCX mixed or incomplete table borders require explicit normalization"); }
            String value = border.getAttributeNS(W, "val");
            boolean current = !Set.of("nil", "none").contains(value);
            if (current && !value.equals("single") || drawn != null && drawn != current) { throw new IOException("DOCX mixed table borders are unsupported"); }
            drawn = current;
        }
        return Boolean.TRUE.equals(drawn);
    }

    /** Task-confined normalization state; continuation rows never become model content anchors. */
    private static final class ReadAnchor {
        private final int rowIndex;
        private final TableCell cell;
        private int rowSpan = 1;
        private ReadAnchor(int rowIndex, TableCell cell) { this.rowIndex = rowIndex; this.cell = cell; }
    }

    private static TableCell readTableCell(Element cell, int columnSpan, Map<Element, String> ids, DocxNumbering.Index numbers,
            Map<String, String> hyperlinks, DocxImages.Reader images, DocxFields.Reader fields,
            Policy policy, List<Diagnostic> diagnostics, DocxCells.Budget budget, int depth, boolean continuation) throws IOException {
        var content = DocxCells.content(cell);
        if (!continuation && DocxCells.blockCell(cell, content)) {
            if (content.isEmpty()) { throw new IOException("DOCX block cell requires at least one modeled block"); }
            List<Block> blocks = new ArrayList<>();
            for (var element : content) {
                if (element.getLocalName().equals("tbl")) {
                    blocks.add(readTable(element, ids, numbers, hyperlinks, images, fields, policy, diagnostics, budget, depth + 1));
                } else {
                    if (element.hasAttributeNS(OD, "boundary") || child(child(element, "pPr"), "sectPr") != null) {
                        throw new IOException("DOCX block cells cannot contain section boundaries");
                    }
                    var properties = child(element, "pPr");
                    if (child(properties, "numPr") != null || value(child(properties, "pStyle"), "").matches("(?i)Heading[1-6]")
                            || element.getAttributeNS(OD, "kind").equals("break")) {
                        throw new IOException("DOCX block cells cannot contain lists, headings or page breaks");
                    }
                    var breaks = element.getElementsByTagNameNS(W, "br");
                    for (int i = 0; i < breaks.getLength(); i++) {
                        if (((Element) breaks.item(i)).getAttributeNS(W, "type").equals("page")) {
                            throw new IOException("DOCX block cells cannot contain inline page breaks");
                        }
                    }
                    var block = readParagraph(element, ids.get(element), numbers, hyperlinks, images, fields, policy, diagnostics, budget);
                    if (!(block instanceof Paragraph paragraph) || paragraph.format().pagination().pageBreakBefore()
                            || paragraph.format().pagination().keepWithNext()) {
                        throw new IOException("DOCX block cells support plain paragraphs without page-break-before or keep-next only");
                    }
                    blocks.add(block);
                }
            }
            var format = new CellFormat(attributeNumber(cell, OD, "fontSize", 12), attributeNumber(cell, OD, "lineHeight", 18),
                    ParagraphAlignment.valueOf(attribute(cell, OD, "alignment", "LEFT")),
                    com.mirakyux.export4j.model.LineBreakPolicy.valueOf(attribute(cell, OD, "lineBreak", "STANDARD")));
            return TableCell.builder().blocks(blocks).format(format).columnSpan(columnSpan).build();
        }
        var paragraphs = children(cell).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("p")).toList();
        StringBuilder text = new StringBuilder();
        List<InlineText> rich = new ArrayList<>();
        List<Paragraph.ImageSpan> cellImages = new ArrayList<>();
        List<Paragraph.FieldSpan> cellFields = new ArrayList<>();
        CellFormat cellFormat = CellFormat.of(12);
        for (var p : paragraphs) {
            var block = readParagraph(p, "cell", numbers, hyperlinks, images, fields, policy, diagnostics, budget);
            if (!(block instanceof Paragraph)) { loss(policy, diagnostics, "word/document.xml", "tc", "cell list/heading semantics are reduced to text"); }
            Paragraph paragraph = paragraph(block);
            if (p != paragraphs.getFirst()) { text.append('\n'); rich.add(new InlineText("\n", InlineStyle.of(paragraph.format().fontSize()))); }
            int base = text.length();
            paragraph.images().forEach(image -> cellImages.add(new Paragraph.ImageSpan(base + image.sourceIndex(), image.image())));
            paragraph.fields().forEach(field -> cellFields.add(new Paragraph.FieldSpan(base + field.sourceIndex(), field.field())));
            text.append(paragraph.text());
            rich.addAll(paragraph.inlines().isEmpty() ? List.of(new InlineText(paragraph.text(), InlineStyle.of(paragraph.format().fontSize()))) : paragraph.inlines());
            var f = paragraph.format(); cellFormat = new CellFormat(f.fontSize(), f.lineHeight(), f.alignment(), f.lineBreakPolicy());
        }
        double size = cellFormat.fontSize();
        boolean uniform = rich.stream().allMatch(run -> run.style().equals(InlineStyle.of(size)) && run.hyperlink().isEmpty());
        return new TableCell(text.toString(), cellFormat, uniform && cellFields.isEmpty() ? List.of() : rich,
                cellImages, cellFields, columnSpan);
    }

    private static VerticalMerge readVerticalMerge(Element cell) throws IOException {
        var merges = children(child(cell, "tcPr")).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("vMerge")).toList();
        if (merges.size() > 1) { throw new IOException("DOCX cell has multiple vertical merge properties"); }
        if (merges.isEmpty()) { return VerticalMerge.NONE; }
        var merge = merges.getFirst();
        if (!children(merge).isEmpty() || !xmlWhitespace(merge.getTextContent())) {
            throw new IOException("DOCX vertical merge property cannot contain content");
        }
        var attributes = merge.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            var attribute = attributes.item(index);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && !(W.equals(attribute.getNamespaceURI()) && attribute.getLocalName().equals("val"))) {
                throw new IOException("DOCX vertical merge property has unsupported attributes");
            }
        }
        if (!merge.hasAttributeNS(W, "val")) { return VerticalMerge.CONTINUE; }
        return switch (merge.getAttributeNS(W, "val").trim()) {
            case "restart" -> VerticalMerge.RESTART;
            case "continue" -> VerticalMerge.CONTINUE;
            default -> throw new IOException("DOCX vertical merge property has an invalid value");
        };
    }

    private static void validateContinuationCell(Element cell) throws IOException {
        if (cell.hasAttributeNS(OD, "content")) { throw new IOException("DOCX vertical continuation cannot declare block content"); }
        boolean paragraph = false;
        for (Node node = cell.getFirstChild(); node != null; node = node.getNextSibling()) {
            if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !xmlWhitespace(node.getTextContent())) {
                throw new IOException("DOCX vertical continuation cannot contain text");
            }
        }
        for (var element : children(cell)) {
            if (W.equals(element.getNamespaceURI()) && element.getLocalName().equals("tcPr")) { continue; }
            if (!W.equals(element.getNamespaceURI()) || !element.getLocalName().equals("p")) {
                throw new IOException("DOCX vertical continuation must contain empty paragraphs only");
            }
            paragraph = true;
            validateEmptyContinuation(element);
        }
        if (!paragraph) { throw new IOException("DOCX vertical continuation requires an empty paragraph"); }
    }

    private static void validateEmptyContinuation(Element element) throws IOException {
        String name = element.getLocalName();
        if (!W.equals(element.getNamespaceURI()) || !EMPTY_CONTINUATION_MARKUP.contains(name)
                || name.equals("t") && !element.getTextContent().isEmpty()) {
            throw new IOException("DOCX vertical continuation cannot contain content or semantic objects");
        }
        var attributes = element.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            var attribute = attributes.item(index);
            if (OD.equals(attribute.getNamespaceURI()) && Set.of("id", "content", "cellTail", "boundary", "kind", "owner")
                    .contains(attribute.getLocalName())) {
                throw new IOException("DOCX vertical continuation cannot carry semantic identity or content markers");
            }
        }
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) { validateEmptyContinuation(child); }
            else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !xmlWhitespace(node.getTextContent())) {
                throw new IOException("DOCX vertical continuation cannot contain text");
            }
        }
    }

    private static boolean xmlWhitespace(String text) {
        return text.chars().allMatch(value -> value == ' ' || value == '\t' || value == '\n' || value == '\r');
    }

    /** Geometry cannot be guessed after duplicate or malformed properties, even in ALLOW_LOSS. */
    static int readColumnSpan(Element cell) throws IOException {
        var properties = children(cell).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("tcPr")).toList();
        if (properties.size() > 1) { throw new IOException("DOCX cell has multiple property containers"); }
        if (properties.isEmpty()) { return 1; }
        for (Node node = properties.getFirst().getFirstChild(); node != null; node = node.getNextSibling()) {
            if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                    && !xmlWhitespace(node.getTextContent())) {
                throw new IOException("DOCX cell properties cannot contain text");
            }
        }
        var propertyAttributes = properties.getFirst().getAttributes();
        for (int index = 0; index < propertyAttributes.getLength(); index++) {
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(propertyAttributes.item(index).getNamespaceURI())) {
                throw new IOException("DOCX cell properties have unsupported attributes");
            }
        }
        var spans = children(properties.getFirst()).stream().filter(element -> W.equals(element.getNamespaceURI())
                && element.getLocalName().equals("gridSpan")).toList();
        if (spans.size() > 1) { throw new IOException("DOCX cell has multiple column spans"); }
        if (spans.isEmpty()) { return 1; }
        var span = spans.getFirst();
        if (!span.hasAttributeNS(W, "val") || !children(span).isEmpty() || !xmlWhitespace(span.getTextContent())) {
            throw new IOException("DOCX column span must have one integer value and no content");
        }
        var attributes = span.getAttributes();
        for (int index = 0; index < attributes.getLength(); index++) {
            var attribute = attributes.item(index);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && !(W.equals(attribute.getNamespaceURI()) && attribute.getLocalName().equals("val"))) {
                throw new IOException("DOCX column span has unsupported attributes");
            }
        }
        String lexical = span.getAttributeNS(W, "val").trim();
        if (!lexical.matches("\\+?[0-9]+")) { throw new IOException("DOCX column span must be a positive integer"); }
        try {
            int value = Integer.parseInt(lexical);
            if (value <= 0) { throw new IOException("DOCX column span must be positive"); }
            return value;
        } catch (NumberFormatException failure) {
            throw new IOException("DOCX column span exceeds integer range", failure);
        }
    }

    private static Section readSection(Element element, int index, List<Block> blocks) throws IOException {
        var size = child(element, "pgSz"); var margin = child(element, "pgMar");
        var settings = new PageSettings(new PageSize(attributeNumber(size, W, "w", 11906) / 20, attributeNumber(size, W, "h", 16838) / 20),
                new PageMargins(attributeNumber(margin, W, "top", 1080) / 20, attributeNumber(margin, W, "right", 1080) / 20,
                        attributeNumber(margin, W, "bottom", 1080) / 20, attributeNumber(margin, W, "left", 1080) / 20));
        return new Section(attribute(element, OD, "id", "section-" + index), settings, blocks, integer(attribute(child(element, "pgNumType"), W, "start", "0")));
    }

    Map<String, byte[]> unzip(InputStream input) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        long expanded = 0;
        try (var zip = new ZipInputStream(new BoundedInput(input, limits.maxInputBytes()))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                String name = entry.getName();
                if (name.startsWith("/") || name.contains("\\") || name.contains(":") || List.of(name.split("/", -1)).contains("..")) { throw new IOException("unsafe DOCX package path"); }
                if (result.size() >= limits.maxEntries() || result.containsKey(name)) { throw new IOException("DOCX entry budget or duplicate name"); }
                var bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = zip.read(buffer)) != -1;) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    expanded += count;
                    if (count > limits.maxEntryBytes() - bytes.size() || expanded > limits.maxExpandedBytes()) { throw new IOException("DOCX expanded byte budget exceeded"); }
                    bytes.write(buffer, 0, count);
                }
                result.put(name, bytes.toByteArray());
            }
        }
        return result;
    }

    static Element parse(byte[] bytes) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false); factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "128");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws SAXException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException failure) { throw new IOException("invalid or unsafe DOCX XML", failure); }
    }

    static void validateMarkup(Element element, Policy policy, List<Diagnostic> diagnostics) throws IOException {
        String name = element.getLocalName();
        if (W.equals(element.getNamespaceURI())) { validateStructure(element, policy, diagnostics); }
        // DrawingML is validated while reading the image so ALLOW_LOSS can omit it with a diagnostic.
        if (W.equals(element.getNamespaceURI()) && name.equals("drawing")) { return; }
        if (!W.equals(element.getNamespaceURI()) || !MARKUP.contains(name)) { loss(policy, diagnostics, "word/document.xml", name, "unsupported markup"); }
        Set<String> attributes = switch (name) {
            case "spacing" -> Set.of("line", "lineRule", "before", "after");
            case "ind" -> Set.of("left", "firstLine", "hanging");
            case "pgSz" -> Set.of("w", "h", "orient");
            case "pgMar" -> Set.of("top", "right", "bottom", "left", "header", "footer");
            case "headerReference", "footerReference" -> Set.of("type");
            case "titlePg" -> Set.of("val");
            case "pgNumType" -> Set.of("start");
            case "br", "proofErr" -> Set.of("type");
            case "gridCol" -> Set.of("w");
            case "top", "right", "bottom", "left", "insideH", "insideV" -> Set.of("w", "type", "val", "sz", "color", "space");
            case "fldSimple" -> Set.of("instr", "dirty", "fldLock");
            case "fldChar" -> Set.of("fldCharType", "dirty", "fldLock");
            case "bookmarkStart" -> Set.of("id", "name");
            case "bookmarkEnd" -> Set.of("id");
            default -> Set.of("val");
        };
        var attrs = element.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            var attr = attrs.item(i);
            if (W.equals(attr.getNamespaceURI()) && !attributes.contains(attr.getLocalName())) {
                loss(policy, diagnostics, "word/document.xml", name, "unsupported attribute: " + attr.getLocalName());
            }
        }
        if (name.equals("jc") && !Set.of("left", "center", "right", "both").contains(value(element, "left"))) { loss(policy, diagnostics, "word/document.xml", name, "unsupported paragraph alignment"); }
        if (name.equals("kinsoku") && enabled(element) && !"CJK_STRICT".equals(attribute(element, OD, "policy", ""))) {
            loss(policy, diagnostics, "word/document.xml", name, "external Chinese line-break settings do not define the engine's strict subset");
        }
        if (name.equals("spacing") && !Set.of("exact", "auto").contains(attribute(element, W, "lineRule", "auto"))) { loss(policy, diagnostics, "word/document.xml", name, "unsupported line spacing rule"); }
        if (name.equals("ind") && (attributeNumber(element, W, "left", 0) < 0 || attributeNumber(element, W, "left", 0) + attributeNumber(element, W, "firstLine", 0) - attributeNumber(element, W, "hanging", 0) < 0)) { loss(policy, diagnostics, "word/document.xml", name, "negative indents are unsupported"); }
        if (name.equals("type") && !value(element, "nextPage").equals("nextPage")) { loss(policy, diagnostics, "word/document.xml", name, "only next-page sections are supported"); }
        if (name.equals("u") && !Set.of("single", "none").contains(value(element, "single"))) { loss(policy, diagnostics, "word/document.xml", name, "unsupported underline style"); }
        if (name.equals("br") && !Set.of("page", "textWrapping").contains(attribute(element, W, "type", "textWrapping"))) { loss(policy, diagnostics, "word/document.xml", name, "unsupported break type"); }
        for (var child : children(element)) { validateMarkup(child, policy, diagnostics); }
    }

    /** Checks the semantic containers consumed by the importer, rather than accepting a known name anywhere. */
    private static void validateStructure(Element element, Policy policy, List<Diagnostic> diagnostics) throws IOException {
        DocxCells.validateTailLocation(element);
        String parent = wordParent(element);
        if (Set.of("gridSpan", "vMerge").contains(element.getLocalName()) && (!parent.equals("tcPr")
                || !(element.getParentNode() instanceof Element properties) || !wordParent(properties).equals("tc")
                || !(properties.getParentNode() instanceof Element cell) || !wordParent(cell).equals("tr")
                || !(cell.getParentNode() instanceof Element row) || !wordParent(row).equals("tbl"))) {
            throw new IOException("DOCX merge geometry requires direct cell, row and table containers");
        }
        boolean supported = switch (element.getLocalName()) {
            case "document" -> element.getParentNode() instanceof org.w3c.dom.Document;
            case "body" -> parent.equals("document");
            case "hdr", "ftr" -> element.getParentNode() instanceof org.w3c.dom.Document;
            case "headerReference", "footerReference", "titlePg" -> parent.equals("sectPr");
            case "tbl" -> Set.of("body", "tc", "hdr", "ftr").contains(parent);
            case "p" -> Set.of("body", "tc", "hdr", "ftr").contains(parent);
            case "tr", "tblPr", "tblGrid" -> parent.equals("tbl");
            case "tc", "trPr" -> parent.equals("tr");
            case "tcPr" -> parent.equals("tc");
            case "gridSpan", "vMerge" -> parent.equals("tcPr");
            case "gridCol" -> parent.equals("tblGrid");
            case "tblCellMar" -> parent.equals("tblPr");
            case "tblHeader", "cantSplit" -> parent.equals("trPr");
            case "pPr" -> parent.equals("p");
            case "r" -> Set.of("p", "hyperlink", "fldSimple").contains(parent);
            case "rPr" -> parent.equals("r") || parent.equals("pPr");
            case "t", "br", "tab", "drawing", "fldChar", "instrText" -> parent.equals("r");
            case "hyperlink" -> parent.equals("p");
            case "fldSimple" -> parent.equals("p") || parent.equals("hyperlink");
            case "sectPr" -> parent.equals("body") || parent.equals("pPr")
                    && element.getParentNode() instanceof Element properties
                    && properties.getParentNode() instanceof Element paragraph
                    && wordParent(paragraph).equals("body");
            default -> true;
        };
        if (!supported) {
            loss(policy, diagnostics, "word/document.xml", element.getLocalName(), "unsupported semantic container");
        }
    }

    private static String wordParent(Element element) {
        return element.getParentNode() instanceof Element parent && W.equals(parent.getNamespaceURI())
                ? parent.getLocalName() : "";
    }

    private static void validateStyles(Element root, Policy policy, List<Diagnostic> diagnostics) throws IOException {
        for (var style : children(root)) {
            String id = style.getAttributeNS(W, "styleId");
            if (!style.getLocalName().equals("style")) { loss(policy, diagnostics, "word/styles.xml", style.getLocalName(), "style defaults and latent styles are unsupported"); continue; }
            for (var property : children(style)) {
                if (property.getLocalName().equals("name")) { continue; }
                if (property.getLocalName().equals("pPr") && id.matches("Heading[1-6]") && children(property).stream().allMatch(p -> p.getLocalName().equals("outlineLvl"))) { continue; }
                if (property.getLocalName().equals("rPr") && Set.of("bold", "italic", "boldItalic").contains(id)
                        && children(property).stream().allMatch(p -> Set.of("b", "i").contains(p.getLocalName()) && enabled(p))) { continue; }
                loss(policy, diagnostics, "word/styles.xml", property.getLocalName(), "style formatting or inheritance is unsupported");
            }
        }
    }

    private static long escapedBytes(String source) {
        long bytes = 0;
        for (int i = 0; i < source.length();) {
            int cp = source.codePointAt(i); i += Character.charCount(cp);
            bytes += switch (cp) { case '&' -> 5; case '<', '>' -> 4; case '"', '\'' -> 6; default -> cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4; };
        }
        return bytes;
    }

    static void loss(Policy policy, List<Diagnostic> diagnostics, String part, String element, String message) throws IOException {
        if (policy == Policy.STRICT) { throw new IOException(message + " in " + part + ": " + element); }
        if (diagnostics.size() >= 10_000) { throw new IOException("DOCX diagnostic budget exceeded"); }
        diagnostics.add(new Diagnostic(part, element, message));
    }

    static List<Element> children(Element parent) {
        List<Element> result = new ArrayList<>();
        if (parent != null) { for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) { if (child instanceof Element element) { result.add(element); } } }
        return result;
    }
    static Element child(Element parent, String name) { return children(parent).stream().filter(e -> W.equals(e.getNamespaceURI()) && e.getLocalName().equals(name)).findFirst().orElse(null); }
    private static String value(Element e, String fallback) { return attribute(e, W, "val", fallback); }
    private static String attribute(Element e, String ns, String name, String fallback) { return e == null || !e.hasAttributeNS(ns, name) ? fallback : e.getAttributeNS(ns, name); }
    private static double attributeNumber(Element e, String ns, String name, double fallback) throws IOException { return number(attribute(e, ns, name, Double.toString(fallback))); }
    private static boolean enabled(Element e) { return e != null && !Set.of("false", "0", "off").contains(value(e, "true")); }
    private static String descendantText(Element root, String ns, String name) { var nodes = root.getElementsByTagNameNS(ns, name); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent(); }
    private static int integer(String value) throws IOException { try { return Integer.parseInt(value); } catch (NumberFormatException failure) { throw new IOException("invalid OOXML integer", failure); } }
    static double number(String value) throws IOException { try { double n = Double.parseDouble(value); if (!Double.isFinite(n)) { throw new NumberFormatException(); } return n; } catch (NumberFormatException failure) { throw new IOException("invalid OOXML number", failure); } }
    private static long twips(double points) { return Math.round(points * 20); }
    static String escape(String value) {
        if (value.codePoints().anyMatch(cp -> cp < 32 && cp != 9 && cp != 10 && cp != 13 || cp >= 0xd800 && cp <= 0xdfff || cp == 0xfffe || cp == 0xffff)) { throw new IllegalArgumentException("invalid XML character"); }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
    private static final class BorrowedOutput extends FilterOutputStream {
        BorrowedOutput(OutputStream output) { super(output); }
        @Override public void write(byte[] b, int start, int count) throws IOException { out.write(b, start, count); }
        @Override public void close() { }
    }
    private static final class BoundedInput extends FilterInputStream {
        private final long limit; private long count;
        BoundedInput(InputStream input, long limit) { super(input); this.limit = limit; }
        @Override public int read() throws IOException { int b = in.read(); if (b != -1) { add(1); } return b; }
        @Override public int read(byte[] b, int start, int length) throws IOException { int n = in.read(b, start, length); if (n > 0) { add(n); } return n; }
        private void add(int n) throws IOException { count += n; if (count > limit) { throw new IOException("DOCX compressed input budget exceeded"); } }
        @Override public void close() { }
    }
}
