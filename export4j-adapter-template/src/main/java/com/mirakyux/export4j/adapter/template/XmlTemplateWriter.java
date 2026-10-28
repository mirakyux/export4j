// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Saves supported semantic content as editable version-1 XML and explicit images in an ordinary ZIP.
 * Uses the existing XML compiler/binder; unsupported runtime components require an explicit
 * discard policy. Fonts/licenses are provided by the caller, never discovered. Requests are task-confined;
 * prepared values are immutable except for caller execution callbacks. No layout or implicit I/O occurs.
 */
public final class XmlTemplateWriter {
    public static final String FORMAT = "XML_TEMPLATE";
    private XmlTemplateWriter() { }

    /** Aggregate work/report budgets, in addition to XML compiler/binding and ZIP resource budgets. */
    public record Limits(int maxBlocks, int maxCells, int maxIssues) {
        public static final Limits DEFAULT = new Limits(10_000, 100_000, 10_000);
        public Limits { if (maxBlocks < 1 || maxCells < 1 || maxIssues < 1) { throw new IllegalArgumentException("invalid XML writer limits"); } }
    }

    public static Request from(Document document) {
        Objects.requireNonNull(document, "document");
        return new Request(new SectionedDocument(document.metadata(), List.of(new Section("document", document.pageSettings(), document.blocks(), 0))), false);
    }
    public static Request from(SectionedDocument document) { return new Request(Objects.requireNonNull(document, "document"), true); }
    public static Request from(PageDocument document) {
        Objects.requireNonNull(document, "document");
        var body = document.document();
        return new Request(new SectionedDocument(body.metadata(), List.of(new Section("document",
                body.pageSettings(), body.blocks(), 0, document.pageRegions()))), false);
    }

    /** Target for import/edit/save conversion; emits the same ordinary template ZIP. Font bytes remain
     * an explicit caller resource when reopening; this factory does not collect runtime font handles. */
    public static DocumentConversion.Target conversionTarget() {
        return new DocumentConversion.Target(FORMAT, (document, notes, policy, output) ->
                from(document).notes(notes).policy(policy).prepare().to(output));
    }

    /** Saves the edited model while retaining explicitly supplied font bytes and license notices.
     * Old XML, fragments and pictures are not copied: pictures are collected from the current model,
     * so a removed picture cannot reappear through a stale source resource. */
    public static DocumentConversion.Target conversionTarget(XmlTemplateBundle resources) {
        Objects.requireNonNull(resources, "resources");
        return new DocumentConversion.Target(FORMAT, (document, notes, policy, output) ->
                from(document).notes(notes).resources(resources).policy(policy).prepare().to(output));
    }

    public static final class Request {
        private final SectionedDocument document;
        private final boolean sections;
        private List<Note> notes = List.of();
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private Limits limits = Limits.DEFAULT;
        private XmlTemplateBundle.Limits bundleLimits = XmlTemplateBundle.Limits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private final Map<String, byte[]> fonts = new LinkedHashMap<>();
        private final Map<String, String> licenses = new LinkedHashMap<>();
        private Request(SectionedDocument document, boolean sections) { this.document = document; this.sections = sections; }
        public Request notes(List<Note> value) { notes = List.copyOf(value); return this; }
        public Request policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request limits(Limits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Request bundleLimits(XmlTemplateBundle.Limits value) { bundleLimits = Objects.requireNonNull(value, "bundleLimits"); return this; }
        public Request execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        public Request font(String name, byte[] bytes) { fonts.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(bytes, "bytes").clone()); return this; }
        public Request license(String name, String text) { licenses.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(text, "text")); return this; }
        /** Snapshots explicit portable fonts/notices, without retaining the old model or unused images.
         * Later font/license calls can replace individual resources. No font discovery or loading occurs. */
        public Request resources(XmlTemplateBundle source) {
            Objects.requireNonNull(source, "source");
            source.fonts().forEach(this::font); source.licenses().forEach(this::license); return this;
        }
        /** Validates all supported content and policies, compiles the produced XML and encodes the ZIP once.
         * No final destination is touched. Mutating this request later cannot change the prepared result. */
        public Prepared prepare() throws IOException {
            var renderer = new Renderer(document, sections, notes, policy, limits, bundleLimits, execution);
            var fontSnapshot = Map.copyOf(fonts); var licenseSnapshot = Map.copyOf(licenses); var options = execution;
            return ExecutionContext.execute(options, () -> {
                var bundle = renderer.render(fontSnapshot, licenseSnapshot);
                bundle.compile().renderComplete(Map.of());
                return new Prepared(bundle, bundle.encode(), List.copyOf(renderer.issues), options);
            });
        }
    }

    /** Exact immutable ZIP encoding with the issues observed during preparation. */
    public static final class Prepared {
        private final XmlTemplateBundle bundle;
        private final byte[] bytes;
        private final List<ProcessingIssue> issues;
        private final ExecutionOptions execution;
        private Prepared(XmlTemplateBundle bundle, byte[] bytes, List<ProcessingIssue> issues, ExecutionOptions execution) {
            this.bundle = bundle; this.bytes = bytes; this.issues = issues; this.execution = execution;
        }
        public XmlTemplateBundle bundle() { return bundle; }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, issues); }
        /** Borrowed output; never closed/flushed. A physical write failure can leave partial bytes. */
        public ProcessingReport to(OutputStream output) throws IOException {
            Objects.requireNonNull(output, "output");
            return ExecutionContext.execute(execution, () -> { ExecutionContext.output(output).write(bytes); return actual(); });
        }
        /** Atomic single-path publication; the parent must exist. No non-atomic fallback. */
        public ProcessingReport to(Path output) throws IOException { TemplateBundleIO.publish(bytes, output, execution); return actual(); }
        private ProcessingReport actual() { return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues); }
    }

    private static final class Renderer {
        private final SectionedDocument document;
        private final boolean sections;
        private final List<Note> notes;
        private final ProcessingPolicy policy;
        private final Limits limits;
        private final XmlTemplateBundle.Limits bundleLimits;
        private final ExecutionOptions execution;
        private final List<ProcessingIssue> issues = new ArrayList<>();
        private final StringBuilder source = new StringBuilder();
        private final Map<String, ImageResource> images = new LinkedHashMap<>();
        private final Map<String, String> identifiers = new HashMap<>();
        private final HashSet<String> reserved = new HashSet<>();
        private final Map<String, Map<PageRegion, String>> regionSources = new LinkedHashMap<>();
        private int blocks;
        private int cells;
        private int sequence;
        private ProcessingIssue.Location location = new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1);
        private Renderer(SectionedDocument document, boolean sections, List<Note> notes, ProcessingPolicy policy,
                Limits limits, XmlTemplateBundle.Limits bundleLimits, ExecutionOptions execution) {
            this.document = document; this.sections = sections; this.notes = notes; this.policy = policy;
            this.limits = limits; this.bundleLimits = bundleLimits; this.execution = execution;
        }
        private XmlTemplateBundle render(Map<String, byte[]> fonts, Map<String, String> licenses) throws IOException {
            for (var section : document.sections()) {
                reserve(section.blocks());
                section.pageRegions().map((name, region) -> {
                    String kind = name.split("[_:]", 2)[0];
                    var sources = regionSources.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
                    if (!sources.containsKey(region)) { sources.put(region, ""); reserve(region.blocks()); }
                    return region;
                });
                reserved.add(section.id());
            }
            for (var note : notes) {
                ExecutionContext.checkpoint();
                if (++blocks > limits.maxBlocks()) { throw new IllegalArgumentException("XML writer block/note budget exceeded"); }
                reserved.add(note.id());
            }
            Note.validate(document, notes);
            document.navigation().validate(document);
            regionSources.clear();
            add("<document version=\"1\" literal-text=\"true\""); attr("title", document.metadata().title()); attr("author", document.metadata().author()); add(">");
            if (sections) {
                navigation();
                notes();
                for (var section : document.sections()) {
                    location = new ProcessingIssue.Location(section.id(), "BODY", "", List.of(), -1, -1);
                    add("<section"); attr("id", identifier(section.id())); attr("first-page-number", section.firstPageNumber()); add(">");
                    section(section); add("</section>");
                }
            } else { section(document.sections().getFirst()); }
            add("</document>");
            var result = XmlTemplateBundle.builder(source.toString()).limits(bundleLimits).execution(execution);
            images.forEach(result::image); fonts.forEach(result::font); licenses.forEach(result::license);
            return result.build();
        }
        private void section(Section section) {
            location = new ProcessingIssue.Location(section.id(), "BODY", "", List.of(), -1, -1);
            page(section.pageSettings());
            regions(section.pageRegions());
            if (!sections) { navigation(); notes(); }
            location = new ProcessingIssue.Location(section.id(), "BODY", "", List.of(), -1, -1);
            for (var block : section.blocks()) { block(block, List.of()); }
        }
        private void navigation() {
            var value = document.navigation();
            if (value.equals(DocumentNavigation.NONE)) { return; }
            add("<navigation"); attr("headings", value.headings()); attr("max-level", value.maxHeadingLevel()); add(">");
            for (var bookmark : value.bookmarks()) {
                ExecutionContext.checkpoint();
                if (++blocks > limits.maxBlocks()) { throw new IllegalArgumentException("XML navigation budget exceeded"); }
                add("<bookmark"); attr("target", identifier(bookmark.blockId())); attr("level", bookmark.level());
                attr("title", bookmark.title()); add("/>");
            }
            var toc = value.contents();
            add("<contents"); attr("enabled", toc.enabled()); attr("title", toc.title()); add(">");
            navigationStyle("ENTRY", toc.entries());
            for (var part : List.of(DocumentNavigation.Part.TITLE, DocumentNavigation.Part.PAGE_LABEL)) {
                if (toc.styles().containsKey(part)) { navigationStyle(part.name(), toc.styles().get(part)); }
            }
            for (var part : DocumentNavigation.Part.values()) {
                if (toc.colors().containsKey(part)) {
                    add("<color"); attr("part", part); attr("value", String.format(Locale.ROOT, "#%06X", toc.colors().get(part))); add("/>");
                }
            }
            add("</contents></navigation>");
        }
        private void navigationStyle(String part, DocumentNavigation.TextStyle style) {
            add("<typography"); attr("part", part); attr("size", style.size()); attr("line-height", style.lineHeight());
            attr("first-indent", style.firstIndent()); attr("subsequent-indent", style.subsequentIndent());
            attr("align", style.alignment()); attr("line-break", style.lineBreak()); add("/>");
        }
        private void notes() {
            if (notes.isEmpty()) { return; }
            add("<notes>");
            for (var note : notes) {
                location = new ProcessingIssue.Location("", "NOTES", note.id(), List.of(), -1, -1);
                add("<note"); attr("id", identifier(note.id())); attr("paragraph", identifier(note.paragraphId())); attr("kind", note.kind());
                add(">"); escaped(note.text()); add("</note>");
            }
            add("</notes>");
        }
        private void reserve(List<Block> values) {
            SemanticTree.walk(values, visit -> {
                ExecutionContext.checkpoint();
                if (++blocks > limits.maxBlocks()) { throw new IllegalArgumentException("XML writer block budget exceeded"); }
                reserved.add(visit.block().id());
            });
        }
        private void regions(PageRegions regions) {
            band("header", "default", regions.header()); band("footer", "default", regions.footer());
            for (var variant : PageRegions.Variant.values()) {
                var pair = regions.variants().get(variant);
                if (pair != null) {
                    String name = variant.name().toLowerCase(Locale.ROOT);
                    band("header", name, pair.header()); band("footer", name, pair.footer());
                }
            }
            overlays("background", regions.background()); overlays("foreground", regions.foreground());
        }
        private void band(String tag, String variant, PageRegion region) {
            regionLocation(tag.toUpperCase(Locale.ROOT) + (variant.equals("default") ? "" : "_" + variant.toUpperCase(Locale.ROOT)));
            add("<" + tag); attr("variant", variant);
            regionContent(tag, tag, region);
        }
        private void overlays(String tag, List<PageOverlay> values) {
            add("<" + tag + ">");
            for (int index = 0; index < values.size(); index++) {
                var box = values.get(index); regionLocation(tag.toUpperCase(Locale.ROOT) + ":" + index);
                add("<box"); attr("x", box.x()); attr("y", box.y()); attr("width", box.width());
                regionContent(tag, "box", box.content());
            }
            add("</" + tag + ">");
        }
        private void regionLocation(String name) {
            location = new ProcessingIssue.Location(location.sectionId(), name, "", List.of(), -1, -1);
        }
        private void regionContent(String kind, String tag, PageRegion region) {
            var sources = regionSources.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
            String existing = sources.get(region);
            if (existing != null) { attr("source-ref", existing); add("/>"); return; }
            String name = "region-" + (sources.size() + 1); sources.put(region, name);
            attr("source-id", name); attr("height", region.height());
            if (!tag.equals("box")) { attr("gap", region.gap()); }
            add(">"); for (var block : region.blocks()) { block(block, List.of()); } add("</" + tag + ">");
        }
        private void page(PageSettings settings) {
            add("<page"); attr("width", settings.size().width()); attr("height", settings.size().height());
            var margin = settings.margins(); attr("margin-top", margin.top()); attr("margin-right", margin.right());
            attr("margin-bottom", margin.bottom()); attr("margin-left", margin.left()); add("/>");
        }
        private void block(Block value, List<CellLocation> path) {
            ExecutionContext.checkpoint();
            location = new ProcessingIssue.Location(location.sectionId(), location.region(), value.id(), path, -1, -1);
            if (value instanceof Table table) { table(table, path); return; }
            if (value instanceof PageBreak) { add("<page-break"); attr("id", identifier(value.id())); add("/>"); return; }
            if (value instanceof Caption caption) {
                add("<caption"); attr("id", identifier(caption.id())); attr("kind", caption.kind()); format(caption.format());
                add(">"); escaped(caption.text()); add("</caption>"); return;
            }
            if (value instanceof CrossReference reference) {
                add("<cross-reference"); attr("id", identifier(reference.id())); attr("kind", reference.kind());
                attr("target", identifier(reference.targetId())); format(reference.format());
                add(">"); escaped(reference.prefix()); add("</cross-reference>"); return;
            }
            String tag; ParagraphFormat format; List<InlineContent> content;
            if (value instanceof Paragraph paragraph) { tag = "p"; format = paragraph.format(); content = paragraph.content(); }
            else if (value instanceof Heading heading) { tag = "heading"; format = heading.format(); content = runs(heading.text(), heading.inlines(), format.fontSize()); }
            else if (value instanceof BulletItem bullet) { tag = "bullet"; format = bullet.format(); content = runs(bullet.text(), bullet.inlines(), format.fontSize()); }
            else if (value instanceof NumberedItem numbered) { tag = "numbered"; format = numbered.format(); content = runs(numbered.text(), numbered.inlines(), format.fontSize()); }
            else { loss("UNSUPPORTED_BLOCK", ProcessingPolicy.Treatment.DISCARD, "BLOCK_CONTENT_REMOVED"); return; }
            add("<" + tag); attr("id", identifier(value.id())); format(format);
            if (value instanceof Heading heading) { attr("level", heading.level()); }
            if (value instanceof BulletItem bullet) { attr("level", bullet.level()); attr("marker", bullet.marker()); marker(bullet.markerStyle()); }
            if (value instanceof NumberedItem numbered) {
                attr("list-id", identifier(numbered.listId())); attr("level", numbered.level()); attr("restart", numbered.restartAt()); marker(numbered.markerStyle());
            }
            add(">"); inline(content); add("</" + tag + ">");
        }
        private static List<InlineContent> runs(String text, List<InlineText> runs, double size) {
            return runs.isEmpty() ? List.of(new InlineText(text, InlineStyle.of(size))) : List.copyOf(runs);
        }
        private void format(ParagraphFormat format) {
            attr("size", format.fontSize()); attr("line-height", format.lineHeight()); attr("align", format.alignment());
            attr("space-before", format.spaceBefore()); attr("space-after", format.spaceAfter()); attr("keep-together", format.keepTogether());
            attr("first-indent", format.firstLineIndent()); attr("subsequent-indent", format.subsequentLineIndent()); attr("line-break", format.lineBreakPolicy());
            var pagination = format.pagination(); attr("keep-with-next", pagination.keepWithNext()); attr("page-break-before", pagination.pageBreakBefore());
            attr("min-lines-before", pagination.minLinesBeforeBreak()); attr("min-lines-after", pagination.minLinesAfterBreak());
        }
        private void marker(InlineStyle style) {
            attr("marker-role", role(style.fontRole())); attr("marker-size", style.fontSize()); attr("marker-color", rgb(style.rgb()));
            attr("marker-shift", style.baselineShift()); attr("marker-underline", style.underline()); attr("marker-strike", style.strikeThrough());
        }
        private void style(InlineStyle style) {
            attr("role", role(style.fontRole())); attr("size", style.fontSize()); attr("color", rgb(style.rgb()));
            attr("baseline-shift", style.baselineShift()); attr("underline", style.underline()); attr("strike", style.strikeThrough());
        }
        private static String rgb(int value) { return String.format(Locale.ROOT, "#%06x", value); }
        private String role(String role) {
            if (role.isEmpty() || role.matches("[A-Za-z_][A-Za-z_0-9]{0,255}")) { return role; }
            loss("FONT_ROLE", ProcessingPolicy.Treatment.DEGRADE, "FONT_ROLE_REMOVED"); return "";
        }
        private void inline(List<InlineContent> values) {
            var owner = location; int offset = 0;
            for (var value : values) {
                ExecutionContext.checkpoint();
                int length = value instanceof InlineText text ? text.text().length() : 1;
                location = owner.range(offset, offset + length); offset += length;
                String link = value instanceof InlineText text ? text.hyperlink() : value instanceof InlineImage image ? image.hyperlink() : "";
                if (!link.isEmpty()) { add("<link"); attr("href", link); add(">"); }
                if (value instanceof InlineText text) { add("<span"); style(text.style()); add(">"); escaped(text.text()); add("</span>"); }
                else if (value instanceof InlineImage image) {
                    String name = "image_" + image.resource().id(); images.putIfAbsent(name, image.resource());
                    add("<image"); attr("ref", name); attr("width", image.width()); attr("height", image.height());
                    attr("baseline-shift", image.baselineShift()); attr("alt", image.altText()); add("/>");
                } else if (value instanceof InlineField field) {
                    String tag = switch (field.kind()) { case PAGE -> "page-number"; case PAGES -> "page-count";
                        case REFERENCE_PAGE -> "page-reference"; case REFERENCE_NUMBER -> "number-reference"; case NOTE_MARKER -> "note-marker"; };
                    add("<" + tag); attr("width", field.width()); style(field.style());
                    if (!field.targetId().isEmpty()) { attr("target", identifier(field.targetId())); } add("/>");
                }
                if (!link.isEmpty()) { add("</link>"); }
            }
            location = owner;
        }
        private void table(Table table, List<CellLocation> path) {
            var owner = location; add("<table"); attr("id", identifier(table.id())); var format = table.format();
            attr("row-split", format.allowRowSplit()); attr("space-before", format.spaceBefore()); attr("space-after", format.spaceAfter());
            var padding = format.padding(); attr("padding-top", padding.top()); attr("padding-right", padding.right());
            attr("padding-bottom", padding.bottom()); attr("padding-left", padding.left()); add("><columns>");
            for (var column : table.columns()) {
                add("<column");
                if (column instanceof ColumnWidth.Fixed fixed) { attr("width", fixed.points()); }
                else if (column instanceof ColumnWidth.Fraction fraction) { attr("weight", fraction.weight()); }
                else if (column instanceof ColumnWidth.Auto auto) { attr("min-width", auto.minPoints()); attr("max-width", auto.maxPoints()); }
                add("/>");
            }
            add("</columns>"); var grid = table.grid();
            for (int row = 0; row < table.rows().size(); row++) {
                String tag = row < table.headerRows() ? "header" : "row"; add("<" + tag + ">");
                for (var anchor : grid.anchorRows().get(row)) {
                    if (++cells > limits.maxCells()) { throw new IllegalArgumentException("XML writer cell budget exceeded"); }
                    var cell = anchor.cell(); var nestedPath = new ArrayList<>(path);
                    nestedPath.add(new CellLocation(table.id(), row, anchor.columnIndex(), -1));
                    location = new ProcessingIssue.Location(owner.sectionId(), owner.region(), table.id(), nestedPath, -1, -1);
                    add("<cell"); attr("column-span", cell.columnSpan()); attr("row-span", cell.rowSpan());
                    attr("size", cell.format().fontSize()); attr("line-height", cell.format().lineHeight()); attr("align", cell.format().alignment());
                    attr("line-break", cell.format().lineBreakPolicy()); add(">");
                    if (cell.hasBlocks()) {
                        for (int index = 0; index < cell.blocks().size(); index++) {
                            var childPath = new ArrayList<>(path); childPath.add(new CellLocation(table.id(), row, anchor.columnIndex(), index));
                            block(cell.blocks().get(index), childPath);
                        }
                    } else {
                        inline(new Paragraph("cell", cell.text(), ParagraphFormat.of(cell.format().fontSize()), cell.inlines(), cell.images(), cell.fields()).content());
                    }
                    add("</cell>"); location = owner;
                }
                add("</" + tag + ">");
            }
            add("</table>"); location = owner;
        }
        private String identifier(String original) {
            if (original.matches("[A-Za-z_][A-Za-z_0-9-]{0,127}")) { return original; }
            var existing = identifiers.get(original); if (existing != null) { return existing; }
            loss("IDENTIFIER", ProcessingPolicy.Treatment.DEGRADE, "IDENTIFIER_RENAMED");
            String name; do { name = "saved-" + ++sequence; } while (!reserved.add(name));
            identifiers.put(original, name); return name;
        }
        private void loss(String feature, ProcessingPolicy.Treatment treatment, String effect) {
            ExecutionContext.checkpoint(); var rule = policy.resolve(FORMAT, feature, "EXPORT");
            var issue = new ProcessingIssue("EXPORT", "MODEL", FORMAT, feature, "EXPORT", location, "NOT_REPRESENTABLE",
                    rule.action(), treatment, List.of(effect));
            if (rule.action() == ProcessingPolicy.Action.ERROR || rule.treatment() != treatment) { throw new ProcessingException(issue); }
            if (issues.size() >= limits.maxIssues()) { throw new IllegalArgumentException("XML writer report budget exceeded"); }
            issues.add(issue);
        }
        private void attr(String name, Object value) {
            add(" " + name + "=\"");
            escaped(value instanceof Double number ? BigDecimal.valueOf(number).toPlainString() : value.toString());
            add("\"");
        }
        private void escaped(String text) {
            for (int offset = 0; offset < text.length();) {
                if ((offset & 1023) == 0) { ExecutionContext.checkpoint(); }
                int cp = text.codePointAt(offset); offset += Character.charCount(cp);
                if (!(cp == 9 || cp == 10 || cp == 13 || cp >= 32 && cp <= 0xd7ff || cp >= 0xe000 && cp <= 0xfffd || cp >= 0x10000 && cp <= 0x10ffff)) {
                    throw new IllegalArgumentException("text is not valid XML 1.0 Unicode");
                }
                add(switch (cp) { case '&' -> "&amp;"; case '<' -> "&lt;"; case '>' -> "&gt;"; case '"' -> "&quot;";
                    case 9 -> "&#9;"; case 10 -> "&#10;"; case 13 -> "&#13;"; default -> new String(Character.toChars(cp)); });
            }
        }
        private void add(String text) {
            ExecutionContext.checkpoint();
            if (text.length() > bundleLimits.maxSourceUtf16() - source.length()) { throw new IllegalArgumentException("XML writer source budget exceeded"); }
            source.append(text);
        }
    }
}
