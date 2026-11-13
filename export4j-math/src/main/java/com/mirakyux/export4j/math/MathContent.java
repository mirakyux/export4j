// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.math;

import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.web.ContentImporter;
import com.mirakyux.export4j.adapter.web.ImportLimits;
import com.mirakyux.export4j.adapter.web.FormulaSyntax;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.ContentOptions;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.VectorResource;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * 一次编译显式源内容，同时得到视觉文档和可编辑 Word 数学文档。
 * 每次编译独立捕获公式身份，既不按 TeX 文本合并出现位置，也不保存全局文档缓存。
 * 字体、资源访问和输出流仍由调用方显式提供；本类不查找系统字体或访问网络。
 * 实例不可变并可并发复用；编写回调同步执行且不被保存。
 */
public final class MathContent {
    public enum Target { VISUAL, DOCX }
    public enum Fallback { IMAGE, TEXT }
    public enum Reason { UNSUPPORTED_FORMULA, UNSUPPORTED_APPEARANCE, INVALID_FORMULA, RENDERING_FAILED, TOO_WIDE }

    /** 逐式、逐目标的脱敏表示变更，不含公式正文。 */
    public record Degradation(int formulaIndex, Target target, Fallback representation, Reason reason) {
        public Degradation {
            if (formulaIndex < 0) { throw new IllegalArgumentException("negative formula index"); }
            Objects.requireNonNull(target, "target"); Objects.requireNonNull(representation, "representation"); Objects.requireNonNull(reason, "reason");
        }
    }

    /** 源公式的编译身份；索引依照本次编译的实际解析顺序。 */
    public record Formula(int index, String tex, boolean display) {
        public Formula {
            if (index < 0 || Objects.requireNonNull(tex, "tex").isBlank()) { throw new IllegalArgumentException("invalid formula identity"); }
        }
    }

    /** 两个目标来自同一次源解析。visual 不含假槽，docx 的所有数学槽均有绑定。 */
    public record Result(Document document, DocxMathDocument docx, List<Formula> formulas,
            List<Degradation> degradations) {
        public Result {
            Objects.requireNonNull(document, "document"); Objects.requireNonNull(docx, "docx");
            formulas = List.copyOf(formulas); degradations = List.copyOf(degradations);
        }
    }

    /** Visual output only; native Word support does not constrain this compilation. */
    public record VisualResult(Document document, List<Formula> formulas, List<Degradation> degradations) {
        public VisualResult {
            Objects.requireNonNull(document, "document");
            formulas = List.copyOf(formulas); degradations = List.copyOf(degradations);
        }
    }

    /** Native Word output only, with occurrence-specific representation diagnostics.
     * The shared bounded TeX and outline preparation contract still applies.
     */
    public record DocxResult(DocxMathDocument document, List<Formula> formulas, List<Degradation> degradations) {
        public DocxResult {
            Objects.requireNonNull(document, "document");
            formulas = List.copyOf(formulas); degradations = List.copyOf(degradations);
        }
    }

    /** 可用同一个解析器编写 Java 模板或编译 XML；返回后不保留编写器及中间文档。 */
    @FunctionalInterface
    public interface Author { Document compile(MathContentSource importer) throws IOException; }

    private final ContentOptions.Resolved defaults;
    private final ImportLimits limits;
    private final MathOptions options;
    private final MathTextFonts textFonts;
    private final int maxFormulas, maxSegments, rasterScale;
    private final double minimumImageScale;
    private final boolean expandObjectLines;
    private final List<Fallback> fallbacks;
    private final Map<String, InlineImage> images;
    private final Map<String, List<String>> families;
    private final PageSettings pageSettings;
    private final DocumentMetadata metadata;

    private MathContent(Builder b) {
        defaults = b.defaults; limits = b.limits; options = b.options;
        textFonts = b.textFonts;
        maxFormulas = b.maxFormulas; maxSegments = b.maxSegments; rasterScale = b.rasterScale;
        minimumImageScale = b.minimumImageScale; fallbacks = List.copyOf(b.fallbacks);
        expandObjectLines = b.expandObjectLines;
        images = Map.copyOf(b.images); families = Map.copyOf(b.families);
        pageSettings = b.pageSettings; metadata = b.metadata;
    }

    public static Builder builder() { return new Builder(); }

    /** 按明确格式编译单块源内容；输入始终是正文，不是文件地址。 */
    public Result read(String source) throws IOException {
        Objects.requireNonNull(source, "source");
        return compile(importer -> importer.read(source).execute().document());
    }

    /** Compiles source for visual output without constructing a native Word projection. */
    public VisualResult readVisual(String source) throws IOException {
        Objects.requireNonNull(source, "source");
        return compileVisual(importer -> importer.read(source).execute().document());
    }

    /** Compiles source for native Word output without constructing a final visual document.
     * Outline preparation still checks the shared source and geometry budgets.
     */
    public DocxResult readDocx(String source) throws IOException {
        Objects.requireNonNull(source, "source");
        return compileDocx(importer -> importer.read(source).execute().document());
    }

    /** 所有公式额度跨回调内的源块累计，预算与取消异常不能进入降级分支。 */
    public Result compile(Author author) throws IOException {
        var capture = new Capture();
        var source = source(author, capture);
        var visual = new Projection(capture, Target.VISUAL, source).document(source);
        var docx = docx(source, capture);
        return new Result(visual, docx, formulas(capture), capture.degradations);
    }

    /** Parses once in the shared source session and projects only visual output.
     * The author is synchronous and is not retained; omitted or duplicated formulas still fail.
     */
    public VisualResult compileVisual(Author author) throws IOException {
        var capture = new Capture();
        var source = source(author, capture);
        var visual = new Projection(capture, Target.VISUAL, source).document(source);
        return new VisualResult(visual, formulas(capture), capture.degradations);
    }

    /** Parses once in the shared source session and projects only native Word output.
     * The author is synchronous and is not retained; omitted or duplicated formulas still fail.
     * Shared bounded TeX and outline preparation remain required for formula geometry.
     */
    public DocxResult compileDocx(Author author) throws IOException {
        var capture = new Capture();
        var source = source(author, capture);
        return new DocxResult(docx(source, capture), formulas(capture), capture.degradations);
    }

    private Document source(Author author, Capture capture) throws IOException {
        var builder = ContentImporter.builder().defaults(defaults).limits(limits)
                .maxFormulas(maxFormulas).maxFormulaUtf16(options.maxSourceLength())
                .maxVectorSegments(maxSegments).pageSettings(pageSettings).metadata(metadata)
                .formulaProcessor(capture::render);
        images.forEach(builder::image);
        families.forEach((name, roles) -> builder.fontFamily(name, roles.get(0), roles.get(1), roles.get(2), roles.get(3)));
        Document source;
        try (var sources = new MathContentSource(builder.build(), defaults, limits, metadata, pageSettings)) {
            source = Objects.requireNonNull(Objects.requireNonNull(author, "author").compile(sources), "document");
        }
        catch (FormulaSyntax.LimitExceededException failure) { throw new MathException(MathException.Reason.LIMIT_EXCEEDED, failure); }
        return source;
    }

    private DocxMathDocument docx(Document source, Capture capture) throws IOException {
        var nativeProjection = new Projection(capture, Target.DOCX, source);
        var nativeSource = nativeProjection.document(source);
        return NativeMathBinding.bind(nativeSource, nativeProjection.nativeEquations, true);
    }

    private static List<Formula> formulas(Capture capture) {
        return capture.ordered.stream().map(o -> o.formula).toList();
    }

    public static final class Builder {
        private ContentOptions.Resolved defaults = ContentOptions.builder().format(ContentOptions.Format.MARKDOWN)
                .formulas(true).build().resolve(ContentOptions.defaults());
        private ImportLimits limits = ImportLimits.DEFAULT;
        private MathOptions options = MathOptions.DEFAULT;
        private MathTextFonts textFonts = MathTextFonts.EMPTY;
        private int maxFormulas = 1_000, maxSegments = 1_000_000, rasterScale = 4;
        private double minimumImageScale = 0.5;
        private boolean expandObjectLines = true;
        private List<Fallback> fallbacks = List.of();
        private final Map<String, InlineImage> images = new LinkedHashMap<>();
        private final Map<String, List<String>> families = new LinkedHashMap<>();
        private PageSettings pageSettings = PageSettings.A4;
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private Builder() { }
        public Builder defaults(ContentOptions value) { defaults = Objects.requireNonNull(value, "defaults").resolve(defaults); return this; }
        /** Uses a complete immutable source snapshot, including distinct heading and cell styles. */
        public Builder defaults(ContentOptions.Resolved value) { defaults = Objects.requireNonNull(value, "defaults"); return this; }
        public Builder limits(ImportLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Builder options(MathOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
        /** Explicit physical fonts for text/textrm inside formulas; encoded sources are snapshotted by MathTextFonts. */
        public Builder textFonts(MathTextFonts value) { textFonts = Objects.requireNonNull(value, "textFonts"); return this; }
        public Builder maxFormulas(int value) { if (value < 1) { throw new IllegalArgumentException("formula limit must be positive"); } maxFormulas = value; return this; }
        public Builder maxVectorSegments(int value) { if (value < 1) { throw new IllegalArgumentException("segment limit must be positive"); } maxSegments = value; return this; }
        /** 显式候选顺序；空列表严格失败，不会默认改变公式表示。 */
        public Builder fallbacks(List<Fallback> value) { fallbacks = List.copyOf(value); if (new HashSet<>(fallbacks).size() != fallbacks.size()) { throw new IllegalArgumentException("duplicate formula fallback"); } return this; }
        /** 图片采用该像素/pt 比例；不使用生产系统 DPI。最终像素仍受 ImageResource 限制。 */
        public Builder rasterScale(int value) { if (value < 1 || value > 8) { throw new IllegalArgumentException("raster scale must be from 1 to 8"); } rasterScale = value; return this; }
        /** 允许的最小等比缩放；更窄位置继续尝试下一候选，不裁切或拉伸。 */
        public Builder minimumImageScale(double value) { if (!Double.isFinite(value) || value <= 0 || value > 1) { throw new IllegalArgumentException("image scale must be in (0,1]"); } minimumImageScale = value; return this; }
        /** 新编排入口默认扩展对象行框；false 严格保留调用方声明的固定行距。 */
        public Builder expandObjectLines(boolean value) { expandObjectLines = value; return this; }
        public Builder image(String key, InlineImage value) { if (Objects.requireNonNull(key, "key").isBlank()) { throw new IllegalArgumentException("empty image key"); } images.put(key, Objects.requireNonNull(value, "image")); return this; }
        public Builder fontFamily(String family, String regular, String bold, String italic, String boldItalic) { families.put(Objects.requireNonNull(family, "family"), List.of(regular, bold, italic, boldItalic)); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder metadata(DocumentMetadata value) { metadata = Objects.requireNonNull(value, "metadata"); return this; }
        public MathContent build() { return new MathContent(this); }
    }

    private final class Capture {
        private final IdentityHashMap<VectorResource, Occurrence> occurrences = new IdentityHashMap<>();
        private final List<Occurrence> ordered = new ArrayList<>();
        private final List<Degradation> degradations = new ArrayList<>();
        private long segments;
        private InlineVector render(String tex, boolean display, double size, int rgb, int available) {
            ExecutionContext.checkpoint();
            if (ordered.size() >= maxFormulas) { throw limit(); }
            if (available <= 1) { throw limit(); }
            var local = new MathOptions(size, display, options.maxSourceLength(), options.maxDepth(), options.maxCommands(),
                    options.maxPaths(), Math.min(Math.min(options.maxSegments(), VectorResource.MAX_SEGMENTS - 1), available - 1), options.maxExtent());
            InlineVector vector;
            MathException failure = null;
            try {
                var rendered = TexMath.vector(tex, local, textFonts);
                vector = new InlineVector(rendered.resource(), rendered.logicalText(), rgb, "");
            } catch (MathException ex) {
                if (ex.reason() == MathException.Reason.LIMIT_EXCEEDED) { throw ex; }
                failure = ex;
                // 假槽只在编译中存在，最终视觉投影必须换成明确的候选表示。
                vector = new InlineVector(VectorResource.of(1, 1, 0, List.of(
                        new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 0.0)),
                        new VectorResource.Segment(VectorResource.Operation.LINE, List.of(1.0, -1.0)))),
                        tex.replace('\r', ' ').replace('\n', ' ').replace('\t', ' '), rgb, "");
            }
            // 无绘制效果的末尾 MOVE 为编译身份保留独立资源；模型的值压缩不能合并两次相同公式。
            // 该段同样计入源预算，最终视觉文档使用原轮廓，不包含身份段。
            var geometry = new ArrayList<>(vector.resource().segments());
            geometry.add(new VectorResource.Segment(VectorResource.Operation.MOVE,
                    List.of(Math.scalb((double) ordered.size() + 1, -32) * vector.resource().width(), 0.0)));
            var slot = new InlineVector(VectorResource.of(vector.resource().width(), vector.resource().ascent(),
                    vector.resource().descent(), geometry), vector.logicalText(), vector.rgb(), "");
            segments += slot.resource().segmentCount();
            if (segments > maxSegments) { throw limit(); }
            var occurrence = new Occurrence(new Formula(ordered.size(), tex, display), local, vector, slot, failure);
            ordered.add(occurrence); occurrences.put(slot.resource(), occurrence);
            return slot;
        }
    }

    private record Occurrence(Formula formula, MathOptions options, InlineVector vector, InlineVector slot, MathException failure) { }

    private final class Projection {
        private final Capture capture;
        private final Target target;
        private final Set<String> ids = new HashSet<>();
        private final Set<Occurrence> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<String, NativeMathBinding.Equation> nativeEquations = new LinkedHashMap<>();
        private int sequence;
        private Projection(Capture capture, Target target, Document source) {
            this.capture = capture; this.target = target;
            SemanticTree.walk(source.blocks(), visit -> ids.add(visit.block().id()));
        }
        private Document document(Document source) throws IOException {
            var blocks = blocks(source.blocks(), source.pageSettings().contentWidth());
            if (visited.size() != capture.ordered.size()) { throw new IllegalArgumentException("compiled formula was omitted or duplicated by author"); }
            return new Document(source.metadata(), source.pageSettings(), blocks);
        }
        private List<Block> blocks(List<Block> source, double width) throws IOException {
            var output = new ArrayList<Block>();
            for (var block : source) {
                ExecutionContext.checkpoint();
                if (block instanceof Paragraph p) { output.addAll(paragraphs(p, width)); }
                else if (block instanceof Heading h) {
                    var p = paragraph(h.paragraph(), width);
                    output.add(Heading.mixed(h.id(), p.content(), h.level(), p.format()));
                } else if (block instanceof NumberedItem n) {
                    var p = paragraph(n.paragraph(), width - (n.level() - 1) * n.format().fontSize() * 1.5);
                    output.add(NumberedItem.mixed(n.id(), n.listId(), p.content(), n.restartAt(), p.format(), n.level(), n.markerStyle()));
                } else if (block instanceof BulletItem b) {
                    var p = paragraph(b.paragraph(), width - (b.level() - 1) * b.format().fontSize() * 1.5);
                    output.add(BulletItem.mixed(b.id(), p.content(), b.marker(), b.level(), p.format(), b.markerStyle()));
                } else if (block instanceof Table t) { output.add(hasSlots(t) ? table(t, width) : t); }
                else if (block instanceof ColumnFlow c) {
                    double total = c.columnWeights().isEmpty() ? c.columns() : c.columnWeights().stream().mapToDouble(Double::doubleValue).sum();
                    double minimum = c.columnWeights().isEmpty() ? 1 : c.columnWeights().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
                    double inner = (width - (c.columns() - 1) * c.gap()) * minimum / total;
                    output.add(c.toBuilder().blocks(blocks(c.blocks(), inner)).build());
                } else if (block instanceof GridLayout g) {
                    double track = (width - (g.tracks() - 1) * g.columnGap()) / g.tracks();
                    var items = new ArrayList<GridItem>();
                    for (var item : g.items()) {
                        double inner = track * item.columnSpan() + (item.columnSpan() - 1) * g.columnGap();
                        items.add(item.toBuilder().blocks(blocks(item.blocks(), inner)).build());
                    }
                    output.add(g.toBuilder().items(items).build());
                }
                else { output.add(block); }
            }
            return List.copyOf(output);
        }
        private List<Block> paragraphs(Paragraph p, double width) throws IOException {
            var result = new ArrayList<Block>();
            var chunk = new ArrayList<InlineContent>();
            String id = p.id();
            for (var item : p.content()) {
                var o = item instanceof InlineVector v ? capture.occurrences.get(v.resource()) : null;
                if (o != null && o.formula.display()) {
                    if (!chunk.isEmpty()) { result.add(paragraph(Paragraph.mixed(id, chunk, p.format()), width)); id = nextId(); chunk.clear(); }
                    var format = p.format().toBuilder().firstLineIndent(0).subsequentLineIndent(0).alignment(ParagraphAlignment.CENTER).build();
                    result.add(paragraph(Paragraph.mixed(id, List.of(item), format), width)); id = nextId();
                } else { chunk.add(item); }
            }
            if (!chunk.isEmpty() || result.isEmpty()) { result.add(paragraph(Paragraph.mixed(id, chunk, p.format()), width)); }
            return List.copyOf(result);
        }
        private Paragraph paragraph(Paragraph p, double width) throws IOException {
            var content = new ArrayList<InlineContent>();
            // An atomic object may be moved to either a first or a subsequent line by layout.
            // Reserve the narrower line; nested list indentation was already deducted by its host.
            double available = width - Math.max(p.format().firstLineIndent(), p.format().subsequentLineIndent());
            for (var item : p.content()) {
                if (item instanceof InlineVector v && capture.occurrences.containsKey(v.resource())) {
                    var occurrence = capture.occurrences.get(v.resource());
                    if (!v.logicalText().equals(occurrence.slot.logicalText()) || v.rgb() != occurrence.slot.rgb()) {
                        throw new IllegalArgumentException("compiled formula appearance or reading text was changed by author");
                    }
                    if (!visited.add(occurrence)) { throw new IllegalArgumentException("formula identity occurs more than once"); }
                    if (available <= 0) { throw new IllegalArgumentException("formula has no available line width"); }
                    content.add(representation(occurrence, v.hyperlink(), available));
                } else { content.add(item); }
            }
            double ascent = 0, descent = 0;
            for (var item : content) {
                if (item instanceof InlineVector v) { ascent = Math.max(ascent, v.resource().ascent()); descent = Math.max(descent, v.resource().descent()); }
                else if (item instanceof InlineImage image) { ascent = Math.max(ascent, image.height() - image.baselineShift()); descent = Math.max(descent, image.baselineShift()); }
            }
            // The compiler has no paragraph font stack or baseline metrics. Preserve the entire
            // declared text frame on either side of the object's baseline, instead of assuming
            // that point size equals text ascent or that text has no descenders. For any text
            // ascent/descent fitting the original frame, this bounds both combined extents.
            double lineHeight = Math.max(ascent + descent, p.format().lineHeight() + Math.max(ascent, descent));
            var format = expandObjectLines && ascent + descent > 0
                    ? p.format().toBuilder().lineHeight(lineHeight).build() : p.format();
            return Paragraph.mixed(p.id(), content, format);
        }
        private InlineContent representation(Occurrence o, String link, double available) throws IOException {
            MathException failure = o.failure;
            Reason reason = failure == null ? Reason.TOO_WIDE : reason(failure);
            if (target == Target.DOCX) {
                if (o.options.fontSize() * 2 != Math.rint(o.options.fontSize() * 2)) {
                    failure = new MathException(MathException.Reason.UNSUPPORTED_SYNTAX);
                    reason = Reason.UNSUPPORTED_APPEARANCE;
                } else {
                    try {
                        DocxMath expression = TexMath.docx(o.formula.tex(), o.options);
                        if (o.failure == null && o.vector.resource().width() <= available) {
                            nativeEquations.put("equation-" + o.formula.index(), new NativeMathBinding.Equation(o.slot, expression, o.formula.display(), o.options.fontSize()));
                            return new InlineVector(o.slot.resource(), o.slot.logicalText(), o.slot.rgb(), link);
                        }
                    } catch (MathException ex) {
                        if (ex.reason() == MathException.Reason.LIMIT_EXCEEDED) { throw ex; }
                        failure = ex; reason = reason(ex);
                    }
                }
            } else if (failure == null && o.vector.resource().width() <= available) {
                return new InlineVector(o.vector.resource(), o.vector.logicalText(), o.vector.rgb(), link);
            }
            for (var candidate : fallbacks) {
                if (candidate == Fallback.IMAGE && o.failure == null) {
                    double scale = Math.min(1, available / o.vector.resource().width());
                    if (scale < minimumImageScale) { continue; }
                    var image = raster(o.vector, scale, link);
                    capture.degradations.add(new Degradation(o.formula.index(), target, candidate, reason));
                    return image;
                }
                if (candidate == Fallback.TEXT) {
                    capture.degradations.add(new Degradation(o.formula.index(), target, candidate, reason));
                    String delimiter = o.formula.display() ? "$$" : "$";
                    return new InlineText(delimiter + o.formula.tex() + delimiter, new InlineStyle("", o.options.fontSize(), o.vector.rgb(), 0, false, false), link);
                }
            }
            if (failure != null) { throw failure; }
            throw new IllegalArgumentException("formula exceeds available width and no permitted representation fits");
        }
        private Table table(Table t, double width) throws IOException {
            var widths = columnWidths(t.columns(), width);
            var grid = t.grid();
            var rows = new ArrayList<TableRow>();
            for (int row = 0; row < t.rows().size(); row++) {
                var cells = new ArrayList<TableCell>();
                for (var anchor : grid.anchorRows().get(row)) {
                    var cell = anchor.cell(); double inner = 0;
                    for (int col = anchor.columnIndex(); col < anchor.columnIndex() + cell.columnSpan(); col++) { inner += widths[col]; }
                    inner = Math.max(1, inner - t.format().padding().left() - t.format().padding().right());
                    if (cell.hasBlocks()) { cell = cell.toBuilder().blocks(blocks(cell.blocks(), inner)).build(); }
                    else {
                        var f = cell.format();
                        var p = new Paragraph("cell", cell.text(), new ParagraphFormat(f.fontSize(), f.lineHeight(), 0, 0, f.alignment(), 0, 0, false)
                                .withLineBreakPolicy(f.lineBreakPolicy()), cell.inlines(), cell.images(), cell.fields(), cell.vectors());
                        var changed = paragraph(p, inner);
                        cell = cell.toBuilder().content(changed.content()).format(f.toBuilder().lineHeight(changed.format().lineHeight()).build()).build();
                    }
                    cells.add(cell);
                }
                rows.add(new TableRow(cells));
            }
            return new Table(t.id(), t.columns(), rows, t.headerRows(), t.format());
        }
        private boolean hasSlots(Block block) {
            boolean[] found = {false};
            SemanticTree.walk(block, visit -> {
                if (visit.block() instanceof Paragraph p) {
                    found[0] |= p.vectors().stream().anyMatch(v -> capture.occurrences.containsKey(v.vector().resource()));
                } else if (visit.block() instanceof Table t) {
                    for (var row : t.rows()) {
                        for (var cell : row.cells()) {
                            if (!cell.hasBlocks()) {
                                found[0] |= cell.vectors().stream().anyMatch(v -> capture.occurrences.containsKey(v.vector().resource()));
                            }
                        }
                    }
                }
            });
            return found[0];
        }
        private String nextId() { String id; do { id = "math-part-" + sequence++; } while (!ids.add(id)); return id; }
    }

    private static double[] columnWidths(List<ColumnWidth> columns, double width) {
        var result = new double[columns.size()]; double fixed = 0, weights = 0;
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i) instanceof ColumnWidth.Fixed f) { result[i] = f.points(); fixed += f.points(); }
            else if (columns.get(i) instanceof ColumnWidth.Fraction f) { weights += f.weight(); }
            else { throw new IllegalArgumentException("formula fitting requires explicit fixed or fractional table widths"); }
        }
        if (fixed > width) { throw new IllegalArgumentException("fixed table columns exceed available width"); }
        for (int i = 0; i < columns.size(); i++) {
            if (!(columns.get(i) instanceof ColumnWidth.Fixed)) {
                result[i] = (width - fixed) * ((ColumnWidth.Fraction) columns.get(i)).weight() / weights;
            }
        }
        return result;
    }

    private InlineImage raster(InlineVector vector, double fit, String link) throws IOException {
        var resource = vector.resource(); double height = resource.ascent() + resource.descent();
        int widthPixels = (int) Math.ceil(resource.width() * rasterScale), heightPixels = (int) Math.ceil(height * rasterScale);
        if ((long) widthPixels * heightPixels > ImageResource.MAX_PIXELS) { throw limit(); }
        var image = new BufferedImage(widthPixels, heightPixels, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.scale(rasterScale, rasterScale); g.translate(0, resource.ascent()); g.setColor(new Color(vector.rgb()));
            var path = new Path2D.Double();
            for (var segment : resource.segments()) {
                ExecutionContext.checkpoint(); var c = segment.coordinates();
                switch (segment.operation()) {
                    case MOVE -> path.moveTo(c.get(0), c.get(1));
                    case LINE -> path.lineTo(c.get(0), c.get(1));
                    case CURVE -> path.curveTo(c.get(0), c.get(1), c.get(2), c.get(3), c.get(4), c.get(5));
                    case CLOSE -> path.closePath();
                }
            }
            g.fill(path);
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) { throw new IOException("PNG writer unavailable"); }
            ExecutionContext.checkpoint();
            return new InlineImage(ImageResource.load(output.toByteArray()), resource.width() * fit, height * fit,
                    resource.descent() * fit, vector.logicalText(), link);
        } finally { g.dispose(); image.flush(); }
    }

    private static Reason reason(MathException failure) {
        return switch (failure.reason()) {
            case INVALID_SYNTAX -> Reason.INVALID_FORMULA;
            case UNSUPPORTED_SYNTAX -> Reason.UNSUPPORTED_FORMULA;
            case RENDERING_FAILED -> Reason.RENDERING_FAILED;
            case LIMIT_EXCEEDED -> throw failure;
        };
    }
    private static MathException limit() { return new MathException(MathException.Reason.LIMIT_EXCEEDED); }
}
