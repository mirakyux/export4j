// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.PageBackend;
import com.mirakyux.export4j.graphics.PageNavigation;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutFragment;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.layout.core.DocumentLayouter;
import com.mirakyux.export4j.layout.core.DocumentPainter;
import com.mirakyux.export4j.layout.core.InlineLinkGeometry;
import com.mirakyux.export4j.layout.core.SemanticStreamingTableLayouter;
import com.mirakyux.export4j.layout.core.StreamingTableLimits;
import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.StreamingTable;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.TableRowSource;
import com.mirakyux.export4j.text.InlineFieldInk;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextLine;

/** Task-confined explicit replay. Holds only finite declarations, one source group and one body page.
 * Reopens a caller-owned stable snapshot, never reissues an implicit database query. Complete semantic
 * digests are compared before successful writer.finish; source changes fail atomic Path output.
 * A bounded prefix probe is a separate optional pass. Fonts/resources remain explicit and borrowed.
 */
public final class StreamingReplayRequest {
    private final StreamingTable table;
    private final TableRowSource.Factory factory;
    private final FontGroup fonts;
    private final Map<String, FontGroup> roles;
    private final boolean replay;
    private StreamingReplayPlan plan = StreamingReplayPlan.EMPTY;
    private StreamingTableLimits limits = StreamingTableLimits.DEFAULT;
    private PageBackend backend;
    private ExecutionOptions options = ExecutionOptions.DEFAULT;
    private Consumer<ContextualDiagnostic> diagnostics = ignored -> { };

    StreamingReplayRequest(StreamingTable table, TableRowSource.Factory source, FontGroup fonts, Map<String, FontGroup> roles) {
        this(table, source, fonts, roles, true);
    }
    StreamingReplayRequest(StreamingTable table, TableRowSource.Factory source, FontGroup fonts, Map<String, FontGroup> roles, boolean replay) {
        this.table = Objects.requireNonNull(table, "table"); factory = Objects.requireNonNull(source, "source");
        this.fonts = fonts; this.roles = Map.copyOf(roles);
        this.replay = replay;
    }
    public StreamingReplayRequest plan(StreamingReplayPlan value) { plan = Objects.requireNonNull(value, "plan"); return this; }
    public StreamingReplayRequest backend(PageBackend value) { backend = Objects.requireNonNull(value, "backend"); return this; }
    public StreamingReplayRequest limits(StreamingTableLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
    public StreamingReplayRequest limits(Consumer<StreamingTableLimits.Builder> configure) {
        var builder = limits.toBuilder(); configure.accept(builder); return limits(builder.build());
    }
    public StreamingReplayRequest options(ExecutionOptions value) { options = Objects.requireNonNull(value, "options"); return this; }
    public StreamingReplayRequest options(Consumer<ExecutionOptions.Builder> configure) {
        var builder = options.toBuilder(); configure.accept(builder); return options(builder.build());
    }
    public StreamingReplayRequest diagnostics(Consumer<ContextualDiagnostic> value) { diagnostics = Objects.requireNonNull(value, "diagnostics"); return this; }
    public StreamingExportSummary to(Path target) throws IOException {
        Objects.requireNonNull(backend, "backend must be configured");
        return DocumentEngine.writeAtomically(target, this::to);
    }
    public StreamingExportSummary to(OutputStream output) throws IOException {
        Objects.requireNonNull(output, "output");
        var run = new Run(plan, limits, Objects.requireNonNull(backend, "backend must be configured"), diagnostics);
        return ExecutionContext.execute(options, () -> run.write(output));
    }

    private record Anchor(int page, Point point) { }
    private record PendingLink(int page, Rect bounds, String target) { }
    private record Probe(List<Double> widths, byte[] digest, int rows, boolean complete) { }
    private record NoteLayout(String id, long row, ParagraphLayout layout) { }
    private record TextSource(String text, List<InlineText> inlines, double lineHeight) { }

    private final class Run {
        private final StreamingReplayPlan plan;
        private final StreamingTableLimits limits;
        private final PageBackend backend;
        private final Consumer<ContextualDiagnostic> diagnostics;
        private final ParagraphLayouter text = new ParagraphLayouter();
        private final Map<String, Anchor> anchors = new HashMap<>();
        private final Map<String, String> labels = new HashMap<>();
        private final List<PendingLink> pending = new ArrayList<>();
        private final List<NoteLayout> noteLayouts = new ArrayList<>();
        private final Map<Long, Double> noteHeights = new HashMap<>();
        private long fields;
        private long generated;
        private long diagnosticCount;
        private long externalCount;
        private long commands;
        private long extraLines;
        private int contentsPages;
        private int finalPages;

        private Run(StreamingReplayPlan plan, StreamingTableLimits limits, PageBackend backend, Consumer<ContextualDiagnostic> diagnostics) {
            this.plan = plan; this.limits = limits; this.backend = backend; this.diagnostics = diagnostics;
        }

        private StreamingExportSummary write(OutputStream target) throws IOException {
            ExecutionContext.checkpoint();
            if (!replay) { return writeSingle(target); }
            var probe = probe();
            var specification = table.toBuilder().columnWidths(probe == null ? table.columnWidths() : probe.widths())
                    .semanticCells(true).expectedPages(0).build();
            prepareNotes(specification);
            for (var declaration : plan.targets()) {
                if (!declaration.number().isEmpty()) { labels.put(declaration.id(), declaration.number()); }
            }
            SemanticStreamingTableLayouter.Summary first;
            byte[] digest;
            try (var source = Objects.requireNonNull(factory.open(), "row source")) {
                var hashed = new HashedSource(source, probe);
                first = new SemanticStreamingTableLayouter().layout(specification, hashed, fonts, roles, limits, noteHeights,
                        (page, notes, sources) -> collect(appendNotes(page, notes), sources));
                hashed.verifyProbe(); digest = hashed.digest.finish();
            }
            for (var declared : plan.targets()) {
                if (!anchors.containsKey(declared.id())) { throw new IOException("streaming replay target was not found"); }
            }
            for (var note : noteLayouts) {
                if (!anchors.containsKey(note.id())) { throw new IOException("streaming replay note was not placed"); }
            }
            var contents = contents(specification);
            contentsPages = contents == null ? 0 : contents.pages().size();
            finalPages = Math.addExact(contentsPages, first.pages());
            if (finalPages > limits.document().maxPages() || table.expectedPages() > 0 && table.expectedPages() != finalPages) {
                throw new IOException("streaming replay final page total exceeds budget or differs from declaration");
            }
            if (contents != null) { for (var page : contents.pages()) { collectLinks(page); } }
            var navigation = navigation();
            var output = new CountedOutput(ExecutionContext.output(target));
            SemanticStreamingTableLayouter.Summary result;
            try (var writer = Objects.requireNonNull(backend.open(table.metadata(), output), "page writer")) {
                writer.configureNavigation(navigation);
                if (contents != null) {
                    for (var page : contents.pages()) {
                        var resolved = resolve(page, Map.of());
                        var display = DocumentPainter.paintPage(resolved); commands += display.commands().size();
                        extraLines += lineCount(resolved); writer.accept(display);
                    }
                }
                try (var source = Objects.requireNonNull(factory.open(), "row source")) {
                    var hashed = new HashedSource(source, probe);
                    result = new SemanticStreamingTableLayouter().layout(specification, hashed, fonts, roles, limits, noteHeights,
                            (page, notes, sources) -> {
                                var withNotes = appendNotes(page, notes);
                                var shifted = new LayoutPage(page.pageIndex() + contentsPages, page.size(), page.contentBounds(), withNotes.elements());
                                var resolved = resolve(shifted, sources);
                                extraLines += lineCount(withNotes) - lineCount(page);
                                var links = externalLinks(resolved, sources);
                                var display = DocumentPainter.paintPage(resolved); commands = Math.addExact(commands, display.commands().size());
                                writer.accept(display, links);
                            });
                    hashed.verifyProbe();
                    if (!MessageDigest.isEqual(digest, hashed.digest.finish()) || result.bodyRows() != first.bodyRows() || result.pages() != first.pages()) {
                        throw new IOException("streaming replay source changed between passes");
                    }
                }
                if (extraLines > limits.document().maxLines() - result.outputLines()) { throw new IOException("streaming replay generated line budget exceeded"); }
                writer.finish();
            }
            return new StreamingExportSummary(result.bodyRows(), finalPages, result.sourceCells(), result.sourceCodePoints(),
                    result.outputLines() + extraLines, commands, diagnosticCount, output.count);
        }

        private StreamingExportSummary writeSingle(OutputStream target) throws IOException {
            if (!plan.equals(StreamingReplayPlan.EMPTY)) { throw new IllegalArgumentException("single-pass semantic output cannot apply a replay plan"); }
            finalPages = table.expectedPages();
            var output = new CountedOutput(ExecutionContext.output(target));
            SemanticStreamingTableLayouter.Summary result;
            try (var writer = Objects.requireNonNull(backend.open(table.metadata(), output), "page writer")) {
                try (var source = Objects.requireNonNull(factory.open(), "row source")) {
                    result = new SemanticStreamingTableLayouter().layout(table, source, fonts, roles, limits, Map.of(),
                            (page, notes, sources) -> {
                                var resolved = resolve(page, sources); var display = DocumentPainter.paintPage(resolved);
                                commands = Math.addExact(commands, display.commands().size());
                                writer.accept(display, externalLinks(resolved, sources));
                            });
                }
                writer.finish();
            }
            return new StreamingExportSummary(result.bodyRows(), result.pages(), result.sourceCells(), result.sourceCodePoints(),
                    result.outputLines(), commands, diagnosticCount, output.count);
        }

        private Probe probe() throws IOException {
            if (plan.columns().isEmpty()) { return null; }
            if (plan.columns().size() != table.columnWidths().size()) { throw new IllegalArgumentException("streaming probe column count differs"); }
            var rows = new ArrayList<TableRow>(); var digest = new StreamingSourceDigest(plan.maxProbeBytes()); boolean complete = false;
            try (var source = Objects.requireNonNull(factory.open(), "row source")) {
                for (int row = 0; row < plan.maxProbeRows(); row++) {
                    ExecutionContext.checkpoint(); var next = Objects.requireNonNull(source.next(), "row source returned null");
                    if (next.isEmpty()) { complete = true; break; }
                    precheck(next.orElseThrow()); digest.add(next.orElseThrow()); rows.add(next.orElseThrow());
                }
            }
            var combined = new ArrayList<>(table.headerRows()); combined.addAll(rows);
            if (rows.isEmpty()) { combined.add(new TableRow(java.util.stream.IntStream.range(0, plan.columns().size())
                    .mapToObj(ignored -> new TableCell("", CellFormat.of(1))).toList())); }
            var sample = new Table(table.id(), plan.columns(), combined, table.headerRows().size(), new TableFormat(table.padding(), 0, 0, table.allowRowSplit()));
            var widths = new DocumentLayouter(text, limits.document()).resolveTableColumns(sample,
                    new ComponentContext(table.pageSettings().contentWidth(), table.pageSettings().contentHeight(), fonts, 0, 0, 1, roles));
            return new Probe(widths, digest.finish(), rows.size(), complete);
        }

        private void prepareNotes(StreamingTable specification) {
            long codePoints = 0;
            for (int index = 0; index < plan.footnotes().size(); index++) {
                var note = plan.footnotes().get(index); String label = "[" + (index + 1) + "]";
                labels.put(note.id(), label);
                codePoints += note.text().codePointCount(0, note.text().length());
                if (codePoints > limits.maxBufferedCodePoints()) { throw new IllegalArgumentException("streaming note text budget exceeded"); }
                generated(label.codePointCount(0, label.length()) + 1L + note.text().codePointCount(0, note.text().length()));
                var layout = text.layout(label + " " + note.text(), fonts, specification.pageSettings().contentWidth(), ParagraphStyle.of(10));
                noteLayouts.add(new NoteLayout(note.id(), note.bodyRow(), layout));
                noteHeights.merge(note.bodyRow(), layout.height() + 4, Double::sum);
            }
        }

        private LayoutPage appendNotes(LayoutPage page, List<Long> groups) {
            if (groups.isEmpty()) { return page; }
            double height = groups.stream().mapToDouble(noteHeights::get).sum();
            double y = page.contentBounds().y() + page.contentBounds().height() - height;
            var elements = new ArrayList<LayoutFragment>(page.elements());
            for (var note : noteLayouts) {
                if (!groups.contains(note.row())) { continue; }
                y += 4;
                elements.add(new ParagraphFragment(note.id(), 0, 0, new Point(page.contentBounds().x(), y), note.layout()));
                y += note.layout().height();
            }
            return new LayoutPage(page.pageIndex(), page.size(), page.contentBounds(), elements);
        }

        private void collect(LayoutPage page, Map<SemanticStreamingTableLayouter.CellKey, TableCell> sources) {
            for (var fragment : page.elements()) {
                if (fragment instanceof TableFragment root && root.blockId().equals(table.id())) {
                    for (var row : root.rows()) {
                        if (row.header()) { continue; }
                        long bodyRow = (long) row.rowIndex() - table.headerRows().size();
                        double y = row.cells().stream().mapToDouble(cell -> cell.bounds().y()).min().orElse(0);
                        for (var declared : plan.targets()) {
                            boolean covered = declared.bodyRow() == bodyRow;
                            if (!covered) {
                                for (var cell : row.cells()) {
                                    var original = sources.get(new SemanticStreamingTableLayouter.CellKey(row.rowIndex(), cell.columnIndex()));
                                    if (original != null && declared.bodyRow() > bodyRow && declared.bodyRow() < bodyRow + original.rowSpan()) { covered = true; }
                                }
                            }
                            if (covered) { anchors.putIfAbsent(declared.id(), new Anchor(page.pageIndex(), new Point(root.origin().x(), root.origin().y() + y))); }
                        }
                    }
                }
                SemanticFragments.walk(fragment, node -> {
                    for (var declared : plan.targets()) {
                        if (declared.bodyRow() == -1 && declared.blockId().equals(node.fragment().blockId())) {
                            anchors.putIfAbsent(declared.id(), new Anchor(page.pageIndex(), node.origin()));
                        }
                    }
                    for (var note : noteLayouts) {
                        if (note.id().equals(node.fragment().blockId())) { anchors.putIfAbsent(note.id(), new Anchor(page.pageIndex(), node.origin())); }
                    }
                }, ignored -> { });
            }
            collectLinks(page);
        }

        private void collectLinks(LayoutPage page) {
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, ignored -> { }, leaf -> {
                    double step = leaf.layout().lines().isEmpty() ? 0 : leaf.layout().height() / leaf.layout().lines().size();
                    for (var line : leaf.layout().lines()) {
                        for (var box : line.fields()) {
                            var kind = box.field().kind();
                            if (kind != InlineField.Kind.REFERENCE_PAGE && kind != InlineField.Kind.REFERENCE_NUMBER) { continue; }
                            if (pending.size() >= limits.maxLinks()) { throw budget(leaf, page.pageIndex(), box.sourceIndex(), "streaming replay internal link budget exceeded"); }
                            pending.add(new PendingLink(page.pageIndex(), new Rect(leaf.origin().x() + line.offsetX() + box.x(),
                                    leaf.origin().y() + line.baselineY() - leaf.layout().lines().getFirst().baselineY(), box.field().width(), step), box.field().targetId()));
                        }
                    }
                });
            }
        }

        private PageNavigation navigation() throws IOException {
            var bookmarks = new ArrayList<DocumentExtras.Bookmark>();
            for (var declared : plan.bookmarks()) {
                var anchor = anchors.get(declared.targetId());
                bookmarks.add(new DocumentExtras.Bookmark(declared.title(), declared.level(), anchor.page() + contentsPages, anchor.point()));
            }
            var links = new ArrayList<DocumentExtras.Link>();
            for (var link : pending) {
                var anchor = anchors.get(link.target());
                if (anchor == null) { throw new IOException("streaming replay reference requires a declared target"); }
                // Body links are collected first; contents links are appended after contents page count is known.
                int index = links.size(); int bodyCount = pending.size() - contentsLinkCount;
                links.add(new DocumentExtras.Link(link.page() + (index < bodyCount ? contentsPages : 0), link.bounds(),
                        anchor.page() + contentsPages, anchor.point()));
            }
            return new PageNavigation(finalPages, bookmarks, links);
        }
        private int contentsLinkCount;

        private DocumentLayout contents(StreamingTable specification) {
            if (plan.contentsTitle().isEmpty()) { return null; }
            generated(plan.contentsTitle().codePointCount(0, plan.contentsTitle().length()));
            var blocks = new ArrayList<Block>();
            blocks.add(new Paragraph("streaming-contents-title", plan.contentsTitle(), ParagraphFormat.of(16)));
            int index = 0;
            for (var bookmark : plan.bookmarks()) {
                generated(bookmark.title().codePointCount(0, bookmark.title().length()) + 1L);
                blocks.add(Paragraph.mixed("streaming-contents-" + index++, List.of(new InlineText(bookmark.title() + " ", InlineStyle.of(12)),
                        new InlineField(InlineField.Kind.REFERENCE_PAGE, bookmark.targetId(), 40, InlineStyle.of(12))), ParagraphFormat.of(12)));
            }
            var result = new DocumentLayouter(text, limits.document()).layout(new Document(table.metadata(), specification.pageSettings(), blocks), fonts, roles);
            for (var page : result.pages()) {
                for (var fragment : page.elements()) {
                    int[] count = {0}; SemanticFragments.walk(fragment, ignored -> { }, leaf -> leaf.layout().lines().forEach(line -> count[0] += line.fields().size()));
                    contentsLinkCount += count[0];
                }
            }
            return result;
        }

        private void generated(long count) {
            if (count > limits.maxGeneratedCodePoints() - generated) { throw new IllegalArgumentException("streaming generated text budget exceeded"); }
            generated += count;
        }

        private LayoutPage resolve(LayoutPage page, Map<SemanticStreamingTableLayouter.CellKey, TableCell> sources) {
            var elements = new ArrayList<LayoutFragment>();
            for (var fragment : page.elements()) {
                elements.add(SemanticFragments.map(fragment, fonts, roles, leaf -> {
                    var source = leaf.layout(); var lines = new ArrayList<TextLine>();
                    double step = source.lines().isEmpty() ? 0 : source.height() / source.lines().size();
                    for (var diagnostic : source.diagnostics()) { diagnostic(leaf, page.pageIndex(), "STREAMING_REPLAY", diagnostic); }
                    for (var line : source.lines()) {
                        if (line.fields().isEmpty()) { lines.add(line); continue; }
                        var outlines = new ArrayList<>(line.outlines());
                        for (var box : line.fields()) {
                            ExecutionContext.checkpoint();
                            if (++fields > limits.maxFields()) { throw budget(leaf, page.pageIndex(), box.sourceIndex(), "streaming replay field budget exceeded"); }
                            var field = box.field(); var anchor = anchors.get(field.targetId());
                            String value = switch (field.kind()) {
                                case PAGE -> Integer.toString(page.pageIndex() + 1);
                                case PAGES -> {
                                    if (finalPages == 0) { throw failure(leaf, page.pageIndex(), box.sourceIndex(), "single-pass PAGES needs an exact declared total", null); }
                                    yield Integer.toString(finalPages);
                                }
                                case REFERENCE_PAGE -> {
                                    if (anchor == null) { throw failure(leaf, page.pageIndex(), box.sourceIndex(), "streaming reference target missing", null); }
                                    yield Integer.toString(anchor.page() + contentsPages + 1);
                                }
                                case REFERENCE_NUMBER -> {
                                    var number = labels.get(field.targetId());
                                    if (anchor == null || number == null) { throw failure(leaf, page.pageIndex(), box.sourceIndex(), "streaming reference label missing", null); }
                                    yield number;
                                }
                                case NOTE_MARKER -> throw failure(leaf, page.pageIndex(), box.sourceIndex(), "use a declared note number reference", null);
                            };
                            generated += value.codePointCount(0, value.length());
                            if (generated > limits.maxGeneratedCodePoints()) { throw budget(leaf, page.pageIndex(), box.sourceIndex(), "streaming generated text budget exceeded"); }
                            var group = field.style().fontRole().isEmpty() ? fonts : roles.get(field.style().fontRole());
                            if (group == null) { throw failure(leaf, page.pageIndex(), box.sourceIndex(), "streaming field font role missing", null); }
                            try {
                                var resolved = InlineFieldInk.resolve(box, value, step, group, text); outlines.set(box.outlineIndex(), resolved.outline());
                                for (var diagnostic : resolved.diagnostics()) { diagnostic(leaf, page.pageIndex(), "INLINE_FIELD:" + box.sourceIndex(), diagnostic); }
                            } catch (IllegalArgumentException cause) { throw failure(leaf, page.pageIndex(), box.sourceIndex(), "streaming field resolution failed", cause); }
                        }
                        lines.add(new TextLine(line.sourceStart(), line.sourceEnd(), line.offsetX(), line.baselineY(), line.width(), line.runs(), outlines, line.images()));
                    }
                    return new ParagraphLayout(source.width(), source.height(), lines, source.diagnostics());
                }));
            }
            return new LayoutPage(page.pageIndex(), page.size(), page.contentBounds(), elements);
        }

        private void diagnostic(SemanticFragments.Text leaf, int page, String region, com.mirakyux.export4j.text.TextDiagnostic diagnostic) {
            if (++diagnosticCount > limits.maxDiagnostics()) { throw budget(leaf, page, diagnostic.sourceStart(), "streaming replay diagnostic budget exceeded"); }
            diagnostics.accept(new ContextualDiagnostic(leaf.blockId(), page, region, leaf.rowIndex(), leaf.columnIndex(), diagnostic, leaf.sourcePath()));
            ExecutionContext.checkpoint();
        }

        private List<DocumentExtras.ExternalLink> externalLinks(LayoutPage page, Map<SemanticStreamingTableLayouter.CellKey, TableCell> sources) {
            var blocks = new HashMap<String, Block>();
            for (var cell : sources.values()) { if (cell.hasBlocks()) { SemanticTree.walk(cell.blocks(), visit -> blocks.put(visit.block().id(), visit.block())); } }
            var result = new ArrayList<DocumentExtras.ExternalLink>();
            for (var fragment : page.elements()) {
                SemanticFragments.walk(fragment, ignored -> { }, leaf -> {
                    TextSource source;
                    if (leaf.blockId().equals(table.id())) {
                        var cell = sources.get(new SemanticStreamingTableLayouter.CellKey(leaf.rowIndex(), leaf.columnIndex()));
                        if (cell == null || cell.hasBlocks()) { throw new IllegalArgumentException("streaming source owner missing"); }
                        source = new TextSource(cell.text(), cell.inlines(), cell.format().lineHeight());
                    } else if (blocks.get(leaf.blockId()) instanceof Paragraph paragraph) {
                        source = new TextSource(paragraph.text(), paragraph.inlines(), paragraph.format().lineHeight());
                    } else if (blocks.get(leaf.blockId()) instanceof Table nested) {
                        var cell = nested.grid().anchorAt(leaf.rowIndex(), leaf.columnIndex()).orElseThrow().cell();
                        source = new TextSource(cell.text(), cell.inlines(), cell.format().lineHeight());
                    } else { return; }
                    InlineLinkGeometry.collect(source.text(), source.inlines(), source.lineHeight(), leaf.origin(), leaf.layout(), page.pageIndex(), page.size().width(),
                            (link, at) -> {
                                if (++externalCount > limits.maxLinks()) { throw budget(leaf, page.pageIndex(), at, "streaming external link budget exceeded"); }
                                result.add(link);
                            });
                });
            }
            return List.copyOf(result);
        }
        private DocumentLayoutException failure(SemanticFragments.Text leaf, int page, int source, String message, Throwable cause) {
            return new DocumentLayoutException(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, leaf.blockId(), leaf.blockIndex(), page,
                    leaf.rowIndex(), leaf.columnIndex(), source, leaf.sourcePath(), message, cause);
        }
        private DocumentLayoutException budget(SemanticFragments.Text leaf, int page, int source, String message) {
            return new DocumentLayoutException(DocumentLayoutException.Code.BUDGET_EXCEEDED, leaf.blockId(), leaf.blockIndex(), page,
                    leaf.rowIndex(), leaf.columnIndex(), source, leaf.sourcePath(), message, null);
        }

        private void precheck(TableRow row) {
            long[] counts = {0, 0, 0};
            for (var cell : row.cells()) { precheckCell(cell, counts); }
        }
        private void precheckCell(TableCell cell, long[] counts) {
            ExecutionContext.checkpoint();
            if (cell.hasBlocks()) {
                for (var block : cell.blocks()) {
                    if (++counts[2] > limits.document().maxParagraphs()) { throw new IllegalArgumentException("streaming source node budget exceeded before digest"); }
                    if (block instanceof Paragraph paragraph) { precheckText(paragraph.text(), paragraph.inlines().size(), counts); }
                    else { for (var row : ((Table) block).rows()) { for (var child : row.cells()) { precheckCell(child, counts); } } }
                }
            } else { precheckText(cell.text(), cell.inlines().size(), counts); }
        }
        private void precheckText(String value, int runs, long[] counts) {
            counts[1] += Math.max(1, runs);
            if (Math.max(1, runs) > limits.maxCellInlines() || counts[1] > limits.maxRowInlines()) {
                throw new IllegalArgumentException("streaming source run budget exceeded before digest");
            }
            int points = 0;
            for (int offset = 0; offset < value.length();) {
                if ((points & 1023) == 0) { ExecutionContext.checkpoint(); }
                if (++points > limits.maxCellCodePoints() || ++counts[0] > limits.maxRowCodePoints()) {
                    throw new IllegalArgumentException("streaming source text budget exceeded before digest");
                }
                offset += Character.charCount(value.codePointAt(offset));
            }
        }
        private long lineCount(LayoutPage page) {
            long[] result = {0};
            page.elements().forEach(fragment -> SemanticFragments.walk(fragment, ignored -> { }, leaf -> result[0] += leaf.layout().lines().size()));
            return result[0];
        }

        private final class HashedSource implements TableRowSource {
            private final TableRowSource source;
            private final StreamingSourceDigest digest = new StreamingSourceDigest();
            private final StreamingSourceDigest prefix = new StreamingSourceDigest();
            private final Probe probe;
            private long rows;
            private HashedSource(TableRowSource source, Probe probe) { this.source = source; this.probe = probe; }
            @Override public Optional<TableRow> next() throws IOException {
                var result = Objects.requireNonNull(source.next(), "row source returned null");
                if (result.isPresent()) {
                    var row = result.orElseThrow(); precheck(row); digest.add(row);
                    if (probe != null && rows < probe.rows()) { prefix.add(row); }
                    rows++;
                }
                return result;
            }
            private void verifyProbe() throws IOException {
                if (probe != null && (rows < probe.rows() || probe.complete() && rows != probe.rows()
                        || !MessageDigest.isEqual(probe.digest(), prefix.finish()))) {
                    throw new IOException("streaming source changed after column probe");
                }
            }
            @Override public void close() { /* Borrowed by this decorator. */ }
        }
    }

    private static final class CountedOutput extends OutputStream {
        private final OutputStream target;
        private long count;
        private CountedOutput(OutputStream target) { this.target = target; }
        @Override public void write(int value) throws IOException { target.write(value); count++; }
        @Override public void write(byte[] bytes, int start, int length) throws IOException { target.write(bytes, start, length); count = Math.addExact(count, length); }
        @Override public void flush() throws IOException { target.flush(); }
        @Override public void close() { /* Borrowed. */ }
    }
}
