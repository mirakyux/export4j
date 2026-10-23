// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Conservative, task-confined PDF-to-model recovery. Reading order/paragraphs/headings are
 * geometric inferences, always reported. Tables require explicit increasing grid edges; images
 * retain decoded pixels and displayed size as flow blocks. No OCR, arbitrary page reconstruction,
 * font discovery or promise of lossless round-trip is provided.
 */
public final class PdfSemanticImporter {
    private record Grid(int page, List<Double> x, List<Double> y, int headers) { }
    private record Glyph(String text, double x, double baseline, double width, double height, InlineStyle style) { }
    private record Item(Block block, double x, double y, double width, double height) { }
    private final PdfAdapter adapter;
    private final PdfInput input;
    private final PdfLimits limits;
    private final List<Grid> grids = new ArrayList<>();
    private final Map<String, String> roles = new HashMap<>();
    private ProcessingPolicy policy = ProcessingPolicy.STRICT;
    private ExecutionOptions execution = ExecutionOptions.DEFAULT;
    PdfSemanticImporter(PdfAdapter adapter, PdfInput input, PdfLimits limits) { this.adapter = adapter; this.input = input; this.limits = limits; }
    /** Maps an exact PDF font BaseFont name (including any subset prefix) to a model font role. */
    public PdfSemanticImporter fontRole(String pdfName, String role) {
        if (Objects.requireNonNull(pdfName, "pdfName").isBlank() || Objects.requireNonNull(role, "role").isBlank() || roles.size() >= limits.maxResources()) { throw new IllegalArgumentException("invalid PDF font role mapping"); }
        roles.put(pdfName, role); return this;
    }
    /** Absolute top-left pt grid edges. No spans or nested tables are inferred. */
    public PdfSemanticImporter table(int page, List<Double> xEdges, List<Double> yEdges, int headerRows) {
        var x = edges(xEdges); var y = edges(yEdges);
        if (page < 0 || headerRows < 0 || headerRows >= y.size() - 1 || (long) (x.size() - 1) * (y.size() - 1) > limits.maxObjects() || grids.size() >= limits.maxObjects()) { throw new IllegalArgumentException("invalid semantic table grid"); }
        var grid = new Grid(page, x, y, headerRows);
        for (var old : grids) { if (old.page() == page && overlap(old.x().getFirst(), old.y().getFirst(), old.x().getLast(), old.y().getLast(), x.getFirst(), y.getFirst(), x.getLast(), y.getLast())) { throw new IllegalArgumentException("semantic table regions overlap"); } }
        grids.add(grid); return this;
    }
    public PdfSemanticImporter policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
    public PdfSemanticImporter execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
    /** A conversion source snapshots this request, then uses the conversion's shared policy. */
    public DocumentConversion.Request conversion() {
        var snapshot = new PdfSemanticImporter(adapter, input, limits); snapshot.grids.addAll(grids); snapshot.roles.putAll(roles); snapshot.execution = execution;
        return DocumentConversion.from("PDF", strategy -> { var result = snapshot.policy(strategy).prepare().execute(); return new DocumentConversion.Input(result.document(), List.of(), result.report()); });
    }
    public PreparedPdfImport prepare() throws IOException {
        var tableGrids = List.copyOf(grids); var fontRoles = Map.copyOf(roles); var strategy = policy; var control = execution;
        return ExecutionContext.execute(control, () -> {
            var preflight = new PdfPreflight(limits, true);
            try (var document = adapter.open(input, preflight)) {
                if (!document.getCurrentAccessPermission().canExtractContent()) { throw preflight.failure(PdfOperationException.Code.PERMISSION_DENIED); }
                var state = new State(strategy, fontRoles); var snapshot = PdfObjects.inspect(document, limits);
                state.loss("PDF_LAYOUT", ProcessingPolicy.Treatment.DEGRADE, "GEOMETRIC_READING_ORDER_AND_REFLOW_INFERRED", -1);
                if (document.isEncrypted()) { state.loss("ENCRYPTION_REMOVED", ProcessingPolicy.Treatment.DEGRADE, "MODEL_HAS_NO_SOURCE_ENCRYPTION", -1); }
                if (!snapshot.fields().isEmpty()) { state.loss("FORMS", ProcessingPolicy.Treatment.DISCARD, "SOURCE_FIELDS_NOT_RECOVERED", -1); }
                if (!snapshot.annotations().isEmpty()) { state.loss("ANNOTATIONS", ProcessingPolicy.Treatment.DISCARD, "COMMENTS_AND_LINK_TARGETS_NOT_RECOVERED", -1); }
                if (!snapshot.bookmarks().isEmpty()) { state.loss("BOOKMARKS", ProcessingPolicy.Treatment.DISCARD, "SOURCE_OUTLINE_NOT_RECOVERED", -1); }
                if (!snapshot.attachments().isEmpty()) { state.loss("ATTACHMENTS", ProcessingPolicy.Treatment.DISCARD, "SOURCE_ATTACHMENTS_NOT_RECOVERED", -1); }
                if (!snapshot.metadata().isEmpty() || document.getDocumentCatalog().getCOSObject().containsKey(COSName.METADATA)) { state.loss("DOCUMENT_METADATA", ProcessingPolicy.Treatment.DISCARD, "SOURCE_METADATA_NOT_RECOVERED", -1); }
                for (var grid : tableGrids) { if (grid.page() >= document.getNumberOfPages()) { throw new PdfOperationException(PdfOperationException.Code.INVALID_SELECTION, 0, grid.page()); } }
                var sections = new ArrayList<Section>();
                for (int index = 0; index < document.getNumberOfPages(); index++) {
                    ExecutionContext.checkpoint(); var page = document.getPage(index); var media = page.getMediaBox();
                    if (!viewSupported(page)) { state.loss("PDF_PAGE_VIEW", ProcessingPolicy.Treatment.SKIP_BLOCK, "PAGE_NOT_RECOVERED", index); continue; }
                    if (new PdfRasterResources().check(page) != null || complex(page)) { state.loss("PDF_STRUCTURE", ProcessingPolicy.Treatment.SKIP_BLOCK, "PAGE_NOT_RECOVERED", index); continue; }
                    var glyphs = new ArrayList<Glyph>(); int pageIndex = index;
                    var stripper = new PDFTextStripper() {
                        @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException { state.operator(); super.processOperator(operator, operands); }
                        @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException failure) throws IOException { throw failure; }
                        @Override public void beginMarkedContentSequence(COSName tag, org.apache.pdfbox.cos.COSDictionary properties) {
                            if (properties != null && (properties.containsKey(COSName.ACTUAL_TEXT) || properties.containsKey(COSName.ALT) || properties.containsKey(COSName.E))) {
                                state.loss("PDF_LOGICAL_TEXT", ProcessingPolicy.Treatment.DISCARD, "UNVERIFIED_ALTERNATE_TEXT_NOT_SUBSTITUTED", pageIndex);
                            }
                        }
                        @Override public void endMarkedContentSequence() { /* No alternate-text substitution state was entered. */ }
                        @Override protected void processTextPosition(TextPosition position) {
                            try {
                                String text = position.getUnicode();
                                if (text == null || text.isEmpty() || position.getDir() != 0 || !validText(text)) { throw PdfObjects.unsupported(); }
                                state.characters(text.length());
                                if (getGraphicsState().getTextState().getRenderingMode().intValue() == 3) { state.loss("PDF_HIDDEN_TEXT", ProcessingPolicy.Treatment.DISCARD, "INVISIBLE_TEXT_NOT_RECOVERED", pageIndex); return; }
                                double size = position.getFontSizeInPt();
                                if (!Double.isFinite(size) || size <= 0 || !Float.isFinite(position.getXDirAdj()) || !Float.isFinite(position.getYDirAdj())
                                        || !Float.isFinite(position.getWidthDirAdj()) || position.getWidthDirAdj() < 0 || !Float.isFinite(position.getHeightDir())) { throw PdfObjects.invalid(); }
                                if (position.getXDirAdj() < 0 || position.getYDirAdj() < 0 || position.getXDirAdj() + position.getWidthDirAdj() > media.getWidth() + .01 || position.getYDirAdj() > media.getHeight() + .01) {
                                    state.loss("PDF_HIDDEN_TEXT", ProcessingPolicy.Treatment.DISCARD, "OUTSIDE_PAGE_TEXT_NOT_RECOVERED", pageIndex); return;
                                }
                                var style = new InlineStyle(state.role(position.getFont().getName(), pageIndex), size, getGraphicsState().getNonStrokingColor().toRGB(), 0, false, false);
                                glyphs.add(new Glyph(text, position.getXDirAdj(), position.getYDirAdj(), position.getWidthDirAdj(), position.getHeightDir(), style));
                            } catch (IOException failure) { throw new UncheckedIOException(failure); }
                        }
                    };
                    stripper.setStartPage(index + 1); stripper.setEndPage(index + 1); stripper.setSuppressDuplicateOverlappingText(false);
                    try { stripper.getText(document); } catch (UncheckedIOException failure) { throw failure.getCause(); }
                    var images = new Images(page, state, index); images.processPage(page); var items = new ArrayList<>(images.items);
                    double bodySize = glyphs.stream().mapToDouble(glyph -> glyph.style().fontSize()).sorted().skip(glyphs.size() / 2).findFirst().orElse(12);
                    for (var grid : tableGrids) { if (grid.page() == index) { state.grid(grid, glyphs, items, media.getWidth(), media.getHeight()); } }
                    for (var line : lines(glyphs)) {
                        double size = line.stream().mapToDouble(glyph -> glyph.style().fontSize()).max().orElse(12);
                        var runs = runs(line); String id = state.id(); var format = ParagraphFormat.of(size);
                        Block block = size >= bodySize * 1.3 ? Heading.rich(id, runs, size >= bodySize * 1.6 ? 1 : 2, format) : new Paragraph(id, runs, format);
                        double x = line.getFirst().x(); double y = line.stream().mapToDouble(glyph -> glyph.baseline() - glyph.height()).min().orElse(0);
                        double right = line.stream().mapToDouble(glyph -> glyph.x() + glyph.width()).max().orElse(x); double bottom = line.stream().mapToDouble(Glyph::baseline).max().orElse(y);
                        items.add(new Item(block, x, y, right - x, bottom - y));
                    }
                    items.sort(Comparator.comparingDouble(Item::y).thenComparingDouble(Item::x));
                    for (var item : items) { state.sources.add(new PreparedPdfImport.Source(item.block().id(), index, item.x(), item.y(), item.width(), item.height())); }
                    if (items.isEmpty()) { continue; }
                    var settings = PageSettings.builder().size(media.getWidth(), media.getHeight()).margins(Math.min(20, Math.min(media.getWidth(), media.getHeight()) / 10)).build();
                    sections.add(new Section("pdf-page-" + index, settings, items.stream().map(Item::block).toList(), 0));
                }
                if (sections.isEmpty()) { throw PdfObjects.unsupported(); }
                return new PreparedPdfImport(new PreparedPdfImport.Result(new SectionedDocument(DocumentMetadata.EMPTY, sections), state.sources,
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues)), control);
            }
        });
    }
    private final class State {
        final ProcessingPolicy strategy; final Map<String, String> fontRoles; final Set<String> warnedFonts = new HashSet<>();
        final List<ProcessingIssue> issues = new ArrayList<>(); final List<PreparedPdfImport.Source> sources = new ArrayList<>();
        long operators; long characters; long pixels; long encoded; long nodes; long gridWork;
        State(ProcessingPolicy strategy, Map<String, String> fontRoles) { this.strategy = strategy; this.fontRoles = fontRoles; }
        String id() throws IOException { if (++nodes > limits.maxObjects()) { throw new PdfOperationException(PdfOperationException.Code.OBJECT_LIMIT, 0, -1); } return "pdf-block-" + nodes; }
        void operator() throws IOException { ExecutionContext.checkpoint(); if (++operators > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, -1); } }
        void characters(int count) throws IOException { ExecutionContext.checkpoint(); if (count > limits.maxTextCharacters() - characters) { throw new PdfOperationException(PdfOperationException.Code.TEXT_LIMIT, 0, -1); } characters += count; }
        void loss(String feature, ProcessingPolicy.Treatment treatment, String effect, int page) {
            var rule = strategy.resolve("MODEL", feature, "RECOVER_PDF");
            var issue = new ProcessingIssue("IMPORT", "PDF", "MODEL", feature, "RECOVER_PDF", new ProcessingIssue.Location("", "PDF_PAGE_" + page, "", List.of(), -1, -1),
                    "semantic PDF recovery requires the declared treatment", rule.action(), rule.treatment(), List.of(effect));
            if (rule.action() == ProcessingPolicy.Action.ERROR) { throw new ProcessingException(issue); }
            if (rule.treatment() != treatment) { throw new IllegalArgumentException("unsupported PDF recovery treatment"); }
            if (issues.size() >= limits.maxObjects()) { throw new IllegalArgumentException("PDF recovery issue budget exceeded"); } issues.add(issue);
        }
        String role(String name, int page) {
            if (fontRoles.containsKey(name)) { return fontRoles.get(name); }
            if (warnedFonts.add(name)) { loss("PDF_FONT_MAPPING", ProcessingPolicy.Treatment.DEGRADE, "SOURCE_FONT_USES_MODEL_DEFAULT_ROLE", page); } return "";
        }
        void grid(Grid grid, List<Glyph> glyphs, List<Item> items, double width, double height) throws IOException {
            if (grid.x().getLast() > width || grid.y().getLast() > height) { throw new IllegalArgumentException("table grid exceeds source page"); }
            for (var glyph : glyphs) {
                ExecutionContext.checkpoint();
                if (++gridWork > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, grid.page()); }
                if (overlap(glyph.x(), glyph.baseline() - glyph.height(), glyph.x() + glyph.width(), glyph.baseline(),
                        grid.x().getFirst(), grid.y().getFirst(), grid.x().getLast(), grid.y().getLast())
                        && (glyph.x() < grid.x().getFirst() - .01 || glyph.x() + glyph.width() > grid.x().getLast() + .01
                        || glyph.baseline() - glyph.height() < grid.y().getFirst() - .01 || glyph.baseline() > grid.y().getLast() + .01)) {
                    throw new IllegalArgumentException("table grid cuts source glyph");
                }
            }
            var table = Table.builder(id()); var columns = new ColumnWidth[grid.x().size() - 1];
            for (int col = 0; col < columns.length; col++) { columns[col] = new ColumnWidth.Fraction(grid.x().get(col + 1) - grid.x().get(col)); } table.columns(columns);
            for (int row = 0; row < grid.y().size() - 1; row++) {
                var cells = new TableCell[columns.length];
                for (int col = 0; col < cells.length; col++) {
                    id(); var selected = new ArrayList<Glyph>();
                    for (var glyph : glyphs) {
                        ExecutionContext.checkpoint(); if (++gridWork > limits.maxContentOperators()) { throw new PdfOperationException(PdfOperationException.Code.OPERATOR_LIMIT, 0, grid.page()); }
                        if (glyph.x() >= grid.x().get(col) && glyph.x() < grid.x().get(col + 1) && glyph.baseline() >= grid.y().get(row) && glyph.baseline() < grid.y().get(row + 1)) {
                            if (glyph.x() + glyph.width() > grid.x().get(col + 1) + .01 || glyph.baseline() - glyph.height() < grid.y().get(row) - .01) { throw new IllegalArgumentException("table grid cuts source glyph"); } selected.add(glyph);
                        }
                    }
                    var selectedSet = new HashSet<>(selected); glyphs.removeIf(selectedSet::contains); var runs = new ArrayList<InlineText>();
                    for (var line : lines(selected)) { if (!runs.isEmpty()) { runs.add(new InlineText("\n", runs.getLast().style())); } runs.addAll(runs(line)); }
                    cells[col] = TableCell.builder().format(CellFormat.of(selected.stream().mapToDouble(glyph -> glyph.style().fontSize()).max().orElse(12))).content(runs).build();
                }
                if (row < grid.headers()) { table.headerCells(cells); } else { table.row(cells); }
            }
            if (items.stream().anyMatch(item -> overlap(item.x(), item.y(), item.x() + item.width(), item.y() + item.height(), grid.x().getFirst(), grid.y().getFirst(), grid.x().getLast(), grid.y().getLast()))) { throw new IllegalArgumentException("table grid intersects source image"); }
            items.add(new Item(table.build(), grid.x().getFirst(), grid.y().getFirst(), grid.x().getLast() - grid.x().getFirst(), grid.y().getLast() - grid.y().getFirst()));
        }
    }
    private final class Images extends PDFGraphicsStreamEngine {
        final State state; final int page; final List<Item> items = new ArrayList<>(); Point2D point; boolean vectorReported;
        Images(PDPage page, State state, int index) { super(page); this.state = state; this.page = index; }
        @Override protected void processOperator(Operator operator, List<COSBase> operands) throws IOException { state.operator(); if (!Set.of("Tj", "TJ", "'", "\"").contains(operator.getName())) { super.processOperator(operator, operands); } }
        @Override protected void operatorException(Operator operator, List<COSBase> operands, IOException failure) throws IOException { throw failure; }
        @Override public void drawImage(PDImage image) throws IOException {
            if (image.isStencil()) { state.loss("PDF_IMAGE_TRANSFORM", ProcessingPolicy.Treatment.SKIP_MATCH, "IMAGE_NOT_RECOVERED", page); return; }
            var matrix = getGraphicsState().getCurrentTransformationMatrix();
            if (matrix.getShearX() != 0 || matrix.getShearY() != 0 || matrix.getScaleX() <= 0 || matrix.getScaleY() <= 0) { state.loss("PDF_IMAGE_TRANSFORM", ProcessingPolicy.Treatment.SKIP_MATCH, "IMAGE_NOT_RECOVERED", page); return; }
            long count = (long) image.getWidth() * image.getHeight(); if (count > limits.maxImagePixels() - state.pixels) { throw new PdfOperationException(PdfOperationException.Code.IMAGE_LIMIT, 0, page); } state.pixels += count;
            if (!Set.of("DeviceRGB", "DeviceGray").contains(image.getColorSpace().getName())) { state.loss("PDF_IMAGE_COLOR", ProcessingPolicy.Treatment.DEGRADE, "IMAGE_COLOR_CONVERTED_TO_RGB", page); }
            if (image.getBitsPerComponent() > 8) { state.loss("PDF_IMAGE_PRECISION", ProcessingPolicy.Treatment.DEGRADE, "IMAGE_PRECISION_REDUCED_TO_EIGHT_BITS", page); }
            var raster = image.getImage();
            try {
                byte[] bytes = PdfImageSupport.encode(raster, "png", 1, limits.maxOutputBytes() - state.encoded); state.encoded += bytes.length;
                var inline = new InlineImage(ImageResource.load(bytes), matrix.getScaleX(), matrix.getScaleY(), 0, "", "");
                var block = Paragraph.mixed(state.id(), List.of(inline), ParagraphFormat.builder().fontSize(12).lineHeight(inline.height() + 18).build());
                double x = matrix.getTranslateX(); double y = getPage().getMediaBox().getHeight() - matrix.getTranslateY() - matrix.getScaleY();
                if (x < 0 || y < 0 || x + inline.width() > getPage().getMediaBox().getWidth() + .01 || y + inline.height() > getPage().getMediaBox().getHeight() + .01) { throw PdfObjects.unsupported(); }
                items.add(new Item(block, x, y, inline.width(), inline.height()));
            } finally { raster.flush(); }
        }
        void vector() { if (!vectorReported) { state.loss("PDF_VECTOR_GRAPHICS", ProcessingPolicy.Treatment.DISCARD, "PAINTED_PATHS_NOT_RECOVERED", page); vectorReported = true; } }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) { point = p0; }
        @Override public void clip(int windingRule) { throw new IllegalStateException("clipping was not rejected by preflight"); }
        @Override public void moveTo(float x, float y) { point = new Point2D.Float(x, y); }
        @Override public void lineTo(float x, float y) { point = new Point2D.Float(x, y); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { point = new Point2D.Float(x3, y3); }
        @Override public Point2D getCurrentPoint() { return point; }
        @Override public void closePath() { }
        @Override public void endPath() { }
        @Override public void strokePath() { vector(); }
        @Override public void fillPath(int windingRule) { vector(); }
        @Override public void fillAndStrokePath(int windingRule) { vector(); }
        @Override public void shadingFill(COSName name) { vector(); }
    }
    private static List<List<Glyph>> lines(List<Glyph> glyphs) {
        var sorted = glyphs.stream().sorted(Comparator.comparingDouble(Glyph::baseline).thenComparingDouble(Glyph::x)).toList();
        var lines = new ArrayList<List<Glyph>>();
        for (var glyph : sorted) {
            if (lines.isEmpty() || Math.abs(lines.getLast().getFirst().baseline() - glyph.baseline()) > .5) { lines.add(new ArrayList<>()); }
            lines.getLast().add(glyph);
        }
        return lines;
    }
    private static List<InlineText> runs(List<Glyph> line) throws IOException {
        var result = new ArrayList<InlineText>(); Glyph previous = null; var text = new StringBuilder(); InlineStyle style = null;
        for (var glyph : line) {
            ExecutionContext.checkpoint();
            if (previous != null && glyph.x() < previous.x() + previous.width() - .5 && !glyph.text().isBlank() && !previous.text().isBlank()) { throw PdfObjects.unsupported(); }
            String value = glyph.text();
            if (previous != null && glyph.x() - previous.x() - previous.width() > Math.max(.6, glyph.style().fontSize() * .15) && !previous.text().endsWith(" ") && !value.startsWith(" ")) { value = " " + value; }
            if (style != null && !style.equals(glyph.style())) { result.add(new InlineText(text.toString(), style)); text.setLength(0); }
            style = glyph.style(); text.append(value); previous = glyph;
        }
        if (style != null) { result.add(new InlineText(text.toString(), style)); }
        return List.copyOf(result);
    }
    private static boolean viewSupported(PDPage page) {
        var media = page.getMediaBox(); var crop = page.getCropBox();
        return page.getRotation() == 0 && page.getUserUnit() == 1 && media.getLowerLeftX() == 0 && media.getLowerLeftY() == 0
                && crop.getLowerLeftX() == 0 && crop.getLowerLeftY() == 0 && media.getWidth() == crop.getWidth() && media.getHeight() == crop.getHeight();
    }
    private static boolean complex(PDPage page) throws IOException {
        if (page.getResources() != null) { for (var name : page.getResources().getXObjectNames()) { if (page.getResources().getXObject(name) instanceof PDFormXObject) { return true; } } }
        var streams = page.getContentStreams();
        while (streams.hasNext()) {
            var parser = new PDFStreamParser(new PdfContentStream(streams.next().getCOSObject()));
            try {
                Object token; Object previous = null;
                while ((token = parser.parseNextToken()) != null) {
                    ExecutionContext.checkpoint();
                    if (token instanceof Operator operator) {
                        if (Set.of("W", "W*", "gs").contains(operator.getName())) { return true; }
                        if (operator.getName().equals("Tr") && (!(previous instanceof org.apache.pdfbox.cos.COSNumber number) || number.floatValue() != 0 && number.floatValue() != 3)) { return true; }
                    }
                    previous = token;
                }
            }
            finally { parser.close(); }
        }
        return false;
    }
    private static List<Double> edges(List<Double> values) {
        var copy = List.copyOf(values); if (copy.size() < 2 || copy.size() > 4097) { throw new IllegalArgumentException("invalid table edges"); }
        double previous = -1; for (double value : copy) { if (!Double.isFinite(value) || value < 0 || value <= previous || value > 14_400) { throw new IllegalArgumentException("invalid table edges"); } previous = value; } return copy;
    }
    private static boolean overlap(double ax, double ay, double ar, double ab, double bx, double by, double br, double bb) { return ax < br && bx < ar && ay < bb && by < ab; }
    private static boolean validText(String text) { return text.codePoints().noneMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF || cp == 0xFFFE || cp == 0xFFFF || Character.isISOControl(cp)); }
}
