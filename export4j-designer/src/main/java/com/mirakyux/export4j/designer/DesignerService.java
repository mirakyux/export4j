// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.web.HtmlAdapter;
import com.mirakyux.export4j.adapter.web.MarkdownAdapter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.raster.RasterBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.w3c.dom.Element;

/** App orchestration only. No independent pagination or implicit document fonts. */
final class DesignerService {
    enum Format {
        PDF("pdf"), DOCX("docx"), HTML("html"), MARKDOWN("md"), SVG("svg.zip"), PNG("png.zip");
        final String extension;
        Format(String extension) { this.extension = extension; }
    }
    record ImageBounds(double x, double y, double width, double height) { }
    record Hit(int page, String id, String path, double x, double y, double width, double height, List<ImageBounds> images) { }
    record Break(int page, String id, String path, double y) { }
    record Preview(ComponentDocumentLayout layout, List<Hit> hits, List<Break> breaks) {
        BufferedImage image(int page) throws IOException {
            var bytes = new ByteArrayOutputStream();
            new RasterBackend(96).writePage(layout.display().pages().get(page), bytes);
            return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
        }
    }
    private DesignerService() { }
    static ProcessingPolicy policy(Format format, ProcessingPolicy.Action action, String treatment) {
        if (!treatment.equals("RECOMMENDED")) {
            return ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.valueOf(treatment)).build();
        }
        var rules = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE);
        if (format == Format.HTML || format == Format.MARKDOWN) {
            for (String feature : List.of("CUSTOM_BLOCK", "METADATA", "PAGE_NUMBERING", "NAVIGATION", "NOTES")) {
                rules.feature(feature, action, ProcessingPolicy.Treatment.DISCARD);
            }
        }
        if (format == Format.SVG || format == Format.PNG) {
            rules.feature("INTERACTIVE_CONTENT", action, ProcessingPolicy.Treatment.DISCARD);
        }
        if (format == Format.DOCX) {
            for (String feature : List.of("NAVIGATION", "NOTES", "PAGE_OVERLAYS")) {
                rules.feature(feature, action, ProcessingPolicy.Treatment.DISCARD);
            }
        }
        return rules.build();
    }
    static ExecutionOptions execution(java.util.function.BooleanSupplier cancelled) {
        return ExecutionOptions.builder().timeout(Duration.ofSeconds(60)).cancelled(cancelled).maxOutputBytes(32 * 1024 * 1024).build();
    }
    static byte[] resource(String name) throws IOException {
        try (var input = DesignerService.class.getResourceAsStream("/designer/fonts/" + name)) {
            if (input == null) { throw new IOException("bundled designer resource missing: " + name); }
            return input.readAllBytes();
        }
    }
    static XmlTemplateBundle.Builder defaults(String source) throws IOException {
        return XmlTemplateBundle.builder(source).limits(new XmlTemplateBundle.Limits(16 * 1024 * 1024,
                16 * 1024 * 1024, 256, 1_000_000, 8_000_000))
                .font("Regular.ttf", resource("LiberationSans-Regular.ttf"))
                .font("Bold.ttf", resource("LiberationSans-Bold.ttf"))
                .font("Chinese.ttf", resource("ZCOOLXiaoWei-Regular.ttf"))
                .license("LiberationSans-OFL.txt", new String(resource("LiberationSans-OFL.txt"), StandardCharsets.UTF_8))
                .license("ZCOOLXiaoWei-OFL.txt", new String(resource("ZCOOLXiaoWei-OFL.txt"), StandardCharsets.UTF_8));
    }
    static DesignerProject open(Path path, String data) throws Exception {
        if (path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xml")) {
            String source = text(path); var project = new DesignerProject(defaults(source).build(), data);
            return project.source(source, data);
        }
        try (var input = Files.newInputStream(path)) {
            var bundle = XmlTemplateBundle.read(input, new XmlTemplateBundle.Limits(16 * 1024 * 1024,
                    16 * 1024 * 1024, 256, 1_000_000, 8_000_000), ExecutionOptions.DEFAULT);
            return new DesignerProject(bundle, data).source(bundle.source(), data);
        }
    }
    static String text(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(4_000_001);
            if (bytes.length > 4_000_000) { throw new IOException("text input exceeds 4,000,000 bytes"); }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
    static void saveText(Path target, String text) throws IOException {
        Path absolute = target.toAbsolutePath(); Path temporary = Files.createTempFile(absolute.getParent(), ".export4j-designer-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    static DocumentEngine engine(XmlTemplateBundle bundle) throws IOException { return engine(bundle, List.of()); }
    private static DocumentEngine engine(XmlTemplateBundle bundle, List<com.mirakyux.export4j.model.Note> notes) throws IOException {
        var fonts = bundle.fonts();
        if (!fonts.containsKey("Regular.ttf")) { throw new IOException("select a document font by importing it as Regular.ttf"); }
        var body = new ArrayList<com.mirakyux.export4j.font.FontFace>();
        body.add(TrueTypeFontLoader.load(fonts.get("Regular.ttf")));
        if (fonts.containsKey("Chinese.ttf")) { body.add(TrueTypeFontLoader.load(fonts.get("Chinese.ttf"))); }
        var builder = DocumentEngine.builder(FontGroup.strict(body)).notes(notes);
        if (fonts.containsKey("Bold.ttf")) {
            var bold = new ArrayList<com.mirakyux.export4j.font.FontFace>();
            bold.add(TrueTypeFontLoader.load(fonts.get("Bold.ttf")));
            if (body.size() == 2) { bold.add(body.get(1)); }
            builder.fontRole("bold", FontGroup.strict(bold));
        }
        return builder.build();
    }
    static Preview preview(DesignerProject project, ExecutionOptions execution) throws IOException {
        return ExecutionContext.execute(execution, () -> {
            DesignerXml.PreviewSource source;
            try { source = DesignerXml.previewSource(project.bundle().source()); }
            catch (Exception ex) { throw new IOException("cannot prepare designer preview source", ex); }
            var bound = project.bundle().toBuilder().source(source.source()).build().compile().renderComplete(BindingJson.parse(project.data()));
            var layout = engine(project.bundle(), bound.notes()).layout(bound.document());
            if (layout.display().pages().size() > 80) { throw new IOException("designer preview exceeds 80 pages; use the library streaming API for large workloads"); }
            var hits = new ArrayList<Hit>();
            for (var page : layout.body().pages()) {
                for (var fragment : page.elements()) {
                    double width = page.contentBounds().width(); double height = 12;
                    var images = new ArrayList<ImageBounds>();
                    if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment p) {
                        width = p.layout().width(); height = p.layout().height();
                        for (var line : p.layout().lines()) {
                            for (var box : line.images()) {
                                var image = box.image();
                                images.add(new ImageBounds(p.origin().x() + line.offsetX() + box.x(),
                                        p.origin().y() + line.baselineY() + image.baselineShift() - image.height(), image.width(), image.height()));
                            }
                        }
                    }
                    if (fragment instanceof com.mirakyux.export4j.layout.TableFragment table) { width = table.width(); height = table.height(); }
                    String path = source.paths().getOrDefault(fragment.blockId().split("@", 2)[0], "");
                    hits.add(new Hit(page.pageIndex() + layout.bodyPageOffset(), fragment.blockId(), path, fragment.origin().x(), fragment.origin().y(), width, height, List.copyOf(images)));
                }
            }
            Element sourceTree;
            try { sourceTree = DesignerXml.parse(source.source()).getDocumentElement(); }
            catch (Exception ex) { throw new IOException("cannot map designer page regions", ex); }
            var sections = new ArrayList<String>();
            for (int index = 0; index < DesignerXml.children(sourceTree).size(); index++) {
                if (DesignerXml.children(sourceTree).get(index).getTagName().equals("section")) { sections.add("/" + index); }
            }
            if (sections.isEmpty()) { sections.add(""); }
            var starts = new HashMap<String, Integer>();
            for (var hit : hits) {
                String owner = sectionPath(sourceTree, hit.path());
                starts.merge(owner, hit.page(), Math::min);
            }
            for (var placed : layout.decorations()) {
                String kind = placed.region().name().toLowerCase(java.util.Locale.ROOT);
                if (!kind.equals("header") && !kind.equals("footer")) { continue; }
                int sectionIndex = 0;
                for (int index = 1; index < sections.size(); index++) {
                    if (starts.getOrDefault(sections.get(index), Integer.MAX_VALUE) <= placed.pageIndex()) { sectionIndex = index; }
                }
                String section = sections.get(sectionIndex);
                int firstPage = starts.getOrDefault(section, layout.bodyPageOffset());
                var regions = bound.document().sections().get(sectionIndex).pageRegions();
                int displayed = 1;
                for (int index = 0; index <= sectionIndex; index++) {
                    if (index > 0) {
                        displayed += starts.getOrDefault(sections.get(index), firstPage)
                                - starts.getOrDefault(sections.get(index - 1), layout.bodyPageOffset());
                    }
                    int override = bound.document().sections().get(index).firstPageNumber();
                    if (override > 0) { displayed = override; }
                }
                displayed += placed.pageIndex() - firstPage;
                String variant = placed.pageIndex() == firstPage && regions.variants().containsKey(com.mirakyux.export4j.model.PageRegions.Variant.FIRST)
                        ? "first" : displayed % 2 == 0 && regions.variants().containsKey(com.mirakyux.export4j.model.PageRegions.Variant.EVEN) ? "even" : "default";
                String path = regionPath(sourceTree, section, kind, variant);
                if (path.isEmpty()) { continue; }
                var band = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                if (band.hasAttribute("source-ref")) {
                    String target = band.getAttribute("source-ref");
                    path = sourceIdPath(sourceTree, kind, target, "");
                    if (path.isEmpty()) { continue; }
                    band = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                }
                var children = DesignerXml.children(band);
                if (children.size() == 1 && (children.getFirst().getTagName().equals("p") || children.getFirst().getTagName().equals("table"))) {
                    path += "/0";
                }
                var node = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                var box = placed.viewport();
                hits.add(new Hit(placed.pageIndex(), node.getAttribute("id"), path, box.x(), box.y(), box.width(), box.height(), List.of()));
            }
            var breaks = new ArrayList<Break>();
            for (var section : bound.document().sections()) {
                var pending = new ArrayList<String>();
                for (var block : section.blocks()) {
                    if (block instanceof com.mirakyux.export4j.model.PageBreak pageBreak) { pending.add(pageBreak.id()); }
                    else {
                        var first = hits.stream().filter(hit -> hit.id().equals(block.id())).findFirst();
                        if (first.isPresent()) {
                            var hit = first.orElseThrow();
                            pending.forEach(id -> breaks.add(new Break(hit.page(), id, source.paths().getOrDefault(id.split("@", 2)[0], ""), hit.y())));
                            pending.clear();
                        }
                    }
                }
            }
            return new Preview(layout, List.copyOf(hits), List.copyOf(breaks));
        });
    }
    private static String sectionPath(Element root, String path) {
        if (path.isEmpty()) { return ""; }
        String section = ""; String current = "";
        for (String part : path.substring(1).split("/")) {
            current += "/" + part;
            if (DesignerXml.at(root.getOwnerDocument(), current).getTagName().equals("section")) { section = current; }
        }
        return section;
    }
    private static String regionPath(Element root, String section, String kind, String variant) {
        String found = directRegionPath(root, section, kind, variant);
        return found.isEmpty() ? directRegionPath(root, "", kind, variant) : found;
    }
    private static String directRegionPath(Element root, String parentPath, String kind, String variant) {
        var parent = DesignerXml.at(root.getOwnerDocument(), parentPath);
        var children = DesignerXml.children(parent);
        for (int index = 0; index < children.size(); index++) {
            var child = children.get(index);
            if (child.getTagName().equals(kind) && (variant.equals("default")
                    ? child.getAttribute("variant").isEmpty() || child.getAttribute("variant").equals("default")
                    : child.getAttribute("variant").equals(variant))) {
                return parentPath + "/" + index;
            }
        }
        return "";
    }
    private static String sourceIdPath(Element root, String kind, String id, String path) {
        if (root.getTagName().equals(kind) && root.getAttribute("source-id").equals(id)) { return path; }
        var children = DesignerXml.children(root);
        for (int index = 0; index < children.size(); index++) {
            String found = sourceIdPath(children.get(index), kind, id, path + "/" + index);
            if (!found.isEmpty()) { return found; }
        }
        return "";
    }
    static DocumentConversion.Prepared prepare(DesignerProject project, Format format, ProcessingPolicy policy, ExecutionOptions execution) throws IOException {
        return ExecutionContext.execute(execution, () -> {
            var bound = project.bundle().compile().renderComplete(BindingJson.parse(project.data()));
            var engine = engine(project.bundle());
            DocumentConversion.Target target = switch (format) {
                case PDF -> engine.conversionTarget("PDF", new PdfBackend());
                case DOCX -> withRotationLoss(format, new DocxAdapter().conversionTarget());
                case HTML -> withRotationLoss(format, new HtmlAdapter().conversionTarget());
                case MARKDOWN -> withRotationLoss(format, new MarkdownAdapter().conversionTarget());
                case SVG, PNG -> new DocumentConversion.Target(format.name(), (document, notes, rules, output) -> {
                    var layout = engine(project.bundle(), notes).layout(document);
                    var display = layout.display(); var issues = new ArrayList<ProcessingIssue>();
                    if (!display.extras().equals(com.mirakyux.export4j.graphics.DocumentExtras.EMPTY)) {
                        var rule = rules.resolve(format.name(), "INTERACTIVE_CONTENT", "EXPORT");
                        var issue = new ProcessingIssue("EXPORT", "MODEL", format.name(), "INTERACTIVE_CONTENT", "EXPORT",
                                new ProcessingIssue.Location("", "DOCUMENT", "", List.of(), -1, -1),
                                "static page images cannot retain navigation and interactive objects", rule.action(), rule.treatment(), List.of("INTERACTIVE_OBJECTS_REMOVED"));
                        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
                        if (rule.treatment() != ProcessingPolicy.Treatment.DISCARD) { throw new IllegalArgumentException("static page interaction loss requires DISCARD"); }
                        issues.add(issue); display = new DisplayDocument(display.metadata(), display.pages());
                    }
                    if (format == Format.SVG) {
                        var borrowed = new java.io.FilterOutputStream(output) {
                            @Override public void write(byte[] bytes, int offset, int length) throws IOException { out.write(bytes, offset, length); }
                            @Override public void close() { }
                        };
                        try (var zip = new java.util.zip.ZipOutputStream(borrowed)) {
                            int index = 1;
                            for (var page : display.pages()) {
                                ExecutionContext.checkpoint();
                                var entry = new java.util.zip.ZipEntry("page-" + index++ + ".svg"); entry.setTime(0);
                                zip.putNextEntry(entry);
                                issues.addAll(new SvgBackend().write(new DisplayDocument(display.metadata(), List.of(page)), zip, rules).issues());
                                zip.closeEntry();
                            }
                        }
                    }
                    else { issues.addAll(new RasterBackend(96).write(display, output, rules).issues()); }
                    return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
                });
            };
            return DocumentConversion.from(bound.document(), bound.notes()).policy(policy).execution(execution).prepare(target);
        });
    }

    private static DocumentConversion.Target withRotationLoss(Format format, DocumentConversion.Target target) {
        return new DocumentConversion.Target(target.format(), (document, notes, rules, output) -> {
            var issues = rotationLosses(document, format, rules);
            var actual = target.writer().write(document, notes, rules, output);
            issues.addAll(actual.issues());
            return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        });
    }

    private static ArrayList<ProcessingIssue> rotationLosses(SectionedDocument document, Format format, ProcessingPolicy rules) {
        var issues = new ArrayList<ProcessingIssue>();
        for (var section : document.sections()) {
            rotationBlocks(issues, format, rules, section.id(), "BODY", section.blocks());
            for (var region : section.pageRegions().headers()) {
                regionLosses(issues, format, rules, section.id(), "HEADER", region);
            }
            for (var region : section.pageRegions().footers()) {
                regionLosses(issues, format, rules, section.id(), "FOOTER", region);
            }
        }
        return issues;
    }

    private static void regionLosses(List<ProcessingIssue> issues, Format format, ProcessingPolicy rules,
            String section, String name, com.mirakyux.export4j.model.PageRegion region) {
        if (region.edgeOffset() >= 0 || region.leftInset() >= 0 || region.rightInset() >= 0) {
            var rule = rules.resolve(format.name(), "PAGE_REGION_POSITION", "EXPORT");
            var issue = new ProcessingIssue("EXPORT", "MODEL", format.name(), "PAGE_REGION_POSITION", "EXPORT",
                    new ProcessingIssue.Location(section, name, "", List.of(), -1, -1),
                    "target format cannot retain independent page-band edge offsets", rule.action(), rule.treatment(),
                    List.of("PAGE_REGION_POSITION_REMOVED"));
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) {
                throw new IllegalArgumentException("page region position loss requires DEGRADE");
            }
            issues.add(issue);
        }
        rotationBlocks(issues, format, rules, section, name, region.blocks());
    }

    private static void rotationBlocks(List<ProcessingIssue> issues, Format format, ProcessingPolicy rules,
            String section, String region, List<com.mirakyux.export4j.model.Block> blocks) {
            SemanticTree.walk(blocks, visit -> {
                var block = visit.block();
                double angle = switch (block) {
                    case com.mirakyux.export4j.model.Paragraph p -> p.format().rotation();
                    case com.mirakyux.export4j.model.Heading h -> h.format().rotation();
                    case com.mirakyux.export4j.model.BulletItem b -> b.format().rotation();
                    case com.mirakyux.export4j.model.NumberedItem n -> n.format().rotation();
                    case com.mirakyux.export4j.model.Caption c -> c.format().rotation();
                    case com.mirakyux.export4j.model.CrossReference r -> r.format().rotation();
                    case com.mirakyux.export4j.model.Table t -> t.format().rotation();
                    default -> 0;
                };
                if (angle != 0) { rotationIssue(issues, format, rules, section, region, block.id(), -1, -1); }
                if (block instanceof com.mirakyux.export4j.model.Paragraph p) {
                    for (var image : p.images()) {
                        if (image.image().rotation() != 0) {
                            rotationIssue(issues, format, rules, section, region, p.id(), image.sourceIndex(), image.sourceIndex() + 1);
                        }
                    }
                }
                if (block instanceof com.mirakyux.export4j.model.Table table) {
                    for (var row : table.rows()) {
                        for (var cell : row.cells()) {
                            for (var image : cell.images()) {
                                if (image.image().rotation() != 0) {
                                    rotationIssue(issues, format, rules, section, region, table.id(), -1, -1);
                                }
                            }
                        }
                    }
                }
            });
    }

    private static void rotationIssue(List<ProcessingIssue> issues, Format format, ProcessingPolicy rules,
            String section, String region, String block, int start, int end) {
        var rule = rules.resolve(format.name(), "ELEMENT_ROTATION", "EXPORT");
        var issue = new ProcessingIssue("EXPORT", "MODEL", format.name(), "ELEMENT_ROTATION", "EXPORT",
                new ProcessingIssue.Location(section, region, block, List.of(), start, end),
                "target format retains content but cannot retain element rotation", rule.action(), rule.treatment(),
                List.of("ROTATION_REMOVED"));
        if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
        if (rule.treatment() != ProcessingPolicy.Treatment.DEGRADE) {
            throw new IllegalArgumentException("element rotation loss requires DEGRADE");
        }
        issues.add(issue);
    }
}
