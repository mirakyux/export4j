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
    record TableCellBounds(double x, double y, double width, double height, int column, int columnSpan) { }
    record Hit(int page, String id, String path, double x, double y, double width, double height, List<ImageBounds> images, TableCellBounds tableCell) {
        Hit(int page, String id, String path, double x, double y, double width, double height, List<ImageBounds> images) {
            this(page, id, path, x, y, width, height, images, null);
        }
    }
    record Break(int page, String id, String path, double y) { }
    record ImageSpace(int page, String path, double width, double height) { }
    record Preview(ComponentDocumentLayout layout, List<Hit> hits, List<Break> breaks, Map<String, Map<String, String>> formats,
            List<ImageSpace> imageSpaces) {
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
        if (format == Format.DOCX || format == Format.HTML || format == Format.MARKDOWN) {
            rules.feature("COLUMN_FLOW", action, ProcessingPolicy.Treatment.DEGRADE);
            rules.feature("GRID_LAYOUT", action, ProcessingPolicy.Treatment.DEGRADE);
        }
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
        return defaults(source, "ZCOOLXiaoWei-Regular.ttf", "ZCOOLXiaoWei-OFL.txt");
    }
    static XmlTemplateBundle.Builder defaults(String source, String chineseFont, String chineseLicense) throws IOException {
        return XmlTemplateBundle.builder(source).limits(new XmlTemplateBundle.Limits(16 * 1024 * 1024,
                16 * 1024 * 1024, 256, 1_000_000, 8_000_000))
                .font("Regular.ttf", resource("LiberationSans-Regular.ttf"))
                .font("Bold.ttf", resource("LiberationSans-Bold.ttf"))
                .font("Chinese.ttf", resource(chineseFont))
                .license("LiberationSans-OFL.txt", new String(resource("LiberationSans-OFL.txt"), StandardCharsets.UTF_8))
                .license(chineseLicense, new String(resource(chineseLicense), StandardCharsets.UTF_8));
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
            var renderer = engine(project.bundle(), bound.notes());
            var layout = renderer.layout(bound.document());
            // Local region probes exclude main-body notes, while sharing one engine per preview.
            var regionRenderer = bound.notes().isEmpty() ? renderer : engine(project.bundle());
            var measuredRegions = new HashMap<RegionMeasure, com.mirakyux.export4j.layout.DocumentLayout>();
            var remainingRegions = new HashMap<RegionMeasure, Double>();
            var imageSpaces = new ArrayList<ImageSpace>();
            if (layout.display().pages().size() > 80) { throw new IOException("designer preview exceeds 80 pages; use the library streaming API for large workloads"); }
            var hits = new ArrayList<Hit>();
            Element sourceTree;
            try { sourceTree = DesignerXml.parse(source.source()).getDocumentElement(); }
            catch (Exception ex) { throw new IOException("cannot map designer page regions", ex); }
            for (var page : layout.body().pages()) {
                if (!page.elements().isEmpty()) {
                    String path = source.paths().getOrDefault(page.elements().getFirst().blockId().split("@", 2)[0], "");
                    imageSpaces.add(new ImageSpace(page.pageIndex() + layout.bodyPageOffset(), sectionPath(sourceTree, path),
                            page.contentBounds().width(), page.contentBounds().height()));
                }
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
                    if (fragment instanceof com.mirakyux.export4j.layout.ComponentFragment component) { width = component.layout().width(); height = component.layout().height(); }
                    String path = source.paths().getOrDefault(fragment.blockId().split("@", 2)[0], "");
                    double x = fragment.origin().x(), y = fragment.origin().y();
                    if (!path.isEmpty() && images.size() == 1) {
                        var node = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                        if (node.getTagName().equals("p") && node.getTextContent().isBlank() && DesignerXml.children(node).size() == 1
                                && DesignerXml.children(node).getFirst().getTagName().equals("image")) {
                            var image = images.getFirst(); x = image.x(); y = image.y(); width = image.width(); height = image.height();
                        }
                    }
                    hits.add(new Hit(page.pageIndex() + layout.bodyPageOffset(), fragment.blockId(), path, x, y, width, height, List.copyOf(images)));
                    if (fragment instanceof com.mirakyux.export4j.layout.TableFragment table && !path.isEmpty()) {
                        tableHits(hits, page.pageIndex() + layout.bodyPageOffset(), table, sourceTree, path, 0, 0);
                    }
                }
            }
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
                if (!List.of("header", "footer", "background", "foreground").contains(kind)) { continue; }
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
                boolean overlay = kind.equals("background") || kind.equals("foreground");
                String variant = placed.pageIndex() == firstPage && regions.variants().containsKey(com.mirakyux.export4j.model.PageRegions.Variant.FIRST)
                        ? "first" : displayed % 2 == 0 && regions.variants().containsKey(com.mirakyux.export4j.model.PageRegions.Variant.EVEN) ? "even" : "default";
                String path = regionPath(sourceTree, section, kind, overlay ? "default" : variant);
                if (path.isEmpty()) { continue; }
                var band = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                if (overlay) {
                    var boxes = DesignerXml.children(band);
                    if (placed.placementIndex() >= boxes.size()) { continue; }
                    path += "/" + placed.placementIndex(); band = boxes.get(placed.placementIndex());
                }
                if (band.hasAttribute("source-ref")) {
                    String target = band.getAttribute("source-ref");
                    path = sourceIdPath(sourceTree, overlay ? "box" : kind, target, "");
                    if (path.isEmpty()) { continue; }
                    band = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                }
                String regionRoot = path;
                com.mirakyux.export4j.model.PageRegion content;
                if (overlay) {
                    content = (kind.equals("background") ? regions.background() : regions.foreground()).get(placed.placementIndex()).content();
                } else {
                    var pair = variant.equals("default") ? new com.mirakyux.export4j.model.PageRegions.Bands(regions.header(), regions.footer())
                            : regions.variants().get(variant.equals("first") ? com.mirakyux.export4j.model.PageRegions.Variant.FIRST
                                    : com.mirakyux.export4j.model.PageRegions.Variant.EVEN);
                    content = kind.equals("header") ? pair.header() : pair.footer();
                }
                var children = DesignerXml.children(band);
                if (children.size() == 1 && (children.getFirst().getTagName().equals("p") || children.getFirst().getTagName().equals("table"))) {
                    path += "/0";
                }
                var node = DesignerXml.at(sourceTree.getOwnerDocument(), path);
                var box = placed.viewport();
                // Pasting into a fixed box switches it to AUTO height; its physical bottom stays bounded.
                double availableHeight = overlay ? layout.display().pages().get(placed.pageIndex()).size().height() - box.y() : box.height();
                var measure = new RegionMeasure(content, box.width(), availableHeight);
                Double remaining = remainingRegions.get(measure);
                if (remaining == null) {
                    remaining = imageAppendHeight(content, box.width(), availableHeight, regionRenderer);
                    remainingRegions.put(measure, remaining);
                }
                imageSpaces.add(new ImageSpace(placed.pageIndex(), regionRoot, box.width(), remaining));
                if (node.getTagName().equals("p") && node.getTextContent().isBlank() && DesignerXml.children(node).size() == 1
                        && DesignerXml.children(node).getFirst().getTagName().equals("image") && !node.hasAttribute("rotation")) {
                    var image = placed.layout().commands().stream().filter(com.mirakyux.export4j.graphics.DrawCommand.Image.class::isInstance)
                            .map(com.mirakyux.export4j.graphics.DrawCommand.Image.class::cast).findFirst();
                    if (image.isPresent()) { var bounds = image.orElseThrow().bounds();
                        hits.add(new Hit(placed.pageIndex(), node.getAttribute("id"), path, box.x() + bounds.x(), box.y() + bounds.y(), bounds.width(), bounds.height(),
                                List.of(new ImageBounds(box.x() + bounds.x(), box.y() + bounds.y(), bounds.width(), bounds.height())))); continue;
                    }
                }
                hits.add(new Hit(placed.pageIndex(), node.getAttribute("id"), path, box.x(), box.y(), box.width(),
                        overlay ? placed.layout().height() : box.height(), List.of()));
                regionHits(hits, placed.pageIndex(), content, box, sourceTree, source.paths(), regionRoot, path,
                        regionRenderer, measuredRegions);
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
            return new Preview(layout, List.copyOf(hits), List.copyOf(breaks), paragraphDefaults(project, source, sourceTree), List.copyOf(imageSpaces));
        });
    }
    /** Compile a reduced, unbound source to read paragraph defaults without guessing theme inheritance.
     * Only inline content is replaced in the private probe; the actual source and resources stay untouched.
     * Reducing each paragraph to one literal also avoids expanding data or increasing XML work budgets.
     */
    private static Map<String, Map<String, String>> paragraphDefaults(DesignerProject project, DesignerXml.PreviewSource source, Element tree) throws IOException {
        try {
            var probe = DesignerXml.parse(DesignerXml.serialize(tree));
            var paragraphs = probe.getElementsByTagName("p");
            for (int index = 0; index < paragraphs.getLength(); index++) { paragraphs.item(index).setTextContent("P"); }
            var template = project.bundle().toBuilder().source(DesignerXml.serialize(probe)).build().compile();
            var result = new HashMap<String, Map<String, String>>();
            collectDefaults(template.nodes(), source.paths(), result);
            for (var section : template.sections()) { collectDefaults(section.nodes(), source.paths(), result); regionDefaults(section.pageRegions(), source.paths(), result); }
            regionDefaults(template.pageRegions(), source.paths(), result);
            return Map.copyOf(result);
        } catch (Exception ex) { throw new IOException("cannot preserve resolved paragraph formatting", ex); }
    }
    private static void collectDefaults(List<com.mirakyux.export4j.model.DocumentTemplate.Node> nodes, Map<String, String> paths,
            Map<String, Map<String, String>> result) {
        for (var node : nodes) {
            if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.ValueBlock value) { blockDefaults(List.of(value.prototype()), paths, result); }
            else if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.Columns columns) { collectDefaults(columns.children(), paths, result); }
            else if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.Grid grid) { collectDefaults(grid.children(), paths, result); }
            else if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.GridItem item) { collectDefaults(item.children(), paths, result); }
            else if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.Each each) { collectDefaults(each.children(), paths, result); }
            else if (node instanceof com.mirakyux.export4j.model.DocumentTemplate.When when) { collectDefaults(when.yes(), paths, result); collectDefaults(when.no(), paths, result); }
        }
    }
    private static void regionDefaults(com.mirakyux.export4j.model.PageRegions regions, Map<String, String> paths,
            Map<String, Map<String, String>> result) {
        java.util.stream.Stream.of(regions.headers(), regions.footers(), regions.backgrounds(), regions.foregrounds())
                .flatMap(List::stream).forEach(region -> blockDefaults(region.blocks(), paths, result));
    }
    private static void blockDefaults(List<com.mirakyux.export4j.model.Block> blocks, Map<String, String> paths,
            Map<String, Map<String, String>> result) {
        com.mirakyux.export4j.model.SemanticTree.walk(blocks, visit -> {
            if (!(visit.block() instanceof com.mirakyux.export4j.model.Paragraph p) || !paths.containsKey(p.id())) { return; }
            var f = p.format(); var style = p.inlines().getFirst().style();
            result.put(paths.get(p.id()), Map.ofEntries(Map.entry("size", Double.toString(f.fontSize())), Map.entry("line-height", Double.toString(f.lineHeight())),
                    Map.entry("role", style.fontRole()), Map.entry("color", String.format(java.util.Locale.ROOT, "#%06X", style.rgb())),
                    Map.entry("align", f.alignment().name()), Map.entry("first-indent", Double.toString(f.firstLineIndent())),
                    Map.entry("subsequent-indent", Double.toString(f.subsequentLineIndent())), Map.entry("space-before", Double.toString(f.spaceBefore())),
                    Map.entry("space-after", Double.toString(f.spaceAfter())), Map.entry("keep-together", Boolean.toString(f.keepTogether())),
                    Map.entry("line-break", f.lineBreakPolicy().name()), Map.entry("keep-with-next", Boolean.toString(f.pagination().keepWithNext())),
                    Map.entry("page-break-before", Boolean.toString(f.pagination().pageBreakBefore())),
                    Map.entry("min-lines-before", Integer.toString(f.pagination().minLinesBeforeBreak())),
                    Map.entry("min-lines-after", Integer.toString(f.pagination().minLinesAfterBreak()))));
        });
    }
    private record RegionMeasure(com.mirakyux.export4j.model.PageRegion region, double width, double height) { }

    /** Measure the actual next line origin, including the preceding block's spacing and pagination.
     * The marker is private to this layout and never enters the retained source or resource history. */
    private static double imageAppendHeight(com.mirakyux.export4j.model.PageRegion region, double width, double height,
            DocumentEngine renderer) throws IOException {
        if (height < 18) { return 0; }
        String marker = "e4j-image-space-" + java.util.UUID.randomUUID();
        var blocks = new ArrayList<com.mirakyux.export4j.model.Block>(region.blocks());
        blocks.add(new com.mirakyux.export4j.model.Paragraph(marker, "", com.mirakyux.export4j.model.ParagraphFormat.builder()
                .fontSize(12).lineHeight(18).spaceBefore(0).spaceAfter(0).build()));
        var settings = com.mirakyux.export4j.model.PageSettings.builder().size(width, height).margins(0).build();
        try {
            var measured = renderer.layout(new com.mirakyux.export4j.model.Document(
                    com.mirakyux.export4j.model.DocumentMetadata.EMPTY, settings, blocks)).body();
            for (var page : measured.pages()) {
                for (var fragment : page.elements()) {
                    if (fragment.blockId().equals(marker)) { return page.pageIndex() == 0 ? Math.max(0, height - fragment.origin().y()) : 0; }
                }
            }
            throw new IOException("cannot measure image insertion space");
        } catch (com.mirakyux.export4j.layout.core.DocumentLayoutException ex) {
            if (ex.code() == com.mirakyux.export4j.layout.core.DocumentLayoutException.Code.CONTENT_TOO_TALL) { return 0; }
            throw ex;
        }
    }

    /** The composition trace contains final drawing commands. Reuse the same engine and concrete bound
     * model to recover local cell geometry for editing; this never changes final pagination or drawing. */
    private static void regionHits(List<Hit> hits, int page, com.mirakyux.export4j.model.PageRegion region,
            com.mirakyux.export4j.graphics.Rect viewport, Element sourceTree, Map<String, String> sourcePaths,
            String regionRoot, String primaryPath, DocumentEngine renderer,
            Map<RegionMeasure, com.mirakyux.export4j.layout.DocumentLayout> measuredRegions) throws IOException {
        // A single paragraph already has its exact hit above. Multiple paragraphs and tables need descendants.
        if (region.blocks().size() <= 1 && region.blocks().stream().noneMatch(com.mirakyux.export4j.model.Table.class::isInstance)) { return; }
        var key = new RegionMeasure(region, viewport.width(), viewport.height());
        var measured = measuredRegions.get(key);
        if (measured == null) {
            var settings = com.mirakyux.export4j.model.PageSettings.builder().size(viewport.width(), viewport.height()).margins(0).build();
            measured = renderer.layout(new com.mirakyux.export4j.model.Document(
                    com.mirakyux.export4j.model.DocumentMetadata.EMPTY, settings, region.blocks())).body();
            if (measured.pages().size() != 1) { throw new IOException("designer page-region geometry requires one local page"); }
            measuredRegions.put(key, measured);
        }
        for (var fragment : measured.pages().getFirst().elements()) {
            String path = sourcePaths.getOrDefault(fragment.blockId().split("@", 2)[0], "");
            if (!path.startsWith(regionRoot + "/")) { continue; }
            if (fragment instanceof com.mirakyux.export4j.layout.TableFragment table) {
                if (!path.equals(primaryPath)) {
                    hits.add(new Hit(page, table.blockId(), path, viewport.x() + table.origin().x(), viewport.y() + table.origin().y(),
                            table.width(), table.height(), List.of()));
                }
                var model = region.blocks().stream().filter(block -> block.id().equals(table.blockId())).findFirst().orElseThrow();
                if (((com.mirakyux.export4j.model.Table) model).format().rotation() == 0) {
                    tableHits(hits, page, table, sourceTree, path, viewport.x(), viewport.y());
                }
            } else if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment paragraph && !path.equals(primaryPath)) {
                var images = new ArrayList<ImageBounds>();
                for (var line : paragraph.layout().lines()) {
                    for (var box : line.images()) {
                        var image = box.image();
                        images.add(new ImageBounds(viewport.x() + paragraph.origin().x() + line.offsetX() + box.x(),
                                viewport.y() + paragraph.origin().y() + line.baselineY() + image.baselineShift() - image.height(),
                                image.width(), image.height()));
                    }
                }
                hits.add(new Hit(page, paragraph.blockId(), path, viewport.x() + paragraph.origin().x(), viewport.y() + paragraph.origin().y(),
                        paragraph.layout().width(), paragraph.layout().height(), List.copyOf(images)));
            }
        }
    }
    private static void tableHits(List<Hit> hits, int page, com.mirakyux.export4j.layout.TableFragment table,
            Element sourceTree, String path, double parentX, double parentY) {
        var source = DesignerXml.at(sourceTree.getOwnerDocument(), path);
        // Rotated text is shown by the engine image; a local unrotated cell box cannot be an editor target.
        if (source.hasAttribute("rotation") && Double.parseDouble(source.getAttribute("rotation")) != 0) { return; }
        var rows = DesignerXml.children(source).stream().filter(row -> List.of("row", "header").contains(row.getTagName())).toList();
        int headers = (int) rows.stream().filter(row -> row.getTagName().equals("header")).count();
        int columns = DesignerXml.children(DesignerXml.children(source).getFirst()).size();
        int[] occupied = new int[columns];
        var paths = new HashMap<String, String>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            var row = rows.get(rowIndex); int column = 0;
            for (var cell : DesignerXml.children(row)) {
                while (column < columns && occupied[column] > rowIndex) { column++; }
                int span = cell.hasAttribute("column-span") ? Integer.parseInt(cell.getAttribute("column-span")) : 1;
                int rowSpan = cell.hasAttribute("row-span") ? Integer.parseInt(cell.getAttribute("row-span")) : 1;
                paths.put(rowIndex + ":" + column, path + "/" + DesignerXml.children(source).indexOf(row) + "/" + DesignerXml.children(row).indexOf(cell));
                for (int slot = column; slot < Math.min(columns, column + span); slot++) { occupied[slot] = rowIndex + rowSpan; }
                column += span;
            }
        }
        for (var row : table.rows()) {
            int sourceRow = source.getTagName().equals("rows") && row.rowIndex() >= headers ? headers : row.rowIndex();
            for (var cell : row.cells()) {
                String cellPath = paths.get(sourceRow + ":" + cell.columnIndex()); if (cellPath == null) { continue; }
                double x = parentX + table.origin().x(), y = parentY + table.origin().y();
                var sourceCell = DesignerXml.at(sourceTree.getOwnerDocument(), cellPath);
                int columnSpan = sourceCell.hasAttribute("column-span") ? Integer.parseInt(sourceCell.getAttribute("column-span")) : 1;
                var bounds = new TableCellBounds(x + cell.bounds().x(), y + cell.bounds().y(), cell.bounds().width(), cell.bounds().height(), cell.columnIndex(), columnSpan);
                if (!cell.hasBlocks()) {
                    hits.add(new Hit(page, table.blockId() + "#" + row.rowIndex() + ":" + cell.columnIndex(), cellPath,
                            x + cell.textOrigin().x(), y + cell.textOrigin().y(), cell.layout().width(),
                            Math.max(cell.layout().height(), 12), List.of(), bounds));
                } else {
                    hits.add(new Hit(page, table.blockId() + "#" + row.rowIndex() + ":" + cell.columnIndex(), cellPath,
                            bounds.x(), bounds.y(), bounds.width(), bounds.height(), List.of(), bounds));
                    var children = DesignerXml.children(DesignerXml.at(sourceTree.getOwnerDocument(), cellPath));
                    for (int index = 0; index < cell.blocks().size(); index++) {
                        var fragment = cell.blocks().get(index); int ordinal = cell.sourceBlockIndices().get(index);
                        var blocks = children.stream().filter(child -> List.of("p", "table").contains(child.getTagName())).toList();
                        if (ordinal >= blocks.size()) { continue; }
                        String childPath = cellPath + "/" + children.indexOf(blocks.get(ordinal));
                        double cx = x + cell.textOrigin().x(), cy = y + cell.textOrigin().y();
                        if (fragment instanceof com.mirakyux.export4j.layout.ParagraphFragment p) {
                            hits.add(new Hit(page, p.blockId(), childPath, cx + p.origin().x(), cy + p.origin().y(), p.layout().width(), p.layout().height(), List.of()));
                        } else if (fragment instanceof com.mirakyux.export4j.layout.TableFragment nested) { tableHits(hits, page, nested, sourceTree, childPath, cx, cy); }
                    }
                }
            }
        }
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
                case DOCX -> withPageRegionLoss(format, new DocxAdapter().conversionTarget());
                case HTML -> withPageRegionLoss(format, new HtmlAdapter().conversionTarget());
                case MARKDOWN -> withPageRegionLoss(format, new MarkdownAdapter().conversionTarget());
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

    private static DocumentConversion.Target withPageRegionLoss(Format format, DocumentConversion.Target target) {
        return new DocumentConversion.Target(target.format(), (document, notes, rules, output) -> {
            var issues = pageRegionLosses(document, format, rules);
            var actual = target.writer().write(document, notes, rules, output);
            issues.addAll(actual.issues());
            return new ProcessingReport(ProcessingReport.Phase.ACTUAL, issues);
        });
    }

    private static ArrayList<ProcessingIssue> pageRegionLosses(SectionedDocument document, Format format, ProcessingPolicy rules) {
        var issues = new ArrayList<ProcessingIssue>();
        for (var section : document.sections()) {
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
    }

}
