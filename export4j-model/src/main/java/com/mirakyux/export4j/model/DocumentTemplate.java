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
 */
public record DocumentTemplate(DocumentMetadata metadata, PageSettings pageSettings, List<Node> nodes, Limits limits) {
    private static final double LOG10_2 = 0.3010299956639812;

    public DocumentTemplate {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(pageSettings, "pageSettings");
        nodes = List.copyOf(nodes);
        Objects.requireNonNull(limits, "limits");
    }

    public sealed interface Node permits ValueBlock, When, Each, Rows { }
    /** Task-confined nested template authoring; branches are snapshotted when the callback returns. */
    public static final class Nodes {
        private final List<Node> values = new ArrayList<>();
        private Nodes() { }
        public Nodes node(Node value) { values.add(Objects.requireNonNull(value, "node")); return this; }
        public Nodes block(Block value) { return node(new ValueBlock(value)); }
        public Nodes when(String path, java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            var left = new Nodes(); var right = new Nodes(); yes.accept(left); no.accept(right);
            return node(new When(path, left.snapshot(), right.snapshot()));
        }
        public Nodes each(String path, String variable, java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value); return node(new Each(path, variable, value.snapshot()));
        }
        private List<Node> snapshot() { return List.copyOf(values); }
    }
    /** Supports paragraphs (including styled runs), headings, lists, page breaks and semantic tables with
     * bounded Paragraph/Table block cells. Descendants share expansion budgets and retain their formats. */
    public record ValueBlock(Block prototype) implements Node {
        public ValueBlock { Objects.requireNonNull(prototype, "prototype"); }
    }
    /** Path must resolve to a boolean. Both branches are immutable. */
    public record When(String path, List<Node> yes, List<Node> no) implements Node {
        public When { checkPath(path); yes = List.copyOf(yes); no = List.copyOf(no); }
    }
    /** Iteration variable is lexical; generated block IDs receive a deterministic iteration suffix.
     * Static field targets declared in this copy scope relocate to the current copy, including targets in
     * conditional branches. External targets and dynamically bound target strings remain unchanged. */
    public record Each(String path, String variable, List<Node> children) implements Node {
        public Each { checkPath(path); checkVariable(variable); children = List.copyOf(children); }
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
        public enum Code { MISSING_VALUE, TYPE_MISMATCH, INVALID_EXPRESSION, UNSUPPORTED_BLOCK, BUDGET_EXCEEDED, EMPTY_TABLE }
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
        var state = new Binding();
        Object frozen = state.freeze(Objects.requireNonNull(data, "data"), 0);
        if (!(frozen instanceof Map<?, ?> map)) { throw new AssertionError(); }
        var root = new Scope(map, null);
        state.expand(nodes, root, "", 0, Map.of());
        return new Document(new DocumentMetadata(state.text(metadata.title(), root), state.text(metadata.author(), root)), pageSettings, state.blocks);
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

    private final class Binding {
        private int visited;
        private int rows;
        private int codePoints;
        private int dataNodes;
        private int blockCount;
        private final List<Block> blocks = new ArrayList<>();

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
            if (depth > limits.maxDepth()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "nodes"); }
            for (var node : source) {
                visit("nodes");
                switch (node) {
                    case ValueBlock value -> add(bind(value.prototype(), scope, suffix,
                            blockIds(value.prototype(), suffix, relocations), depth));
                    case When when -> {
                        Object value = get(when.path(), scope);
                        if (!(value instanceof Boolean flag)) { throw error(BindingException.Code.TYPE_MISMATCH, when.path()); }
                        expand(flag ? when.yes() : when.no(), scope, suffix, depth + 1, relocations);
                    }
                    case Each each -> {
                        var items = collection(each.path(), scope);
                        for (int i = 0; i < items.size(); i++) {
                            visit("iterations");
                            var nested = new Scope(Map.of(each.variable(), items.get(i)), scope);
                            String nestedSuffix = suffix + "@" + i;
                            var nestedIds = new HashMap<>(relocations);
                            collectIds(each.children(), nestedSuffix, nestedIds, depth + 1);
                            expand(each.children(), nested, nestedSuffix, depth + 1, nestedIds);
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
                        add(new Table(table.id() + suffix, table.columns(), result, table.headers().size(), table.format()));
                    }
                }
            }
        }

        private void add(Block block) {
            ExecutionContext.checkpoint();
            blocks.add(block);
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
                    case When when -> { collectIds(when.yes(), suffix, ids, depth + 1); collectIds(when.no(), suffix, ids, depth + 1); }
                    case Rows table -> {
                        ids.put(table.id(), table.id() + suffix);
                        for (var row : table.headers()) { collectRowIds(row, suffix, ids); }
                    }
                    case Each ignored -> { /* A nested iteration owns its own lexical copy scope. */ }
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
                        for (var ignored : row.cells()) { visit("ids"); }
                    }
                }
            });
        }
        private void collectRowIds(TableRow row, String suffix, Map<String, String> ids) {
            visit("ids");
            for (var cell : row.cells()) {
                visit("ids");
                for (var block : cell.blocks()) { collectBlockIds(block, suffix, ids); }
            }
        }
        private TableRow bindRow(TableRow row, Scope scope, String suffix, Map<String, String> relocations, int depth) {
            ExecutionContext.checkpoint();
            visit("rows");
            if (depth > limits.maxDepth() || ++rows > limits.maxRows()) { throw error(BindingException.Code.BUDGET_EXCEEDED, "rows"); }
            return new TableRow(row.cells().stream().map(cell -> {
                visit("cells");
                var format = cell.format();
                if (cell.hasBlocks()) {
                    var children = cell.blocks().stream().map(block -> bind(block, scope, suffix, relocations, depth + 1)).toList();
                    return new TableCell("", format, List.of(), List.of(), List.of(), cell.columnSpan(), cell.rowSpan(), children);
                }
                var prototype = new Paragraph("cell", cell.text(), new ParagraphFormat(format.fontSize(), format.lineHeight(), 0, 0, format.alignment(), 0, 0, false), cell.inlines(), cell.images(), cell.fields());
                var bound = bindParagraph(prototype, "cell", scope, relocations);
                return new TableCell(bound.text(), format, bound.inlines(), bound.images(), bound.fields(), cell.columnSpan(), cell.rowSpan());
            }).toList());
        }
        private Block bind(Block block, Scope scope, String suffix, Map<String, String> relocations, int depth) {
            consumeBlock(depth);
            String id = block.id() + suffix;
            return switch (block) {
                case Paragraph p -> bindParagraph(p, id, scope, relocations);
                case Heading h -> {
                    var p = bindParagraph(h.paragraph(), id, scope, relocations);
                    yield new Heading(id, p.text(), h.level(), h.format(), p.inlines());
                }
                case PageBreak ignored -> new PageBreak(id);
                case BulletItem b -> {
                    var p = bindParagraph(b.paragraph(), id, scope, relocations);
                    yield new BulletItem(id, p.text(), b.marker(), b.level(), b.format(), p.inlines(), b.markerStyle());
                }
                case NumberedItem n -> {
                    var p = bindParagraph(n.paragraph(), id, scope, relocations);
                    yield new NumberedItem(id, n.listId(), p.text(), n.restartAt(), n.format(), n.level(), p.inlines(), n.markerStyle());
                }
                case Table t -> new Table(id, t.columns(), t.rows().stream()
                        .map(row -> bindRow(row, scope, suffix, relocations, depth + 1)).toList(), t.headerRows(), t.format());
                default -> throw error(BindingException.Code.UNSUPPORTED_BLOCK, "block");
            };
        }
        private Paragraph bindParagraph(Paragraph p, String id, Scope scope, Map<String, String> relocations) {
            if (p.images().isEmpty() && p.fields().isEmpty()) {
                return p.inlines().isEmpty() ? new Paragraph(id, text(p.text(), scope), p.format())
                        : new Paragraph(id, p.inlines().stream().map(run -> {
                            visit("inlines");
                            return new InlineText(text(run.text(), scope), run.style(), text(run.hyperlink(), scope));
                        }).toList(), p.format());
            }
            var content = p.content().stream().map(item -> {
                visit("inlines");
                return switch (item) {
                case InlineText run -> (InlineContent) new InlineText(text(run.text(), scope), run.style(), text(run.hyperlink(), scope));
                case InlineImage image -> new InlineImage(image.resource(), image.width(), image.height(), image.baselineShift(), text(image.altText(), scope), text(image.hyperlink(), scope));
                case InlineField field -> new InlineField(field.kind(), fieldTarget(field.targetId(), scope, relocations), field.width(), field.style());
                };
            }).toList();
            return Paragraph.mixed(id, content, p.format());
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
    public Builder toBuilder() { return new Builder().metadata(metadata()).pageSettings(pageSettings()).nodes(nodes()).limits(limits()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private DocumentMetadata metadata = DocumentMetadata.EMPTY;
        private PageSettings pageSettings = PageSettings.A4;
        private List<Node> nodes = new java.util.ArrayList<>();
        private Limits limits = Limits.DEFAULT;

        private Builder() { }
        public Builder metadata(DocumentMetadata value) { metadata = java.util.Objects.requireNonNull(value, "metadata"); return this; }
        public Builder pageSettings(PageSettings value) { pageSettings = java.util.Objects.requireNonNull(value, "pageSettings"); return this; }
        public Builder nodes(List<Node> value) { nodes = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder limits(Limits value) { limits = java.util.Objects.requireNonNull(value, "limits"); return this; }
        public Builder node(Node value) { nodes.add(Objects.requireNonNull(value, "node")); return this; }
        public Builder block(Block value) { return node(new ValueBlock(value)); }
        public Builder when(String path, java.util.function.Consumer<Nodes> yes, java.util.function.Consumer<Nodes> no) {
            var left = new Nodes(); var right = new Nodes(); yes.accept(left); no.accept(right);
            return node(new When(path, left.snapshot(), right.snapshot()));
        }
        public Builder each(String path, String variable, java.util.function.Consumer<Nodes> configure) {
            var value = new Nodes(); configure.accept(value); return node(new Each(path, variable, value.snapshot()));
        }
        public DocumentTemplate build() { return new DocumentTemplate(metadata, pageSettings, nodes, limits); }
    }
}
