// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.Caption;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.InlineContent;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Immutable semantic editing, independent of layout and file adapters. Lengths are points; text offsets
 * are UTF-16. No I/O, font discovery or background threads. Requests are task-confined, prepared results
 * immutable for standard model types. Custom Block implementations remain trusted caller-owned values.
 */
public final class DocumentEditor {
    private DocumentEditor() { }
    public static Request<Document> edit(Document source) {
        Objects.requireNonNull(source, "source");
        return new Request<>(new SectionedDocument(source.metadata(), List.of(new Section("document", source.pageSettings(), source.blocks(), 0))),
                value -> new Document(value.metadata(), value.sections().getFirst().pageSettings(), value.sections().getFirst().blocks()));
    }
    public static Request<PageDocument> edit(PageDocument source) {
        Objects.requireNonNull(source, "source");
        return new Request<>(new SectionedDocument(source.document().metadata(), List.of(new Section("document", source.document().pageSettings(),
                source.document().blocks(), 0, source.pageRegions()))), value -> {
                    var section = value.sections().getFirst();
                    return new PageDocument(new Document(value.metadata(), section.pageSettings(), section.blocks()), section.pageRegions());
                });
    }
    public static Request<SectionedDocument> edit(SectionedDocument source) {
        return new Request<>(Objects.requireNonNull(source, "source"), Function.identity());
    }
    /** Actual intentional change. Location refers to the input of its sequential operation. */
    public record Change(String operation, ProcessingIssue.Location location) {
        public Change { Objects.requireNonNull(operation, "operation"); Objects.requireNonNull(location, "location"); }
    }
    public record Result<T>(T document, List<Note> notes, List<Change> changes, ProcessingReport report) {
        public Result {
            Objects.requireNonNull(document, "document"); notes = List.copyOf(notes); changes = List.copyOf(changes);
            Objects.requireNonNull(report, "report");
        }
    }
    /** A literal occurrence; flags identify boundaries that require a replacement policy decision. */
    public record Match(ProcessingIssue.Location location, boolean containsObject, boolean uniformStyle,
            boolean completeGrapheme) { }
    public record FindResult(List<Match> matches, ProcessingReport report) {
        public FindResult { matches = List.copyOf(matches); Objects.requireNonNull(report, "report"); }
    }
    /** Bound to the exact input/configuration snapshot used by prepare(). Execution publishes this already
     * validated candidate, never re-reads another input or invokes editing callbacks again. No output I/O is implied.
     */
    public static final class Prepared<T> {
        private final Result<T> result;
        private final SectionedDocument semantic;
        private final ExecutionOptions execution;
        private Prepared(Result<T> result, SectionedDocument semantic, ExecutionOptions execution) {
            this.result = result; this.semantic = semantic; this.execution = execution;
        }
        public ProcessingReport preview() { return new ProcessingReport(ProcessingReport.Phase.PREVIEW, result.report().issues()); }
        public Result<T> execute() throws IOException { return ExecutionContext.execute(execution, () -> { ExecutionContext.checkpoint(); return result; }); }
    }
    @FunctionalInterface private interface Operation {
        SectionedDocument apply(SectionedDocument source, EditSupport.State state);
    }
    @FunctionalInterface private interface ParagraphTransform {
        Paragraph apply(Paragraph source, ProcessingIssue.Location location, EditSupport.State state);
    }
    /** Ordered editing request. Existing IDs are stable; full-tree validity is checked before publication.
     * Replacements are literal, case-sensitive, nonoverlapping and never span paragraphs or atomic objects.
     */
    public static final class Request<T> {
        private final SectionedDocument source;
        private final Function<SectionedDocument, T> finish;
        private final List<Operation> operations = new ArrayList<>();
        private List<Note> notes = List.of();
        private ProcessingPolicy policy = ProcessingPolicy.STRICT;
        private EditLimits limits = EditLimits.DEFAULT;
        private ExecutionOptions execution = ExecutionOptions.DEFAULT;
        private Request(SectionedDocument source, Function<SectionedDocument, T> finish) { this.source = source; this.finish = finish; }
        public Request<T> policy(ProcessingPolicy value) { policy = Objects.requireNonNull(value, "policy"); return this; }
        public Request<T> limits(EditLimits value) { limits = Objects.requireNonNull(value, "limits"); return this; }
        public Request<T> execution(ExecutionOptions value) { execution = Objects.requireNonNull(value, "execution"); return this; }
        /** Supplies the complete borrowed note snapshot; no automatic cascade deletion or renumbering. */
        public Request<T> notes(List<Note> values) { notes = List.copyOf(values); return this; }
        public Request<T> remove(String id) { return blockOperation(id, "REMOVE_BLOCK", block -> List.of()); }
        /** Appends to the sole section body, including an initially empty document. */
        public Request<T> append(Block value) {
            if (source.sections().size() != 1) { throw new IllegalArgumentException("select a destination section explicitly"); }
            return append(source.sections().getFirst().id(), value);
        }
        public Request<T> append(String sectionId, Block value) {
            key(sectionId); Objects.requireNonNull(value, "block");
            return add((input, state) -> {
                var sections = new ArrayList<Section>(); boolean found = false;
                for (var section : input.sections()) {
                    state.work(1);
                    if (!section.id().equals(sectionId)) { sections.add(section); continue; }
                    found = true; var blocks = new ArrayList<>(section.blocks()); blocks.add(value);
                    sections.add(new Section(section.id(), section.pageSettings(), blocks, section.firstPageNumber(), section.pageRegions()));
                    state.change("APPEND_BLOCK", new ProcessingIssue.Location(sectionId, "BODY", value.id(), List.of(), -1, -1));
                }
                if (!found) { throw new IllegalArgumentException("destination section missing"); }
                return new SectionedDocument(input.metadata(), sections);
            });
        }
        public Request<T> replaceBlock(String id, Block replacement) {
            Objects.requireNonNull(replacement, "replacement");
            if (!key(id).equals(replacement.id())) { throw new IllegalArgumentException("replacement must retain the target block ID"); }
            return blockOperation(id, "REPLACE_BLOCK", block -> List.of(replacement));
        }
        public Request<T> insertBefore(String id, Block value) {
            Objects.requireNonNull(value, "value"); return blockOperation(id, "INSERT_BLOCK", block -> List.of(value, block));
        }
        public Request<T> insertAfter(String id, Block value) {
            Objects.requireNonNull(value, "value"); return blockOperation(id, "INSERT_BLOCK", block -> List.of(block, value));
        }
        /** Moves one complete subtree before another block; moving into itself or removing the only cell block fails. */
        public Request<T> moveBefore(String id, String beforeId) {
            key(id); key(beforeId);
            if (id.equals(beforeId)) { throw new IllegalArgumentException("cannot move block before itself"); }
            return add((input, state) -> {
                Block[] found = { null };
                var removed = EditSupport.map(input, state, (block, location) -> {
                    if (!block.id().equals(id)) { return List.of(block); }
                    found[0] = block; state.change("MOVE_BLOCK", location); return List.of();
                }, (cell, location) -> cell);
                if (found[0] == null) { missing(state, id, "MOVE_BLOCK"); return input; }
                boolean[] inserted = { false };
                var result = EditSupport.map(removed, state, (block, location) -> {
                    if (!block.id().equals(beforeId)) { return List.of(block); }
                    inserted[0] = true; return List.of(found[0], block);
                }, (cell, location) -> cell);
                if (!inserted[0]) { throw new IllegalArgumentException("move destination missing or inside moved subtree"); }
                return result;
            });
        }
        /** Replaces exactly one physical anchor, preserving validation of spans and the complete grid.
         * Covered-slot aliases are rejected instead of modifying an unexpected owner.
         */
        public Request<T> cell(String tableId, int row, int column, TableCell replacement) {
            key(tableId); Objects.requireNonNull(replacement, "replacement");
            if (row < 0 || column < 0) { throw new IllegalArgumentException("negative cell coordinate"); }
            return add((input, state) -> {
                boolean[] found = { false };
                var result = EditSupport.map(input, state, (block, location) -> List.of(block), (cell, location) -> {
                    var anchor = location.cellPath().getLast();
                    if (!anchor.tableId().equals(tableId) || anchor.rowIndex() != row || anchor.columnIndex() != column) { return cell; }
                    found[0] = true; state.change("REPLACE_CELL", location); return replacement;
                });
                if (!found[0]) { throw new IllegalArgumentException("cell target is missing or not a physical anchor"); }
                return result;
            });
        }
        public Request<T> paragraphFormat(String id, ParagraphFormat value) {
            Objects.requireNonNull(value, "format");
            return paragraphOperation(id, "PARAGRAPH_FORMAT", (paragraph, location, state) -> paragraph.inlines().isEmpty()
                    ? new Paragraph(paragraph.id(), paragraph.text(), value, List.of(), paragraph.images(), paragraph.fields())
                    : Paragraph.mixed(paragraph.id(), paragraph.content(), value));
        }
        /** Applies an explicit style to a nonempty text range. Existing links remain attached to their
         * original slices. Objects and partial grapheme clusters cannot be styled through this method.
         */
        public Request<T> inlineStyle(String id, int startUtf16, int endUtf16, InlineStyle value) {
            Objects.requireNonNull(value, "style");
            return paragraphOperation(id, "INLINE_STYLE", (paragraph, location, state) -> TextEdits.style(paragraph, startUtf16, endUtf16, value, location, state));
        }
        /** Replaces an existing image at its current UTF-16 marker offset, without deleting fields or text. */
        public Request<T> image(String id, int sourceIndex, InlineImage value) {
            Objects.requireNonNull(value, "image");
            if (sourceIndex < 0) { throw new IllegalArgumentException("negative image source index"); }
            return paragraphOperation(id, "REPLACE_IMAGE", (paragraph, location, state) -> {
                var content = new ArrayList<InlineContent>();
                int offset = 0; boolean found = false;
                for (var item : paragraph.content()) {
                    if (offset == sourceIndex && item instanceof InlineImage) { content.add(value); found = true; }
                    else { content.add(item); }
                    offset += item instanceof com.mirakyux.export4j.model.InlineText run ? run.text().length() : 1;
                }
                if (!found) { throw new IllegalArgumentException("image marker target missing"); }
                return Paragraph.mixed(paragraph.id(), content, paragraph.format());
            });
        }
        /** All supported text blocks and inline cells, including each distinct shared page region once. */
        public Request<T> replaceText(String needle, String replacement) { return replaceText("", needle, replacement); }
        /** Searches the prepared candidate after all queued edits. Results contain positions, never source text. */
        public FindResult find(String needle) throws IOException {
            Objects.requireNonNull(needle, "needle");
            if (needle.isEmpty()) { throw new IllegalArgumentException("empty search"); }
            var prepared = prepare(); var limitsSnapshot = limits; var policySnapshot = policy;
            return ExecutionContext.execute(prepared.execution, () -> {
                var state = new EditSupport.State(limitsSnapshot, policySnapshot); EditSupport.unicode(needle, state);
                var matches = new ArrayList<Match>(); state.issues.addAll(prepared.result.report().issues());
                try {
                    EditSupport.map(prepared.semantic, state, (block, location) -> {
                        var paragraph = paragraph(block);
                        if (paragraph != null) { matches.addAll(TextEdits.find(paragraph, needle, location, state)); }
                        else if (!(block instanceof Table) && !(block instanceof com.mirakyux.export4j.model.PageBreak)) {
                            state.unsupported("OPAQUE_BLOCK", "FIND_TEXT", location, "block has no searchable semantic text",
                                    List.of("BLOCK_NOT_SEARCHED"), ProcessingPolicy.Treatment.SKIP_BLOCK,
                                    ProcessingPolicy.Treatment.SKIP_REQUEST, ProcessingPolicy.Treatment.PRESERVE);
                        }
                        return List.of(block);
                    }, (cell, location) -> {
                        if (!cell.hasBlocks()) { matches.addAll(TextEdits.find(inlineCell(cell), needle, location, state)); }
                        return cell;
                    });
                } catch (EditSupport.SkipRequest skipped) {
                    var issue = state.issues.getLast(); state.issues.clear(); state.issues.add(issue); matches.clear();
                }
                return new FindResult(matches, new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues));
            });
        }
        /** A block ID restricts matching to that text block or that table's inline cells. Empty ID selects all. */
        public Request<T> replaceText(String id, String needle, String replacement) {
            Objects.requireNonNull(id, "id"); Objects.requireNonNull(needle, "needle"); Objects.requireNonNull(replacement, "replacement");
            if (needle.isEmpty()) { throw new IllegalArgumentException("empty replacement search"); }
            if (replacement.indexOf('\ufffc') >= 0) { throw new IllegalArgumentException("replacement text cannot insert object markers"); }
            return add((input, state) -> {
                EditSupport.unicode(needle, state); EditSupport.unicode(replacement, state);
                boolean[] found = { id.isEmpty() };
                var result = EditSupport.map(input, state, (block, location) -> {
                    if (!id.isEmpty() && !id.equals(block.id())) { return List.of(block); }
                    found[0] = true;
                    var paragraph = paragraph(block);
                    if (paragraph != null) {
                        var changed = TextEdits.replace(paragraph, needle, replacement, location, state);
                        return List.of(changed == paragraph ? block : rebuild(block, changed));
                    }
                    if (!(block instanceof Table) && !(block instanceof com.mirakyux.export4j.model.PageBreak)) {
                        state.unsupported("OPAQUE_BLOCK", "REPLACE_TEXT", location, "block has no editable semantic text",
                                List.of("BLOCK_NOT_EDITED"), ProcessingPolicy.Treatment.SKIP_BLOCK,
                                ProcessingPolicy.Treatment.SKIP_REQUEST, ProcessingPolicy.Treatment.PRESERVE);
                    }
                    return List.of(block);
                }, (cell, location) -> {
                    if (cell.hasBlocks() || !id.isEmpty() && !id.equals(location.blockId())) { return cell; }
                    found[0] = true;
                    var paragraph = inlineCell(cell);
                    var changed = TextEdits.replace(paragraph, needle, replacement, location, state);
                    return changed == paragraph ? cell : cell.toBuilder().content(changed.content()).build();
                });
                if (!found[0]) { missing(state, id, "REPLACE_TEXT"); }
                return result;
            });
        }
        private Request<T> paragraphOperation(String id, String operation, ParagraphTransform transform) {
            key(id);
            return add((input, state) -> {
                boolean[] found = { false };
                var result = EditSupport.map(input, state, (block, location) -> {
                    if (!block.id().equals(id)) { return List.of(block); }
                    found[0] = true;
                    var paragraph = paragraph(block);
                    if (paragraph == null || block instanceof Caption && operation.equals("INLINE_STYLE")) {
                        state.unsupported("NON_TEXT_TARGET", operation, location, "operation requires a semantic text block",
                                List.of("BLOCK_NOT_EDITED"), ProcessingPolicy.Treatment.SKIP_BLOCK, ProcessingPolicy.Treatment.SKIP_REQUEST);
                        return List.of(block);
                    }
                    var changed = transform.apply(paragraph, location, state);
                    if (changed.equals(paragraph)) { return List.of(block); }
                    state.change(operation, location);
                    return List.of(rebuild(block, changed));
                }, (cell, location) -> cell);
                if (!found[0]) { missing(state, id, operation); }
                return result;
            });
        }
        private Request<T> blockOperation(String id, String operation, Function<Block, List<Block>> transform) {
            key(id);
            return add((input, state) -> {
                boolean[] found = { false };
                var result = EditSupport.map(input, state, (block, location) -> {
                    if (!block.id().equals(id)) { return List.of(block); }
                    found[0] = true; state.change(operation, location); return transform.apply(block);
                }, (cell, location) -> cell);
                if (!found[0]) { missing(state, id, operation); }
                return result;
            });
        }
        private Request<T> add(Operation operation) {
            if (operations.size() >= limits.maxOperations()) { throw new IllegalArgumentException("editing operation budget exceeded"); }
            operations.add(operation); return this;
        }
        public Prepared<T> prepare() throws IOException {
            var steps = List.copyOf(operations); var noteSnapshot = List.copyOf(notes);
            var policySnapshot = policy; var limitsSnapshot = limits; var executionSnapshot = execution;
            if (steps.size() > limitsSnapshot.maxOperations()) { throw new IllegalArgumentException("editing operation budget exceeded"); }
            return ExecutionContext.execute(executionSnapshot, () -> {
                var state = new EditSupport.State(limitsSnapshot, policySnapshot);
                EditValidation.validate(source, noteSnapshot, state, true);
                var candidate = source;
                try {
                    for (var step : steps) {
                        state.work(1); candidate = step.apply(candidate, state);
                        EditValidation.validate(candidate, noteSnapshot, state, false);
                    }
                    EditValidation.validate(candidate, noteSnapshot, state, true);
                } catch (EditSupport.SkipRequest skipped) {
                    // No tentative losses or changes are published when the whole request is rolled back.
                    var issue = state.issues.getLast(); state.issues.clear(); state.issues.add(issue);
                    state.changes.clear(); candidate = source;
                }
                var result = new Result<>(finish.apply(candidate), noteSnapshot, state.changes,
                        new ProcessingReport(ProcessingReport.Phase.ACTUAL, state.issues));
                return new Prepared<>(result, candidate, executionSnapshot);
            });
        }
        public Result<T> execute() throws IOException { return prepare().execute(); }
    }
    private static String key(String id) {
        if (Objects.requireNonNull(id, "id").isBlank()) { throw new IllegalArgumentException("blank block ID"); } return id;
    }
    private static void missing(EditSupport.State state, String id, String operation) {
        state.unsupported("TARGET_NOT_FOUND", operation, new ProcessingIssue.Location("", "", id, List.of(), -1, -1),
                "target block not found", List.of("OPERATION_NOT_APPLIED"), ProcessingPolicy.Treatment.SKIP_MATCH, ProcessingPolicy.Treatment.SKIP_REQUEST);
    }
    static Paragraph inlineCell(TableCell cell) {
        return new Paragraph("cell", cell.text(), ParagraphFormat.of(cell.format().fontSize()), cell.inlines(), cell.images(), cell.fields());
    }
    static Paragraph paragraph(Block block) {
        return switch (block) {
            case Paragraph value -> value;
            case Heading value -> value.paragraph();
            case BulletItem value -> value.paragraph();
            case NumberedItem value -> value.paragraph();
            case Caption value -> new Paragraph(value.id(), value.text(), value.format());
            default -> null;
        };
    }
    private static Block rebuild(Block source, Paragraph value) {
        return switch (source) {
            case Paragraph ignored -> value;
            case Heading original -> new Heading(value.id(), value.text(), original.level(), value.format(), value.inlines());
            case BulletItem original -> new BulletItem(value.id(), value.text(), original.marker(), original.level(), value.format(), value.inlines(), original.markerStyle());
            case NumberedItem original -> new NumberedItem(value.id(), original.listId(), value.text(), original.restartAt(), value.format(), original.level(), value.inlines(), original.markerStyle());
            case Caption original -> new Caption(value.id(), original.kind(), value.text(), value.format());
            default -> throw new IllegalArgumentException("block has no editable paragraph");
        };
    }
}
