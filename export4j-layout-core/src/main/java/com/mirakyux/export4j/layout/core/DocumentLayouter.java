// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Rect;
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
import com.mirakyux.export4j.model.ColumnWidth;
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
     * initial regions do not count. At most 16 initial regions and 100,016 logical indices are permitted.
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
     * complete-line continuation of inline cells; split vertical spans and block cells remain unsupported.
     *
     * <p>Source budgets apply once. Each distinct width is prepared at most once, with at most 4,000,000
     * cumulative source UTF-16 units and this layouter's line limit per prepared alternative. Only selected
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
        if (table.format().allowRowSplit() && (table.hasRowSpans() || table.hasBlockCells())) {
            throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, 0, 0, table.headerRows(), -1, -1,
                    "variable-width split rows do not support vertical spans or block cells", null);
        }
        var document = new Document(DocumentMetadata.EMPTY, new PageSettings(
                new PageSize(context.width(), regions.continuationHeight()), PageMargins.uniform(0)), List.of(table));
        var flow = new Flow(document, context.fontGroup(), context.fontRoles(), preflight(document));
        return table.format().allowRowSplit() ? flow.new VariableInlineTable(table, regions, widths).layout()
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
                    }
                    case Heading heading -> codePoints = countText(heading.text(), codePoints, block, index, -1, -1);
                    case PageBreak ignored -> { }
                    case Table table -> {
                        long count = (long) table.rows().size() * table.columns().size();
                        if (table.rows().size() > limits.maxTableRows() || table.columns().size() > limits.maxTableColumns()
                                || count > limits.maxCells() - cells) {
                            throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, -1, -1, -1, -1,
                                    "table structural budget exceeded", null);
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
        private final List<LayoutPage> pages = new ArrayList<>();
        private final List<LayoutFragment> elements = new ArrayList<>();
        private final List<Block> blocks;
        private final Map<String, FontGroup> roles;
        private final FontGroup fontGroup;
        private final Map<Integer, ParagraphLayout> paragraphs = new HashMap<>();
        private final Map<Integer, MeasuredTable> tables = new HashMap<>();
        private final Map<String, MeasuredTable> cellTables = new HashMap<>();
        private final Map<Integer, List<ComponentLayout>> components = new HashMap<>();
        private boolean pendingBreak;
        private double used;
        private double previousAfter;
        private long lineCount;
        private long codePoints;
        private long componentCommands;
        private record NestedSource(Table source, int hostIndex) { }
        private final List<NestedSource> nestedSources = new ArrayList<>();
        private final SemanticSourceRegistry semanticSources;
        private long semanticPlacements;
        private List<CellLocation> measuringPath = List.of();
        private boolean measuringVariableTable;
        private int keptThrough = -1;
        private Flow(Document document, FontGroup group, Map<String, FontGroup> roles, long codePoints) {
            this.document = document;
            blocks = document.blocks().stream().map(block -> block instanceof Heading heading ? heading.paragraph() : block).toList();
            settings = document.pageSettings();
            this.fontGroup = group;
            this.roles = Map.copyOf(roles);
            this.codePoints = codePoints;
            semanticSources = new SemanticSourceRegistry(sourceIds(document.blocks()));
        }

        private DocumentLayout layout() {
            for (int index = 0; index < blocks.size(); index++) {
                com.mirakyux.export4j.model.ExecutionContext.progress("BODY", blocks.get(index).id(), pages.size(), index, blocks.size());
                Block block = blocks.get(index);
                if (block instanceof PageBreak) {
                    pendingBreak = !elements.isEmpty();
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
                    case ComponentBlock component -> component(component, index);
                    default -> throw new IllegalStateException("preflight must reject unknown blocks");
                }
            }
            pages.add(page());
            if (!nestedSources.isEmpty()) {
                var budget = new SourceBudget();
                SemanticTree.walk(document.blocks(), budget::accept);
                for (var nested : nestedSources) {
                    SemanticTree.walk(nested.source(), visit -> budget.accept(
                            new SemanticTree.Visit(visit.block(), nested.hostIndex(), visit.cellPath())));
                }
            }
            return new DocumentLayout(document.metadata(), pages);
        }

        private void component(ComponentBlock block, int index) {
            var parts = measuredComponents(block, index);
            for (int part = 0; part < parts.size(); part++) {
                var shaped = parts.get(part);
                if (part == 0) {
                    beginBlock(shaped.height(), block.spaceBefore(), block, index, -1);
                } else {
                    nextPage(block, index, -1, 0);
                }
                elements.add(new ComponentFragment(block.id(), index, origin(), shaped));
                used += shaped.height();
            }
            previousAfter = block.spaceAfter();
            components.remove(index);
        }

        private List<ComponentLayout> measuredComponents(ComponentBlock block, int index) {
            double spacing = elements.isEmpty() ? 0 : previousAfter + block.spaceBefore();
            return measuredComponents(block, index, Math.max(0, settings.contentHeight() - used - spacing));
        }

        private List<ComponentLayout> measuredComponents(ComponentBlock block, int index, double firstHeight) {
            if (components.containsKey(index)) {
                return components.get(index);
            }
            List<ComponentLayout> parts;
            try {
                var context = new ComponentContext(settings.contentWidth(), settings.contentHeight(), fontGroup, pages.size(), 0, pages.size() + 1, roles);
                parts = block.component() instanceof PageableComponent pageable ? List.copyOf(pageable.layoutParts(context, firstHeight))
                        : List.of(block.component().layout(context));
                if (parts.isEmpty() || parts.size() > limits.maxPages()) {
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
            if (shaped.staticCommands().size() > componentLimits.maxCommands() - componentCommands) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(),
                        -1, -1, -1, "component command budget exceeded", null);
            }
            componentCommands += shaped.staticCommands().size();
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
                nestedSources.add(new NestedSource(placed.source(), index));
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
            for (var command : shaped.staticCommands()) {
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
            }
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
                double available = Math.max(0, settings.contentHeight() - used);
                int take = whole ? remaining
                        : (int) Math.min(remaining, Math.floor(Math.nextUp(available / format.lineHeight())));
                if (take < remaining) {
                    take = Math.min(take, remaining - format.pagination().minLinesAfterBreak());
                    if (take < format.pagination().minLinesBeforeBreak()) {
                        take = 0;
                    }
                }
                if (take == 0) {
                    if (elements.isEmpty()) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, paragraph, index,
                                pages.size(), -1, -1, shaped.lines().get(firstLine).sourceStart(),
                                "paragraph fragment constraints cannot fit a fresh page", null);
                    }
                    nextPage(paragraph, index, -1, shaped.lines().get(firstLine).sourceStart());
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
                if (!paragraph.inlines().isEmpty() || !paragraph.images().isEmpty() || !paragraph.fields().isEmpty()) {
                    ParagraphLayout result;
                    var sourceInlines = paragraph.inlines().isEmpty() ? List.of(new com.mirakyux.export4j.model.InlineText(paragraph.text(),
                            com.mirakyux.export4j.model.InlineStyle.of(format.fontSize()))) : paragraph.inlines();
                    var spans = styledSpans(sourceInlines, paragraph, index, -1, -1);
                    try {
                        result = paragraphLayouter.layout(spans, width, new ParagraphStyle(format.fontSize(),
                                format.lineHeight(), format.firstLineIndent(), format.subsequentLineIndent(), alignment(format.alignment()), format.lineBreakPolicy()),
                                paragraph.images().stream().collect(java.util.stream.Collectors.toMap(Paragraph.ImageSpan::sourceIndex, Paragraph.ImageSpan::image)),
                                paragraph.fields().stream().collect(java.util.stream.Collectors.toMap(Paragraph.FieldSpan::sourceIndex, Paragraph.FieldSpan::field)));
                    } catch (TextLayoutException cause) {
                        DocumentLayoutException.Code code = cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED
                                ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED;
                        throw failure(code, paragraph, index, pages.size(), -1, -1, cause.sourceIndex(),
                                "rich text layout failed", cause);
                    } catch (IllegalArgumentException cause) {
                        throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, paragraph, index, pages.size(),
                                -1, -1, -1, "rich text font role or geometry is invalid", cause);
                    }
                    addLines(result.lines().size(), paragraph, index, -1, -1);
                    return result;
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
            for (int next = index; next < blocks.size(); next++) {
                Block block = blocks.get(next);
                if (block instanceof PageBreak || next > index && block instanceof Paragraph p && p.format().pagination().pageBreakBefore()) {
                    break;
                }
                height += next == index ? 0 : after + before(block);
                boolean linked = block instanceof Paragraph p && p.format().pagination().keepWithNext();
                height += minimumHeight(block, next, linked, Math.max(0, settings.contentHeight() - height));
                keptThrough = next;
                after = after(block);
                if (!linked) {
                    break;
                }
            }
            requireHeight(height, first, index, -1);
            double spacing = elements.isEmpty() ? 0 : previousAfter + first.format().spaceBefore();
            if (!fits(spacing + height, settings.contentHeight() - used) && !elements.isEmpty()) {
                nextPage(first, index, -1, 0);
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
                case ComponentBlock c -> measuredComponents(c, index, firstHeight).getFirst().height();
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
                elements.add(new TableFragment(table.id(), index, start, tableWidth, height, placed));
                used += height;
                if (body < rows.size()) {
                    nextPage(table, index, body, 0);
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
                    double preferred = auto.minPoints();
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
                    autoMinimum += auto.minPoints();
                    autoPreferred += automatic[column];
                }
            }
            if (autoPreferred > 0) {
                preferredMergedWidths(table, grid, index, availableWidth, automatic);
                autoPreferred = 0;
                for (double value : automatic) { autoPreferred += value; }
            }
            double autoSpace = availableWidth - fixed - (autoPreferred > 0 ? fractionMinimum : 0);
            if (autoMinimum > autoSpace) { throw failure(DocumentLayoutException.Code.INVALID_GEOMETRY, table, index, pages.size(), -1, -1, -1, "minimum column widths exceed page", null); }
            double ratio = autoPreferred > autoMinimum ? Math.min(1, (autoSpace - autoMinimum) / (autoPreferred - autoMinimum)) : 1;
            double autoUsed = 0;
            for (int column = 0; column < automatic.length; column++) {
                if (table.columns().get(column) instanceof ColumnWidth.Auto auto) {
                    automatic[column] = auto.minPoints() + (automatic[column] - auto.minPoints()) * ratio;
                    autoUsed += automatic[column];
                }
            }
            double remaining = availableWidth - fixed - autoUsed;
            if (!Double.isFinite(fixed) || fixed > availableWidth || maxWeight > 0 && remaining <= 0) {
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
                cells.add(new CellFragment(column, new Rect(x, y, width, measured.cellHeights().get(anchor)),
                        new Point(x + padding.left(), y + padding.top()), measured.cells().get(anchor).content()));
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
                elements.add(new TableFragment(table.id(), index, start, tableWidth, height, placed));
                used += height;
                if (row < table.rows().size()) { nextPage(table, index, row, 0); repeated = true; }
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
                    if (visit.block() instanceof Paragraph paragraph) { sourceUnits += paragraph.text().length(); }
                    else if (visit.block() instanceof Table nested) {
                        for (var anchors : nested.grid().anchorRows()) {
                            for (var anchor : anchors) {
                                if (!anchor.cell().hasBlocks()) { sourceUnits += anchor.cell().text().length(); }
                            }
                        }
                    }
                });
            }

            private void account(long units, int row) {
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
                        java.util.Arrays.stream(measured.widths()).sum(), height, placed)));
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
                for (int row = table.headerRows(); row < table.rows().size(); row++) {
                    ExecutionContext.checkpoint();
                    var sources = table.rows().get(row).cells();
                    double minimumStep = sources.stream().mapToDouble(cell -> cell.format().lineHeight()).max().orElseThrow();
                    double minimum = minimumStep + table.format().padding().top() + table.format().padding().bottom();
                    while (true) {
                        begin(row);
                        if (fits(height + minimum, regions.height(region))) { break; }
                        if (!body && region >= regions.initialHeights().size()) { throw tooTall(row, -1); }
                        advance(row);
                    }
                    var capacities = new RowCapacities(row, region, height, minimumStep);
                    var layouts = new ArrayList<ParagraphLayout>();
                    int column = 0;
                    for (var cell : sources) {
                        preparations.account(cell.text().length(), row);
                        var lineWidths = new CellWidths(column, cell.columnSpan(), cell.format().lineHeight(), capacities);
                        try { layouts.add(shapeCell(cell, settings.contentWidth(), table, 0, row, column, false, lineWidths)); }
                        catch (DocumentLayoutException cause) { throw cause.withSourcePath(cellPath(List.of(), table, row, column, -1)); }
                        column += cell.columnSpan();
                    }
                    int[] consumed = new int[sources.size()];
                    boolean continued = false;
                    int part = 0;
                    while (true) {
                        ExecutionContext.checkpoint();
                        var capacity = capacities.at(part++);
                        while (region < capacity.region()) { advance(row); }
                        begin(row);
                        double inside = capacity.inside();
                        double rowHeight = 0;
                        int[] taken = new int[sources.size()];
                        boolean complete = true;
                        for (int anchor = 0; anchor < sources.size(); anchor++) {
                            double step = sources.get(anchor).format().lineHeight();
                            int remaining = layouts.get(anchor).lines().size() - consumed[anchor];
                            taken[anchor] = (int) Math.min(remaining, Math.floor(Math.nextUp(inside / step)));
                            complete &= taken[anchor] == remaining;
                            rowHeight = Math.max(rowHeight, Math.max(1, taken[anchor]) * step);
                        }
                        rowHeight += table.format().padding().top() + table.format().padding().bottom();
                        var cells = new ArrayList<CellFragment>();
                        column = 0;
                        for (int anchor = 0; anchor < sources.size(); anchor++) {
                            var cell = sources.get(anchor);
                            var source = layouts.get(anchor);
                            double x = spannedWidth(active.widths(), 0, column);
                            double width = spannedWidth(active.widths(), column, cell.columnSpan());
                            double step = cell.format().lineHeight();
                            ParagraphLayout slice;
                            if (taken[anchor] == 0) {
                                int end = source.lines().getLast().sourceEnd();
                                slice = new ParagraphLayout(source.width(), step, List.of(new TextLine(end, end, 0,
                                        source.lines().getFirst().baselineY(), 0, List.of())), List.of());
                            } else { slice = fragment(source, consumed[anchor], taken[anchor], step); }
                            slice = new ParagraphLayout(width - table.format().padding().left() - table.format().padding().right(),
                                    slice.height(), slice.lines(), slice.diagnostics());
                            cells.add(new CellFragment(column, new Rect(x, height, width, rowHeight),
                                    new Point(x + table.format().padding().left(), height + table.format().padding().top()), slice, consumed[anchor]));
                            consumed[anchor] += taken[anchor];
                            column += cell.columnSpan();
                        }
                        placed.add(new TableRowFragment(row, false, false, cells, continued));
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
                        java.util.Arrays.stream(active.widths()).sum(), height, placed)));
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
                        java.util.Arrays.stream(measured.widths()).sum(), height, placed);
                row = next;
                consumed = offsets;
                outputs++;
                lineCount += additionalLines;
                return Optional.of(fragment);
            }
        }

        private void recursiveTable(Table table, int index) {
            var cursor = new RecursiveCursor(table, index);
            double spacing = elements.isEmpty() ? 0 : previousAfter + table.format().spaceBefore();
            while (!cursor.done()) {
                var part = cursor.take(Math.max(0, currentCapacity() - used - spacing));
                if (part.isEmpty()) {
                    if (elements.isEmpty()) {
                        throw failure(DocumentLayoutException.Code.CONTENT_TOO_TALL, table, index, pages.size(),
                                cursor.rowIndex(), -1, -1, "table headers and next complete cell content cannot fit", null);
                    }
                    nextPage(table, index, cursor.rowIndex(), 0);
                    spacing = 0;
                    continue;
                }
                used += spacing;
                var placed = part.orElseThrow();
                elements.add(new TableFragment(table.id(), index, origin(), placed.width(), placed.height(), placed.rows()));
                used += placed.height();
                spacing = 0;
                if (!cursor.done()) { nextPage(table, index, cursor.rowIndex(), 0); }
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
            private boolean done() { return row == table.rows().size(); }
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
                        java.util.Arrays.stream(measured.widths()).sum(), height, coalesceRows(rows)), extras);
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
            private boolean done() {
                return full instanceof CellFragment.Inline inline ? line == inline.layout().lines().size()
                        : block == ((CellFragment.Blocks) full).fragments().size();
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
                        output = new TableFragment(f.blockId(), index, new Point(0, height + spacing), f.width(), f.height(), f.rows());
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
                                    java.util.Arrays.stream(measured.widths()).sum(), nestedHeight, nestedRows));
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
            var format = cell.format();
            var style = new ParagraphStyle(format.fontSize(), format.lineHeight(), 0, 0, alignment(format.alignment()), format.lineBreakPolicy());
            if (lineWidths == null && cell.inlines().isEmpty() && cell.images().isEmpty() && cell.fields().isEmpty()) { return shape(cell.text(), width, style, block, index, row, column, countLines); }
            ParagraphLayout result;
            var sourceInlines = cell.inlines().isEmpty() ? List.of(new com.mirakyux.export4j.model.InlineText(cell.text(),
                    com.mirakyux.export4j.model.InlineStyle.of(format.fontSize()))) : cell.inlines();
            var spans = styledSpans(sourceInlines, block, index, row, column);
            try {
                result = paragraphLayouter.layout(spans, width, style,
                        cell.images().stream().collect(java.util.stream.Collectors.toMap(Paragraph.ImageSpan::sourceIndex, Paragraph.ImageSpan::image)),
                        cell.fields().stream().collect(java.util.stream.Collectors.toMap(Paragraph.FieldSpan::sourceIndex, Paragraph.FieldSpan::field)),
                        lineWidths == null ? line -> width : lineWidths);
            } catch (DocumentLayoutException cause) {
                throw cause;
            } catch (TextLayoutException cause) {
                throw failure(cause.code() == TextLayoutException.Code.BUDGET_EXCEEDED ? DocumentLayoutException.Code.BUDGET_EXCEEDED : DocumentLayoutException.Code.TEXT_LAYOUT_FAILED,
                        block, index, pages.size(), row, column, cause.sourceIndex(), "rich cell text layout failed", cause);
            } catch (IllegalArgumentException cause) {
                throw failure(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, block, index, pages.size(), row, column, -1, "rich cell geometry or font role is invalid", cause);
            }
            if (countLines) { addLines(result.lines().size(), block, index, row, column); }
            return result;
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
            return result;
        }

        private void addLines(long count, Block block, int index, int row, int column) {
            if (count > limits.maxLines() - lineCount) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size(), row, column,
                        0, "document line budget exceeded, including repeated headers", null);
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
            double spacing = elements.isEmpty() ? 0 : previousAfter + before;
            if (!fits(spacing + required, currentCapacity() - used)) {
                nextPage(block, index, row, 0);
                spacing = 0;
            }
            used += spacing;
        }

        private double currentCapacity() {
            return settings.contentHeight();
        }

        private void nextPage(Block block, int index, int row, int source) {
            if (pages.size() + 1 >= limits.maxPages()) {
                throw failure(DocumentLayoutException.Code.BUDGET_EXCEEDED, block, index, pages.size() + 1,
                        row, -1, source, "document page budget exceeded", null);
            }
            pages.add(page());
            elements.clear();
            used = 0;
        }

        private LayoutPage page() {
            return new LayoutPage(pages.size(), settings.size(), new Rect(settings.margins().left(),
                    settings.margins().top(), settings.contentWidth(), settings.contentHeight()), elements);
        }

        private Point origin() {
            return new Point(settings.margins().left(), settings.margins().top() + used);
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
        List<TextLine> lines = new ArrayList<>();
        double ascent = shaped.lines().getFirst().baselineY();
        for (int offset = 0; offset < count; offset++) {
            TextLine original = shaped.lines().get(first + offset);
            lines.add(original.atBaseline(offset * lineHeight + ascent));
        }
        int sourceStart = lines.getFirst().sourceStart();
        int sourceEnd = lines.getLast().sourceEnd();
        var diagnostics = shaped.diagnostics().stream()
                .filter(diagnostic -> diagnostic.sourceStart() >= sourceStart && diagnostic.sourceStart() < sourceEnd)
                .toList();
        return new ParagraphLayout(shaped.width(), count * lineHeight, lines, diagnostics);
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
