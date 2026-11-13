// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.GraphicsResources;
import com.mirakyux.export4j.layout.CellFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.TableRowFragment;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BlockSource;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableGrid;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLayoutException;
import com.mirakyux.export4j.text.TextLine;
import com.mirakyux.export4j.text.StyledSpan;

/**
 * Bounded vertical flow of paragraphs, rectangular tables and indivisible components, without file I/O.
 * Wraps source text once; splits paragraphs between lines and tables between complete rows.
 * Safe to reuse if the supplied paragraph layouter and font implementations are thread-safe.
 */
public final class DocumentLayouter {
    private final ParagraphLayouter paragraphLayouter;
    private final DocumentLayoutLimits limits;
    private final ComponentLimits componentLimits;

    public DocumentLayouter() {
        this(new ParagraphLayouter(), DocumentLayoutLimits.DEFAULT);
    }

    public DocumentLayouter(ParagraphLayouter paragraphLayouter, DocumentLayoutLimits limits) {
        this(paragraphLayouter, limits, ComponentLimits.DEFAULT);
    }

    public DocumentLayouter(ParagraphLayouter paragraphLayouter, DocumentLayoutLimits limits, ComponentLimits componentLimits) {
        this.paragraphLayouter = Objects.requireNonNull(paragraphLayouter, "paragraphLayouter");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.componentLimits = Objects.requireNonNull(componentLimits, "componentLimits");
    }

    /**
     * Uses one explicit ordered font stack. Arguments and entries are non-null.
     * Fails with block/page/cell/source context; never returns partial layout. Empty documents yield one page.
     */
    public DocumentLayout layout(Document document, List<FontFace> fonts) {
        return layout(document, fonts, Map.of());
    }

    /** Validates shared structural/source budgets without shaping or allocating pages. */
    public void validate(Document document) {
        preflight(Objects.requireNonNull(document, "document"));
    }

    /** Named font roles must be explicitly registered; the empty role uses the default stack. */
    public DocumentLayout layout(Document document, List<FontFace> fonts, Map<String, List<FontFace>> roles) {
        var groups = new HashMap<String, FontGroup>();
        roles.forEach((key, value) -> groups.put(key, FontGroup.strict(value)));
        return layout(document, FontGroup.strict(fonts), groups);
    }

    public DocumentLayout layout(Document document, FontGroup group) {
        return layout(document, group, Map.of());
    }

    /** Each role carries its own fallback and missing-glyph policy. Returned fragments retain warnings. */
    public DocumentLayout layout(Document document, FontGroup group, Map<String, FontGroup> roles) {
        Objects.requireNonNull(document, "document");
        return new Flow(document, group, roles, preflight(document)).layout();
    }

    /**
     * Paginates around explicit page-coordinate rectangles using full-width vertical avoidance.
     * The callback receives a zero-based physical page index and is invoked once per measured page,
     * including bounded lookahead for region-aware components within the configured page budget.
     * Each returned list is snapshotted, must contain at most 64 rectangles within that page, and owns
     * no resources. Areas outside the body width are ignored; overlapping vertical areas are merged.
     * Content above an area stays in place; continuation resumes below it without consuming source.
     * Ordinary page/source/line budgets and cancellation apply. No callbacks are retained after layout.
     */
    public DocumentLayout layout(Document document, FontGroup group, Map<String, FontGroup> roles,
            java.util.function.IntFunction<List<Rect>> exclusions) {
        Objects.requireNonNull(document, "document");
        return new Flow(document, group, roles, preflight(document), Objects.requireNonNull(exclusions, "exclusions")).layout();
    }

    /** Receives one immutable, fully measured page in increasing physical page order.
     * The synchronous callback is borrowed, is never closed or retried, and may release its page on return.
     * A callback failure stops layout immediately and is propagated unchanged.
     */
    @FunctionalInterface
    public interface PageConsumer {
        void accept(LayoutPage page) throws IOException;
    }

    /** Emits pages without retaining completed page IR. The immutable source and fonts remain borrowed.
     * Source validation, pagination, diagnostics, repeated headers and all budgets match
     * {@link #layout(Document, FontGroup, Map)}.
     * Measurements for consumed blocks are released; the largest current block, necessary keep-chain
     * lookahead and source-ID validation still require memory. This is not an incremental source API.
     * Later content or global semantic validation may fail after earlier callbacks; callers own atomic publication.
     * Empty documents emit one page. All callbacks run synchronously on the calling thread.
     * @return the number of successfully emitted pages
     * @throws IOException when the page consumer fails
     */
    public int layoutPages(Document document, FontGroup group, Map<String, FontGroup> roles,
            PageConsumer consumer) throws IOException {
        Objects.requireNonNull(document, "document");
        var flow = new Flow(document, group, roles, preflight(document), ignored -> List.of(),
                Objects.requireNonNull(consumer, "consumer"));
        flow.adaptiveExclusions = false;
        return stream(flow);
    }

    /** Emits page IR around the same snapshotted page-coordinate exclusions as the full layout overload.
     * Exclusions may be requested for bounded lookahead and are released after their page is emitted.
     * The source and callback ownership and retained-measurement limits match {@link #layoutPages(Document, FontGroup, Map, PageConsumer)}.
     * @return the number of successfully emitted pages
     * @throws IOException when the page consumer fails
     */
    public int layoutPages(Document document, FontGroup group, Map<String, FontGroup> roles,
            java.util.function.IntFunction<List<Rect>> exclusions, PageConsumer consumer) throws IOException {
        Objects.requireNonNull(document, "document");
        return stream(new Flow(document, group, roles, preflight(document),
                Objects.requireNonNull(exclusions, "exclusions"), Objects.requireNonNull(consumer, "consumer")));
    }

    /** Receives one page and the original top-level blocks presented on that page, in source order.
     * Sources include a continuing block on each of its pages and retain Heading rather than its
     * internal paragraph projection. The immutable list is a short-lived snapshot; retaining it or
     * the page is the consumer's choice. Page-break markers with no presentation are excluded.
     */
    @FunctionalInterface
    public interface BlockPageConsumer {
        void accept(LayoutPage page, List<Block> sources) throws IOException;
    }

    /** Lays out a borrowed single-pass block source without collecting the input or completed pages.
     * Supported roots are Paragraph, Heading, Table, PageBreak, ColumnFlow, GridLayout and
     * ComponentBlock; other semantic forms require explicit lowering by the caller.
     * Only the current page's source blocks, the largest active block and necessary keep-chain
     * lookahead are retained. Global source IDs and budget counters remain until completion.
     * All callbacks run synchronously, without retry. This method closes neither source nor consumer.
     * Later source, layout or global validation failures can follow earlier callbacks, so consumers
     * are responsible for atomic publication. Empty sources emit one blank page.
     * @return the number of successfully emitted pages
     * @throws IOException on source or consumer failure, preserving the original exception
     */
    public int layoutBlocks(PageSettings settings, BlockSource source, FontGroup group,
            Map<String, FontGroup> roles, BlockPageConsumer consumer) throws IOException {
        var flow = new Flow(Objects.requireNonNull(settings, "settings"), Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(group, "group"), Objects.requireNonNull(roles, "roles"),
                ignored -> List.of(), Objects.requireNonNull(consumer, "consumer"));
        flow.adaptiveExclusions = false;
        return stream(flow);
    }

    /** Single-pass block flow around the same snapshotted page-coordinate exclusions as layoutPages.
     * Source, callback, validation and retention contracts match the overload without exclusions.
     * @return the number of successfully emitted pages
     * @throws IOException on source or consumer failure, preserving the original exception
     */
    public int layoutBlocks(PageSettings settings, BlockSource source, FontGroup group,
            Map<String, FontGroup> roles, java.util.function.IntFunction<List<Rect>> exclusions,
            BlockPageConsumer consumer) throws IOException {
        return stream(new Flow(Objects.requireNonNull(settings, "settings"), Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(group, "group"), Objects.requireNonNull(roles, "roles"),
                Objects.requireNonNull(exclusions, "exclusions"), Objects.requireNonNull(consumer, "consumer")));
    }

    private int stream(Flow flow) throws IOException {
        try {
            flow.run();
            return flow.pages.size();
        } catch (PageConsumerFailure | FlowBlockInput.SourceFailure failure) {
            throw failure.getCause();
        }
    }

    /** Bridges a checked page callback through the existing synchronous pagination implementation. */
    private static final class PageConsumerFailure extends UncheckedIOException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        private PageConsumerFailure(IOException cause) { super(cause); }
    }

    /** Keeps a physical count independently of whether the caller requests collected or emitted pages. */
    private static final class Pages {
        private final PageConsumer consumer;
        private final List<LayoutPage> collected = new ArrayList<>();
        private int count;

        private Pages(PageConsumer consumer) { this.consumer = consumer; }
        private int size() { return count; }
        private boolean streaming() { return consumer != null; }
        private void add(LayoutPage page) {
            if (consumer == null) { collected.add(page); }
            else {
                try { consumer.accept(page); }
                catch (IOException failure) { throw new PageConsumerFailure(failure); }
            }
            count++;
        }
    }

    /**
     * One occupied logical region and its measured semantic table fragment. The fragment uses local
     * coordinates with origin (0, 0); row/column indices and cell-local UTF-16 source ranges are retained.
     * Region indices are zero-based and independent of physical pages or columns. Immutable; owns no resources.
     */
    public record TableRegion(int regionIndex, TableFragment fragment) {
        public TableRegion {
            Objects.requireNonNull(fragment, "fragment");
            if (regionIndex < 0 || regionIndex >= 100_016) {
                throw new IllegalArgumentException("table region index budget exceeded");
            }
        }
    }

    /**
     * Measures one semantic table once at the context width, excluding outer before/after spacing.
     * Source/structure budgets are checked once and complete source lines are cached with their fields,
     * images and diagnostics. The returned mutable cursor accepts varying heights up to context.height().
     * All arguments are non-null; resources remain borrowed. No drawing, field resolution or I/O occurs.
     */
    public TableCursor prepareTable(Table table, ComponentContext context) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(context, "context");
        var document = new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(context.width(), context.height()), PageMargins.uniform(0)), List.of(table));
        var flow = new Flow(document, context.fontGroup(), context.fontRoles(), preflight(document));
        return new TableCursor(table.format().allowRowSplit() && (table.hasBlockCells() || table.hasRowSpans())
                ? flow.new RecursiveCursor(table, 0) : flow.new Cursor(table));
    }

    /** Prepares a bounded variable-width cursor over the complete original table source.
     * Width changes preserve accepted original line positions, fields, images and nested cell cursors.
     * No I/O occurs; fonts and images remain borrowed. */
    public VariableTableCursor prepareVariableTable(Table table, ComponentContext context) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(context, "context");
        var document = new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(context.width(), context.height()), PageMargins.uniform(0)), List.of(table));
        var flow = new Flow(document, context.fontGroup(), context.fontRoles(), preflight(document));
        return new VariableTableCursor(flow.new VariableCursor(table));
    }

    /** Resolves the declared fixed, fractional and bounded automatic columns of one explicit probe table.
     * Returns immutable positive widths in points after ordinary source/structure and measurement budgets.
     * Does not consume table rows or produce output. Fonts and resources remain borrowed. */
    public List<Double> resolveTableColumns(Table table, ComponentContext context) {
        Objects.requireNonNull(table, "table"); Objects.requireNonNull(context, "context");
        var document = new Document(DocumentMetadata.EMPTY,
                new PageSettings(new PageSize(context.width(), context.height()), PageMargins.uniform(0)), List.of(table));
        var flow = new Flow(document, context.fontGroup(), context.fontRoles(), preflight(document));
        return java.util.Arrays.stream(flow.columnWidths(table, table.grid(), 0, context.width())).boxed().toList();
    }

    /**
     * Measures one table at {@code context.width()} points and distributes it over sequential regions.
     * Resolves column widths before preparing final cell lines, then moves complete rows without reshaping; explicit
     * {@code allowRowSplit} instead consumes complete cell lines and retains inline fields and images.
     * Headers repeat in each occupied region. Too-short initial regions are skipped without blank output;
     * every continuation region must consume body content. Table before/after spacing belongs to the caller.
     *
     * <p>All arguments are non-null. The positive continuation height, in points, must fit the context height.
     * Source code points and cells are counted once; output lines include repeated headers and empty continuation cells.
     * This layouter's {@code maxPages} limits occupied fragments, additionally capped at 100,000; skipped
     * initial regions do not count. At most 100,016 initial regions and logical indices are permitted.
     * Returns a nonempty immutable list or throws {@link DocumentLayoutException} with source context;
     * exception page indices refer to occupied-fragment order, not the logical region index.
     * Uses caller-owned immutable fonts/resources, performs no I/O, and observes the current execution scope.
     */
    public List<TableRegion> layoutTableRegions(Table table, ComponentContext context, FlowRegions regions) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(regions, "regions");
        if (!fits(regions.continuationHeight(), context.height())) {
            throw new IllegalArgumentException("table continuation height exceeds the component viewport");
        }
        var cursor = prepareTable(table, context.viewport(context.width(), regions.continuationHeight()));
        List<TableRegion> result = new ArrayList<>();
        for (int region = 0; !cursor.done(); region++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            if (region >= 100_016) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, cursor.fragmentIndex(),
                        cursor.rowIndex(), -1, 0, "table logical-region budget exceeded", null);
            }
            var part = cursor.take(regions.height(region));
            if (part.isPresent()) { result.add(new TableRegion(region, part.orElseThrow())); }
            else if (region >= regions.initialHeights().size()) {
                throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, 0, cursor.fragmentIndex(),
                        cursor.rowIndex(), -1, 0, "table fragment cannot fit a continuation region", null);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Distributes complete rows or transitive merged-row groups at each region's actual width.
     * Widths are a nonempty cyclic sequence of at most 16 finite positive point values fitting the context.
     * Equal widths use the ordinary cursor. Unequal widths support atomic rows/groups and explicit
     * complete-line continuation of inline cells and recursive block cells, including vertical owners.
     *
     * <p>Source budgets apply once. Each distinct width is prepared at most once, with at most 4,000,000
     * cumulative source UTF-16 units and this layouter's line limit per prepared alternative. Recursive
     * continuation retains accepted line widths and remeasures at width changes under the same work budget. Only selected
     * rows and actual repeated headers consume the final output-line budget. Initial short regions may be
     * skipped; every full continuation region must advance. Fonts/resources remain borrowed; no I/O occurs.
     */
    public List<TableRegion> layoutTableRegions(Table table, ComponentContext context, FlowRegions regions,
            List<Double> widths) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(regions, "regions");
        widths = List.copyOf(widths);
        if (widths.isEmpty() || widths.size() > 16 || !fits(regions.continuationHeight(), context.height())) {
            throw new IllegalArgumentException("invalid table region widths or continuation height");
        }
        for (double width : widths) {
            if (!Double.isFinite(width) || width <= 0 || !fits(width, context.width())) {
                throw new IllegalArgumentException("table region width must be positive and fit the viewport");
            }
        }
        double first = widths.getFirst();
        if (widths.stream().allMatch(width -> width == first)) {
            return layoutTableRegions(table, context.viewport(first, context.height()), regions);
        }
        var document = new Document(DocumentMetadata.EMPTY, new PageSettings(
                new PageSize(context.width(), regions.continuationHeight()), PageMargins.uniform(0)), List.of(table));
        var flow = new Flow(document, context.fontGroup(), context.fontRoles(), preflight(document));
        return table.format().allowRowSplit() && table.hasBlockCells() ? flow.variableRecursiveTable(table, regions, widths)
                : table.format().allowRowSplit() ? flow.new VariableInlineTable(table, regions, widths).layout()
                : flow.variableTableRegions(table, regions, widths);
    }

    private long preflight(Document document) {
        if (document.blocks().size() > limits.maxParagraphs()) {
            throw new DocumentLayoutException(DocumentLayoutException.Code.BUDGET_EXCEEDED, "", -1,
                    -1, -1, "document semantic block budget exceeded", null);
        }
        var budget = new SourceBudget();
        SemanticTree.walk(document.blocks(), budget::accept);
        return budget.codePoints;
    }

    /** One shared source budget for all semantic descendants; covered slots never duplicate content. */
    private final class SourceBudget {
        private long nodes;
        private long codePoints;
        private long cells;
        private long imageBytes;
        private long imagePixels;
        private final java.util.Set<String> imageIds = new java.util.HashSet<>();
        private final java.util.Set<String> vectorIds = new java.util.HashSet<>();
        private long vectorSegments;
        private long vectorBytes;

        private void accept(SemanticTree.Visit visit) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            Block block = visit.block();
            int index = visit.rootBlockIndex();
            try {
                if (++nodes > limits.maxParagraphs()) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1, -1,
                            "document semantic block budget exceeded", null);
                }
                switch (block) {
                    case Paragraph paragraph -> {
                        var owner = visit.cellPath().isEmpty() ? null : visit.cellPath().getLast();
                        codePoints = countText(paragraph.text(), codePoints, block, index,
                                owner == null ? -1 : owner.rowIndex(), owner == null ? -1 : owner.columnIndex());
                        images(paragraph.images(), block, index, visit.cellPath());
                        vectors(paragraph.vectors(), block, index, visit.cellPath());
                    }
                    case Heading heading -> {
                        codePoints = countText(heading.text(), codePoints, block, index, -1, -1);
                        images(heading.images(), block, index, visit.cellPath());
                        vectors(heading.vectors(), block, index, visit.cellPath());
                    }
                    case PageBreak ignored -> { }
                    case ColumnFlow ignored -> { /* Descendants share the ordinary source budget. */ }
                    case GridItem ignored -> { /* Items and descendants retain their original identities. */ }
                    case GridLayout grid -> {
                        long rows = grid.items().stream().mapToInt(GridItem::row).distinct().count();
                        long slots = rows * grid.tracks();
                        if (slots > limits.maxCells() - cells) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1,
                                    -1, -1, -1, "grid tracks exceed the shared cell budget", null);
                        }
                        cells += slots;
                    }
                    case Table table -> {
                        long count = (long) table.rows().size() * table.columns().size();
                        if (table.rows().size() > limits.maxTableRows()) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1, -1,
                                    "table row budget exceeded: actual=" + table.rows().size()
                                            + ", maxTableRows=" + limits.maxTableRows()
                                            + " (source rows include header rows)", null);
                        }
                        if (table.columns().size() > limits.maxTableColumns()) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1, -1,
                                    "table column budget exceeded: actual=" + table.columns().size()
                                            + ", maxTableColumns=" + limits.maxTableColumns(), null);
                        }
                        if (count > limits.maxCells() - cells) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1, -1,
                                    "document cell budget exceeded: used=" + cells + ", requested=" + count
                                            + ", maxCells=" + limits.maxCells(), null);
                        }
                        cells += count;
                        for (var row : table.grid().anchorRows()) {
                            for (var anchor : row) {
                                if (anchor.cell().hasBlocks()) { continue; }
                                var path = cellPath(visit.cellPath(), table, anchor.rowIndex(), anchor.columnIndex(), -1);
                                try {
                                    codePoints = countText(anchor.cell().text(), codePoints, block, index,
                                            anchor.rowIndex(), anchor.columnIndex());
                                    images(anchor.cell().images(), block, index, path);
                                    vectors(anchor.cell().vectors(), block, index, path);
                                } catch (DocumentLayoutException cause) { throw cause.withSourcePath(path); }
                            }
                        }
                    }
                    case ComponentBlock ignored -> { /* Component sources are checked after preparation. */ }
                    default -> throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, block, index,
                            -1, -1, -1, -1, "block has no layout implementation", null);
                }
            } catch (DocumentLayoutException cause) { throw cause.withSourcePath(visit.cellPath()); }
        }

        private void vectors(List<Paragraph.VectorSpan> spans, Block block, int index, List<CellLocation> path) {
            for (var span : spans) {
                ExecutionContext.checkpoint();
                var vector = span.vector();
                var owner = path.isEmpty() ? null : path.getLast();
                codePoints = countText(vector.logicalText(), codePoints, block, index,
                        owner == null ? -1 : owner.rowIndex(), owner == null ? -1 : owner.columnIndex());
                if (vectorIds.add(vector.resource().id())) {
                    vectorSegments += vector.resource().segmentCount();
                    vectorBytes += vector.resource().byteSize();
                }
                if (vectorSegments > 5_000_000 || vectorBytes > 64L * 1024 * 1024) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1,
                            span.sourceIndex(), "inline vector resource budget exceeded", null).withSourcePath(path);
                }
            }
        }

        private void images(List<Paragraph.ImageSpan> spans, Block block, int index, List<CellLocation> path) {
            for (var span : spans) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var image = span.image().resource();
                if (imageIds.add(image.id())) {
                    imageBytes += image.byteSize();
                    imagePixels += (long) image.pixelWidth() * image.pixelHeight();
                }
                if (imageBytes > 64L * 1024 * 1024 || imagePixels > 50_000_000) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1,
                            span.sourceIndex(), "inline image resource budget exceeded", null).withSourcePath(path);
                }
            }
        }
    }

    private long countText(String text, long current, Block block, int index, int row, int column) {
        int count = text.codePointCount(0, text.length());
        if (count > limits.maxCodePoints() - current) {
            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, row, column, 0,
                    "document code point budget exceeded", null);
        }
        return current + count;
    }

    /** All mutable layout state is confined to a single call. */
    private final class Flow {
        private final Document document;
        private final PageSettings settings;
        private final Pages pages;
        private final List<LayoutFragment> elements = new ArrayList<>();
        private final FlowBlockInput blocks;
        private final Map<String, FontGroup> roles;
        private final FontGroup fontGroup;
        private final Map<Integer, ParagraphLayout> paragraphs = new HashMap<>();
        private final SimpleLayoutValues simpleLayouts;
        private final CellGeometryValues cellGeometry;
        private final Map<Integer, MeasuredTable> tables = new HashMap<>();
        private final Map<String, MeasuredTable> cellTables = new HashMap<>();
        private final Map<Integer, List<ComponentLayout>> components = new HashMap<>();
        private record PlannedRegion(int page, double start, double height) { }
        private final Map<Integer, List<PlannedRegion>> componentRegions = new HashMap<>();
        private boolean pendingBreak;
        private double used;
        private double previousAfter;
        private long lineCount;
        private long containerMeasureUnits;
        private final Map<String, GridRowPlan> gridRows = new HashMap<>();
        private long codePoints;
        private long componentCommands;
        private record NestedSource(Table source, int hostIndex) { }
        private final List<NestedSource> nestedSources = new ArrayList<>();
        private SemanticSourceRegistry semanticSources;
        private final java.util.Set<String> streamedSourceIds;
        private final SourceBudget streamedSourceBudget;
        private long streamedSemanticNodes;
        private long semanticPlacements;
        private List<CellLocation> measuringPath = List.of();
        private boolean measuringVariableTable;
        private Map<Object, List<Double>> retainedLineWidths;
        private final Map<Object, Double> maximumSourceWidths = new java.util.IdentityHashMap<>();
        private VariableMeasurements activeVariableWork;
        private java.util.function.LongConsumer retainedWork;
        private boolean suppressLineCharge;
        private boolean speculativeLines;
        private long logicalFragmentLimit = limits.maxPages();
        private int keptThrough = -1;
        private int activeBlockIndex;
        private final java.util.function.IntFunction<List<Rect>> exclusionSource;
        private final Map<Integer, List<double[]>> exclusionPages = new HashMap<>();
        private boolean adaptiveExclusions = true;
        private List<double[]> exclusions = List.of();
        private int exclusionIndex;
        private double regionStart;
        private Flow(Document document, FontGroup group, Map<String, FontGroup> roles, long codePoints) {
            this(document, group, roles, codePoints, ignored -> List.of());
            adaptiveExclusions = false;
        }
        private Flow(Document document, FontGroup group, Map<String, FontGroup> roles, long codePoints,
                java.util.function.IntFunction<List<Rect>> exclusions) {
            this(document, group, roles, codePoints, exclusions, null);
        }
        private Flow(Document document, FontGroup group, Map<String, FontGroup> roles, long codePoints,
                java.util.function.IntFunction<List<Rect>> exclusions, PageConsumer consumer) {
            this.document = document;
            pages = new Pages(consumer);
            simpleLayouts = pages.streaming() ? null : new SimpleLayoutValues();
            cellGeometry = pages.streaming() ? null : new CellGeometryValues();
            blocks = FlowBlockInput.fixed(document.blocks());
            settings = document.pageSettings();
            this.fontGroup = group;
            this.roles = Map.copyOf(roles);
            this.codePoints = codePoints;
            var originalIds = sourceIds(document.blocks());
            semanticSources = new SemanticSourceRegistry(consumer == null ? originalIds : List.of());
            streamedSourceIds = consumer == null ? null : new java.util.HashSet<>(originalIds);
            streamedSourceBudget = consumer == null ? null : new SourceBudget();
            if (streamedSourceBudget != null) { SemanticTree.walk(document.blocks(), streamedSourceBudget::accept); }
            exclusionSource = exclusions;
            loadExclusions();
        }

        private Flow(PageSettings settings, BlockSource source, FontGroup group, Map<String, FontGroup> roles,
                java.util.function.IntFunction<List<Rect>> exclusions, BlockPageConsumer consumer) {
            document = new Document(DocumentMetadata.EMPTY, settings, List.of());
            this.settings = settings;
            fontGroup = group;
            this.roles = Map.copyOf(roles);
            semanticSources = new SemanticSourceRegistry(List.of());
            streamedSourceIds = new java.util.HashSet<>();
            streamedSourceBudget = new SourceBudget();
            blocks = FlowBlockInput.streaming(source, this::acceptBlock);
            pages = new Pages(page -> consumer.accept(page, blocks.sources(page)));
            simpleLayouts = null;
            cellGeometry = null;
            exclusionSource = exclusions;
            loadExclusions();
        }

        private void acceptBlock(Block block, int index) {
            if (block instanceof GridItem) {
                throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, block, index, pages.size(),
                        -1, -1, -1, "grid items belong inside a grid layout", null);
            }
            long previousCodePoints = streamedSourceBudget.codePoints;
            SemanticTree.walk(block, visit -> {
                String id = visit.block().id();
                if (id == null || id.isBlank() || !streamedSourceIds.add(id)) {
                    throw new DocumentLayoutException(DocumentLayoutException.Code.INVALID_GEOMETRY, id == null ? "" : id,
                            index, pages.size(), -1, -1, -1, visit.cellPath(),
                            "block IDs must be nonblank and globally unique", null);
                }
                streamedSourceBudget.accept(new SemanticTree.Visit(visit.block(), index, visit.cellPath()));
            });
            long added = streamedSourceBudget.codePoints - previousCodePoints;
            if (added > limits.maxCodePoints() - codePoints) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(),
                        -1, -1, -1, "document code point budget exceeded", null);
            }
            codePoints += added;
        }

        private DocumentLayout layout() {
            run();
            return new DocumentLayout(document.metadata(), pages.collected);
        }

        private void run() {
            for (int index = 0; blocks.has(index); index++) {
                activeBlockIndex = index;
                com.mirakyux.export4j.model.ExecutionContext.progress("BODY", blocks.get(index).id(), pages.size(), index, blocks.totalHint());
                Block block = blocks.get(index);
                if (block instanceof PageBreak) {
                    pendingBreak = !elements.isEmpty();
                    if (blocks.streaming()) { releaseCompletedBlock(index); }
                    continue;
                }
                boolean breakBefore = block instanceof Paragraph p && p.format().pagination().pageBreakBefore();
                if ((pendingBreak || breakBefore) && !elements.isEmpty()) {
                    nextPage(block, index, -1, 0);
                }
                pendingBreak = false;
                keepChain(index);
                switch (block) {
                    case Paragraph paragraph -> paragraph(paragraph, index);
                    case Table table -> table(table, index);
                    case ColumnFlow columns -> columns(columns, index);
                    case GridLayout grid -> grid(grid, index);
                    case ComponentBlock component -> component(component, index);
                    default -> throw new IllegalStateException("preflight must reject unknown blocks");
                }
                releaseCompletedBlock(index);
            }
            pages.add(page());
            if (blocks.streaming()) {
                elements.clear();
                blocks.releaseBefore(Integer.MAX_VALUE);
            }
            if (!nestedSources.isEmpty()) {
                var budget = new SourceBudget();
                SemanticTree.walk(document.blocks(), budget::accept);
                for (var nested : nestedSources) {
                    SemanticTree.walk(nested.source(), visit -> budget.accept(
                            new SemanticTree.Visit(visit.block(), nested.hostIndex(), visit.cellPath())));
                }
            }
        }

        private void releaseCompletedBlock(int index) {
            paragraphs.remove(index);
            tables.remove(index);
            components.remove(index);
            componentRegions.remove(index);
            // Keep-chain preparation may already own a future block's recursive table measurements.
            if (index >= keptThrough && paragraphs.isEmpty() && tables.isEmpty()
                    && components.isEmpty() && componentRegions.isEmpty()) {
                cellTables.clear();
                gridRows.clear();
                maximumSourceWidths.clear();
                if (retainedLineWidths != null) { retainedLineWidths.clear(); }
            }
            if (pages.streaming()) {
                var completeSources = semanticSources.sources();
                if (!completeSources.isEmpty()) {
                    // Completed registries contain first-placement IR; retain only collision IDs across blocks.
                    semanticSources = new SemanticSourceRegistry(List.of());
                }
            }
            int firstPageSource = elements.isEmpty() ? index + 1 : elements.getFirst().blockIndex();
            blocks.releaseBefore(Math.min(index + 1, firstPageSource));
        }

        private record ColumnBand(int page, double start, double height) { }
        private record ColumnPart(int band, LayoutFragment fragment) { }
        private record ColumnPlan(List<ColumnPart> parts, long lines, int firstRegion, double spaceAfter) { }

        private record GridItemPlan(GridItem source, double x, double width, double height,
                List<LayoutFragment> fragments) { }
        private record GridRowPlan(double height, List<GridItemPlan> items) { }

        /** The entire row is atomic, while its children remain ordinary semantic fragments. */
        private void grid(GridLayout source, int index) {
            for (int first = 0; first < source.items().size();) {
                ExecutionContext.checkpoint();
                int end = first + 1;
                while (end < source.items().size() && source.items().get(end).row() == source.items().get(first).row()) { end++; }
                var row = measuredGridRow(source, index, first, end);
                requireHeight(row.height(), source.items().get(first), index, source.items().get(first).row() - 1);
                beginBlock(row.height(), first == 0 ? 0 : source.rowGap(), source, index, source.items().get(first).row() - 1);
                var base = origin();
                elements.add(new ComponentFragment(source.id(), index, base,
                        new ComponentLayout(settings.contentWidth(), row.height(), 0, List.of(), List.of())));
                for (var item : row.items()) {
                    var point = new Point(base.x() + item.x(), base.y());
                    elements.add(new ComponentFragment(item.source().id(), index, point,
                            new ComponentLayout(item.width(), row.height(), 0, List.of(), List.of())));
                    for (var fragment : item.fragments()) { elements.add(placeColumn(fragment, index, point)); }
                }
                used += row.height(); previousAfter = 0;
                gridRows.remove(source.items().get(first).id());
                first = end;
            }
        }

        private GridRowPlan measuredGridRow(GridLayout source, int index, int first, int end) {
            return gridRows.computeIfAbsent(source.items().get(first).id(), ignored -> {
                double track = (settings.contentWidth() - source.columnGap() * (source.tracks() - 1)) / source.tracks();
                if (!Double.isFinite(track) || track <= 0) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, source, index, pages.size(),
                            -1, -1, 0, "grid gaps consume the body width", null);
                }
                double rowHeight = 0;
                var items = new ArrayList<GridItemPlan>();
                for (int at = first; at < end; at++) {
                    var item = source.items().get(at);
                    double width = item.columnSpan() * track + (item.columnSpan() - 1) * source.columnGap();
                    double x = (item.columnStart() - 1) * (track + source.columnGap());
                    var fragments = new ArrayList<LayoutFragment>();
                    double height = 0, previous = 0;
                    for (var child : item.blocks()) {
                        ExecutionContext.checkpoint();
                        gridMeasurement(child, index);
                        if (!fragments.isEmpty()) { height += previous + before(child); }
                        if (child instanceof Paragraph paragraph) {
                            var layout = shapeParagraph(paragraph, index, width);
                            requireGridText(layout, paragraph, index);
                            fragments.add(new ParagraphFragment(paragraph.id(), index, 0, new Point(0, height), layout));
                            height += layout.height();
                        } else {
                            var table = (Table) child;
                            var measured = prepareMeasuredTable(table, index, width, false);
                            double tableWidth = java.util.Arrays.stream(measured.widths()).sum();
                            if (!fits(tableWidth, width)) {
                                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                                        -1, -1, 0, "table exceeds its grid item width", null);
                            }
                            double tableHeight = 0;
                            var rows = new ArrayList<TableRowFragment>();
                            for (int r = 0; r < measured.rows().size(); r++) {
                                ExecutionContext.checkpoint();
                                appendRow(rows, table, r, measured.rows().get(r), measured.widths(), tableHeight, false);
                                tableHeight += measured.rows().get(r).height();
                            }
                            var fragment = new TableFragment(table.id(), index, new Point(0, height), tableWidth,
                                    tableHeight, rows, table.format().drawBorders());
                            requireGridTable(fragment, table, index);
                            fragments.add(fragment); height += tableHeight;
                        }
                        requireHeight(height, child, index, item.row() - 1);
                        previous = after(child);
                    }
                    items.add(new GridItemPlan(item, x, width, height, List.copyOf(fragments)));
                    rowHeight = Math.max(rowHeight, height);
                }
                return new GridRowPlan(rowHeight, List.copyOf(items));
            });
        }

        private void gridMeasurement(Block child, int index) {
            SemanticTree.walk(child, visit -> {
                var block = visit.block();
                long units = 1;
                if (block instanceof Paragraph p) {
                    units += p.text().length();
                    if (p.format().rotation() != 0) {
                        throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, p, index, pages.size(), -1,
                                -1, 0, "rotated grid content is unsupported", null);
                    }
                } else if (block instanceof Table table) {
                    if (table.format().rotation() != 0) {
                        throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(), -1,
                                -1, 0, "rotated grid content is unsupported", null);
                    }
                    for (var row : table.grid().anchorRows()) {
                        for (var anchor : row) {
                            ExecutionContext.checkpoint();
                            units++;
                            if (!anchor.cell().hasBlocks()) { units += anchor.cell().text().length(); }
                        }
                    }
                }
                containerMeasurement(units, block, index);
            });
        }

        private void containerMeasurement(long units, Block source, int index) {
            ExecutionContext.checkpoint();
            if (units > 4_000_000L - containerMeasureUnits) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, source, index, pages.size(), -1,
                        -1, 0, "semantic container measurement source-unit budget exceeded", null);
            }
            containerMeasureUnits += units;
        }

        private void requireGridText(ParagraphLayout layout, Block source, int index) {
            for (var line : layout.lines()) {
                ExecutionContext.checkpoint();
                if (!fits(line.offsetX() + line.width(), layout.width())) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, source, index, pages.size(), -1,
                            -1, line.sourceStart(), "grid text or inline image exceeds its item width", null);
                }
            }
        }

        private void requireGridTable(TableFragment table, Block source, int index) {
            for (var row : table.rows()) {
                for (var cell : row.cells()) {
                    ExecutionContext.checkpoint();
                    if (cell.content() instanceof CellFragment.Inline inline) { requireGridText(inline.layout(), source, index); }
                    else {
                        for (var nested : ((CellFragment.Blocks) cell.content()).fragments()) {
                            if (nested instanceof ParagraphFragment p) { requireGridText(p.layout(), source, index); }
                            else { requireGridTable((TableFragment) nested, source, index); }
                        }
                    }
                }
            }
        }

        /** Columns retain real semantic fragments. Only the geometry marker is a component. */
        private void columns(ColumnFlow source, int index) {
            var widths = columnWidths(source, index);
            double capacity = currentCapacity();
            // Leading spacing belongs to the current free band and is discarded at its boundary.
            double start = Math.min(capacity, used + (used == regionStart ? 0 : previousAfter));
            var bands = new ColumnBands(source, index, start, used != regionStart && start < capacity);
            java.util.function.LongConsumer budget = units -> containerMeasurement(units, source, index);
            ColumnPlan plan = new ColumnTrial(source, index, widths, bands, -1, 0, budget).layout();
            if (source.balanced() && source.columns() > 1) {
                int first = plan.parts().getFirst().band(), last = plan.parts().getLast().band();
                double low = 0, high = bands.get(last).height();
                for (int attempt = 0; attempt < 24; attempt++) {
                    ExecutionContext.checkpoint();
                    double candidate = (low + high) / 2;
                    ColumnPlan trial;
                    try {
                        trial = new ColumnTrial(source, index, widths, bands, last, candidate, budget).layout();
                    } catch (DocumentLayoutException cause) {
                        if (cause.code() != DocumentLayoutException.Code.CONTENT_TOO_TALL) { throw cause; }
                        low = candidate; continue;
                    }
                    // A shorter trial must not evade leading spacing by abandoning the first usable column.
                    if (trial.firstRegion() == plan.firstRegion()
                            && trial.parts().getFirst().band() == first && trial.parts().getLast().band() <= last) {
                        high = candidate; plan = trial;
                    } else { low = candidate; }
                }
            }
            addLines(plan.lines(), source, index, -1, -1);
            int at = 0;
            while (at < plan.parts().size()) {
                int bandIndex = plan.parts().get(at).band();
                var band = bands.get(bandIndex);
                while (pages.size() < band.page()) { nextPage(source, index, -1, 0); }
                double height = 0;
                int end = at;
                while (end < plan.parts().size() && plan.parts().get(end).band() == bandIndex) {
                    var fragment = plan.parts().get(end++).fragment();
                    double bottom = fragment.origin().y() + (fragment instanceof ParagraphFragment paragraph
                            ? paragraph.layout().height() : ((TableFragment) fragment).height());
                    height = Math.max(height, bottom);
                }
                var base = new Point(settings.margins().left(), settings.margins().top() + band.start());
                elements.add(new ComponentFragment(source.id(), index, base,
                        new ComponentLayout(settings.contentWidth(), height, 0, List.of(), List.of())));
                while (at < end) { elements.add(placeColumn(plan.parts().get(at++).fragment(), index, base)); }
                used = band.start() + height;
                exclusionIndex = 0;
                while (exclusionIndex < exclusions.size() && exclusions.get(exclusionIndex)[1] <= used) { exclusionIndex++; }
                regionStart = band.start();
            }
            previousAfter = plan.spaceAfter();
        }

        private List<Double> columnWidths(ColumnFlow source, int index) {
            double availableWidth = settings.contentWidth() - source.gap() * (source.columns() - 1);
            if (!Double.isFinite(availableWidth) || availableWidth <= 0) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, source, index, pages.size(), -1,
                        -1, 0, "column gaps consume the body width", null);
            }
            double maximum = source.columnWeights().stream().mapToDouble(Double::doubleValue).max().orElse(1);
            double total = source.columnWeights().isEmpty() ? source.columns()
                    : source.columnWeights().stream().mapToDouble(value -> value / maximum).sum();
            var widths = new ArrayList<Double>();
            for (int column = 0; column < source.columns(); column++) {
                double weight = source.columnWeights().isEmpty() ? 1 : source.columnWeights().get(column) / maximum;
                double width = availableWidth * (weight / total);
                if (!Double.isFinite(width) || width <= 0) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, source, index, pages.size(), -1,
                            column, 0, "column weight produces an invalid width", null);
                }
                widths.add(width);
            }
            return List.copyOf(widths);
        }

        /** A root keep chain must reserve a viable first fragment at one of the actual column widths. */
        private double minimumColumnHeight(ColumnFlow source, int index) {
            var widths = columnWidths(source, index);
            double maximum = widths.stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            var child = source.blocks().getFirst();
            var viewport = new PageSettings(new PageSize(maximum, settings.contentHeight()), PageMargins.uniform(0));
            var measure = new Flow(new Document(document.metadata(), viewport, List.of(child)), fontGroup, roles, 0);
            measure.retainedWork = units -> containerMeasurement(units, source, index);
            measure.suppressLineCharge = true;
            var heights = new HashMap<Double, Double>();
            double minimum = Double.POSITIVE_INFINITY;
            if (child instanceof Paragraph paragraph) {
                measure.retainedLineWidths = new java.util.IdentityHashMap<>();
                measure.maximumSourceWidths.put(paragraph, maximum);
                for (int column = 0; column < widths.size(); column++) {
                    double width = widths.get(column);
                    double height = heights.computeIfAbsent(width, ignored -> {
                        measure.retainedWork.accept(Math.max(1, paragraph.text().length() + vectorWork(paragraph.vectors())));
                        var shaped = measure.shapeParagraph(paragraph, index, width);
                        return measure.minimumParagraphHeight(paragraph, shaped, 0);
                    });
                    minimum = Math.min(minimum, height + (column == 0 ? before(child) : 0));
                }
            } else {
                var table = (Table) child;
                var cursor = measure.new VariableCursor(table);
                double lowerBound = table.columns().stream().mapToDouble(column -> switch (column) {
                    case ColumnWidth.Fixed fixed -> fixed.points();
                    case ColumnWidth.Auto auto -> auto.minPoints();
                    case ColumnWidth.Fraction ignored -> 0;
                }).sum();
                boolean fractional = table.columns().stream().anyMatch(ColumnWidth.Fraction.class::isInstance);
                for (int column = 0; column < widths.size(); column++) {
                    double width = widths.get(column);
                    if (!fits(lowerBound, width) || fractional && width <= lowerBound) { continue; }
                    double height = heights.computeIfAbsent(width, ignored -> {
                        cursor.width(width);
                        return cursor.progress.minimumInitialHeight();
                    });
                    minimum = Math.min(minimum, height + (column == 0 ? before(child) : 0));
                }
            }
            return minimum;
        }

        private double minimumParagraphHeight(Paragraph paragraph, ParagraphLayout shaped, int first) {
            int remaining = shaped.lines().size() - first;
            var policy = paragraph.format().pagination();
            int count = paragraph.format().keepTogether()
                    || remaining < (long) policy.minLinesBeforeBreak() + policy.minLinesAfterBreak()
                    ? remaining : policy.minLinesBeforeBreak();
            return fittingLines(shaped, first, count) == count
                    ? count * paragraph.format().lineHeight() : Double.POSITIVE_INFINITY;
        }

        private LayoutFragment placeColumn(LayoutFragment fragment, int index, Point base) {
            var point = new Point(base.x() + fragment.origin().x(), base.y() + fragment.origin().y());
            return switch (fragment) {
                case ParagraphFragment p -> new ParagraphFragment(p.blockId(), index, p.firstLineIndex(), point, p.layout());
                case TableFragment t -> new TableFragment(t.blockId(), index, point, t.width(), t.height(), t.rows(), t.drawBorders());
                default -> throw new IllegalStateException("columns contain only semantic text and tables");
            };
        }

        /** Lazily plans free physical bands; each band is repeated once for every logical column. */
        private final class ColumnBands {
            private final ColumnFlow source;
            private final int index;
            private final List<ColumnBand> values = new ArrayList<>();
            private int nextPage = pages.size();
            private final double firstStart;
            private final boolean continuesRegion;
            private ColumnBands(ColumnFlow source, int index, double firstStart, boolean continuesRegion) {
                this.source = source; this.index = index; this.firstStart = firstStart;
                this.continuesRegion = continuesRegion;
            }
            private ColumnBand get(int band) {
                while (band >= values.size()) {
                    ExecutionContext.checkpoint();
                    if (nextPage >= limits.maxPages()) {
                        throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, source, index, nextPage,
                                -1, -1, 0, "continuous columns exceed the document page budget", null);
                    }
                    int page = nextPage++;
                    double start = page == pages.size() ? Math.min(firstStart, settings.contentHeight()) : 0;
                    for (var exclusion : readExclusions(page)) {
                        if (exclusion[1] <= start) { continue; }
                        if (exclusion[0] > start) { values.add(new ColumnBand(page, start, exclusion[0] - start)); }
                        start = exclusion[1];
                    }
                    if (start < settings.contentHeight()) {
                        values.add(new ColumnBand(page, start, settings.contentHeight() - start));
                    }
                }
                return values.get(band);
            }
        }

        /** A bounded speculative layout owns its cursors, so failed balancing never consumes source. */
        private final class ColumnTrial {
            private final ColumnFlow source;
            private final int index;
            private final List<Double> widths;
            private final ColumnBands bands;
            private final int reducedBand;
            private final double reducedHeight;
            private final java.util.function.LongConsumer work;
            private final Flow measure;
            private final List<ColumnPart> parts = new ArrayList<>();
            private int region;
            private double occupied;
            private double after;
            private int firstRegion = -1;
            private int failedColumns;

            private ColumnTrial(ColumnFlow source, int index, List<Double> widths, ColumnBands bands,
                    int reducedBand, double reducedHeight, java.util.function.LongConsumer work) {
                this.source = source; this.index = index; this.widths = widths; this.bands = bands;
                this.reducedBand = reducedBand; this.reducedHeight = reducedHeight; this.work = work;
                var viewport = new PageSettings(new PageSize(widths.stream().mapToDouble(Double::doubleValue).max().orElseThrow(),
                        settings.contentHeight()), PageMargins.uniform(0));
                measure = new Flow(new Document(document.metadata(), viewport, source.blocks()), fontGroup, roles, 0);
                measure.retainedWork = work;
                measure.speculativeLines = true;
                measure.logicalFragmentLimit = (long) limits.maxPages() * source.columns() * 65;
            }

            private ColumnPlan layout() {
                try {
                    for (var child : source.blocks()) {
                        ExecutionContext.checkpoint();
                        failedColumns = 0;
                        if (child instanceof Paragraph paragraph) { paragraph(paragraph); }
                        else { table((Table) child); }
                    }
                    return new ColumnPlan(List.copyOf(parts), measure.lineCount, firstRegion, after);
                } catch (DocumentLayoutException cause) {
                    int physical = bands.values.isEmpty() ? pages.size()
                            : bands.values.get(Math.min(region / source.columns(), bands.values.size() - 1)).page();
                    throw new DocumentLayoutException(cause.code(), cause.blockId(), index,
                            physical, cause.rowIndex(), cause.columnIndex(),
                            cause.sourceIndex(), cause.sourcePath(), "continuous columns layout failed", cause);
                }
            }

            private double capacity() {
                int band = region / source.columns();
                return band == reducedBand ? reducedHeight : bands.get(band).height();
            }
            private double width() { return widths.get(region % source.columns()); }
            private double spacing(boolean firstFragment, double before) {
                if (!firstFragment) { return 0; }
                if (occupied > 0) { return after + before; }
                // Only the initial column can continue a region that already contains body content.
                return region == 0 && bands.continuesRegion ? before : 0;
            }
            private Point point(double spacing) {
                int column = region % source.columns();
                double x = column * source.gap();
                for (int previous = 0; previous < column; previous++) { x += widths.get(previous); }
                return new Point(x, occupied + spacing);
            }
            private void advance(Block child, boolean failed) {
                if (failed && occupied == 0) { failedColumns++; }
                else { failedColumns = 0; }
                if (failedColumns >= source.columns() && region % source.columns() == source.columns() - 1
                        && (region / source.columns() == reducedBand
                        || bands.get(region / source.columns()).height() == settings.contentHeight())) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, child, index,
                            bands.get(region / source.columns()).page(), -1, -1, 0,
                            "content cannot advance in any column of a fresh body band", null);
                }
                region++; occupied = 0; after = 0;
                if (region % source.columns() == 0) { failedColumns = 0; }
                if (reducedBand >= 0 && region / source.columns() > reducedBand) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, child, index,
                            bands.get(reducedBand).page(), -1, -1, 0,
                            "balancing requires another physical band", null);
                }
                bands.get(region / source.columns());
            }

            private void paragraph(Paragraph paragraph) {
                measure.retainedLineWidths = new java.util.IdentityHashMap<>();
                measure.maximumSourceWidths.put(paragraph, measure.settings.contentWidth());
                measure.activeVariableWork = null;
                int first = 0;
                double shapedWidth = Double.NaN;
                ParagraphLayout shaped = null;
                var format = paragraph.format();
                while (true) {
                    ExecutionContext.checkpoint();
                    if (width() != shapedWidth) {
                        work.accept(Math.max(1, paragraph.text().length()));
                        measure.suppressLineCharge = true;
                        try { shaped = measure.shapeParagraph(paragraph, index, width()); }
                        finally { measure.suppressLineCharge = false; }
                        shapedWidth = width();
                    }
                    int remaining = shaped.lines().size() - first;
                    double spacing = spacing(first == 0, format.spaceBefore());
                    double available = Math.max(0, capacity() - occupied - spacing);
                    int take = (int) Math.min(remaining, Math.floor(Math.nextUp(available / format.lineHeight())));
                    take = measure.fittingLines(shaped, first, take);
                    if (format.keepTogether() && take < remaining) { take = 0; }
                    if (take < remaining) {
                        take = Math.min(take, remaining - format.pagination().minLinesAfterBreak());
                        if (take < format.pagination().minLinesBeforeBreak()) { take = 0; }
                    }
                    if (take <= 0) { advance(paragraph, true); continue; }
                    var part = fragment(shaped, first, take, format.lineHeight());
                    if (parts.isEmpty()) { firstRegion = region; }
                    parts.add(new ColumnPart(region / source.columns(),
                            new ParagraphFragment(paragraph.id(), index, first, point(spacing), part)));
                    measure.rememberWidths(paragraph, first, part);
                    measure.addLines(take, paragraph, index, -1, -1);
                    occupied += spacing + part.height();
                    first += take; failedColumns = 0;
                    if (first == shaped.lines().size()) { after = format.spaceAfter(); return; }
                    advance(paragraph, false);
                }
            }

            private void table(Table table) {
                var cursor = measure.new VariableCursor(table);
                // Declared fixed widths and Auto minima are hard lower bounds, independent of text.
                // Skip those impossible viewports before remeasurement; unrelated geometry errors propagate.
                double minimum = table.columns().stream().mapToDouble(column -> switch (column) {
                    case ColumnWidth.Fixed fixed -> fixed.points();
                    case ColumnWidth.Auto auto -> auto.minPoints();
                    case ColumnWidth.Fraction ignored -> 0;
                }).sum();
                boolean fractional = table.columns().stream().anyMatch(ColumnWidth.Fraction.class::isInstance);
                while (!cursor.done()) {
                    ExecutionContext.checkpoint();
                    if (!fits(minimum, width()) || fractional && width() <= minimum) {
                        advance(table, true); continue;
                    }
                    cursor.width(width());
                    double spacing = spacing(cursor.progress.outputs == 0, table.format().spaceBefore());
                    var value = cursor.take(Math.max(0, capacity() - occupied - spacing));
                    if (value.isEmpty()) { advance(table, true); continue; }
                    var part = value.orElseThrow();
                    if (parts.isEmpty()) { firstRegion = region; }
                    parts.add(new ColumnPart(region / source.columns(), new TableFragment(table.id(), index,
                            point(spacing), part.width(), part.height(), part.rows(), part.drawBorders())));
                    occupied += spacing + part.height(); failedColumns = 0;
                    if (!cursor.done()) { advance(table, false); }
                }
                after = table.format().spaceAfter();
            }
        }

        private void component(ComponentBlock block, int index) {
            var parts = measuredComponents(block, index);
            var assigned = componentRegions.remove(index);
            for (int part = 0; part < parts.size(); part++) {
                var shaped = parts.get(part);
                if (assigned != null) {
                    var target = assigned.get(part);
                    while (pages.size() < target.page()) { nextPage(block, index, -1, 0); }
                    used = target.start(); regionStart = used;
                    exclusionIndex = 0;
                    while (exclusionIndex < exclusions.size() && exclusions.get(exclusionIndex)[1] <= used) { exclusionIndex++; }
                    if (!fits(shaped.height(), currentCapacity() - used)) {
                        throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index, pages.size(), -1,
                                -1, -1, "region component no longer fits its measured assignment", null);
                    }
                } else if (part == 0) {
                    beginBlock(shaped.height(), block.spaceBefore(), block, index, -1);
                } else {
                    nextRegion(block, index, -1, 0);
                    beginBlock(shaped.height(), 0, block, index, -1);
                }
                elements.add(new ComponentFragment(block.id(), index, origin(), shaped));
                used += shaped.height();
            }
            previousAfter = block.spaceAfter();
            components.remove(index);
        }

        private List<ComponentLayout> measuredComponents(ComponentBlock block, int index) {
            double available = currentCapacity() - used;
            double spacing = used == regionStart ? 0 : previousAfter + block.spaceBefore();
            return measuredComponents(block, index, Math.max(0, available - spacing));
        }

        private List<ComponentLayout> measuredComponents(ComponentBlock block, int index, double firstHeight) {
            if (components.containsKey(index)) {
                return components.get(index);
            }
            List<ComponentLayout> parts;
            try {
                var context = new ComponentContext(settings.contentWidth(), settings.contentHeight(), fontGroup, pages.size(), 0, pages.size() + 1, roles);
                parts = adaptiveExclusions && block.component() instanceof RegionFlow regionFlow
                        ? regionComponents(block, index, regionFlow, context)
                        : block.component() instanceof PageableComponent pageable ? List.copyOf(pageable.layoutParts(context, firstHeight))
                        : List.of(block.component().layout(context));
                if (parts.isEmpty() || parts.size() > (componentRegions.containsKey(index) ? 100_000 : limits.maxPages())) {
                    throw new IllegalArgumentException("invalid component part count");
                }
            } catch (DocumentLayoutException cause) {
                throw new DocumentLayoutException(cause.code(), cause.blockId(), index, pages.size(),
                        cause.rowIndex(), cause.columnIndex(), cause.sourceIndex(), cause.sourcePath(),
                        "semantic component measurement failed", cause);
            } catch (IllegalArgumentException | NullPointerException cause) {
                throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index,
                        pages.size(), -1, -1, -1, "component layout failed", cause);
            }
            for (var shaped : parts) {
            GraphicsResources.walk(shaped.staticCommands(), command -> {
                if (++componentCommands > componentLimits.maxCommands()) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(),
                            -1, -1, -1, "component command budget exceeded", null);
                }
                long count = switch (command) {
                    case DrawCommand.GlyphText glyph -> glyph.run().glyphs().size();
                    case DrawCommand.Text text -> text.text().codePointCount(0, text.text().length());
                    case DrawCommand.OutlineText text -> text.logicalText().codePointCount(0, text.logicalText().length());
                    default -> 0;
                };
                if (count > limits.maxCodePoints() - codePoints) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(),
                            -1, -1, -1, "component text budget exceeded", null);
                }
                codePoints += count;
            });
            for (var placed : shaped.semanticPlacements()) {
                if (++semanticPlacements > componentLimits.maxPlacements()) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(),
                            -1, -1, -1, "nested semantic placement budget exceeded", null);
                }
                boolean firstSource;
                try {
                    firstSource = semanticSources.accept(block.id(), placed);
                } catch (IllegalArgumentException cause) {
                    throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index, pages.size(),
                            -1, -1, -1, "nested semantic continuation identity is invalid", cause);
                }
                if (!firstSource) { continue; }
                if (pages.streaming()) {
                    SemanticTree.walk(placed.source(), visit -> {
                        if (!streamedSourceIds.add(visit.block().id())) {
                            throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index, pages.size(),
                                    -1, -1, -1, "nested semantic continuation identity is invalid",
                                    new IllegalArgumentException("semantic source descendant ID conflicts with another document source"));
                        }
                        if (++streamedSemanticNodes > 100_000) {
                            throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index, pages.size(),
                                    -1, -1, -1, "nested semantic continuation identity is invalid",
                                    new IllegalArgumentException("semantic source descendant budget exceeded"));
                        }
                        streamedSourceBudget.accept(new SemanticTree.Visit(visit.block(), index, visit.cellPath()));
                    });
                } else {
                    nestedSources.add(new NestedSource(placed.source(), index));
                }
                SemanticTree.walk(placed.source(), visit -> {
                    var source = visit.block();
                    if (source instanceof Paragraph paragraph) {
                        var owner = visit.cellPath().isEmpty() ? null : visit.cellPath().getLast();
                        try {
                            codePoints = countText(paragraph.text(), codePoints, source, index,
                                    owner == null ? -1 : owner.rowIndex(), owner == null ? -1 : owner.columnIndex());
                        } catch (DocumentLayoutException cause) { throw cause.withSourcePath(visit.cellPath()); }
                    } else if (source instanceof Table table) {
                        for (var row : table.grid().anchorRows()) {
                            for (var anchor : row) {
                                if (!anchor.cell().hasBlocks()) {
                                    try {
                                        codePoints = countText(anchor.cell().text(), codePoints, source, index,
                                                anchor.rowIndex(), anchor.columnIndex());
                                    } catch (DocumentLayoutException cause) {
                                        throw cause.withSourcePath(cellPath(visit.cellPath(), table,
                                                anchor.rowIndex(), anchor.columnIndex(), -1));
                                    }
                                }
                            }
                        }
                    }
                });
            }
            ComponentSemantics.locate(shaped, new Point(0, 0));
            if (shaped.width() > Math.nextUp(settings.contentWidth())) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, block, index, pages.size(),
                        -1, -1, -1, "component exceeds content width", null);
            }
            requireHeight(shaped.height(), block, index, -1);
            addLines(shaped.textLines(), block, index, -1, -1);
            }
            try {
                semanticSources.sources();
            } catch (IllegalArgumentException cause) {
                throw failure(DocumentLayoutException.Code.COMPONENT_LAYOUT_FAILED, block, index, pages.size(),
                        -1, -1, -1, "nested semantic continuation stream is incomplete", cause);
            }
            components.put(index, parts);
            return parts;
        }

        private List<ComponentLayout> regionComponents(ComponentBlock block, int index, RegionFlow flow, ComponentContext context) {
            int requested = 16;
            long work = 0;
            for (int probe = 0; probe < 32; probe++) {
                ExecutionContext.checkpoint();
                var plan = planRegions(block, requested);
                var regions = new FlowRegions(plan.stream().map(PlannedRegion::height).toList(), settings.contentHeight());
                var fragments = List.copyOf(flow.layoutRegions(context, regions));
                if (fragments.isEmpty() || fragments.size() > 100_000) { throw new IllegalArgumentException("invalid region component fragment count"); }
                int previous = -1;
                var measuredSources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Block, Boolean>());
                long[] sourceWork = {0};
                for (var fragment : fragments) {
                    if (fragment.regionIndex() <= previous) { throw new IllegalArgumentException("region component fragments must be ordered and unique"); }
                    previous = fragment.regionIndex();
                    for (var command : fragment.layout().staticCommands()) {
                        work += switch (command) {
                            case DrawCommand.GlyphText text -> text.run().text().length();
                            case DrawCommand.Text text -> text.text().length();
                            case DrawCommand.OutlineText text -> text.logicalText().length();
                            default -> 0;
                        };
                    }
                    for (var placed : fragment.layout().semanticPlacements()) {
                        if (!measuredSources.add(placed.source())) { continue; }
                        SemanticTree.walk(placed.source(), visit -> {
                            if (visit.block() instanceof Paragraph paragraph) {
                                sourceWork[0] += paragraph.text().length() + vectorWork(paragraph.vectors());
                            }
                            else if (visit.block() instanceof Table table) {
                                for (var row : table.grid().anchorRows()) {
                                    for (var anchor : row) {
                                        if (!anchor.cell().hasBlocks()) {
                                            sourceWork[0] += anchor.cell().text().length() + vectorWork(anchor.cell().vectors());
                                        }
                                    }
                                }
                            }
                        });
                    }
                }
                work += sourceWork[0];
                if (work > 4_000_000) { throw new IllegalArgumentException("region component planning text-work budget exceeded"); }
                if (previous < plan.size()) {
                    var assigned = new ArrayList<PlannedRegion>();
                    for (var fragment : fragments) {
                        var target = plan.get(fragment.regionIndex());
                        if (!fits(fragment.layout().height(), target.height())) { throw new IllegalArgumentException("region component exceeds assigned free area"); }
                        assigned.add(target);
                    }
                    componentRegions.put(index, List.copyOf(assigned));
                    return fragments.stream().map(RegionFlow.Fragment::layout).toList();
                }
                if (plan.size() < requested || requested == 100_016) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, limits.maxPages(), -1,
                            -1, -1, "region component exceeds available document pages", null);
                }
                requested = Math.min(100_016, requested * 2);
            }
            throw new IllegalArgumentException("region component planning did not converge within 32 probes");
        }

        private List<PlannedRegion> planRegions(ComponentBlock block, int count) {
            currentCapacity();
            var result = new ArrayList<PlannedRegion>();
            double firstStart = Math.min(currentCapacity(), used + (used == regionStart ? 0 : previousAfter + block.spaceBefore()));
            for (int page = pages.size(); page < limits.maxPages() && result.size() < count; page++) {
                ExecutionContext.checkpoint();
                double start = page == pages.size() ? Math.min(settings.contentHeight(), firstStart) : 0;
                for (var interval : readExclusions(page)) {
                    if (interval[1] <= start) { continue; }
                    if (interval[0] >= start) {
                        result.add(new PlannedRegion(page, start, interval[0] - start));
                        if (result.size() == count) { return List.copyOf(result); }
                    }
                    start = interval[1];
                }
                result.add(new PlannedRegion(page, start, settings.contentHeight() - start));
            }
            return List.copyOf(result);
        }

        private void paragraph(Paragraph paragraph, int index) {
            ParagraphFormat format = paragraph.format();
            ParagraphLayout shaped = measuredParagraph(paragraph, index);
            boolean whole = format.keepTogether() || format.pagination().keepWithNext();
            double required = whole ? shaped.height()
                    : Math.min(shaped.lines().size(), format.pagination().minLinesBeforeBreak()) * format.lineHeight();
            requireHeight(required, paragraph, index, -1);
            beginBlock(required, format.spaceBefore(), paragraph, index, -1);
            int firstLine = 0;
            while (firstLine < shaped.lines().size()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                int remaining = shaped.lines().size() - firstLine;
                double available = Math.max(0, currentCapacity() - used);
                int take = whole ? remaining
                        : (int) Math.min(remaining, Math.floor(Math.nextUp(available / format.lineHeight())));
                if (take < remaining) {
                    take = Math.min(take, remaining - format.pagination().minLinesAfterBreak());
                    if (take < format.pagination().minLinesBeforeBreak()) {
                        take = 0;
                    }
                }
                if (take == 0) {
                    if (emptyUnobstructedPage()) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, paragraph, index,
                                pages.size(), -1, -1, shaped.lines().get(firstLine).sourceStart(),
                                "paragraph fragment constraints cannot fit a fresh page", null);
                    }
                    nextRegion(paragraph, index, -1, shaped.lines().get(firstLine).sourceStart());
                    continue;
                }
                ParagraphLayout part = fragment(shaped, firstLine, take, format.lineHeight());
                elements.add(new ParagraphFragment(paragraph.id(), index, firstLine, origin(), part));
                used += part.height();
                firstLine += take;
            }
            previousAfter = format.spaceAfter();
            paragraphs.remove(index);
        }

        private ParagraphLayout measuredParagraph(Paragraph paragraph, int index) {
            return paragraphs.computeIfAbsent(index, ignored -> shapeParagraph(paragraph, index, settings.contentWidth()));
        }

        private ParagraphLayout shapeParagraph(Paragraph paragraph, int index, double width) {
                var format = paragraph.format();
                if (retainedLineWidths != null || !paragraph.inlines().isEmpty() || !paragraph.images().isEmpty() || !paragraph.fields().isEmpty() || !paragraph.vectors().isEmpty()) {
                    ParagraphLayout result;
                    var sourceInlines = paragraph.inlines().isEmpty() ? List.of(new com.mirakyux.export4j.model.InlineText(paragraph.text(),
                            com.mirakyux.export4j.model.InlineStyle.of(format.fontSize()))) : paragraph.inlines();
                    var spans = styledSpans(sourceInlines, paragraph, index, -1, -1);
                    try {
                        result = shapeRetained(paragraph, width, spans, new ParagraphStyle(format.fontSize(),
                                format.lineHeight(), format.firstLineIndent(), format.subsequentLineIndent(), alignment(format.alignment()), format.lineBreakPolicy()),
                                paragraph.images().stream().collect(java.util.stream.Collectors.toMap(Paragraph.ImageSpan::sourceIndex, Paragraph.ImageSpan::image)),
                                paragraph.fields().stream().collect(java.util.stream.Collectors.toMap(Paragraph.FieldSpan::sourceIndex, Paragraph.FieldSpan::field)),
                                InlineVectors.graphics(paragraph.vectors()));
                    } catch (TextLayoutException cause) {
                        DocumentLayoutException.Code code = cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED
                                ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED;
                        throw failure(code, paragraph, index, pages.size(), -1, -1, cause.sourceIndex(),
                                "rich text layout failed", cause);
                    } catch (IllegalArgumentException cause) {
                        throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, paragraph, index, pages.size(),
                                -1, -1, -1, "rich text font role or geometry is invalid", cause);
                    }
                    if (retainedLineWidths != null) { result = new ParagraphLayout(width, result.height(), result.lines(), result.diagnostics()); }
                    addLines(result.lines().size(), paragraph, index, -1, -1);
                    return simpleLayouts == null ? result : simpleLayouts.reuse(result);
                }
                return shape(paragraph.text(), width, new ParagraphStyle(format.fontSize(),
                        format.lineHeight(), format.firstLineIndent(), format.subsequentLineIndent(), alignment(format.alignment()), format.lineBreakPolicy()),
                        paragraph, index, -1, -1);
        }

        /** Reserve entire linked paragraphs plus the next block's minimum viable first fragment. */
        private void keepChain(int index) {
            if (index <= keptThrough || !(blocks.get(index) instanceof Paragraph first) || !first.format().pagination().keepWithNext()) {
                return;
            }
            double height = 0;
            double after = 0;
            for (int next = index; blocks.has(next); next++) {
                Block block = blocks.get(next);
                if (block instanceof PageBreak || next > index && block instanceof Paragraph p && p.format().pagination().pageBreakBefore()) {
                    break;
                }
                height += next == index ? 0 : after + before(block);
                boolean linked = block instanceof Paragraph p && p.format().pagination().keepWithNext();
                height += minimumHeight(block, next, linked, Math.max(0, settings.contentHeight() - height));
                if (blocks.streaming()) { requireHeight(height, first, index, -1); }
                keptThrough = next;
                after = after(block);
                if (!linked) {
                    break;
                }
            }
            requireHeight(height, first, index, -1);
            currentCapacity();
            double spacing = used == regionStart ? 0 : previousAfter + first.format().spaceBefore();
            while (!fits(spacing + height, currentCapacity() - used)) {
                if (emptyUnobstructedPage()) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, first, index, pages.size(), -1,
                            -1, 0, "kept content cannot fit a fresh unobstructed page", null);
                }
                nextRegion(first, index, -1, 0);
                spacing = 0;
            }
        }

        private double minimumHeight(Block block, int index, boolean linked, double firstHeight) {
            return switch (block) {
                case Paragraph p -> {
                    var measured = measuredParagraph(p, index);
                    int count = measured.lines().size();
                    var policy = p.format().pagination();
                    // A short paragraph that cannot leave the required tail is indivisible.
                    yield linked || p.format().keepTogether() || count < (long) policy.minLinesBeforeBreak() + policy.minLinesAfterBreak()
                            ? measured.height() : policy.minLinesBeforeBreak() * p.format().lineHeight();
                }
                case Table t -> {
                    var measured = measuredTable(t, index);
                    double height = 0;
                    for (int row = 0; row < t.headerRows(); row++) {
                        height += measured.rows().get(row).height();
                    }
                    height += t.format().allowRowSplit() ? minimumRowHeight(t, t.headerRows())
                            : groupHeight(measured, t.headerRows());
                    yield height;
                }
                case ComponentBlock c -> adaptiveExclusions && c.component() instanceof RegionFlow flow
                        ? flow.layoutParts(new ComponentContext(settings.contentWidth(), settings.contentHeight(), fontGroup,
                                pages.size(), 0, pages.size() + 1, roles), firstHeight).getFirst().height()
                        : measuredComponents(c, index, firstHeight).getFirst().height();
                case ColumnFlow columns -> minimumColumnHeight(columns, index);
                case GridLayout grid -> {
                    int end = 1;
                    while (end < grid.items().size() && grid.items().get(end).row() == grid.items().getFirst().row()) { end++; }
                    yield measuredGridRow(grid, index, 0, end).height();
                }
                default -> throw new IllegalStateException("unknown flow block");
            };
        }

        private double before(Block block) {
            return switch (block) {
                case Paragraph p -> p.format().spaceBefore();
                case Table t -> t.format().spaceBefore();
                case ComponentBlock c -> c.spaceBefore();
                default -> 0;
            };
        }

        private double after(Block block) {
            return switch (block) {
                case Paragraph p -> p.format().spaceAfter();
                case Table t -> t.format().spaceAfter();
                case ComponentBlock c -> c.spaceAfter();
                default -> 0;
            };
        }

        private void table(Table table, int index) {
            if (table.format().allowRowSplit() && (table.hasBlockCells() || table.hasRowSpans())) { recursiveTable(table, index); return; }
            if (table.format().allowRowSplit()) { splitTable(table, index); return; }
            var measured = measuredTable(table, index);
            double[] widths = measured.widths();
            List<MeasuredRow> rows = measured.rows();
            double headerHeight = 0;
            long headerLines = 0;
            for (int row = 0; row < table.headerRows(); row++) {
                headerHeight += rows.get(row).height();
                headerLines += rows.get(row).lines();
            }
            for (int row = table.headerRows(); row < rows.size(); row = measured.groupEnds()[row]) {
                requireHeight(headerHeight + groupHeight(measured, row), table, index, row);
            }
            beginBlock(headerHeight + groupHeight(measured, table.headerRows()), table.format().spaceBefore(),
                    table, index, table.headerRows());
            int body = table.headerRows();
            boolean continuation = false;
            double tableWidth = 0;
            for (double width : widths) {
                tableWidth += width;
            }
            while (body < rows.size()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                Point start = origin();
                List<TableRowFragment> placed = new ArrayList<>();
                double height = 0;
                if (continuation) {
                    addLines(headerLines, table, index, 0, -1);
                }
                for (int header = 0; header < table.headerRows(); header++) {
                    appendRow(placed, table, header, rows.get(header), widths, height, continuation);
                    height += rows.get(header).height();
                }
                // Each segment consumes at least one complete connected body group.
                int firstBody = body;
                while (body < rows.size() && fits(height + groupHeight(measured, body), currentCapacity() - used)) {
                    int end = measured.groupEnds()[body];
                    while (body < end) {
                        appendRow(placed, table, body, rows.get(body), widths, height, false);
                        height += rows.get(body).height();
                        body++;
                    }
                }
                if (body == firstBody) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index,
                            pages.size(), body, -1, -1, "header and body row cannot fit the content area", null);
                }
                elements.add(new TableFragment(table.id(), index, start, tableWidth, height, placed, table.format().drawBorders()));
                used += height;
                if (body < rows.size()) {
                    nextRegion(table, index, body, 0);
                    beginBlock(headerHeight + groupHeight(measured, body), 0, table, index, body);
                    continuation = true;
                }
            }
            previousAfter = table.format().spaceAfter();
            tables.remove(index);
        }

        private MeasuredTable measuredTable(Table table, int index) {
            return measuredTable(table, index, true);
        }

        private MeasuredTable measuredTable(Table table, int index, boolean requireRowFit) {
            return tables.computeIfAbsent(index, ignored -> prepareMeasuredTable(table, index, settings.contentWidth(), requireRowFit));
        }

        private MeasuredTable prepareMeasuredTable(Table table, int index, double availableWidth, boolean requireRowFit) {
                var grid = table.grid();
                if (table.hasBlockCells()) {
                    var anchor = grid.anchorRows().stream().flatMap(List::stream)
                            .filter(value -> value.cell().hasBlocks()).findFirst().orElseThrow();
                    boolean header = grid.anchorRows().stream().limit(table.headerRows()).flatMap(List::stream)
                            .anyMatch(value -> value.cell().hasBlocks());
                    if (header || table.columns().stream().anyMatch(ColumnWidth.Auto.class::isInstance)) {
                        throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                                anchor.rowIndex(), anchor.columnIndex(), -1,
                                "block cells require fixed/fraction columns and ordinary parent headers", null)
                                .withSourcePath(cellPath(measuringPath, table, anchor.rowIndex(), anchor.columnIndex(), 0));
                    }
                }
                // Automatic inline-cell columns use this table's actual parent content width.
                // Tables which themselves own block cells still require fixed/fraction columns above.
                double[] widths = columnWidths(table, grid, index, availableWidth);
                var rows = measure(table, grid, index, widths, requireRowFit);
                var measured = new MeasuredTable(widths, rows, groupEnds(grid, rows.size()), table.hasRowSpans());
                cellTables.put(table.id(), measured);
                return measured;
        }

        private double[] columnWidths(Table table, TableGrid grid, int index, double availableWidth) {
            double fixed = 0;
            double maxWeight = 0;
            double[] automatic = new double[table.columns().size()];
            double[] minimums = automaticMinimums(table, grid, index);
            double autoMinimum = 0;
            double autoPreferred = 0;
            var cellPadding = table.format().padding();
            for (ColumnWidth column : table.columns()) {
                switch (column) {
                    case ColumnWidth.Fixed value -> fixed += value.points();
                    case ColumnWidth.Fraction value -> maxWeight = Math.max(maxWeight, value.weight());
                    case ColumnWidth.Auto ignored -> { }
                }
            }
            double weights = 0;
            for (ColumnWidth column : table.columns()) {
                if (column instanceof ColumnWidth.Fraction value) { weights += value.weight() / maxWeight; }
            }
            double fractionMinimum = 0;
            for (var row : grid.anchorRows()) {
                for (var anchor : row) {
                    var cell = anchor.cell();
                    int start = anchor.columnIndex();
                    double fixedWidth = 0;
                    double weight = 0;
                    for (int column = start; column < start + cell.columnSpan(); column++) {
                        switch (table.columns().get(column)) {
                            case ColumnWidth.Fixed value -> fixedWidth += value.points();
                            case ColumnWidth.Fraction value -> weight += value.weight() / maxWeight;
                            case ColumnWidth.Auto ignored -> { }
                        }
                    }
                    if (weight > 0) {
                        // Each anchor needs one padding region, distributed by its fraction share.
                        double needed = Math.max(0, cellPadding.left() + cellPadding.right() + 1 - fixedWidth);
                        fractionMinimum = Math.max(fractionMinimum, needed / (weight / weights));
                    }
                }
            }
            for (int column = 0; column < automatic.length; column++) {
                if (table.columns().get(column) instanceof ColumnWidth.Auto auto) {
                    double preferred = minimums[column];
                    double measureWidth = Math.min(auto.maxPoints(), availableWidth) - cellPadding.left() - cellPadding.right();
                    for (var row : grid.anchorRows()) {
                        for (var anchor : row) {
                            if (anchor.columnIndex() != column || anchor.cell().columnSpan() != 1) { continue; }
                            if (measureWidth <= 0) { throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                                    anchor.rowIndex(), column, -1, "automatic column has no text area", null); }
                            var measured = preferredInlineWidth(anchor.cell(), measureWidth, table, index, anchor.rowIndex(), column);
                            preferred = Math.max(preferred, measured
                                    + cellPadding.left() + cellPadding.right());
                        }
                    }
                    automatic[column] = Math.min(auto.maxPoints(), preferred);
                    autoMinimum += minimums[column];
                    autoPreferred += automatic[column];
                }
            }
            if (autoPreferred > 0) {
                preferredMergedWidths(table, grid, index, availableWidth, automatic);
                autoPreferred = 0;
                for (double value : automatic) { autoPreferred += value; }
            }
            double autoSpace = availableWidth - fixed - (autoPreferred > 0 ? fractionMinimum : 0);
            if (!fits(fixed + autoMinimum + (autoPreferred > 0 ? fractionMinimum : 0), availableWidth)) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(), -1, -1, -1, "minimum column widths exceed page", null);
            }
            autoSpace = Math.max(autoMinimum, autoSpace);
            double ratio = autoPreferred > autoMinimum ? Math.min(1, (autoSpace - autoMinimum) / (autoPreferred - autoMinimum)) : 1;
            double autoUsed = 0;
            for (int column = 0; column < automatic.length; column++) {
                if (table.columns().get(column) instanceof ColumnWidth.Auto auto) {
                    automatic[column] = minimums[column] + (automatic[column] - minimums[column]) * ratio;
                    autoUsed += automatic[column];
                }
            }
            double remaining = availableWidth - fixed - autoUsed;
            if (!fits(fixed, availableWidth) || maxWeight > 0 && remaining <= 0) {
                throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                        -1, -1, -1, "column widths exceed the content area", null);
            }
            double[] widths = new double[table.columns().size()];
            for (int column = 0; column < widths.length; column++) {
                widths[column] = switch (table.columns().get(column)) {
                    case ColumnWidth.Fixed value -> value.points();
                    case ColumnWidth.Fraction value -> remaining * (value.weight() / maxWeight) / weights;
                    case ColumnWidth.Auto ignored -> automatic[column];
                };
                if (!Double.isFinite(widths[column]) || widths[column] <= 0) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                            -1, column, -1, "column has no positive width", null);
                }
            }
            return widths;
        }

        private double[] automaticMinimums(Table table, TableGrid grid, int index) {
            double[] minimums = new double[table.columns().size()];
            boolean fractional = false;
            boolean hasAutomatic = false;
            for (int column = 0; column < minimums.length; column++) {
                switch (table.columns().get(column)) {
                    case ColumnWidth.Fixed value -> minimums[column] = value.points();
                    case ColumnWidth.Auto value -> { minimums[column] = value.minPoints(); hasAutomatic = true; }
                    case ColumnWidth.Fraction ignored -> fractional = true;
                }
            }
            // Fraction widths depend on the final remainder. Keep their existing weighted policy;
            // a mixed span cannot be converted into independent automatic-column lower bounds.
            if (fractional || !hasAutomatic) { return minimums; }
            var anchors = grid.anchorRows().stream().flatMap(List::stream)
                    .filter(anchor -> atomicInlineWidth(anchor.cell()) > 0)
                    .sorted(java.util.Comparator.comparingInt((TableGrid.Anchor anchor) -> anchor.columnIndex() + anchor.cell().columnSpan())
                            .thenComparing(java.util.Comparator.comparingInt(TableGrid.Anchor::columnIndex).reversed())
                            .thenComparingInt(TableGrid.Anchor::rowIndex)).toList();
            double padding = table.format().padding().left() + table.format().padding().right();
            for (var anchor : anchors) {
                ExecutionContext.checkpoint();
                int start = anchor.columnIndex(), end = start + anchor.cell().columnSpan();
                double current = 0;
                boolean automatic = false;
                for (int column = start; column < end; column++) {
                    current += minimums[column];
                    automatic |= table.columns().get(column) instanceof ColumnWidth.Auto;
                }
                if (!automatic) { continue; }
                double required = atomicInlineWidth(anchor.cell()) + padding;
                double needed = Math.max(0, required - current);
                // Interval lower bounds must share their overlap. Filling from the right in end
                // order minimizes the total floor; proportional span floors can reject valid tables.
                for (int column = end - 1; column >= start && needed > 0; column--) {
                    if (table.columns().get(column) instanceof ColumnWidth.Auto auto) {
                        double growth = Math.min(needed, auto.maxPoints() - minimums[column]);
                        minimums[column] += growth;
                        needed -= growth;
                    }
                }
                current = 0;
                for (int column = start; column < end; column++) { current += minimums[column]; }
                if (!fits(required, current)) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                            anchor.rowIndex(), start, -1, "atomic inline content exceeds automatic column bounds", null)
                            .withSourcePath(cellPath(measuringPath, table, anchor.rowIndex(), start, -1));
                }
            }
            return minimums;
        }

        private static double atomicInlineWidth(com.mirakyux.export4j.model.TableCell cell) {
            double width = 0;
            for (var span : cell.images()) { ExecutionContext.checkpoint(); width = Math.max(width, span.image().width()); }
            for (var span : cell.fields()) { ExecutionContext.checkpoint(); width = Math.max(width, span.field().width()); }
            for (var span : cell.vectors()) { ExecutionContext.checkpoint(); width = Math.max(width, span.vector().resource().width()); }
            return width;
        }

        private void preferredMergedWidths(Table table, TableGrid grid, int index, double availableWidth, double[] automatic) {
            var anchors = grid.anchorRows().stream().flatMap(List::stream)
                    .filter(anchor -> anchor.cell().columnSpan() > 1)
                    .sorted(java.util.Comparator.comparingInt((TableGrid.Anchor anchor) -> anchor.cell().columnSpan())
                            .thenComparingInt(TableGrid.Anchor::rowIndex).thenComparingInt(TableGrid.Anchor::columnIndex)).toList();
            double padding = table.format().padding().left() + table.format().padding().right();
            for (var anchor : anchors) {
                ExecutionContext.checkpoint();
                int start = anchor.columnIndex(), end = start + anchor.cell().columnSpan();
                boolean fraction = false;
                double maximum = 0, preferred = 0, capacity = 0;
                for (int column = start; column < end; column++) {
                    switch (table.columns().get(column)) {
                        case ColumnWidth.Fixed value -> { maximum += value.points(); preferred += value.points(); }
                        case ColumnWidth.Auto value -> {
                            // A column never needs more than the entire available table width.
                            double cap = Math.min(value.maxPoints(), availableWidth);
                            maximum += cap; preferred += automatic[column];
                            capacity += Math.max(0, cap - automatic[column]);
                        }
                        case ColumnWidth.Fraction ignored -> fraction = true;
                    }
                }
                // Fraction columns share the final remainder; they need no separate preferred-width claim.
                if (fraction || capacity == 0) { continue; }
                double measureWidth = Math.min(maximum, availableWidth) - padding;
                if (measureWidth <= 0) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                            anchor.rowIndex(), start, -1, "merged automatic columns have no text area", null);
                }
                double wanted = preferredInlineWidth(anchor.cell(), measureWidth, table, index, anchor.rowIndex(), start) + padding;
                double growth = Math.min(capacity, Math.max(0, wanted - preferred));
                for (int column = start; column < end; column++) {
                    if (table.columns().get(column) instanceof ColumnWidth.Auto value) {
                        double cap = Math.min(value.maxPoints(), availableWidth);
                        automatic[column] += growth * (Math.max(0, cap - automatic[column]) / capacity);
                    }
                }
            }
        }

        private double preferredInlineWidth(com.mirakyux.export4j.model.TableCell cell, double width,
                Table table, int index, int row, int column) {
            try {
                return shapeCell(cell, width, table, index, row, column, false)
                        .lines().stream().mapToDouble(TextLine::width).max().orElse(0);
            } catch (DocumentLayoutException cause) {
                throw cause.withSourcePath(cellPath(measuringPath, table, row, column, -1));
            }
        }

        private List<MeasuredRow> measure(Table table, TableGrid grid, int index, double[] widths, boolean requireRowFit) {
            return measure(table, grid, index, widths, requireRowFit, table.rows().size());
        }

        private List<MeasuredRow> measure(Table table, TableGrid grid, int index, double[] widths, boolean requireRowFit, int rowLimit) {
            List<MeasuredRow> rows = new ArrayList<>();
            List<HeightConstraint> constraints = new ArrayList<>();
            double[] heights = new double[rowLimit];
            var padding = table.format().padding();
            for (int row = 0; row < rowLimit; row++) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                List<MeasuredCell> cells = new ArrayList<>();
                double height = 0;
                long lines = 0;
                for (var anchor : grid.anchorRows().get(row)) {
                    var cell = anchor.cell();
                    int column = anchor.columnIndex();
                    double width = spannedWidth(widths, column, cell.columnSpan()) - padding.left() - padding.right();
                    if (width <= 0) {
                        throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(),
                                row, column, -1, "cell has no positive text width after padding", null);
                    }
                    MeasuredCell layout = measureCell(cell, width, table, index, row, column);
                    double required = layout.height() + padding.top() + padding.bottom();
                    if (!Double.isFinite(required)) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index, pages.size(),
                                row, column, -1, "cell height exceeds the finite content area", null);
                    }
                    if (cell.rowSpan() == 1) { height = Math.max(height, required); }
                    else { constraints.add(new HeightConstraint(row, column, cell.rowSpan(), required)); }
                    lines += layout.lineCount();
                    cells.add(layout);
                }
                heights[row] = height;
                rows.add(new MeasuredRow(height, lines, List.copyOf(cells), grid.anchorRows().get(row), List.of()));
            }
            constraints.sort(java.util.Comparator.comparingInt(HeightConstraint::span)
                    .thenComparingInt(HeightConstraint::row).thenComparingInt(HeightConstraint::column));
            for (var constraint : constraints) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                double current = 0;
                for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { current += heights[row]; }
                if (current < constraint.height()) {
                    double increment = (constraint.height() - current) / constraint.span();
                    for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { heights[row] += increment; }
                    double actual = 0;
                    for (int row = constraint.row(); row < constraint.row() + constraint.span(); row++) { actual += heights[row]; }
                    if (actual < constraint.height()) { heights[constraint.row() + constraint.span() - 1] += constraint.height() - actual; }
                }
            }
            List<MeasuredRow> resolved = new ArrayList<>();
            for (int row = 0; row < rows.size(); row++) {
                var original = rows.get(row);
                List<Double> cellHeights = new ArrayList<>();
                for (var anchor : original.anchors()) {
                    double height = 0;
                    for (int covered = row; covered < row + anchor.cell().rowSpan(); covered++) { height += heights[covered]; }
                    if (!Double.isFinite(height)) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index, pages.size(),
                                row, anchor.columnIndex(), -1, "merged row height overflow", null);
                    }
                    cellHeights.add(height);
                }
                if (requireRowFit && (!table.format().allowRowSplit() || row < table.headerRows())) {
                    requireHeight(heights[row], table, index, row);
                }
                resolved.add(new MeasuredRow(heights[row], original.lines(), original.cells(), original.anchors(), List.copyOf(cellHeights)));
            }
            return List.copyOf(resolved);
        }

        /** All-covered physical rows have no painted/source anchors; their height is still consumed. */
        private void appendRow(List<TableRowFragment> target, Table table, int row, MeasuredRow measured,
                double[] widths, double y, boolean repeated) {
            if (!measured.cells().isEmpty()) { target.add(placeRow(table, row, measured, widths, y, repeated)); }
        }

        private TableRowFragment placeRow(Table table, int row, MeasuredRow measured,
                double[] widths, double y, boolean repeated) {
            List<CellFragment> cells = new ArrayList<>();
            var padding = table.format().padding();
            for (int anchor = 0; anchor < measured.cells().size(); anchor++) {
                int column = measured.anchors().get(anchor).columnIndex();
                int span = measured.anchors().get(anchor).cell().columnSpan();
                double x = spannedWidth(widths, 0, column);
                double width = spannedWidth(widths, column, span);
                var bounds = new Rect(x, y, width, measured.cellHeights().get(anchor));
                var textOrigin = new Point(x + padding.left(), y + padding.top());
                if (cellGeometry != null) {
                    bounds = cellGeometry.reuse(bounds);
                    textOrigin = cellGeometry.reuse(textOrigin);
                }
                cells.add(new CellFragment(column, bounds, textOrigin, measured.cells().get(anchor).content()));
            }
            return new TableRowFragment(row, row < table.headerRows(), repeated, cells);
        }

        private double minimumRowHeight(Table table, int row) {
            return table.format().padding().top() + table.format().padding().bottom()
                    + table.rows().get(row).cells().stream().mapToDouble(cell -> cell.format().lineHeight()).max().orElseThrow();
        }

        /** Opt-in complete-line row splitting; source rows and cell line offsets survive every continuation. */
        private void splitTable(Table table, int index) {
            var measured = measuredTable(table, index);
            var widths = measured.widths();
            double tableWidth = java.util.Arrays.stream(widths).sum();
            double headerHeight = 0;
            long headerLines = 0;
            for (int row = 0; row < table.headerRows(); row++) {
                headerHeight += measured.rows().get(row).height();
                headerLines += measured.rows().get(row).lines();
            }
            for (int row = table.headerRows(); row < table.rows().size(); row++) {
                requireHeight(headerHeight + minimumRowHeight(table, row), table, index, row);
            }
            beginBlock(headerHeight + minimumRowHeight(table, table.headerRows()), table.format().spaceBefore(), table, index, table.headerRows());
            int row = table.headerRows();
            int[] consumed = new int[table.rows().get(row).cells().size()];
            boolean repeated = false;
            while (row < table.rows().size()) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                Point start = origin();
                List<TableRowFragment> placed = new ArrayList<>();
                double height = 0;
                if (repeated) { addLines(headerLines, table, index, 0, -1); }
                for (int header = 0; header < table.headerRows(); header++) {
                    appendRow(placed, table, header, measured.rows().get(header), widths, height, repeated);
                    height += measured.rows().get(header).height();
                }
                boolean progressed = false;
                while (row < table.rows().size()) {
                    double available = currentCapacity() - used - height;
                    var candidate = splitRow(table, row, measured.rows().get(row), widths, consumed, available, height);
                    if (candidate.isEmpty()) { break; }
                    var slice = candidate.orElseThrow();
                    int column = 0;
                    for (int anchor = 0; anchor < consumed.length; anchor++) {
                        if (slice.taken()[anchor] == 0) { addLines(1, table, index, row, column); }
                        consumed[anchor] += slice.taken()[anchor];
                        column += table.rows().get(row).cells().get(anchor).columnSpan();
                    }
                    placed.add(slice.fragment());
                    height += slice.height();
                    progressed = true;
                    if (!slice.complete()) { break; }
                    row++;
                    consumed = new int[row < table.rows().size() ? table.rows().get(row).cells().size() : 0];
                }
                if (!progressed) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index, pages.size(), row, -1, 0,
                            "table headers and one line per active cell cannot fit", null);
                }
                elements.add(new TableFragment(table.id(), index, start, tableWidth, height, placed, table.format().drawBorders()));
                used += height;
                if (row < table.rows().size()) {
                    nextRegion(table, index, row, 0);
                    beginBlock(headerHeight + minimumRowHeight(table, row), 0, table, index, row);
                    repeated = true;
                }
            }
            previousAfter = table.format().spaceAfter();
            tables.remove(index);
        }

        /** Pure row allocation shared by document paging and variable-height table cursors. */
        private Optional<RowSlice> splitRow(Table table, int row, MeasuredRow original, double[] widths,
                int[] consumed, double available, double y) {
            var padding = table.format().padding();
            double inside = available - padding.top() - padding.bottom();
            int[] take = new int[original.cells().size()];
            double textHeight = 0;
            boolean complete = true;
            for (int column = 0; column < take.length; column++) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                int remaining = original.cells().get(column).lines().size() - consumed[column];
                double step = table.rows().get(row).cells().get(column).format().lineHeight();
                take[column] = (int) Math.min(remaining, Math.max(0, Math.floor(Math.nextUp(inside / step))));
                if (remaining > 0 && take[column] == 0) { return Optional.empty(); }
                if (take[column] < remaining) { complete = false; }
                textHeight = Math.max(textHeight, Math.max(1, take[column]) * step);
            }
            double height = textHeight + padding.top() + padding.bottom();
            if (!fits(height, available)) { return Optional.empty(); }
            List<CellFragment> cells = new ArrayList<>();
            double x = 0;
            int column = 0;
            for (int anchor = 0; anchor < take.length; anchor++) {
                var source = original.cells().get(anchor);
                var cell = table.rows().get(row).cells().get(anchor);
                double step = cell.format().lineHeight();
                double width = spannedWidth(widths, column, cell.columnSpan());
                ParagraphLayout slice;
                if (take[anchor] == 0) {
                    int end = source.lines().getLast().sourceEnd();
                    slice = new ParagraphLayout(source.width(), step, List.of(new TextLine(end, end, 0,
                            source.lines().getFirst().baselineY(), 0, List.of())), List.of());
                } else { slice = fragment(source.layout(), consumed[anchor], take[anchor], step); }
                cells.add(new CellFragment(column, new Rect(x, y, width, height),
                        new Point(x + padding.left(), y + padding.top()), slice, consumed[anchor]));
                x += width;
                column += cell.columnSpan();
            }
            boolean continued = java.util.Arrays.stream(consumed).anyMatch(value -> value > 0);
            return Optional.of(new RowSlice(new TableRowFragment(row, false, false, cells, continued), height, take, complete));
        }

        /** Rebuilds only unconsumed wrapping; accepted line widths retain the full original shaping context. */
        private List<TableRegion> variableRecursiveTable(Table table, FlowRegions regions, List<Double> widths) {
            var cursor = new VariableCursor(table);
            var result = new ArrayList<TableRegion>();
            for (int region = 0; !cursor.done(); region++) {
                ExecutionContext.checkpoint();
                if (region >= 100_016) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, result.size(),
                            cursor.progress.row, -1, 0, "recursive table region budget exceeded", null);
                }
                if (regions.height(region) == 0) { continue; }
                cursor.width(widths.get(region % widths.size()));
                var attempt = cursor.take(regions.height(region));
                if (attempt.isPresent()) { result.add(new TableRegion(region, attempt.orElseThrow())); }
                else if (region >= regions.initialHeights().size()) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, 0, result.size(),
                            cursor.progress.row, -1, 0, "recursive content cannot advance at the actual region width", null);
                }
            }
            return List.copyOf(result);
        }

        private final class VariableCursor implements VariableTableCursor.State {
            private final Table table;
            private final VariableMeasurements work;
            private TableProgress progress;
            private double selected = Double.NaN;

            private VariableCursor(Table table) {
                this.table = table;
                retainedLineWidths = new java.util.IdentityHashMap<>();
                work = new VariableMeasurements(table);
                activeVariableWork = work;
                width(settings.contentWidth());
            }
            @Override public void width(double width) {
                ExecutionContext.checkpoint();
                if (!Double.isFinite(width) || width <= 0 || !fits(width, settings.contentWidth())) {
                    throw new IllegalArgumentException("table cursor width must fit the preparation viewport");
                }
                if (width == selected) { return; }
                work.account(work.sourceUnits, progress == null ? table.headerRows() : progress.row);
                MeasuredTable measured;
                long saved = lineCount;
                lineCount = 0;
                measuringVariableTable = true;
                cellTables.clear();
                try { measured = prepareMeasuredTable(table, 0, width, false); }
                finally { lineCount = saved; measuringVariableTable = false; }
                progress = progress == null ? new TableProgress(table, measured, 0) : progress.reflow(measured);
                selected = width;
            }
            @Override public double remainingHeight() { return progress.remainingHeight(); }
            @Override public boolean done() { return progress.done(); }
            @Override public Optional<TableFragment> take(double height) {
                if (!Double.isFinite(height) || height < 0 || !fits(height, settings.contentHeight())) {
                    throw new IllegalArgumentException("table take height must fit the preparation viewport");
                }
                var attempt = progress.take(height);
                if (attempt == null) { return Optional.empty(); }
                if (progress.outputs >= Math.min(logicalFragmentLimit, 100_000)) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, progress.outputs,
                            progress.row, -1, 0, "variable table fragment budget exceeded", null);
                }
                var fragment = attempt.fragment();
                addLines(fragment.rows().stream().flatMap(row -> row.cells().stream()).mapToLong(CellFragment::lineCount).sum(),
                        table, 0, progress.row, -1);
                rememberWidths(table, fragment);
                progress = attempt.next();
                // A subsequent width change must use accepted widths even when returning to this width.
                return Optional.of(fragment);
            }
        }

        private IntToDoubleFunction retainedWidths(Object source, double currentWidth) {
            var accepted = retainedLineWidths == null ? List.<Double>of() : retainedLineWidths.getOrDefault(source, List.of());
            return line -> line < accepted.size() ? accepted.get(line) : currentWidth;
        }

        private double retainedMaximum(Object source, double currentWidth) {
            if (retainedLineWidths == null) { return currentWidth; }
            maximumSourceWidths.merge(source, currentWidth, Math::max);
            return Math.max(maximumSourceWidths.get(source),
                    retainedLineWidths.getOrDefault(source, List.of()).stream().mapToDouble(Double::doubleValue).max().orElse(currentWidth));
        }

        /** Future atomic content may be measured at its maximum width, but can never be emitted
         * from that probe in a narrower region. Only explicit GLYPH_TOO_WIDE is deferred. */
        private ParagraphLayout shapeRetained(Object source, double width, List<StyledSpan> spans, ParagraphStyle style,
                Map<Integer, com.mirakyux.export4j.model.InlineImage> images,
                Map<Integer, com.mirakyux.export4j.model.InlineField> fields,
                Map<Integer, com.mirakyux.export4j.text.InlineGraphic> graphics) {
            double maximum = retainedMaximum(source, width);
            if (retainedLineWidths == null) { return paragraphLayouter.layout(spans, maximum, style, images, fields, graphics); }
            var accepted = retainedWidths(source, width);
            var deferred = new java.util.HashSet<Integer>();
            int[] requested = {-1};
            while (true) {
                ExecutionContext.checkpoint();
                try {
                    return paragraphLayouter.layout(spans, maximum, style, images, fields, graphics, line -> {
                        requested[0] = line;
                        return deferred.contains(line) ? maximum : accepted.applyAsDouble(line);
                    });
                } catch (TextLayoutException failure) {
                    int line = requested[0];
                    if (failure.code() != TextLayoutException.Code.GLYPH_TOO_WIDE || width >= maximum
                            || line < retainedLineWidths.getOrDefault(source, List.of()).size() || !deferred.add(line)) { throw failure; }
                    long units = spans.stream().mapToLong(span -> span.text().length()).sum()
                            + graphics.values().stream().mapToLong(graphic -> graphic.ink().segments().size()
                                    + (long) graphic.ink().logicalText().length()).sum();
                    if (activeVariableWork != null) { activeVariableWork.account(Math.max(1, units), -1); }
                    else if (retainedWork != null) { retainedWork.accept(Math.max(1, units)); }
                    else { throw failure; }
                }
            }
        }

        private int fittingLines(ParagraphLayout layout, int first, int count) {
            if (retainedLineWidths == null) { return count; }
            int result = 0;
            while (result < count) {
                var line = layout.lines().get(first + result);
                if (!fits(line.offsetX() + line.width(), layout.width())) { break; }
                result++;
            }
            return result;
        }

        private boolean contentFits(CellFragment.Content content) {
            if (content instanceof CellFragment.Inline inline) {
                return fittingLines(inline.layout(), 0, inline.layout().lines().size()) == inline.layout().lines().size();
            }
            for (var child : ((CellFragment.Blocks) content).fragments()) {
                if (child instanceof ParagraphFragment paragraph) {
                    if (fittingLines(paragraph.layout(), 0, paragraph.layout().lines().size()) != paragraph.layout().lines().size()) { return false; }
                } else {
                    for (var row : ((TableFragment) child).rows()) {
                        for (var cell : row.cells()) { if (!contentFits(cell.content())) { return false; } }
                    }
                }
            }
            return true;
        }

        private void rememberWidths(Object source, int first, ParagraphLayout layout) {
            var accepted = retainedLineWidths.computeIfAbsent(source, ignored -> new ArrayList<>());
            if (first > accepted.size()) { throw new IllegalStateException("noncontiguous recursive text source"); }
            for (int at = 0; at < layout.lines().size(); at++) {
                if (first + at == accepted.size()) { accepted.add(layout.width()); }
            }
        }

        private void rememberWidths(Table source, TableFragment fragment) {
            var grid = source.grid();
            for (var row : fragment.rows()) {
                if (row.header()) { continue; }
                for (var cell : row.cells()) {
                    var owner = grid.ownerAt(row.rowIndex(), cell.columnIndex()).cell();
                    if (cell.content() instanceof CellFragment.Inline inline) {
                        rememberWidths(owner, inline.firstLineIndex(), inline.layout());
                    } else {
                        for (int at = 0; at < cell.blocks().size(); at++) {
                            var child = cell.blocks().get(at);
                            var original = owner.blocks().get(cell.sourceBlockIndices().get(at));
                            if (child instanceof ParagraphFragment paragraph) {
                                rememberWidths(original, paragraph.firstLineIndex(), paragraph.layout());
                            } else { rememberWidths((Table) original, (TableFragment) child); }
                        }
                    }
                }
            }
        }

        private final class VariableMeasurements {
            private final Table table;
            private final boolean headersOnly;
            private final Map<Double, MeasuredTable> alternatives = new HashMap<>();
            private long sourceUnits;
            private long measuredUnits;

            private VariableMeasurements(Table table) {
                this(table, false);
            }

            private VariableMeasurements(Table table, boolean headersOnly) {
                this.table = table;
                this.headersOnly = headersOnly;
                SemanticTree.walk(List.of(table), visit -> {
                    ExecutionContext.checkpoint();
                    if (visit.block() instanceof Paragraph paragraph) {
                        sourceUnits += paragraph.text().length() + vectorWork(paragraph.vectors());
                    }
                    else if (visit.block() instanceof Table nested) {
                        for (var anchors : nested.grid().anchorRows()) {
                            for (var anchor : anchors) {
                                if (!anchor.cell().hasBlocks()) {
                                    sourceUnits += anchor.cell().text().length() + vectorWork(anchor.cell().vectors());
                                }
                            }
                        }
                    }
                });
            }

            private void account(long units, int row) {
                if (retainedWork != null) { retainedWork.accept(units); }
                if (units > 4_000_000L - measuredUnits) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, 0, row, -1, 0,
                            "variable table measurement source-unit budget exceeded", null);
                }
                measuredUnits += units;
            }

            private MeasuredTable at(double width, int row) {
                var measured = alternatives.get(width);
                if (measured != null) { return measured; }
                account(sourceUnits, row);
                long savedLines = lineCount;
                lineCount = 0;
                measuringVariableTable = true;
                try {
                    if (headersOnly) {
                        var grid = table.grid();
                        var columns = columnWidths(table, grid, 0, width);
                        measured = new MeasuredTable(columns, measure(table, grid, 0, columns, false, table.headerRows()),
                                new int[0], false);
                    } else { measured = prepareMeasuredTable(table, 0, width, false); }
                }
                finally { lineCount = savedLines; measuringVariableTable = false; }
                alternatives.put(width, measured);
                cellTables.clear();
                return measured;
            }
        }

        private List<TableRegion> variableTableRegions(Table table, FlowRegions regions, List<Double> widths) {
            var preparations = new VariableMeasurements(table);
            var result = new ArrayList<TableRegion>();
            int row = table.headerRows();
            for (int region = 0; row < table.rows().size(); region++) {
                ExecutionContext.checkpoint();
                if (region >= 100_016 || result.size() >= Math.min(limits.maxPages(), 100_000)) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, result.size(), row, -1, 0,
                            "table region budget exceeded", null);
                }
                if (regions.height(region) == 0) { continue; }
                double width = widths.get(region % widths.size());
                var measured = preparations.at(width, row);
                var placed = new ArrayList<TableRowFragment>();
                double height = 0;
                long lines = 0;
                for (int header = 0; header < table.headerRows(); header++) {
                    var original = measured.rows().get(header);
                    appendRow(placed, table, header, original, measured.widths(), height, !result.isEmpty());
                    height += original.height();
                    lines += original.lines();
                }
                int next = row;
                while (next < table.rows().size() && fits(height + groupHeight(measured, next), regions.height(region))) {
                    int end = measured.groupEnds()[next];
                    while (next < end) {
                        ExecutionContext.checkpoint();
                        var original = measured.rows().get(next);
                        appendRow(placed, table, next, original, measured.widths(), height, false);
                        height += original.height();
                        lines += original.lines();
                        next++;
                    }
                }
                if (next == row) {
                    if (region >= regions.initialHeights().size()) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, 0, result.size(), row, -1, 0,
                                "table headers and next merged-row group cannot fit the actual region width", null);
                    }
                    continue;
                }
                if (lines > limits.maxLines() - lineCount) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, result.size(), row, -1, 0,
                            "table output-line budget exceeded, including repeated headers", null);
                }
                lineCount += lines;
                result.add(new TableRegion(region, new TableFragment(table.id(), 0, new Point(0, 0),
                        java.util.Arrays.stream(measured.widths()).sum(), height, placed, table.format().drawBorders())));
                row = next;
            }
            return List.copyOf(result);
        }

        /** Plans complete original cell sources against the exact region capacities before consuming lines. */
        private final class VariableInlineTable {
            private final Table table;
            private final FlowRegions regions;
            private final List<Double> widths;
            private final VariableMeasurements preparations;
            private final List<TableRegion> result = new ArrayList<>();
            private final List<TableRowFragment> placed = new ArrayList<>();
            private MeasuredTable active;
            private int region;
            private double height;
            private boolean body;

            private VariableInlineTable(Table table, FlowRegions regions, List<Double> widths) {
                this.table = table; this.regions = regions; this.widths = widths;
                preparations = new VariableMeasurements(table, true);
            }

            private List<TableRegion> layout() {
                var grid = table.grid();
                for (int row = table.headerRows(); row < table.rows().size(); row++) {
                    ExecutionContext.checkpoint();
                    var anchors = new ArrayList<TableGrid.Anchor>();
                    for (int column = 0; column < table.columns().size();) {
                        var owner = grid.ownerAt(row, column);
                        anchors.add(owner);
                        column += owner.cell().columnSpan();
                    }
                    final int sourceRow = row;
                    double minimumStep = anchors.stream().filter(anchor -> anchor.rowIndex() == sourceRow)
                            .mapToDouble(anchor -> anchor.cell().format().lineHeight()).max().orElse(0);
                    // Fully covered rows introduce no new content, matching the equal-width owner cursor.
                    if (minimumStep == 0) { continue; }
                    double minimum = minimumStep + table.format().padding().top() + table.format().padding().bottom();
                    while (true) {
                        begin(row);
                        if (fits(height + minimum, regions.height(region))) { break; }
                        if (!body && region >= regions.initialHeights().size()) { throw tooTall(row, -1); }
                        advance(row);
                    }
                    var capacities = new RowCapacities(row, region, height, minimumStep);
                    var layouts = new ArrayList<ParagraphLayout>();
                    for (var anchor : anchors) {
                        if (anchor.rowIndex() < row) { layouts.add(null); continue; }
                        var cell = anchor.cell();
                        int column = anchor.columnIndex();
                        preparations.account(cell.text().length() + vectorWork(cell.vectors()), row);
                        var lineWidths = new CellWidths(column, cell.columnSpan(), cell.format().lineHeight(), capacities);
                        try { layouts.add(shapeCell(cell, settings.contentWidth(), table, 0, row, column, false, lineWidths)); }
                        catch (DocumentLayoutException cause) { throw cause.withSourcePath(cellPath(List.of(), table, row, column, -1)); }
                    }
                    int[] consumed = new int[anchors.size()];
                    boolean continued = false;
                    int part = 0;
                    while (true) {
                        ExecutionContext.checkpoint();
                        var capacity = capacities.at(part++);
                        while (region < capacity.region()) { advance(row); }
                        begin(row);
                        double inside = capacity.inside();
                        double rowHeight = 0;
                        int[] taken = new int[anchors.size()];
                        boolean complete = true;
                        for (int anchor = 0; anchor < anchors.size(); anchor++) {
                            if (layouts.get(anchor) == null) { continue; }
                            double step = anchors.get(anchor).cell().format().lineHeight();
                            int remaining = layouts.get(anchor).lines().size() - consumed[anchor];
                            taken[anchor] = (int) Math.min(remaining, Math.floor(Math.nextUp(inside / step)));
                            complete &= taken[anchor] == remaining;
                            rowHeight = Math.max(rowHeight, Math.max(1, taken[anchor]) * step);
                        }
                        rowHeight += table.format().padding().top() + table.format().padding().bottom();
                        for (int anchor = 0; anchor < anchors.size(); anchor++) {
                            var owner = anchors.get(anchor);
                            var cell = owner.cell();
                            int column = owner.columnIndex();
                            var source = layouts.get(anchor);
                            double x = spannedWidth(active.widths(), 0, column);
                            double width = spannedWidth(active.widths(), column, cell.columnSpan());
                            double step = cell.format().lineHeight();
                            var bounds = new Rect(x, height, width, rowHeight);
                            var origin = new Point(x + table.format().padding().left(), height + table.format().padding().top());
                            if (source == null) {
                                placed.add(new TableRowFragment(owner.rowIndex(), false, false,
                                        List.of(new CellFragment(column, bounds, origin, new CellFragment.Blocks(List.of(), List.of()))), true));
                                continue;
                            }
                            ParagraphLayout slice;
                            if (taken[anchor] == 0) {
                                int end = source.lines().getLast().sourceEnd();
                                slice = new ParagraphLayout(source.width(), step, List.of(new TextLine(end, end, 0,
                                        source.lines().getFirst().baselineY(), 0, List.of())), List.of());
                            } else { slice = fragment(source, consumed[anchor], taken[anchor], step); }
                            slice = new ParagraphLayout(width - table.format().padding().left() - table.format().padding().right(),
                                    slice.height(), slice.lines(), slice.diagnostics());
                            placed.add(new TableRowFragment(row, false, false,
                                    List.of(new CellFragment(column, bounds, origin, slice, consumed[anchor])), continued));
                            consumed[anchor] += taken[anchor];
                        }
                        height += rowHeight;
                        body = true;
                        if (complete) { break; }
                        continued = true;
                        advance(row);
                    }
                }
                finish();
                return List.copyOf(result);
            }

            private void checkRegion(int target, int row, int column) {
                checkRegion(target, row, column, (long) result.size() + 1);
            }

            private void checkRegion(int target, int row, int column, long outputs) {
                ExecutionContext.checkpoint();
                if (target >= 100_016 || outputs > Math.min(limits.maxPages(), 100_000)) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, result.size(), row, column, 0,
                            "variable split table region budget exceeded", null);
                }
            }

            private MeasuredTable measured(int target, int row) {
                checkRegion(target, row, -1);
                return preparations.at(widths.get(target % widths.size()), row);
            }

            private double headerHeight(MeasuredTable measured) {
                double value = 0;
                for (int header = 0; header < table.headerRows(); header++) { value += measured.rows().get(header).height(); }
                return value;
            }

            private void begin(int row) {
                if (active != null) { return; }
                active = measured(region, row);
                for (int header = 0; header < table.headerRows(); header++) {
                    var original = active.rows().get(header);
                    appendRow(placed, table, header, original, active.widths(), height, !result.isEmpty());
                    height += original.height();
                }
            }

            private void finish() {
                if (!body) { return; }
                long lines = placed.stream().flatMap(row -> row.cells().stream()).mapToLong(CellFragment::lineCount).sum();
                if (lines > limits.maxLines() - lineCount) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, result.size(), -1, -1, 0,
                            "table output-line budget exceeded, including repeated headers", null);
                }
                lineCount += lines;
                result.add(new TableRegion(region, new TableFragment(table.id(), 0, new Point(0, 0),
                        java.util.Arrays.stream(active.widths()).sum(), height, coalesceRows(placed), table.format().drawBorders())));
            }

            private void advance(int row) {
                finish(); region++; active = null; placed.clear(); height = 0; body = false;
                checkRegion(region, row, -1);
            }

            private DocumentLayoutException tooTall(int row, int column) {
                return failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, 0, result.size(), row, column, -1,
                        "table headers and next complete cell line cannot fit the actual region", null);
            }

            private record LineBand(int end, double width) { }

            private record RegionCapacity(int region, double inside, MeasuredTable measured) { }

            /** All cells skip the same short regions, including cells already consumed in an earlier part. */
            private final class RowCapacities {
                private final int row, firstRegion, firstOutput;
                private final double firstHeight, minimumStep;
                private final List<RegionCapacity> capacities = new ArrayList<>();
                private RowCapacities(int row, int firstRegion, double firstHeight, double minimumStep) {
                    this.row = row; this.firstRegion = firstRegion; this.firstHeight = firstHeight;
                    this.minimumStep = minimumStep; firstOutput = result.size();
                }
                private RegionCapacity at(int part) {
                    while (capacities.size() <= part) {
                        int target = capacities.isEmpty() ? firstRegion : capacities.getLast().region() + 1;
                        while (true) {
                            checkRegion(target, row, -1, (long) firstOutput + capacities.size() + 1);
                            var measured = measured(target, row);
                            double used = target == firstRegion ? firstHeight : headerHeight(measured);
                            double inside = regions.height(target) - used - table.format().padding().top() - table.format().padding().bottom();
                            if (fits(minimumStep, inside)) {
                                capacities.add(new RegionCapacity(target, inside, measured));
                                break;
                            }
                            if (target >= regions.initialHeights().size()) { throw tooTall(row, -1); }
                            target++;
                        }
                    }
                    return capacities.get(part);
                }
            }

            private final class CellWidths implements IntToDoubleFunction {
                private final int column, span;
                private final double step;
                private final RowCapacities capacities;
                private final List<LineBand> bands = new ArrayList<>();
                private CellWidths(int column, int span, double step, RowCapacities capacities) {
                    this.column = column; this.span = span; this.step = step; this.capacities = capacities;
                }
                @Override public double applyAsDouble(int line) {
                    while (bands.isEmpty() || bands.getLast().end() <= line) {
                        var capacity = capacities.at(bands.size());
                        int count = (int) Math.min(Integer.MAX_VALUE / 2, Math.floor(Math.nextUp(capacity.inside() / step)));
                        int end = (bands.isEmpty() ? 0 : bands.getLast().end()) + count;
                        double width = spannedWidth(capacity.measured().widths(), column, span)
                                - table.format().padding().left() - table.format().padding().right();
                        bands.add(new LineBand(end, width));
                    }
                    int low = 0, high = bands.size() - 1;
                    while (low < high) {
                        int middle = (low + high) >>> 1;
                        if (line < bands.get(middle).end()) { high = middle; } else { low = middle + 1; }
                    }
                    return bands.get(low).width();
                }
            }
        }

        private final class Cursor implements TableCursor.State {
            private final Table table;
            private final MeasuredTable measured;
            private final double headerHeight;
            private final long headerLines;
            private final double[] suffixHeights;
            private int row;
            private int[] consumed;
            private int outputs;

            private Cursor(Table table) {
                this.table = table;
                measured = measuredTable(table, 0, false);
                row = table.headerRows();
                consumed = new int[table.rows().get(row).cells().size()];
                headerHeight = measured.rows().subList(0, row).stream().mapToDouble(MeasuredRow::height).sum();
                headerLines = measured.rows().subList(0, row).stream().mapToLong(MeasuredRow::lines).sum();
                suffixHeights = new double[measured.rows().size() + 1];
                for (int index = measured.rows().size() - 1; index >= 0; index--) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    suffixHeights[index] = suffixHeights[index + 1] + measured.rows().get(index).height();
                }
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            }

            @Override public boolean done() { return row == table.rows().size(); }
            @Override public int rowIndex() { return row; }
            @Override public int fragmentIndex() { return outputs; }

            @Override public double remainingHeight() {
                if (done()) { return 0; }
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                double remaining = headerHeight + suffixHeights[row];
                if (table.format().allowRowSplit()) {
                    double content = 0;
                    for (int column = 0; column < consumed.length; column++) {
                        int lines = measured.rows().get(row).cells().get(column).lines().size() - consumed[column];
                        content = Math.max(content, Math.max(1, lines) * table.rows().get(row).cells().get(column).format().lineHeight());
                    }
                    var padding = table.format().padding();
                    remaining = headerHeight + suffixHeights[row + 1] + content + padding.top() + padding.bottom();
                }
                if (!Double.isFinite(remaining)) {
                    throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, 0, outputs, row, -1, -1,
                            "remaining table height overflow", null);
                }
                return remaining;
            }

            @Override public Optional<TableFragment> take(double available) {
                if (!Double.isFinite(available) || available < 0 || !fits(available, settings.contentHeight())) {
                    throw new IllegalArgumentException("table take height must be finite and within the preparation viewport");
                }
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                if (done()) { return Optional.empty(); }
                int next = row;
                int[] offsets = consumed.clone();
                List<TableRowFragment> placed = new ArrayList<>();
                double height = headerHeight;
                long additionalLines = outputs == 0 ? 0 : headerLines;
                int budgetRow = 0;
                int budgetColumn = -1;
                boolean budgetExceeded = additionalLines > limits.maxLines() - lineCount;
                double headerY = 0;
                for (int header = 0; header < table.headerRows(); header++) {
                    appendRow(placed, table, header, measured.rows().get(header), measured.widths(), headerY, outputs > 0);
                    headerY += measured.rows().get(header).height();
                }
                boolean progressed = false;
                while (next < measured.rows().size()) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    if (!table.format().allowRowSplit()) {
                        if (!fits(height + groupHeight(measured, next), available)) { break; }
                        int end = measured.groupEnds()[next];
                        while (next < end) {
                            var original = measured.rows().get(next);
                            appendRow(placed, table, next, original, measured.widths(), height, false);
                            height += original.height();
                            next++;
                        }
                        progressed = true;
                    } else {
                        var candidate = splitRow(table, next, measured.rows().get(next), measured.widths(), offsets, available - height, height);
                        if (candidate.isEmpty()) { break; }
                        var slice = candidate.orElseThrow();
                        int column = 0;
                        for (int anchor = 0; anchor < offsets.length; anchor++) {
                            if (slice.taken()[anchor] == 0) {
                                additionalLines++;
                                if (!budgetExceeded && additionalLines > limits.maxLines() - lineCount) {
                                    budgetRow = next; budgetColumn = column; budgetExceeded = true;
                                }
                            }
                            offsets[anchor] += slice.taken()[anchor];
                            column += table.rows().get(next).cells().get(anchor).columnSpan();
                        }
                        placed.add(slice.fragment());
                        height += slice.height();
                        progressed = true;
                        if (!slice.complete()) { break; }
                        next++;
                        offsets = new int[next < table.rows().size() ? table.rows().get(next).cells().size() : 0];
                    }
                }
                if (!progressed) { return Optional.empty(); }
                if (outputs >= Math.min(limits.maxPages(), 100_000)) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, outputs, row, -1, 0,
                            "table occupied-region budget exceeded", null);
                }
                if (additionalLines > limits.maxLines() - lineCount) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, table, 0, outputs, budgetRow, budgetColumn, -1,
                            "document line budget exceeded", null);
                }
                var fragment = new TableFragment(table.id(), 0, new Point(0, 0),
                        java.util.Arrays.stream(measured.widths()).sum(), height, placed, table.format().drawBorders());
                row = next;
                consumed = offsets;
                outputs++;
                lineCount += additionalLines;
                return Optional.of(fragment);
            }
        }

        private void recursiveTable(Table table, int index) {
            var cursor = new RecursiveCursor(table, index);
            currentCapacity();
            double spacing = used == regionStart ? 0 : previousAfter + table.format().spaceBefore();
            while (!cursor.done()) {
                var part = cursor.take(Math.max(0, currentCapacity() - used - spacing));
                if (part.isEmpty()) {
                    if (emptyUnobstructedPage()) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index, pages.size(),
                                cursor.rowIndex(), -1, -1, "table headers and next complete cell content cannot fit", null);
                    }
                    nextRegion(table, index, cursor.rowIndex(), 0);
                    spacing = 0;
                    continue;
                }
                used += spacing;
                var placed = part.orElseThrow();
                elements.add(new TableFragment(table.id(), index, origin(), placed.width(), placed.height(), placed.rows(), placed.drawBorders()));
                used += placed.height();
                spacing = 0;
                if (!cursor.done()) { nextRegion(table, index, cursor.rowIndex(), 0); }
            }
            previousAfter = table.format().spaceAfter();
            tables.remove(index);
        }

        /** All tentative allocations operate on copies; only the outer accepted take charges budgets. */
        private final class RecursiveCursor implements TableCursor.State {
            private TableProgress progress;
            private final int index;

            private RecursiveCursor(Table table, int index) {
                this.index = index;
                progress = new TableProgress(table, measuredTable(table, index, false), index);
            }
            @Override public boolean done() { return progress.done(); }
            @Override public int rowIndex() { return progress.row; }
            @Override public int fragmentIndex() { return progress.outputs; }
            @Override public double remainingHeight() { return progress.remainingHeight(); }
            @Override public Optional<TableFragment> take(double height) {
                if (!Double.isFinite(height) || height < 0 || !fits(height, settings.contentHeight())) {
                    throw new IllegalArgumentException("table take height must be finite and within the preparation viewport");
                }
                var attempt = progress.take(height);
                if (attempt == null) { return Optional.empty(); }
                if (progress.outputs >= Math.min(limits.maxPages(), 100_000)) {
                    throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, progress.table, index,
                            progress.outputs, progress.row, -1, -1, "table occupied-region budget exceeded", null);
                }
                addLines(attempt.extraLines(), progress.table, index, progress.row, -1);
                progress = attempt.next();
                return Optional.of(attempt.fragment());
            }
        }

        private record TableAttempt(TableProgress next, TableFragment fragment, long extraLines) { }
        private record ContentAttempt(ContentProgress next, CellFragment.Content content, double height, long extraLines) { }

        private final class TableProgress {
            private final Table table;
            private final MeasuredTable measured;
            private final int index;
            private final TableGrid grid;
            private int row;
            private int outputs;
            private int rowParts;
            private List<ContentProgress> cells;
            private Map<Long, ContentProgress> spanning = Map.of();

            private TableProgress(Table table, MeasuredTable measured, int index) {
                this(table, measured, index, table.grid());
            }
            private TableProgress(Table table, MeasuredTable measured, int index, TableGrid grid) {
                this.table = table; this.measured = measured; this.index = index; row = table.headerRows();
                this.grid = grid;
            }
            private TableProgress copy() {
                var next = new TableProgress(table, measured, index, grid);
                next.row = row; next.outputs = outputs; next.rowParts = rowParts;
                next.cells = cells; next.spanning = spanning;
                return next;
            }
            private TableProgress reflow(MeasuredTable replacement) {
                var next = new TableProgress(table, replacement, index, grid);
                next.row = row; next.outputs = outputs; next.rowParts = rowParts;
                if (cells != null) {
                    var owners = owners();
                    var values = new ArrayList<ContentProgress>();
                    for (int at = 0; at < cells.size(); at++) {
                        var owner = owners.get(at);
                        var sourceRow = replacement.rows().get(owner.rowIndex());
                        values.add(cells.get(at).reflow(sourceRow.cells().get(sourceRow.anchors().indexOf(owner)).content()));
                    }
                    next.cells = List.copyOf(values);
                }
                var owners = new HashMap<Long, ContentProgress>();
                for (var entry : spanning.entrySet()) {
                    int ownerRow = (int) (entry.getKey() >>> 32);
                    int ownerColumn = (int) (long) entry.getKey();
                    var anchor = grid.ownerAt(ownerRow, ownerColumn);
                    var sourceRow = replacement.rows().get(ownerRow);
                    owners.put(entry.getKey(), entry.getValue().reflow(sourceRow.cells().get(sourceRow.anchors().indexOf(anchor)).content()));
                }
                next.spanning = Map.copyOf(owners);
                return next;
            }
            private boolean done() { return row == table.rows().size(); }
            private double minimumInitialHeight() {
                ExecutionContext.checkpoint();
                double height = 0;
                for (int header = 0; header < table.headerRows(); header++) {
                    ExecutionContext.checkpoint();
                    var source = measured.rows().get(header);
                    if (source.cells().stream().anyMatch(cell -> !contentFits(cell.content()))) {
                        return Double.POSITIVE_INFINITY;
                    }
                    height += source.height();
                }
                if (!table.format().allowRowSplit()) {
                    for (int at = row; at < measured.groupEnds()[row]; at++) {
                        ExecutionContext.checkpoint();
                        if (measured.rows().get(at).cells().stream().anyMatch(cell -> !contentFits(cell.content()))) {
                            return Double.POSITIVE_INFINITY;
                        }
                    }
                    return height + groupHeight(measured, row);
                }
                double content = 0;
                for (var owner : owners()) {
                    ExecutionContext.checkpoint();
                    var original = measured.rows().get(owner.rowIndex());
                    int ordinal = original.anchors().indexOf(owner);
                    content = Math.max(content, new ContentProgress(owner.cell(), original.cells().get(ordinal).content(), index).minimumInitialHeight());
                }
                return height + content + table.format().padding().top() + table.format().padding().bottom();
            }
            private double remainingHeight() {
                if (done()) { return 0; }
                double height = measured.rows().subList(0, table.headerRows()).stream().mapToDouble(MeasuredRow::height).sum();
                for (int at = row; at < measured.rows().size(); at++) { height += measured.rows().get(at).height(); }
                if (measured.hasSpans() && table.format().allowRowSplit()) {
                    height = measured.rows().subList(0, table.headerRows()).stream().mapToDouble(MeasuredRow::height).sum();
                    for (int at = row; at < measured.rows().size(); at++) {
                        double content = 0;
                        for (var anchor : measured.rows().get(at).anchors()) {
                            var saved = spanning.get(anchorKey(anchor));
                            var original = measured.rows().get(at);
                            int ordinal = original.anchors().indexOf(anchor);
                            content = Math.max(content, saved == null ? original.cells().get(ordinal).height() : saved.remainingHeight());
                        }
                        if (at == row && cells != null) { content = cells.stream().mapToDouble(ContentProgress::remainingHeight).max().orElse(0); }
                        if (content > 0) { height += content + table.format().padding().top() + table.format().padding().bottom(); }
                    }
                } else if (cells != null) {
                    height -= measured.rows().get(row).height();
                    height += cells.stream().mapToDouble(ContentProgress::remainingHeight).max().orElse(0)
                            + table.format().padding().top() + table.format().padding().bottom();
                }
                if (!Double.isFinite(height)) { throw new IllegalArgumentException("remaining table height overflow"); }
                return height;
            }
            private TableAttempt take(double available) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                if (done()) { return null; }
                var next = copy();
                var rows = new ArrayList<TableRowFragment>();
                double height = 0;
                long extras = 0;
                for (int header = 0; header < table.headerRows(); header++) {
                    var source = measured.rows().get(header);
                    if (retainedLineWidths != null && source.cells().stream().anyMatch(cell -> !contentFits(cell.content()))) { return null; }
                    appendRow(rows, table, header, source, measured.widths(), height, outputs > 0);
                    height += source.height();
                    if (outputs > 0) { extras += source.lines(); }
                }
                boolean progressed = false;
                while (!next.done()) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    if (!table.format().allowRowSplit()) {
                        if (!fits(height + groupHeight(measured, next.row), available)) { break; }
                        int end = measured.groupEnds()[next.row];
                        boolean valid = true;
                        if (retainedLineWidths != null) {
                            for (int at = next.row; at < end; at++) {
                                if (measured.rows().get(at).cells().stream().anyMatch(cell -> !contentFits(cell.content()))) { valid = false; break; }
                            }
                        }
                        if (!valid) { break; }
                        while (next.row < end) {
                            var source = measured.rows().get(next.row);
                            appendRow(rows, table, next.row++, source, measured.widths(), height, false);
                            height += source.height();
                        }
                        progressed = true;
                        continue;
                    }
                    var anchors = next.owners();
                    var padding = table.format().padding();
                    double inside = available - height - padding.top() - padding.bottom();
                    var current = next.cells;
                    if (current == null) {
                        current = new ArrayList<>();
                        for (var anchor : anchors) {
                            var saved = next.spanning.get(anchorKey(anchor));
                            var original = measured.rows().get(anchor.rowIndex());
                            int ordinal = original.anchors().indexOf(anchor);
                            current.add(saved == null ? new ContentProgress(anchor.cell(), original.cells().get(ordinal).content(), index) : saved);
                        }
                    }
                    var parts = new ArrayList<ContentAttempt>();
                    double contentHeight = 0;
                    for (int at = 0; at < current.size(); at++) {
                        var cell = current.get(at);
                        var part = cell.done() && anchors.get(at).rowIndex() < next.row
                                ? new ContentAttempt(cell, new CellFragment.Blocks(List.of(), List.of()), 0, 0) : cell.take(inside);
                        if (part == null) { parts.clear(); break; }
                        parts.add(part); contentHeight = Math.max(contentHeight, part.height());
                    }
                    if (parts.isEmpty()) { break; }
                    if (contentHeight == 0) { next.row++; next.cells = null; next.rowParts = 0; continue; }
                    double rowHeight = contentHeight + padding.top() + padding.bottom();
                    if (!fits(height + rowHeight, available)) { break; }
                    for (int at = 0; at < parts.size(); at++) {
                        var anchor = anchors.get(at);
                        double x = spannedWidth(measured.widths(), 0, anchor.columnIndex());
                        double width = spannedWidth(measured.widths(), anchor.columnIndex(), anchor.cell().columnSpan());
                        var part = parts.get(at);
                        var placed = new CellFragment(anchor.columnIndex(), new Rect(x, height, width, rowHeight),
                                new Point(x + padding.left(), height + padding.top()), part.content());
                        rows.add(new TableRowFragment(anchor.rowIndex(), false, false, List.of(placed),
                                next.rowParts > 0 || anchor.rowIndex() < next.row));
                        extras += part.extraLines();
                    }
                    height += rowHeight; progressed = true; next.rowParts++;
                    next.cells = parts.stream().map(ContentAttempt::next).toList();
                    if (next.cells.stream().anyMatch(cell -> !cell.done())) { break; }
                    var saved = new HashMap<Long, ContentProgress>();
                    for (int at = 0; at < anchors.size(); at++) {
                        var anchor = anchors.get(at);
                        if (anchor.rowIndex() + anchor.cell().rowSpan() > next.row + 1) {
                            saved.put(anchorKey(anchor), next.cells.get(at));
                        }
                    }
                    next.spanning = Map.copyOf(saved);
                    next.row++; next.cells = null; next.rowParts = 0;
                }
                if (!progressed) { return null; }
                next.outputs++;
                return new TableAttempt(next, new TableFragment(table.id(), index, new Point(0, 0),
                        java.util.Arrays.stream(measured.widths()).sum(), height, coalesceRows(rows), table.format().drawBorders()), extras);
            }

            private List<TableGrid.Anchor> owners() {
                if (!measured.hasSpans()) { return measured.rows().get(row).anchors(); }
                var result = new ArrayList<TableGrid.Anchor>();
                for (int column = 0; column < measured.widths().length;) {
                    var owner = grid.ownerAt(row, column);
                    result.add(owner); column += owner.cell().columnSpan();
                }
                return result;
            }
        }

        private long anchorKey(TableGrid.Anchor anchor) { return (long) anchor.rowIndex() << 32 | anchor.columnIndex(); }

        /** Extend a completed vertical owner through covered rows without drawing internal borders. */
        private List<TableRowFragment> coalesceRows(List<TableRowFragment> rows) {
            var result = new java.util.LinkedHashMap<Integer, TableRowFragment>();
            for (var row : rows) {
                var previous = result.get(row.rowIndex());
                if (previous == null) { result.put(row.rowIndex(), row); continue; }
                var cells = new ArrayList<>(previous.cells());
                for (var cell : row.cells()) {
                    int match = -1;
                    for (int at = 0; at < cells.size(); at++) { if (cells.get(at).columnIndex() == cell.columnIndex()) { match = at; break; } }
                    if (match < 0) { cells.add(cell); continue; }
                    var old = cells.get(match);
                    if (!cell.hasBlocks() || !cell.blocks().isEmpty()
                            || !fits(Math.abs(old.bounds().y() + old.bounds().height() - cell.bounds().y()), Math.ulp(cell.bounds().y()))) {
                        throw new IllegalStateException("noncontiguous vertical cell presentation");
                    }
                    cells.set(match, new CellFragment(old.columnIndex(), new Rect(old.bounds().x(), old.bounds().y(),
                            old.bounds().width(), cell.bounds().y() + cell.bounds().height() - old.bounds().y()), old.textOrigin(), old.content()));
                }
                cells.sort(java.util.Comparator.comparingInt(CellFragment::columnIndex));
                result.put(row.rowIndex(), new TableRowFragment(row.rowIndex(), row.header(), row.repeatedHeader(), cells, previous.continuedRow()));
            }
            return List.copyOf(result.values());
        }

        private final class ContentProgress {
            private final com.mirakyux.export4j.model.TableCell source;
            private final CellFragment.Content full;
            private final int index;
            private int block;
            private int line;
            private TableProgress nested;

            private ContentProgress(com.mirakyux.export4j.model.TableCell source, CellFragment.Content full, int index) {
                this.source = source; this.full = full; this.index = index;
            }
            private ContentProgress copy() {
                var next = new ContentProgress(source, full, index);
                next.block = block; next.line = line; next.nested = nested;
                return next;
            }
            private ContentProgress reflow(CellFragment.Content replacement) {
                var next = new ContentProgress(source, replacement, index);
                next.block = block; next.line = line;
                if (nested != null) {
                    var child = (Table) source.blocks().get(block);
                    next.nested = nested.reflow(cellTables.get(child.id()));
                }
                return next;
            }
            private boolean done() {
                return full instanceof CellFragment.Inline inline ? line == inline.layout().lines().size()
                        : block == ((CellFragment.Blocks) full).fragments().size();
            }
            private double minimumInitialHeight() {
                ExecutionContext.checkpoint();
                if (full instanceof CellFragment.Inline inline) {
                    return fittingLines(inline.layout(), 0, 1) == 1
                            ? source.format().lineHeight() : Double.POSITIVE_INFINITY;
                }
                var first = ((CellFragment.Blocks) full).fragments().getFirst();
                if (first instanceof ParagraphFragment paragraph) {
                    return minimumParagraphHeight((Paragraph) source.blocks().getFirst(), paragraph.layout(), 0);
                }
                var table = (Table) source.blocks().getFirst();
                return new TableProgress(table, cellTables.get(table.id()), index).minimumInitialHeight();
            }
            private double remainingHeight() {
                if (done()) { return 0; }
                if (full instanceof CellFragment.Inline inline) {
                    return (inline.layout().lines().size() - line) * source.format().lineHeight();
                }
                var fragments = ((CellFragment.Blocks) full).fragments();
                double result = 0;
                for (int at = block; at < fragments.size(); at++) {
                    var child = fragments.get(at);
                    if (at > block) { result += before(source.blocks().get(at)) + after(source.blocks().get(at - 1)); }
                    result += child instanceof ParagraphFragment paragraph
                            ? paragraph.layout().height() - (at == block ? line * ((Paragraph) source.blocks().get(at)).format().lineHeight() : 0)
                            : at == block && nested != null ? nested.remainingHeight() : ((TableFragment) child).height();
                }
                return result;
            }
            private ContentAttempt take(double available) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var next = copy();
                if (full instanceof CellFragment.Inline inline) {
                    var layout = inline.layout();
                    double step = source.format().lineHeight();
                    if (done()) {
                        if (!fits(step, available)) { return null; }
                        int end = layout.lines().getLast().sourceEnd();
                        var empty = new ParagraphLayout(layout.width(), step, List.of(new TextLine(end, end, 0,
                                layout.lines().getFirst().baselineY(), 0, List.of())), List.of());
                        return new ContentAttempt(next, new CellFragment.Inline(empty, line), step, 1);
                    }
                    int count = (int) Math.min(layout.lines().size() - line, Math.max(0, Math.floor(Math.nextUp(available / step))));
                    count = fittingLines(layout, line, count);
                    if (count == 0) { return null; }
                    next.line += count;
                    return new ContentAttempt(next, new CellFragment.Inline(fragment(layout, line, count, step), line), count * step, 0);
                }
                if (done()) { return new ContentAttempt(next, new CellFragment.Blocks(List.of(), List.of()), 0, 0); }
                var fullBlocks = ((CellFragment.Blocks) full).fragments();
                var placed = new ArrayList<LayoutFragment>();
                var ordinals = new ArrayList<Integer>();
                double height = 0;
                long extras = 0;
                while (!next.done()) {
                    int sourceBlock = next.block;
                    var child = source.blocks().get(next.block);
                    double spacing = placed.isEmpty() ? 0 : before(child) + after(source.blocks().get(next.block - 1));
                    double capacity = available - height - spacing;
                    LayoutFragment output;
                    double usedHeight;
                    if (child instanceof Paragraph paragraph) {
                        var shaped = ((ParagraphFragment) fullBlocks.get(next.block)).layout();
                        double step = paragraph.format().lineHeight();
                        int remaining = shaped.lines().size() - next.line;
                        int count = (int) Math.min(remaining, Math.max(0, Math.floor(Math.nextUp(capacity / step))));
                        count = fittingLines(shaped, next.line, count);
                        if (count < remaining) {
                            var policy = paragraph.format().pagination();
                            count = Math.min(count, remaining - policy.minLinesAfterBreak());
                            if (paragraph.format().keepTogether() || count < policy.minLinesBeforeBreak()) { count = 0; }
                        }
                        if (count == 0) { break; }
                        output = new ParagraphFragment(child.id(), index, next.line, new Point(0, height + spacing),
                                fragment(shaped, next.line, count, step));
                        usedHeight = count * step;
                        next.line += count;
                        if (next.line == shaped.lines().size()) { next.block++; next.line = 0; }
                    } else {
                        var table = (Table) child;
                        var current = next.nested == null ? new TableProgress(table, cellTables.get(table.id()), index) : next.nested;
                        var part = current.take(capacity);
                        if (part == null) { break; }
                        var f = part.fragment();
                        output = new TableFragment(f.blockId(), index, new Point(0, height + spacing), f.width(), f.height(), f.rows(), f.drawBorders());
                        usedHeight = f.height(); extras += part.extraLines(); next.nested = part.next();
                        if (next.nested.done()) { next.block++; next.nested = null; }
                    }
                    placed.add(output); ordinals.add(sourceBlock);
                    height += spacing + usedHeight;
                    if (next.line > 0 || next.nested != null) { break; }
                }
                if (placed.isEmpty()) { return null; }
                return new ContentAttempt(next, new CellFragment.Blocks(placed, ordinals), height, extras);
            }
        }

        private MeasuredCell measureCell(com.mirakyux.export4j.model.TableCell cell, double width,
                Table table, int index, int row, int column) {
            if (!cell.hasBlocks()) {
                try {
                    var layout = shapeCell(cell, width, table, index, row, column, true);
                    return new MeasuredCell(new CellFragment.Inline(layout, 0), layout.height(), layout.lines().size());
                } catch (DocumentLayoutException cause) {
                    throw cause.withSourcePath(cellPath(measuringPath, table, row, column, -1));
                }
            }
            var savedPath = measuringPath;
            var fragments = new ArrayList<LayoutFragment>();
            double height = 0;
            double previous = 0;
            long lines = 0;
            try {
                for (int ordinal = 0; ordinal < cell.blocks().size(); ordinal++) {
                    com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                    Block child = cell.blocks().get(ordinal);
                    measuringPath = cellPath(savedPath, table, row, column, ordinal);
                    double before = child instanceof Paragraph paragraph ? paragraph.format().spaceBefore()
                            : ((Table) child).format().spaceBefore();
                    double after = child instanceof Paragraph paragraph ? paragraph.format().spaceAfter()
                            : ((Table) child).format().spaceAfter();
                    if (!fragments.isEmpty()) { height += previous + before; }
                    try {
                        if (child instanceof Paragraph paragraph) {
                            var layout = shapeParagraph(paragraph, index, width);
                            fragments.add(new ParagraphFragment(child.id(), index, 0, new Point(0, height), layout));
                            height += layout.height();
                            lines += layout.lines().size();
                        } else if (child instanceof Table nested) {
                            var measured = prepareMeasuredTable(nested, index, width, false);
                            var nestedRows = new ArrayList<TableRowFragment>();
                            double nestedHeight = 0;
                            for (int innerRow = 0; innerRow < measured.rows().size(); innerRow++) {
                                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                                appendRow(nestedRows, nested, innerRow, measured.rows().get(innerRow),
                                        measured.widths(), nestedHeight, false);
                                nestedHeight += measured.rows().get(innerRow).height();
                                lines += measured.rows().get(innerRow).lines();
                            }
                            if (!measuringVariableTable && !table.format().allowRowSplit() && !fits(nestedHeight, settings.contentHeight())) {
                                throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, nested, index, pages.size(),
                                        row, column, -1, "atomic nested table exceeds a fresh content region", null);
                            }
                            fragments.add(new TableFragment(nested.id(), index, new Point(0, height),
                                    java.util.Arrays.stream(measured.widths()).sum(), nestedHeight, nestedRows, nested.format().drawBorders()));
                            height += nestedHeight;
                        } else {
                            throw failure(DocumentLayoutException.Code.UNSUPPORTED_COMPONENT, child, index,
                                    pages.size(), row, column, -1, "unsupported semantic cell block", null);
                        }
                        if (!measuringVariableTable && !table.format().allowRowSplit() && !fits(height, settings.contentHeight())) {
                            throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, child, index, pages.size(),
                                    row, column, -1, "atomic cell content exceeds a fresh content region", null);
                        }
                    } catch (DocumentLayoutException cause) {
                        if (cause.sourcePath().isEmpty() && cause.rowIndex() < 0) {
                            throw new DocumentLayoutException(cause.code(), cause.blockId(), index, cause.pageIndex(),
                                    row, column, cause.sourceIndex(), measuringPath, cause.getMessage(), cause);
                        }
                        throw cause.withSourcePath(measuringPath);
                    }
                    previous = after;
                }
            } finally { measuringPath = savedPath; }
            return new MeasuredCell(new CellFragment.Blocks(fragments), height, lines);
        }

        private ParagraphLayout shapeCell(com.mirakyux.export4j.model.TableCell cell, double width, Block block, int index, int row, int column, boolean countLines) {
            return shapeCell(cell, width, block, index, row, column, countLines, null);
        }

        private ParagraphLayout shapeCell(com.mirakyux.export4j.model.TableCell cell, double width, Block block,
                int index, int row, int column, boolean countLines, IntToDoubleFunction lineWidths) {
            if (lineWidths == null && retainedLineWidths != null) { lineWidths = retainedWidths(cell, width); }
            var format = cell.format();
            var style = new ParagraphStyle(format.fontSize(), format.lineHeight(), 0, 0, alignment(format.alignment()), format.lineBreakPolicy());
            if (lineWidths == null && cell.inlines().isEmpty() && cell.images().isEmpty() && cell.fields().isEmpty() && cell.vectors().isEmpty()) { return shape(cell.text(), width, style, block, index, row, column, countLines); }
            ParagraphLayout result;
            var sourceInlines = cell.inlines().isEmpty() ? List.of(new com.mirakyux.export4j.model.InlineText(cell.text(),
                    com.mirakyux.export4j.model.InlineStyle.of(format.fontSize()))) : cell.inlines();
            var spans = styledSpans(sourceInlines, block, index, row, column);
            try {
                var imageMap = cell.images().stream().collect(java.util.stream.Collectors.toMap(Paragraph.ImageSpan::sourceIndex, Paragraph.ImageSpan::image));
                var fieldMap = cell.fields().stream().collect(java.util.stream.Collectors.toMap(Paragraph.FieldSpan::sourceIndex, Paragraph.FieldSpan::field));
                var graphics = InlineVectors.graphics(cell.vectors());
                result = retainedLineWidths != null ? shapeRetained(cell, width, spans, style, imageMap, fieldMap, graphics)
                        : paragraphLayouter.layout(spans, width, style, imageMap, fieldMap, graphics, lineWidths == null ? line -> width : lineWidths);
            } catch (DocumentLayoutException cause) {
                throw cause;
            } catch (TextLayoutException cause) {
                throw failure(cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED,
                        block, index, pages.size(), row, column, cause.sourceIndex(), "rich cell text layout failed", cause);
            } catch (IllegalArgumentException cause) {
                throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, block, index, pages.size(), row, column, -1, "rich cell geometry or font role is invalid", cause);
            }
            if (retainedLineWidths != null) { result = new ParagraphLayout(width, result.height(), result.lines(), result.diagnostics()); }
            if (countLines) { addLines(result.lines().size(), block, index, row, column); }
            return simpleLayouts == null ? result : simpleLayouts.reuse(result);
        }

        private List<StyledSpan> styledSpans(List<com.mirakyux.export4j.model.InlineText> inlines,
                Block block, int index, int row, int column) {
            List<StyledSpan> result = new ArrayList<>(inlines.size());
            int sourceOffset = 0;
            for (var inline : inlines) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var ink = inline.style();
                var group = ink.fontRole().isEmpty() ? fontGroup : roles.get(ink.fontRole());
                if (group == null) {
                    throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, block, index, pages.size(),
                            row, column, sourceOffset, "unregistered inline font role", null);
                }
                result.add(new StyledSpan(inline.text(), group, ink.fontSize(), ink.rgb(),
                        ink.baselineShift(), ink.underline(), ink.strikeThrough()));
                sourceOffset += inline.text().length();
            }
            return result;
        }

        private ParagraphLayout shape(String text, double width, ParagraphStyle style, Block block,
                int index, int row, int column) {
            return shape(text, width, style, block, index, row, column, true);
        }

        private ParagraphLayout shape(String text, double width, ParagraphStyle style, Block block,
                int index, int row, int column, boolean countLines) {
            ParagraphLayout result;
            try {
                result = paragraphLayouter.layout(text, fontGroup, width, style);
            } catch (TextLayoutException cause) {
                DocumentLayoutException.Code code = cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED
                        ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED;
                throw failure(code, block, index, pages.size(), row, column, cause.sourceIndex(),
                        "block text layout failed", cause);
            } catch (IllegalArgumentException cause) {
                throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, block, index, pages.size(),
                        row, column, -1, "text geometry is invalid for the content area", cause);
            }
            if (countLines) { addLines(result.lines().size(), block, index, row, column); }
            return simpleLayouts == null ? result : simpleLayouts.reuse(result);
        }

        private void addLines(long count, Block block, int index, int row, int column) {
            if (suppressLineCharge) { return; }
            long maximum = speculativeLines ? 4_000_000L : limits.maxLines();
            if (count > maximum - lineCount) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(), row, column,
                        0, speculativeLines ? "continuous columns candidate line budget exceeded"
                                : "document line budget exceeded, including repeated headers", null);
            }
            lineCount += count;
        }

        private void requireHeight(double height, Block block, int index, int row) {
            if (!fits(height, settings.contentHeight())) {
                throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, block, index, pages.size(), row,
                        -1, 0, "indivisible content exceeds a fresh page's content height", null);
            }
        }

        private void beginBlock(double required, double before, Block block, int index, int row) {
            currentCapacity();
            double spacing = used == regionStart ? 0 : previousAfter + before;
            while (!fits(spacing + required, currentCapacity() - used)) {
                if (emptyUnobstructedPage()) {
                    throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, block, index, pages.size(), row,
                            -1, 0, "content cannot fit a fresh unobstructed page", null);
                }
                nextRegion(block, index, row, 0);
                spacing = 0;
            }
            used += spacing;
        }

        private double currentCapacity() {
            while (exclusionIndex < exclusions.size() && exclusions.get(exclusionIndex)[0] <= used) {
                used = Math.max(used, exclusions.get(exclusionIndex++)[1]);
                regionStart = used;
                previousAfter = 0;
            }
            return exclusionIndex < exclusions.size() ? exclusions.get(exclusionIndex)[0] : settings.contentHeight();
        }

        private boolean emptyUnobstructedPage() {
            return elements.isEmpty() && used == 0 && exclusions.isEmpty();
        }

        private void nextRegion(Block block, int index, int row, int source) {
            ExecutionContext.checkpoint();
            if (exclusionIndex < exclusions.size()) {
                used = exclusions.get(exclusionIndex++)[1];
                regionStart = used;
                previousAfter = 0;
                currentCapacity();
            } else {
                nextPage(block, index, row, source);
            }
        }

        private void loadExclusions() {
            exclusions = readExclusions(pages.size()); exclusionIndex = 0; regionStart = 0;
            currentCapacity();
        }

        private List<double[]> readExclusions(int page) {
            var cached = exclusionPages.get(page);
            if (cached != null) { return cached; }
            var supplied = List.copyOf(exclusionSource.apply(page));
            if (supplied.size() > 64) { throw new IllegalArgumentException("body exclusion budget exceeded"); }
            var intervals = new ArrayList<double[]>();
            double top = settings.margins().top(), bottom = settings.size().height() - settings.margins().bottom();
            for (var area : supplied) {
                if (area.x() < 0 || area.y() < 0 || area.x() + area.width() > Math.nextUp(settings.size().width())
                        || area.y() + area.height() > Math.nextUp(settings.size().height())) {
                    throw new IllegalArgumentException("body exclusion exceeds physical page bounds");
                }
                if (area.x() >= settings.size().width() - settings.margins().right()
                        || area.x() + area.width() <= settings.margins().left()) { continue; }
                double start = Math.max(top, area.y()) - top;
                double end = Math.min(bottom, area.y() + area.height()) - top;
                if (start < end) { intervals.add(new double[] {start, end}); }
            }
            intervals.sort(java.util.Comparator.comparingDouble(interval -> interval[0]));
            var merged = new ArrayList<double[]>();
            for (var interval : intervals) {
                if (!merged.isEmpty() && interval[0] <= merged.getLast()[1]) {
                    merged.getLast()[1] = Math.max(merged.getLast()[1], interval[1]);
                } else { merged.add(interval); }
            }
            var result = List.copyOf(merged);
            exclusionPages.put(page, result);
            return result;
        }

        private void nextPage(Block block, int index, int row, int source) {
            if (pages.size() + 1 >= limits.maxPages()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size() + 1,
                        row, -1, source, "document page budget exceeded", null);
            }
            pages.add(page());
            elements.clear();
            blocks.releaseBefore(activeBlockIndex);
            if (pages.streaming()) { exclusionPages.keySet().removeIf(page -> page < pages.size()); }
            used = 0;
            previousAfter = 0;
            loadExclusions();
        }

        private LayoutPage page() {
            return new LayoutPage(pages.size(), settings.size(), new Rect(settings.margins().left(),
                    settings.margins().top(), settings.contentWidth(), settings.contentHeight()), elements);
        }

        private Point origin() {
            return new Point(settings.margins().left(), settings.margins().top() + used);
        }
    }

    /** Confined to a collected flow; exact immutable geometry has no source identity.
     * Eviction drops only cache references, while returned IR owns its geometry references.
     */
    private static final class CellGeometryValues {
        private static final int MAX_ENTRIES = 2048;
        private final LinkedHashMap<Rect, Rect> bounds = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<Point, Point> origins = new LinkedHashMap<>(16, 0.75f, true);

        private Rect reuse(Rect value) {
            var existing = bounds.get(value);
            if (existing != null) { return existing; }
            if (bounds.size() == MAX_ENTRIES) { bounds.pollFirstEntry(); }
            bounds.put(value, value);
            return value;
        }

        private Point reuse(Point value) {
            var existing = origins.get(value);
            if (existing != null) { return existing; }
            if (origins.size() == MAX_ENTRIES) { origins.pollFirstEntry(); }
            origins.put(value, value);
            return value;
        }
    }

    private record MeasuredRow(double height, long lines, List<MeasuredCell> cells,
            List<TableGrid.Anchor> anchors, List<Double> cellHeights) {
    }

    private record MeasuredCell(CellFragment.Content content, double height, long lineCount) {
        private ParagraphLayout layout() {
            if (content instanceof CellFragment.Inline inline) { return inline.layout(); }
            throw new IllegalStateException("atomic block cell cannot be split as a paragraph");
        }
        private List<TextLine> lines() { return layout().lines(); }
        private double width() { return layout().width(); }
    }

    private record MeasuredTable(double[] widths, List<MeasuredRow> rows, int[] groupEnds, boolean hasSpans) {
    }

    private record HeightConstraint(int row, int column, int span, double height) { }

    /** One range per transitive set of crossed row boundaries; independent of content order. */
    private static int[] groupEnds(TableGrid grid, int rowCount) {
        int[] ends = new int[rowCount];
        for (int row = 0; row < rowCount; row++) {
            ends[row] = row + 1;
            for (var anchor : grid.anchorRows().get(row)) {
                ends[row] = Math.max(ends[row], row + anchor.cell().rowSpan());
            }
        }
        for (int start = 0; start < rowCount;) {
            int end = ends[start];
            for (int row = start + 1; row < end; row++) { end = Math.max(end, ends[row]); }
            for (int row = start; row < end; row++) { ends[row] = end; }
            start = end;
        }
        return ends;
    }

    private static double groupHeight(MeasuredTable measured, int start) {
        double height = 0;
        for (int row = start; row < measured.groupEnds()[start]; row++) { height += measured.rows().get(row).height(); }
        return height;
    }

    private record RowSlice(TableRowFragment fragment, double height, int[] taken, boolean complete) {
    }

    private static double spannedWidth(double[] widths, int start, int span) {
        double width = 0;
        for (int column = start; column < start + span; column++) { width += widths[column]; }
        return width;
    }

    private static TextAlignment alignment(ParagraphAlignment alignment) {
        return switch (alignment) {
            case LEFT -> TextAlignment.LEFT;
            case CENTER -> TextAlignment.CENTER;
            case RIGHT -> TextAlignment.RIGHT;
            case JUSTIFY -> TextAlignment.JUSTIFY;
        };
    }

    static ParagraphLayout fragment(ParagraphLayout shaped, int first, int count, double lineHeight) {
        List<TextLine> lines = null;
        double ascent = shaped.lines().getFirst().baselineY();
        for (int offset = 0; offset < count; offset++) {
            TextLine original = shaped.lines().get(first + offset);
            double baseline = offset * lineHeight + ascent;
            boolean unchanged = Double.doubleToLongBits(original.baselineY()) == Double.doubleToLongBits(baseline);
            if (!unchanged && lines == null) {
                lines = new ArrayList<>(count);
                lines.addAll(shaped.lines().subList(first, first + offset));
            }
            if (lines != null) { lines.add(unchanged ? original : original.atBaseline(baseline)); }
        }
        if (lines == null) {
            lines = first == 0 && count == shaped.lines().size() ? shaped.lines() : shaped.lines().subList(first, first + count);
        }
        int sourceStart = lines.getFirst().sourceStart();
        int sourceEnd = lines.getLast().sourceEnd();
        var diagnostics = shaped.diagnostics();
        for (var diagnostic : diagnostics) {
            if (diagnostic.sourceStart() < sourceStart || diagnostic.sourceStart() >= sourceEnd) {
                diagnostics = shaped.diagnostics().stream()
                        .filter(value -> value.sourceStart() >= sourceStart && value.sourceStart() < sourceEnd)
                        .toList();
                break;
            }
        }
        double height = count * lineHeight;
        // Whole paragraphs commonly reach this method after value pooling. Preserve that sharing
        // only when slicing would leave every line, dimension and diagnostic exactly unchanged.
        if (lines == shaped.lines() && diagnostics == shaped.diagnostics()
                && Double.doubleToLongBits(height) == Double.doubleToLongBits(shaped.height())) { return shaped; }
        return new ParagraphLayout(shaped.width(), height, lines, diagnostics);
    }

    private static long vectorWork(List<Paragraph.VectorSpan> vectors) {
        long work = 0;
        for (var span : vectors) {
            ExecutionContext.checkpoint();
            work += span.vector().resource().segmentCount() + (long) span.vector().logicalText().length();
        }
        return work;
    }

    private static boolean fits(double height, double available) {
        // One ULP absorbs arithmetic rounding, never a visible overflow.
        return Double.isFinite(height) && height <= Math.nextUp(available);
    }

    private static List<CellLocation> cellPath(List<CellLocation> parent, Table table, int row, int column, int ordinal) {
        var result = new ArrayList<CellLocation>(parent);
        result.add(new CellLocation(table.id(), row, column, ordinal));
        return List.copyOf(result);
    }

    private static List<String> sourceIds(List<? extends Block> roots) {
        var ids = new ArrayList<String>();
        SemanticTree.walk(roots, visit -> ids.add(visit.block().id()));
        return List.copyOf(ids);
    }

    private static DocumentLayoutException failure(DocumentLayoutException.Code code, Block block,
            int index, int page, int row, int column, int source, String message, Throwable cause) {
        return new DocumentLayoutException(code, block == null ? "" : block.id(), index,
                page, row, column, source, message, cause);
    }
}
