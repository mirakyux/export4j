// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, strict data binding for business documents. Expressions are dot paths only, never executable code.
 * Values are non-null strings, booleans, exact standard numeric classes, lists or string-keyed maps.
 * Input is snapshotted per render; caller-supplied collection implementations remain trusted Java code.
 * literalText disables interpolation in text/metadata/links while leaving condition and iteration data paths active.
 * Existing constructors default to interpolation; the canonical constructor explicitly selects this mode.
 */
public record DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
        PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates, boolean literalText,
        List<Note> notes, DocumentNavigation navigation, List<CellRule> cellRules, List<CellContent> cellContents) {
    private static final double LOG10_2 = 0.3010299956639812;

    public DocumentTemplate {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(pageSettings, "pageSettings");
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(pageRegions, "pageRegions");
        pageRegions.requireContained(pageSettings.size());
        sections = List.copyOf(sections);
        notes = List.copyOf(notes);
        Objects.requireNonNull(navigation, "navigation");
        cellRules = List.copyOf(cellRules);
        cellContents = List.copyOf(cellContents);
        if (cellContents.size() > limits.maxVisitedNodes()) { throw new IllegalArgumentException("cell content template budget exceeded"); }
        if (cellRules.size() > limits.maxVisitedNodes()) { throw new IllegalArgumentException("cell rule template budget exceeded"); }
        if (notes.size() > limits.maxVisitedNodes()) { throw new IllegalArgumentException("note template budget exceeded"); }
        hyperlinkTemplates = Map.copyOf(hyperlinkTemplates);
        if (hyperlinkTemplates.size() > limits.maxVisitedNodes()) { throw new IllegalArgumentException("hyperlink template budget exceeded"); }
        for (var entry : hyperlinkTemplates.entrySet()) {
            new ExternalDestination(entry.getKey());
            if (entry.getValue().length() > (long) limits.maxCodePoints() * 2) {
                throw new IllegalArgumentException("hyperlink template source budget exceeded");
            }
        }
        if (!sections.isEmpty() && !nodes.isEmpty()) { throw new IllegalArgumentException("sections cannot mix with root body nodes"); }
        requireBodyNodes(nodes, 0);
        for (var section : sections) { requireBodyNodes(section.nodes(), 0); }
    }

    /** A rule attached to this exact prototype cell; equal cells may have independent rules. */
    public record CellRule(TableCell prototype, CellStyleRule rule) {
        public CellRule {
            Objects.requireNonNull(prototype, "prototype"); Objects.requireNonNull(rule, "rule");
            if (prototype.hasBlocks()) { throw new IllegalArgumentException("conditional styles require inline cells"); }
        }
    }

    /** Dynamic semantic content attached to the identity of one prototype cell. Source nodes remain
     * template declarations; final cells contain only complete Paragraph/Table blocks. */
    public record CellContent(TableCell prototype, List<Node> nodes) {
        public CellContent {
            Objects.requireNonNull(prototype, "prototype"); nodes = List.copyOf(nodes);
            requireCellNodes(nodes, 0);
        }
    }

    private static void requireCellNodes(List<Node> nodes, int depth) {
        if (depth > 128) { throw new IllegalArgumentException("cell template nesting exceeds limit"); }
        for (var node : nodes) {
            ExecutionContext.checkpoint();
            switch (node) {
                case ValueBlock value -> SemanticTree.validateCellBlocks(List.of(value.prototype()));
                case Source ignored -> { }
                case When when -> { requireCellNodes(when.yes(), depth + 1); requireCellNodes(when.no(), depth + 1); }
                case Each each -> requireCellNodes(each.children(), depth + 1);
                default -> throw new IllegalArgumentException("cell content requires paragraphs or tables");
            }
        }
    }

    /** Compatibility constructor for templates without dynamic cell content. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates, boolean literalText,
            List<Note> notes, DocumentNavigation navigation, List<CellRule> cellRules) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, literalText,
                notes, navigation, cellRules, List.of());
    }

    /** Compatibility constructor for templates without conditional cell styles. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates,
            boolean literalText, List<Note> notes, DocumentNavigation navigation) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, literalText, notes, navigation, List.of());
    }

    /** Compatibility constructor for templates without portable navigation. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates, boolean literalText, List<Note> notes) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, literalText, notes, DocumentNavigation.NONE);
    }

    /** Compatibility constructor for templates without note declarations. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates, boolean literalText) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, literalText, List.of());
    }

    /** Compatibility constructor: existing templates continue to interpolate text. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections, Map<String, String> hyperlinkTemplates) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, false);
    }

    /** Compatibility constructor for templates with static hyperlinks. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits,
            PageRegions pageRegions, List<SectionNode> sections) {
        this(metadata, pageSettings, nodes, limits, pageRegions, sections, Map.of());
    }

    /** Compatibility constructor for body-only templates. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits) {
        this(metadata, pageSettings, nodes, limits, PageRegions.EMPTY, List.of());
    }

    /** Compatibility constructor for uniform page regions. */
    public DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits, PageRegions pageRegions) {
        this(metadata, pageSettings, nodes, limits, pageRegions, List.of());
    }

    /** One static next-page section; child data binding shares the complete render budget. */
    public record SectionNode(String id, PageSettings pageSettings, List<Node> nodes, int firstPageNumber, PageRegions pageRegions) {
        public SectionNode {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(pageSettings, "pageSettings");
            Objects.requireNonNull(pageRegions, "pageRegions"); nodes = List.copyOf(nodes);
            pageRegions.requireContained(pageSettings.size());
            if (id.isBlank() || firstPageNumber < 0) { throw new IllegalArgumentException("invalid template section"); }
        }
    }

    public sealed interface Node permits ValueBlock, Source, When, Each, Rows, Columns, Grid, GridItem { }
    /** Task-confined nested template authoring; branches are snapshotted when the callback returns. */
    public static final class Nodes {
        private final List<Node> values = new ArrayList<>();
        private Nodes() { }
        public Nodes node(Node value) { values.add(Objects.requireNonNull(value, "node")); return this; }
        public Nodes block(Block value) { return node(new ValueBlock(value)); }
        public Nodes when(String path, java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            return when(path, TemplateCondition.Operator.TRUE, "", yes, no);
        }
        /** Adds a scalar comparison; branch callbacks are snapshotted before returning. */
        public Nodes when(String path, TemplateCondition.Operator operator, String value,
                java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            var left = new Nodes(); var right = new Nodes(); yes.accept(left); no.accept(right);
            return node(new When(path, operator, value, left.snapshot(), right.snapshot()));
        }
        public Nodes each(String path, String variable, java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value); return node(new Each(path, variable, value.snapshot()));
        }
        public Nodes columns(String id, int count, double gap, boolean balanced, List<Double> weights,
                java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value);
            return node(new Columns(id, count, gap, balanced, weights, value.snapshot()));
        }
        public Nodes columns(java.util.function.Consumer<Columns.Builder> configure) {
            var value = Columns.builder(); configure.accept(value); return node(value.build());
        }
        public Nodes grid(String id, int tracks, double columnGap, double rowGap,
                java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value);
            return node(new Grid(id, tracks, columnGap, rowGap, value.snapshot()));
        }
        public Nodes gridItem(String id, int row, int columnStart, int columnSpan,
                java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value);
            return node(new GridItem(id, row, columnStart, columnSpan, value.snapshot()));
        }
        private List<Node> snapshot() { return List.copyOf(values); }
    }
    /** Supports paragraphs (including styled runs), headings, lists, captions, cross references, page breaks, continuous columns and semantic tables with
     * bounded Paragraph/Table block cells. Descendants share expansion budgets and retain their formats. */
    public record ValueBlock(Block prototype) implements Node {
        public ValueBlock { Objects.requireNonNull(prototype, "prototype"); }
    }
    /** Explicit source input, resolved before layout. Literal content is never interpolated; lookup
     * selects one complete string value by dot path and deliberately treats that value as source syntax.
     * Mixed template interpolation is unsupported. Options are complete authoring snapshots and the
     * parser is caller-supplied; one session per parser identity is owned by each render. */
    public record Source(String id, String source, boolean lookup, ContentOptions.Resolved options,
            SourceContentParser parser) implements Node {
        public Source {
            if (Objects.requireNonNull(id, "id").isBlank()) { throw new IllegalArgumentException("blank source ID"); }
            Objects.requireNonNull(source, "source"); Objects.requireNonNull(options, "options");
            Objects.requireNonNull(parser, "parser");
            if (lookup) { checkPath(source); }
        }
        public static Source literal(String id, String source, ContentOptions.Resolved options, SourceContentParser parser) {
            return new Source(id, source, false, options, parser);
        }
        public static Source value(String id, String path, ContentOptions.Resolved options, SourceContentParser parser) {
            return new Source(id, path, true, options, parser);
        }
        public static Source literal(String id, String source, SourceContentParser parser) {
            return literal(id, source, ContentOptions.defaults(), parser);
        }
        public static Source value(String id, String path, SourceContentParser parser) {
            return value(id, path, ContentOptions.defaults(), parser);
        }
    }

    /** A lexical scalar condition with immutable branches. Only the selected branch expands.
     * Nesting another condition in {@code no} forms an ordered, mutually exclusive alternative.
     * The three-argument constructor preserves the original boolean-true condition. */
    public record When(String path, TemplateCondition.Operator operator, String value,
            List<Node> yes, List<Node> no) implements Node {
        public When {
            checkPath(path); new TemplateCondition(operator, value);
            yes = List.copyOf(yes); no = List.copyOf(no);
        }
        public When(String path, List<Node> yes, List<Node> no) {
            this(path, TemplateCondition.Operator.TRUE, "", yes, no);
        }
    }
    /** Iteration variable is lexical; generated block IDs receive a deterministic iteration suffix.
     * Static field targets declared in this copy scope relocate to the current copy, including targets in
     * conditional branches. External targets and dynamically bound target strings remain unchanged. */
    public record Each(String path, String variable, List<Node> children) implements Node {
        public Each { checkPath(path); checkVariable(variable); children = List.copyOf(children); }
    }
    /** Continuous body columns. Static geometry surrounds paragraphs/tables expanded in lexical scope.
     * All branches are checked, nested columns and paragraph page breaks/keep-with-next chains are rejected,
     * and an empty bound flow is omitted.
     * Gap uses points; empty weights select equal widths. Children share the complete binding budget. */
    public record Columns(String id, int count, double gap, boolean balanced, List<Double> weights,
            List<Node> children) implements Node {
        public Columns {
            if (Objects.requireNonNull(id, "id").isBlank()) { throw new IllegalArgumentException("blank column flow ID"); }
            if (count < 1 || count > 3 || !Double.isFinite(gap) || gap < 0) {
                throw new IllegalArgumentException("invalid column geometry");
            }
            weights = List.copyOf(weights); children = List.copyOf(children);
            if (!weights.isEmpty() && weights.size() != count) { throw new IllegalArgumentException("column weight count mismatch"); }
            for (double weight : weights) {
                if (!Double.isFinite(weight) || weight <= 0) { throw new IllegalArgumentException("invalid column weight"); }
            }
            if (children.isEmpty()) { throw new IllegalArgumentException("empty column template"); }
            requireColumnNodes(children, 0);
        }
        public Columns(String id, int count, double gap, boolean balanced, List<Node> children) {
            this(id, count, gap, balanced, List.of(), children);
        }
        public static Builder builder() { return new Builder(); }
        /** Task-confined construction; children and weights are snapshotted on assignment and build. */
        public static final class Builder {
            private String id = "";
            private int count = 2;
            private double gap = 18;
            private boolean balanced = true;
            private List<Double> weights = List.of();
            private List<Node> children = new ArrayList<>();
            private Builder() { }
            public Builder id(String value) { id = Objects.requireNonNull(value, "id"); return this; }
            public Builder count(int value) { count = value; return this; }
            public Builder gap(double value) { gap = value; return this; }
            public Builder balanced(boolean value) { balanced = value; return this; }
            public Builder weights(List<Double> values) { weights = List.copyOf(values); return this; }
            public Builder children(List<Node> values) { children = new ArrayList<>(List.copyOf(values)); return this; }
            public Builder node(Node value) { children.add(Objects.requireNonNull(value, "node")); return this; }
            public Builder block(Block value) { return node(new ValueBlock(value)); }
            public Columns build() { return new Columns(id, count, gap, balanced, weights, children); }
        }
    }

    private static void requireColumnNodes(List<Node> nodes, int depth) {
        if (depth > 128) { throw new IllegalArgumentException("column template nesting exceeds limit"); }
        for (var node : nodes) {
            ExecutionContext.checkpoint();
            switch (node) {
                case ValueBlock value -> {
                    if (!(value.prototype() instanceof Paragraph || value.prototype() instanceof Table)) {
                        throw new IllegalArgumentException("columns require paragraph or table content");
                    }
                    if (value.prototype() instanceof Paragraph paragraph && (paragraph.format().pagination().pageBreakBefore()
                            || paragraph.format().pagination().keepWithNext())) {
                        throw new IllegalArgumentException("columns do not support page breaks or keep-with-next chains");
                    }
                }
                case Rows ignored -> { }
                case Source ignored -> { /* The bound ColumnFlow validates actual generated types. */ }
                case When when -> { requireColumnNodes(when.yes(), depth + 1); requireColumnNodes(when.no(), depth + 1); }
                case Each each -> requireColumnNodes(each.children(), depth + 1);
                case Columns ignored -> throw new IllegalArgumentException("nested column templates are unsupported");
                case Grid ignored -> throw new IllegalArgumentException("grid templates cannot occur inside columns");
                case GridItem ignored -> throw new IllegalArgumentException("grid items cannot occur inside columns");
            }
        }
    }

    /** Static track geometry around ordered item templates. Bound empty items and grids are omitted. */
    public record Grid(String id, int tracks, double columnGap, double rowGap, List<Node> children) implements Node {
        public Grid {
            if (Objects.requireNonNull(id, "id").isBlank() || tracks < 1 || tracks > 12
                    || !Double.isFinite(columnGap) || columnGap < 0 || !Double.isFinite(rowGap) || rowGap < 0) {
                throw new IllegalArgumentException("invalid grid template geometry");
            }
            children = List.copyOf(children);
            if (children.isEmpty()) { throw new IllegalArgumentException("empty grid template"); }
            requireGridNodes(children, tracks, 0);
            if (!hasTemplateContent(children, 0)) { throw new IllegalArgumentException("empty grid template"); }
        }
    }

    /** One explicit one-based placement; content is restricted to paragraphs and tables in every branch. */
    public record GridItem(String id, int row, int columnStart, int columnSpan, List<Node> children) implements Node {
        public GridItem {
            if (Objects.requireNonNull(id, "id").isBlank() || row < 1 || columnStart < 1 || columnSpan < 1
                    || (long) columnStart + columnSpan - 1 > 12) {
                throw new IllegalArgumentException("invalid grid item template geometry");
            }
            children = List.copyOf(children);
            if (children.isEmpty()) { throw new IllegalArgumentException("empty grid item template"); }
            requireColumnNodes(children, 0);
            if (!hasTemplateContent(children, 0)) { throw new IllegalArgumentException("empty grid item template"); }
        }
    }

    private static boolean hasTemplateContent(List<Node> nodes, int depth) {
        if (depth > 128) { throw new IllegalArgumentException("grid template nesting exceeds limit"); }
        for (var node : nodes) {
            ExecutionContext.checkpoint();
            if (node instanceof When when) {
                if (hasTemplateContent(when.yes(), depth + 1) || hasTemplateContent(when.no(), depth + 1)) { return true; }
            } else if (node instanceof Each each) {
                if (hasTemplateContent(each.children(), depth + 1)) { return true; }
            } else { return true; }
        }
        return false;
    }

    private static void requireGridNodes(List<Node> nodes, int tracks, int depth) {
        if (depth > 128) { throw new IllegalArgumentException("grid template nesting exceeds limit"); }
        for (var node : nodes) {
            ExecutionContext.checkpoint();
            switch (node) {
                case GridItem item -> {
                    if ((long) item.columnStart() + item.columnSpan() - 1 > tracks) {
                        throw new IllegalArgumentException("grid item exceeds track count");
                    }
                }
                case ValueBlock value -> {
                    if (!(value.prototype() instanceof com.mirakyux.export4j.model.GridItem item)
                            || (long) item.columnStart() + item.columnSpan() - 1 > tracks) {
                        throw new IllegalArgumentException("grids accept only grid items");
                    }
                }
                case Source ignored -> throw new IllegalArgumentException("grids accept only grid items");
                case When when -> { requireGridNodes(when.yes(), tracks, depth + 1); requireGridNodes(when.no(), tracks, depth + 1); }
                case Each each -> requireGridNodes(each.children(), tracks, depth + 1);
                default -> throw new IllegalArgumentException("grids accept only grid item templates");
            }
        }
    }

    private static void requireBodyNodes(List<Node> nodes, int depth) {
        if (depth > 128) { throw new IllegalArgumentException("body template nesting exceeds limit"); }
        for (var node : nodes) {
            ExecutionContext.checkpoint();
            if (node instanceof GridItem || node instanceof ValueBlock value
                    && value.prototype() instanceof com.mirakyux.export4j.model.GridItem) {
                throw new IllegalArgumentException("grid items require a grid owner");
            }
            if (node instanceof When when) {
                requireBodyNodes(when.yes(), depth + 1); requireBodyNodes(when.no(), depth + 1);
            } else if (node instanceof Each each) { requireBodyNodes(each.children(), depth + 1); }
        }
    }
    /** Expands one data collection into body rows after fixed headers. An empty collection fails explicitly.
     * The repeated body prototype cannot span rows; static headers can span within the complete header group.
     */
    public record Rows(String id, String path, String variable, List<ColumnWidth> columns,
            List<TableRow> headers, TableRow row, TableFormat format) implements Node {
        /** Task-confined builder; build validates and snapshots the complete value. */
        public static Builder builder() { return new Builder(); }
        public static final class Builder {
            private String id = "";
            private String path = "";
            private String variable = "row";
            private List<ColumnWidth> columns = new java.util.ArrayList<>();
            private List<TableRow> headers = new java.util.ArrayList<>();
            private TableRow row = null;
            private TableFormat format = TableFormat.DEFAULT;
            private Builder() { }
            public Builder id(String value) { id = java.util.Objects.requireNonNull(value, "id"); return this; }
            public Builder path(String value) { path = java.util.Objects.requireNonNull(value, "path"); return this; }
            public Builder variable(String value) { variable = java.util.Objects.requireNonNull(value, "variable"); return this; }
            public Builder columns(List<ColumnWidth> value) { columns = new java.util.ArrayList<>(List.copyOf(value)); return this; }
            public Builder headers(List<TableRow> value) { headers = new java.util.ArrayList<>(List.copyOf(value)); return this; }
            public Builder row(TableRow value) { row = java.util.Objects.requireNonNull(value, "row"); return this; }
            public Builder format(TableFormat value) { format = java.util.Objects.requireNonNull(value, "format"); return this; }

            public Rows build() { return new Rows(id, path, variable, columns, headers, row, format); }
        }

        public Rows {
            if (Objects.requireNonNull(id, "id").isBlank()) { throw new IllegalArgumentException("blank template table ID"); }
            checkPath(path); checkVariable(variable);
            columns = List.copyOf(columns); headers = List.copyOf(headers);
            Objects.requireNonNull(row, "row"); Objects.requireNonNull(format, "format");
            if (row.cells().stream().anyMatch(cell -> cell.rowSpan() > 1)) {
                throw new IllegalArgumentException("dynamic table body prototypes do not support row spans");
            }
            var prototype = new ArrayList<>(headers);
            prototype.add(row);
            new Table(id, columns, prototype, headers.size(), format);
        }
    }

    /** Snapshot and expansion each cap visited work items; expansion includes iterations, cells and inlines.
     * Text code points are shared by data strings/map keys and generated strings. Numeric conversion is
     * checked against the available text budget before materializing its decimal representation. */
    public record Limits(int maxVisitedNodes, int maxBlocks, int maxRows, int maxCodePoints, int maxDepth) {
        public static final Limits DEFAULT = new Limits(100_000, 10_000, 10_000, 1_000_000, 32);
        public Limits {
            if (maxVisitedNodes < 1 || maxBlocks < 1 || maxRows < 1 || maxCodePoints < 1 || maxDepth < 1 || maxDepth > 128) {
                throw new IllegalArgumentException("invalid template limits");
            }
        }
    }

    public static final class BindingException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public enum Code { MISSING_VALUE, TYPE_MISMATCH, INVALID_EXPRESSION, INVALID_VALUE, UNSUPPORTED_BLOCK, BUDGET_EXCEEDED, EMPTY_TABLE }
        private final Code code;
        private final String path;
        BindingException(Code code, String path) {
            super("template binding failed: " + code + " at " + diagnosticPath(path));
            this.code = code; this.path = diagnosticPath(path);
        }
        public Code code() { return code; }
        public String path() { return path; }
    }

    /** Strict render: any missing value, invalid type or expansion limit prevents returning a document. */
    public Document render(Map<String, ?> data) {
        if (!sections.isEmpty() || !pageRegions.equals(PageRegions.EMPTY)) {
            throw error(BindingException.Code.UNSUPPORTED_BLOCK, "pageRegions");
        }
        return renderWithPageRegions(data).document();
    }

    /** Binds body and repeating regions against one data snapshot and shared expansion/text budgets.
     * PAGE/PAGES remain fixed-width fields until final pagination by DocumentEngine.
     * Use this entry for templates declaring page regions; render() rejects those declarations.
     */
    public PageDocument renderWithPageRegions(Map<String, ?> data) {
        if (!navigation.equals(DocumentNavigation.NONE)) { throw error(BindingException.Code.UNSUPPORTED_BLOCK, "navigation"); }
        if (!sections.isEmpty()) { throw error(BindingException.Code.UNSUPPORTED_BLOCK, "sections"); }
        requireNoNotes();
        var bound = renderComplete(data).document();
        var section = bound.sections().getFirst();
        return new PageDocument(new Document(bound.metadata(), section.pageSettings(), section.blocks()), section.pageRegions());
    }

    /** Binds static sections and their regions using one input snapshot and shared counters.
     * Root regions are inherited by XML sections; equal immutable bands remain linked sources.
     * Templates without section declarations produce a single document section.
     */
    public SectionedDocument renderSections(Map<String, ?> data) {
        requireNoNotes();
        return renderComplete(data).document();
    }

    private void requireNoNotes() {
        if (!notes.isEmpty()) { throw error(BindingException.Code.UNSUPPORTED_BLOCK, "notes"); }
    }

    /** Complete immutable binding result. Note IDs/anchors are static; note text binds in root scope.
     * Relationships are validated against the final expanded body, never inferred or silently dropped.
     * Legacy render methods reject note declarations; pass both values to editing/layout/export.
     */
    public record Rendered(SectionedDocument document, List<Note> notes) {
        public Rendered {
            Objects.requireNonNull(document, "document"); notes = List.copyOf(notes);
            Note.validate(document, notes);
        }
    }

    /** Binds sections, page regions and notes under one snapshot and shared work/text budgets. */
    public Rendered renderComplete(Map<String, ?> data) {
        try (var state = new Binding()) {
            Object frozen = state.freeze(Objects.requireNonNull(data, "data"), 0);
            if (!(frozen instanceof Map<?, ?> map)) { throw new AssertionError(); }
            var root = new Scope(map, null);
            var values = new ArrayList<Section>();
            var declarations = sections.isEmpty() ? List.of(new SectionNode("document", pageSettings, nodes, 0, pageRegions)) : sections;
            Map<PageRegion, PageRegion> bands = cellRules.isEmpty() && cellContents.isEmpty()
                    ? new HashMap<>() : new java.util.IdentityHashMap<>();
            for (var declaration : declarations) {
                state.visit("sections");
                state.blocks.clear();
                state.expand(declaration.nodes(), root, "", 0, Map.of());
                var regions = declaration.pageRegions();
                var resolved = regions.map((name, value) -> bands.computeIfAbsent(value, band -> state.band(band, root)));
                values.add(new Section(declaration.id(), declaration.pageSettings(), state.blocks,
                        declaration.firstPageNumber(), resolved));
            }
            var boundNotes = new ArrayList<Note>();
            for (var note : notes) {
                state.visit("notes");
                state.consume(note.id()); state.consume(note.paragraphId());
                boundNotes.add(new Note(note.id(), note.paragraphId(), state.text(note.text(), root), note.kind()));
            }
            state.consume(navigation.contents().title());
            for (var bookmark : navigation.bookmarks()) { state.visit("navigation"); state.consume(bookmark.title()); state.consume(bookmark.blockId()); }
            var document = new SectionedDocument(new DocumentMetadata(state.text(metadata.title(), root), state.text(metadata.author(), root)), values, navigation);
            navigation.validate(document);
            return new Rendered(document, boundNotes);
        }
    }

    /** One immutable lexical declaration; iteration does not copy the entire outer data snapshot. */
    private record Scope(Map<?, ?> values, Scope parent) {
        Object value(String key, String path) {
            for (var scope = this; scope != null; scope = scope.parent()) {
                ExecutionContext.checkpoint();
                if (scope.values().containsKey(key)) { return scope.values().get(key); }
            }
            throw error(BindingException.Code.MISSING_VALUE, path);
        }
    }

    private final class Binding implements AutoCloseable {
        private final Map<SourceContentParser, SourceContentParser.Session> sourceSessions = new java.util.IdentityHashMap<>();
        private final List<SourceContentParser.Session> sessionOrder = new ArrayList<>();
        private final Map<TableCell, List<Node>> cellSources = new java.util.IdentityHashMap<>();
        private final Map<TableCell, List<CellStyleRule>> rules = new java.util.IdentityHashMap<>();
        private Binding() {
            for (var entry : cellContents) {
                if (cellSources.put(entry.prototype(), entry.nodes()) != null) {
                    throw new IllegalArgumentException("duplicate cell content declaration");
                }
            }
            for (var entry : cellRules) {
                rules.computeIfAbsent(entry.prototype(), ignored -> new ArrayList<>()).add(entry.rule());
            }
        }
        private int visited;
        private int rows;
        private int codePoints;
        private int dataNodes;
        private int blockCount;
        private final List<Block> blocks = new ArrayList<>();

        private PageRegion band(PageRegion prototype, Scope scope) {
            var values = new ArrayList<Block>();
            for (var block : prototype.blocks()) {
                values.add(bind(block, scope, "", blockIds(block, "", Map.of()), 0));
            }
            return new PageRegion(prototype.height(), prototype.gap(), prototype.edgeOffset(),
                    prototype.leftInset(), prototype.rightInset(), values);
        }

        private Object freeze(Object value, int depth) {
            ExecutionContext.checkpoint();
            if (depth > limits.maxDepth() || ++dataNodes > limits.maxVisitedNodes()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "data"); }
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new HashMap<>();
                map.forEach((key, item) -> {
                    if (!(key instanceof String text)) { throw error(BindingException.Code.TYPE_MISMATCH, "data"); }
                    consume(text);
                    copy.put(text, freeze(item, depth + 1));
                });
                return Map.copyOf(copy);
            }
            if (value instanceof List<?> list) { return list.stream().map(item -> freeze(item, depth + 1)).toList(); }
            if (value instanceof String text) {
                consume(text);
                return text;
            }
            if (value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
                checkNumber(value, limits.maxCodePoints(), "data");
                return value;
            }
            if (value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long
                    || value instanceof Double number && Double.isFinite(number)
                    || value instanceof Float number && Float.isFinite(number)) { return value; }
            throw error(BindingException.Code.TYPE_MISMATCH, "data");
        }

        private void expand(List<Node> source, Scope scope, String suffix, int depth, Map<String, String> relocations) {
            expand(source, scope, suffix, depth, relocations, blocks);
        }

        private void expand(List<Node> source, Scope scope, String suffix, int depth, Map<String, String> relocations,
                List<Block> target) {
            if (depth > limits.maxDepth()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "nodes"); }
            for (var node : source) {
                visit("nodes");
                switch (node) {
                    case ValueBlock value -> target.add(bind(value.prototype(), scope, suffix,
                            blockIds(value.prototype(), suffix, relocations), depth));
                    case Source input -> target.addAll(parseSource(input, scope, suffix, depth));
                    case When when -> {
                        Object value = get(when.path(), scope);
                        boolean flag;
                        try { flag = new TemplateCondition(when.operator(), when.value()).matches(value); }
                        catch (IllegalArgumentException invalid) { throw error(BindingException.Code.TYPE_MISMATCH, when.path()); }
                        expand(flag ? when.yes() : when.no(), scope, suffix, depth + 1, relocations, target);
                    }
                    case Each each -> {
                        var items = collection(each.path(), scope);
                        for (int i = 0; i < items.size(); i++) {
                            visit("iterations");
                            var nested = new Scope(Map.of(each.variable(), items.get(i)), scope);
                            String nestedSuffix = suffix + "@" + i;
                            var nestedIds = new HashMap<>(relocations);
                            collectIds(each.children(), nestedSuffix, nestedIds, depth + 1);
                            expand(each.children(), nested, nestedSuffix, depth + 1, nestedIds, target);
                        }
                    }
                    case Rows table -> {
                        var items = collection(table.path(), scope);
                        if (items.isEmpty()) { throw error(BindingException.Code.EMPTY_TABLE, table.path()); }
                        consumeBlock(depth);
                        List<TableRow> result = new ArrayList<>();
                        var tableIds = new HashMap<>(relocations);
                        tableIds.put(table.id(), table.id() + suffix);
                        for (var header : table.headers()) { collectRowIds(header, suffix, tableIds); }
                        for (var header : table.headers()) { result.add(bindRow(header, scope, suffix, tableIds, depth + 1)); }
                        for (int index = 0; index < items.size(); index++) {
                            ExecutionContext.checkpoint();
                            var nested = new Scope(Map.of(table.variable(), items.get(index)), scope);
                            String rowSuffix = suffix + "@row:" + table.id() + ":" + index;
                            var rowIds = new HashMap<>(tableIds);
                            collectRowIds(table.row(), rowSuffix, rowIds);
                            result.add(bindRow(table.row(), nested, rowSuffix, rowIds, depth + 1));
                        }
                        target.add(new Table(table.id() + suffix, table.columns(), result, table.headers().size(), table.format()));
                    }
                    case Columns columns -> {
                        consumeBlock(depth);
                        var children = new ArrayList<Block>();
                        var columnIds = new HashMap<>(relocations);
                        columnIds.put(columns.id(), columns.id() + suffix);
                        collectIds(columns.children(), suffix, columnIds, depth + 1);
                        expand(columns.children(), scope, suffix, depth + 1, columnIds, children);
                        if (!children.isEmpty()) {
                            target.add(new ColumnFlow(columns.id() + suffix, children, columns.count(), columns.gap(),
                                    columns.balanced(), columns.weights()));
                        }
                    }
                    case Grid grid -> {
                        consumeBlock(depth);
                        var children = new ArrayList<Block>();
                        var gridIds = new HashMap<>(relocations);
                        gridIds.put(grid.id(), grid.id() + suffix);
                        collectIds(grid.children(), suffix, gridIds, depth + 1);
                        expand(grid.children(), scope, suffix, depth + 1, gridIds, children);
                        if (!children.isEmpty()) {
                            target.add(new GridLayout(grid.id() + suffix, grid.tracks(), grid.columnGap(), grid.rowGap(),
                                    children.stream().map(child -> (com.mirakyux.export4j.model.GridItem) child).toList()));
                        }
                    }
                    case GridItem item -> {
                        consumeBlock(depth);
                        var children = new ArrayList<Block>();
                        var itemIds = new HashMap<>(relocations);
                        itemIds.put(item.id(), item.id() + suffix);
                        collectIds(item.children(), suffix, itemIds, depth + 1);
                        expand(item.children(), scope, suffix, depth + 1, itemIds, children);
                        if (!children.isEmpty()) {
                            target.add(new com.mirakyux.export4j.model.GridItem(item.id() + suffix, item.row(),
                                    item.columnStart(), item.columnSpan(), children));
                        }
                    }
                }
            }
        }

        private List<Block> parseSource(Source input, Scope scope, String suffix, int depth) {
            ExecutionContext.checkpoint();
            String source = input.source();
            if (input.lookup()) {
                Object value = get(source, scope);
                if (!(value instanceof String text)) { throw error(BindingException.Code.TYPE_MISMATCH, source); }
                source = text;
            }
            // Debit source material before invoking a parser, independently from its final output.
            consume(source);
            var remaining = new SourceContentParser.Remaining(limits.maxVisitedNodes() - visited,
                    limits.maxBlocks() - blockCount, limits.maxRows() - rows,
                    limits.maxCodePoints() - codePoints, limits.maxDepth() - depth);
            var session = sourceSessions.get(input.parser());
            if (session == null) {
                session = Objects.requireNonNull(input.parser().openSession(), "source parser session");
                sourceSessions.put(input.parser(), session);
                sessionOrder.add(session);
            }
            String namespace = input.id() + suffix;
            List<Block> parsed = Objects.requireNonNull(session.parse(namespace, source, input.options(), remaining),
                    "parsed content");
            ExecutionContext.checkpoint();
            if (parsed.size() > limits.maxBlocks() - blockCount) {
                throw error(BindingException.Code.BUDGET_EXCEEDED, "source.blocks");
            }
            parsed = List.copyOf(parsed);
            for (var block : parsed) {
                if (block instanceof com.mirakyux.export4j.model.GridItem) {
                    throw error(BindingException.Code.UNSUPPORTED_BLOCK, "source.gridItem");
                }
                accountParsed(block, namespace, depth);
            }
            return parsed;
        }

        /** Counts final source output without binding it. Every occurrence is charged, including
         * descendant tables/cells and vector paths. Parser sessions separately bound intermediates. */
        private void accountParsed(Block block, String namespace, int depth) {
            consumeBlock(depth);
            String id = block.id();
            if (id == null || !(id.equals(namespace) || id.startsWith(namespace + ":"))) {
                throw error(BindingException.Code.INVALID_VALUE, "source.id");
            }
            consume(id);
            switch (block) {
                case Paragraph paragraph -> accountParsedParagraph(paragraph);
                case Heading heading -> accountParsedParagraph(heading.paragraph());
                case BulletItem item -> { accountParsedParagraph(item.paragraph()); consume(item.marker()); }
                case NumberedItem item -> { accountParsedParagraph(item.paragraph()); consume(item.listId()); }
                case Caption caption -> { consume(caption.kind()); consume(caption.text()); }
                case CrossReference reference -> { consume(reference.prefix()); consume(reference.targetId()); }
                case PageBreak ignored -> { }
                case Table table -> {
                    for (var row : table.rows()) {
                        visit("source.rows");
                        if (depth + 1 > limits.maxDepth() || ++rows > limits.maxRows()) {
                            throw error(BindingException.Code.BUDGET_EXCEEDED, "source.rows");
                        }
                        for (var cell : row.cells()) {
                            visit("source.cells");
                            if (cell.hasBlocks()) {
                                for (var child : cell.blocks()) { accountParsed(child, namespace, depth + 2); }
                            } else {
                                accountParsedInline(cell.text(), cell.inlines(), cell.images(), cell.fields(), cell.vectors());
                            }
                        }
                    }
                }
                case ColumnFlow columns -> {
                    for (var child : columns.blocks()) { accountParsed(child, namespace, depth + 1); }
                }
                case GridLayout grid -> {
                    for (var child : grid.items()) { accountParsed(child, namespace, depth + 1); }
                }
                case com.mirakyux.export4j.model.GridItem item -> {
                    for (var child : item.blocks()) { accountParsed(child, namespace, depth + 1); }
                }
                default -> throw error(BindingException.Code.UNSUPPORTED_BLOCK, "source.block");
            }
        }

        private void accountParsedParagraph(Paragraph paragraph) {
            accountParsedInline(paragraph.text(), paragraph.inlines(), paragraph.images(), paragraph.fields(), paragraph.vectors());
        }

        private void accountParsedInline(String text, List<InlineText> inlines, List<Paragraph.ImageSpan> images,
                List<Paragraph.FieldSpan> fields, List<Paragraph.VectorSpan> vectors) {
            consume(text);
            for (var run : inlines) { visit("source.inlines"); consume(run.hyperlink()); }
            for (var image : images) {
                visit("source.images"); consume(image.image().altText()); consume(image.image().hyperlink());
            }
            for (var field : fields) { visit("source.fields"); consume(field.field().targetId()); }
            for (var vector : vectors) {
                visit("source.vectors"); consume(vector.vector().logicalText()); consume(vector.vector().hyperlink());
                int segments = vector.vector().resource().segmentCount();
                if (segments > limits.maxVisitedNodes() - visited) {
                    throw error(BindingException.Code.BUDGET_EXCEEDED, "source.vectors");
                }
                visited += segments;
            }
        }

        @Override public void close() {
            Throwable failure = null;
            for (int i = sessionOrder.size() - 1; i >= 0; i--) {
                try { sessionOrder.get(i).close(); }
                catch (RuntimeException | Error cause) {
                    if (failure == null) { failure = cause; }
                    else if (failure != cause) { failure.addSuppressed(cause); }
                }
            }
            sourceSessions.clear(); sessionOrder.clear();
            if (failure instanceof RuntimeException runtime) { throw runtime; }
            if (failure instanceof Error error) { throw error; }
        }

        private void consumeBlock(int depth) {
            visit("blocks");
            if (depth > limits.maxDepth() || ++blockCount > limits.maxBlocks()) {
                throw error(BindingException.Code.BUDGET_EXCEEDED, "blocks");
            }
        }
        private void collectIds(List<Node> source, String suffix, Map<String, String> ids, int depth) {
            if (depth > limits.maxDepth()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "nodes"); }
            for (var node : source) {
                visit("ids");
                switch (node) {
                    case ValueBlock value -> collectBlockIds(value.prototype(), suffix, ids);
                    case Source input -> ids.put(input.id(), input.id() + suffix);
                    case When when -> { collectIds(when.yes(), suffix, ids, depth + 1); collectIds(when.no(), suffix, ids, depth + 1); }
                    case Rows table -> {
                        ids.put(table.id(), table.id() + suffix);
                        for (var row : table.headers()) { collectRowIds(row, suffix, ids); }
                    }
                    case Each ignored -> { /* A nested iteration owns its own lexical copy scope. */ }
                    case Columns columns -> {
                        ids.put(columns.id(), columns.id() + suffix);
                        collectIds(columns.children(), suffix, ids, depth + 1);
                    }
                    case Grid grid -> {
                        ids.put(grid.id(), grid.id() + suffix);
                        collectIds(grid.children(), suffix, ids, depth + 1);
                    }
                    case GridItem item -> {
                        ids.put(item.id(), item.id() + suffix);
                        collectIds(item.children(), suffix, ids, depth + 1);
                    }
                }
            }
        }
        private Map<String, String> blockIds(Block source, String suffix, Map<String, String> parent) {
            var ids = new HashMap<>(parent);
            collectBlockIds(source, suffix, ids);
            return ids;
        }
        private void collectBlockIds(Block source, String suffix, Map<String, String> ids) {
            SemanticTree.walk(source, item -> {
                visit("ids");
                ids.put(item.block().id(), item.block().id() + suffix);
                if (item.block() instanceof Table table) {
                    for (var row : table.rows()) {
                        visit("ids");
                        for (var cell : row.cells()) {
                            visit("ids");
                            var content = cellSources.get(cell);
                            if (content != null) { collectIds(content, suffix, ids, item.cellPath().size() + 1); }
                        }
                    }
                }
            });
        }
        private void collectRowIds(TableRow row, String suffix, Map<String, String> ids) {
            visit("ids");
            for (var cell : row.cells()) {
                visit("ids");
                var content = cellSources.get(cell);
                if (content != null) { collectIds(content, suffix, ids, 0); }
                else { for (var block : cell.blocks()) { collectBlockIds(block, suffix, ids); } }
            }
        }
        private TableRow bindRow(TableRow row, Scope scope, String suffix, Map<String, String> relocations, int depth) {
            ExecutionContext.checkpoint();
            visit("rows");
            if (depth > limits.maxDepth() || ++rows > limits.maxRows()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "rows"); }
            return new TableRow(row.cells().stream().map(cell -> {
                visit("cells");
                var format = cell.format();
                var active = new ArrayList<CellStyleRule.Overrides>();
                for (var rule : rules.getOrDefault(cell, List.of())) {
                    visit("cellRules");
                    boolean match;
                    Object actual = get(rule.path(), scope);
                    try { match = rule.matches(actual); }
                    catch (IllegalArgumentException ex) { throw error(BindingException.Code.TYPE_MISMATCH, rule.path()); }
                    if (match) { format = rule.style().apply(format); active.add(rule.style()); }
                }
                var sourceNodes = cellSources.get(cell);
                if (sourceNodes != null) {
                    if (!active.isEmpty()) { throw error(BindingException.Code.UNSUPPORTED_BLOCK, "cellRules"); }
                    var children = new ArrayList<Block>();
                    var ids = new HashMap<>(relocations);
                    collectIds(sourceNodes, suffix, ids, depth + 1);
                    expand(sourceNodes, scope, suffix, depth + 1, ids, children);
                    SemanticTree.validateCellBlocks(children);
                    return new TableCell("", format, List.of(), List.of(), List.of(), List.of(),
                            cell.columnSpan(), cell.rowSpan(), children);
                }
                if (cell.hasBlocks()) {
                    if (!active.isEmpty()) { throw error(BindingException.Code.UNSUPPORTED_BLOCK, "cellRules"); }
                    var children = cell.blocks().stream().map(block -> bind(block, scope, suffix, relocations, depth + 1)).toList();
                    return new TableCell("", format, List.of(), List.of(), List.of(), cell.columnSpan(), cell.rowSpan(), children);
                }
                var original = cell.format();
                var prototype = new Paragraph("cell", cell.text(), new ParagraphFormat(original.fontSize(), original.lineHeight(), 0, 0, original.alignment(), 0, 0, false), cell.inlines(), cell.images(), cell.fields(), cell.vectors());
                var bound = bindParagraph(prototype, "cell", scope, relocations);
                if (active.isEmpty()) {
                    return new TableCell(bound.text(), format, bound.inlines(), bound.images(), bound.fields(), bound.vectors(), cell.columnSpan(), cell.rowSpan(), List.of());
                }
                var content = new ArrayList<InlineContent>();
                for (var part : bound.content()) {
                    if (part instanceof InlineText text) {
                        var appearance = text.style();
                        for (var override : active) { appearance = override.apply(appearance); }
                        content.add(new InlineText(text.text(), appearance, text.hyperlink()));
                    } else if (part instanceof InlineField field) {
                        var appearance = field.style();
                        for (var override : active) { appearance = override.apply(appearance); }
                        content.add(new InlineField(field.kind(), field.targetId(), field.width(), appearance));
                    } else { content.add(part); }
                }
                return TableCell.builder().format(format).content(content).columnSpan(cell.columnSpan()).rowSpan(cell.rowSpan()).build();
            }).toList());
        }
        private Block bind(Block block, Scope scope, String suffix, Map<String, String> relocations, int depth) {
            consumeBlock(depth);
            String id = block.id() + suffix;
            return switch (block) {
                case Paragraph p -> bindParagraph(p, id, scope, relocations);
                case Heading h -> {
                    var p = bindParagraph(h.paragraph(), id, scope, relocations);
                    yield new Heading(id, p.text(), h.level(), h.format(), p.inlines(), p.images(), p.fields(), p.vectors());
                }
                case PageBreak ignored -> new PageBreak(id);
                case Caption c -> new Caption(id, text(c.kind(), scope), text(c.text(), scope), c.format(), c.appearance());
                case CrossReference r -> new CrossReference(id, text(r.prefix(), scope),
                        fieldTarget(r.targetId(), scope, relocations), r.kind(), r.format(), r.appearance());
                case BulletItem b -> {
                    var p = bindParagraph(b.paragraph(), id, scope, relocations);
                    yield new BulletItem(id, p.text(), b.marker(), b.level(), b.format(), p.inlines(), b.markerStyle(), p.images(), p.fields(), p.vectors());
                }
                case NumberedItem n -> {
                    var p = bindParagraph(n.paragraph(), id, scope, relocations);
                    yield new NumberedItem(id, n.listId(), p.text(), n.restartAt(), n.format(), n.level(), p.inlines(), n.markerStyle(), p.images(), p.fields(), p.vectors());
                }
                case Table t -> new Table(id, t.columns(), t.rows().stream()
                        .map(row -> bindRow(row, scope, suffix, relocations, depth + 1)).toList(), t.headerRows(), t.format());
                case ColumnFlow flow -> new ColumnFlow(id, flow.blocks().stream()
                        .map(child -> bind(child, scope, suffix, relocations, depth + 1)).toList(),
                        flow.columns(), flow.gap(), flow.balanced(), flow.columnWeights());
                case GridLayout grid -> new GridLayout(id, grid.tracks(), grid.columnGap(), grid.rowGap(),
                        grid.items().stream().map(item -> (com.mirakyux.export4j.model.GridItem)
                                bind(item, scope, suffix, relocations, depth + 1)).toList());
                case com.mirakyux.export4j.model.GridItem item -> new com.mirakyux.export4j.model.GridItem(id,
                        item.row(), item.columnStart(), item.columnSpan(), item.blocks().stream()
                                .map(child -> bind(child, scope, suffix, relocations, depth + 1)).toList());
                default -> throw error(BindingException.Code.UNSUPPORTED_BLOCK, "block");
            };
        }
        private Paragraph bindParagraph(Paragraph p, String id, Scope scope, Map<String, String> relocations) {
            if (p.images().isEmpty() && p.fields().isEmpty() && p.vectors().isEmpty()) {
                return p.inlines().isEmpty() ? new Paragraph(id, text(p.text(), scope), p.format())
                        : new Paragraph(id, p.inlines().stream().map(run -> {
                            visit("inlines");
                            return new InlineText(text(run.text(), scope), run.style(), hyperlink(run.hyperlink(), scope));
                        }).toList(), p.format());
            }
            var content = p.content().stream().map(item -> {
                visit("inlines");
                return switch (item) {
                case InlineText run -> (InlineContent) new InlineText(text(run.text(), scope), run.style(), hyperlink(run.hyperlink(), scope));
                case InlineImage image -> new InlineImage(image.resource(), image.width(), image.height(), image.baselineShift(),
                        text(image.altText(), scope), hyperlink(image.hyperlink(), scope), image.rotation());
                case InlineField field -> new InlineField(field.kind(), fieldTarget(field.targetId(), scope, relocations), field.width(), field.style());
                case InlineVector vector -> {
                    // Reading text and geometry form one value; never bind text into stale ink.
                    consume(vector.logicalText());
                    int segments = vector.resource().segmentCount();
                    if (segments > limits.maxVisitedNodes() - visited) { throw error(BindingException.Code.BUDGET_EXCEEDED, "vectors"); }
                    visited += segments;
                    yield new InlineVector(vector.resource(), vector.logicalText(), vector.rgb(), hyperlink(vector.hyperlink(), scope));
                }
                };
            }).toList();
            return Paragraph.mixed(id, content, p.format());
        }
        private String hyperlink(String reference, Scope scope) {
            String template = hyperlinkTemplates.get(reference);
            if (template == null) { return text(reference, scope); }
            String value = text(template, scope);
            try { new ExternalDestination(value); }
            catch (IllegalArgumentException invalid) { throw error(BindingException.Code.INVALID_VALUE, "hyperlink"); }
            return value;
        }
        private String fieldTarget(String source, Scope scope, Map<String, String> relocations) {
            String value = text(source, scope);
            if (source.contains("${")) { return value; }
            String relocated = relocations.getOrDefault(value, value);
            consume(relocated, value.length(), relocated.length());
            return relocated;
        }
        private List<?> collection(String path, Scope scope) {
            if (get(path, scope) instanceof List<?> list) { return list; }
            throw error(BindingException.Code.TYPE_MISMATCH, path);
        }
        private Object get(String path, Scope scope) {
            if (!validPath(path)) { throw error(BindingException.Code.INVALID_EXPRESSION, "expression"); }
            int separator = path.indexOf('.');
            Object value = scope.value(separator < 0 ? path : path.substring(0, separator), path);
            while (separator >= 0) {
                ExecutionContext.checkpoint();
                int start = separator + 1;
                separator = path.indexOf('.', start);
                String key = path.substring(start, separator < 0 ? path.length() : separator);
                if (!(value instanceof Map<?, ?> map)) { throw error(BindingException.Code.TYPE_MISMATCH, path); }
                if (!map.containsKey(key)) { throw error(BindingException.Code.MISSING_VALUE, path); }
                value = map.get(key);
            }
            return value;
        }
        private String text(String template, Scope scope) {
            ExecutionContext.checkpoint();
            if (literalText) { consume(template); return template; }
            StringBuilder result = new StringBuilder();
            for (int cursor = 0; cursor < template.length();) {
                int start = cursor;
                while (cursor < template.length() && !(template.charAt(cursor) == '$'
                        && cursor + 1 < template.length() && template.charAt(cursor + 1) == '{')) {
                    cursor++;
                    if (cursor - start >= 1024) {
                        if (cursor < template.length() && Character.isHighSurrogate(template.charAt(cursor - 1))
                                && Character.isLowSurrogate(template.charAt(cursor))) { cursor++; }
                        append(result, template, start, cursor);
                        start = cursor;
                    }
                }
                append(result, template, start, cursor);
                if (cursor == template.length()) { break; }
                start = cursor;
                int end = start + 2;
                while (end < template.length() && template.charAt(end) != '}') {
                    if ((end & 1023) == 0) { ExecutionContext.checkpoint(); }
                    if (end - start > limits.maxCodePoints() * 2L) { throw error(BindingException.Code.BUDGET_EXCEEDED, "expression"); }
                    end++;
                }
                if (end == template.length()) { end = -1; }
                if (end < 0) { throw error(BindingException.Code.INVALID_EXPRESSION, "expression"); }
                String path = template.substring(start + 2, end);
                Object value = get(path, scope);
                if (value instanceof Map<?, ?> || value instanceof List<?>) { throw error(BindingException.Code.TYPE_MISMATCH, path); }
                checkNumber(value, limits.maxCodePoints() - codePoints, path);
                String scalar = value.toString();
                append(result, scalar, 0, scalar.length());
                cursor = end + 1;
            }
            ExecutionContext.checkpoint();
            return result.toString();
        }
        private void consume(String value) {
            consume(value, 0, value.length());
        }
        private void consume(String value, int start, int end) {
            ExecutionContext.checkpoint();
            int count = 0;
            for (int cursor = start; cursor < end;) {
                if ((cursor & 1023) == 0) { ExecutionContext.checkpoint(); }
                if (++count > limits.maxCodePoints() - codePoints) { throw error(BindingException.Code.BUDGET_EXCEEDED, "text"); }
                char next = value.charAt(cursor++);
                if (Character.isHighSurrogate(next) && cursor < end && Character.isLowSurrogate(value.charAt(cursor))) { cursor++; }
            }
            codePoints += count;
        }
        private void append(StringBuilder result, String source, int start, int end) {
            consume(source, start, end);
            result.append(source, start, end);
        }
        private void visit(String path) {
            ExecutionContext.checkpoint();
            if (++visited > limits.maxVisitedNodes()) { throw error(BindingException.Code.BUDGET_EXCEEDED, path); }
        }
    }

    private static BindingException error(BindingException.Code code, String path) { return new BindingException(code, path); }
    /** Avoid invoking overridable number methods or materializing a decimal value beyond the text budget. */
    private static void checkNumber(Object value, int available, String path) {
        ExecutionContext.checkpoint();
        if (value instanceof java.math.BigInteger integer) {
            if (value.getClass() != java.math.BigInteger.class) { throw error(BindingException.Code.TYPE_MISMATCH, "data"); }
            requireIntegerDigits(integer, available, path);
        } else if (value instanceof java.math.BigDecimal decimal) {
            if (value.getClass() != java.math.BigDecimal.class) { throw error(BindingException.Code.TYPE_MISMATCH, "data"); }
            var unscaled = decimal.unscaledValue();
            if (unscaled.getClass() != java.math.BigInteger.class) { throw error(BindingException.Code.TYPE_MISMATCH, "data"); }
            requireIntegerDigits(unscaled, available, path);
            long precision = decimal.precision();
            long scale = decimal.scale();
            long exponent = precision - scale - 1;
            long length;
            if (scale >= 0 && exponent >= -6) {
                length = scale == 0 ? precision : precision > scale ? precision + 1 : scale + 2;
            } else {
                length = precision == 1 ? 1 : precision + 1;
                if (exponent != 0) { length += 2L + Long.toString(Math.abs(exponent)).length(); }
            }
            if (decimal.signum() < 0) { length++; }
            if (length > available) { throw error(BindingException.Code.BUDGET_EXCEEDED, path); }
        }
        ExecutionContext.checkpoint();
    }

    private static void requireIntegerDigits(java.math.BigInteger value, int available, String path) {
        var magnitude = value.abs();
        int digits = available - (value.signum() < 0 ? 1 : 0);
        int bits = magnitude.bitLength();
        long lower = bits == 0 ? 1 : (long) Math.floor((bits - 1L) * LOG10_2) + 1;
        if (lower > digits) { throw error(BindingException.Code.BUDGET_EXCEEDED, path); }
        long upper = bits == 0 ? 1 : (long) Math.floor(bits * LOG10_2) + 1;
        if (upper > digits && magnitude.compareTo(java.math.BigInteger.TEN.pow(digits)) >= 0) {
            throw error(BindingException.Code.BUDGET_EXCEEDED, path);
        }
    }

    /** Error diagnostics retain only bounded, valid identifiers, never arbitrary expression source. */
    private static String diagnosticPath(String path) {
        return path != null && path.length() <= 128 && validPath(path) ? path : "expression";
    }

    private static boolean validPath(String path) {
        if (path.isEmpty()) { return false; }
        boolean initial = true;
        for (int index = 0; index < path.length(); index++) {
            if ((index & 1023) == 0) { ExecutionContext.checkpoint(); }
            char next = path.charAt(index);
            boolean letter = next >= 'a' && next <= 'z' || next >= 'A' && next <= 'Z' || next == '_';
            if (next == '.') {
                if (initial) { return false; }
                initial = true;
            } else if (letter || !initial && next >= '0' && next <= '9') { initial = false; }
            else { return false; }
        }
        return !initial;
    }

    private static void checkPath(String path) {
        if (!validPath(Objects.requireNonNull(path, "path"))) { throw new IllegalArgumentException("invalid data path"); }
    }
    private static void checkVariable(String name) {
        checkPath(name); if (name.contains(".")) { throw new IllegalArgumentException("invalid iteration variable"); }
    }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().metadata(metadata()).pageSettings(pageSettings()).nodes(nodes()).limits(limits()).pageRegions(pageRegions).sections(sections).hyperlinkTemplates(hyperlinkTemplates).literalText(literalText).notes(notes).navigation(navigation).cellRules(cellRules).cellContents(cellContents); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private List<Node> nodes = new java.util.ArrayList<>();
        private Limits limits = Limits.DEFAULT;
        private PageRegions pageRegions = PageRegions.EMPTY;
        private List<SectionNode> sections = List.of();
        private Map<String, String> hyperlinkTemplates = Map.of();
        private boolean literalText;
        private List<Note> notes = List.of();
        private DocumentNavigation navigation = DocumentNavigation.NONE;
        private List<CellRule> cellRules = List.of();
        private List<CellContent> cellContents = List.of();

        private Builder() { }
        public Builder metadata(DocumentMetadata value) { metadata = java.util.Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = java.util.Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder nodes(List<Node> value) { nodes = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder limits(Limits value) { limits = java.util.Objects.requireNonNull(value, "limits"); return this; }
        public Builder pageRegions(PageRegions value) { pageRegions = Objects.requireNonNull(value, "pageRegions"); return this; }
        public Builder sections(List<SectionNode> values) { sections = List.copyOf(values); return this; }
        public Builder literalText(boolean value) { literalText = value; return this; }
        /** Static global IDs/anchors; text is bound in root scope and shares the render budget. */
        public Builder notes(List<Note> values) { notes = List.copyOf(values); return this; }
        public Builder navigation(DocumentNavigation value) { navigation = Objects.requireNonNull(value, "navigation"); return this; }
        /** Conditions attach by prototype identity, are evaluated in lexical scope, and share work budgets. */
        public Builder cellRules(List<CellRule> values) { cellRules = List.copyOf(values); return this; }
        public Builder cellContents(List<CellContent> values) { cellContents = List.copyOf(values); return this; }
        /** Maps passive prototype URI keys to expressions bound in the link's lexical scope.
         * Keys are valid ExternalDestination URIs; final values undergo the same validation after binding.
         * Does not fetch resources. Copies the map immediately; each render uses independent state.
         */
        public Builder hyperlinkTemplates(Map<String, String> values) { hyperlinkTemplates = Map.copyOf(values); return this; }
        public Builder node(Node value) { nodes.add(Objects.requireNonNull(value, "node")); return this; }
        public Builder block(Block value) { return node(new ValueBlock(value)); }
        public Builder when(String path, java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            return when(path, TemplateCondition.Operator.TRUE, "", yes, no);
        }
        /** Adds a scalar comparison; branch callbacks are snapshotted before returning. */
        public Builder when(String path, TemplateCondition.Operator operator, String value,
                java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            var left = new Nodes(); var right = new Nodes(); yes.accept(left); no.accept(right);
            return node(new When(path, operator, value, left.snapshot(), right.snapshot()));
        }
        public Builder each(String path, String variable, java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value); return node(new Each(path, variable, value.snapshot()));
        }
        public Builder columns(String id, int count, double gap, boolean balanced, List<Double> weights,
                java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value);
            return node(new Columns(id, count, gap, balanced, weights, value.snapshot()));
        }
        public Builder columns(java.util.function.Consumer<Columns.Builder> configure) {
            var value = Columns.builder(); configure.accept(value); return node(value.build());
        }
        public Builder grid(String id, int tracks, double columnGap, double rowGap,
                java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value);
            return node(new Grid(id, tracks, columnGap, rowGap, value.snapshot()));
        }
        public DocumentTemplate build() { return new DocumentTemplate(metadata, pageSettings, nodes, limits, pageRegions, sections, hyperlinkTemplates, literalText, notes, navigation, cellRules, cellContents); }
    }
}
