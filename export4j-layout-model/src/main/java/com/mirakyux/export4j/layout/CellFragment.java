// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout;

import java.util.Objects;
import java.util.List;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.TextDiagnostic;

/** Cell bounds and text origin relative to its table fragment; text ranges are cell-local UTF-16.
 * columnIndex is the physical grid start, not the index in a source row's compact anchor list.
 * Bounds cover the complete rectangular column/row span, with one padding region.
 * Block content has ordered child paragraph/table fragments relative to textOrigin; it is atomic.
 * The enclosing TableRowFragment always retains the original anchor row, even for vertical spans.
 */
public record CellFragment(int columnIndex, Rect bounds, Point textOrigin, Content content) {
    /** Immutable cell content; contains IR only, never components or backend handles. */
    public sealed interface Content permits Inline, Blocks { }
    /** One original inline cell paragraph, possibly a complete-line continuation. */
    public record Inline(ParagraphLayout layout, int firstLineIndex) implements Content {
        public Inline {
            Objects.requireNonNull(layout, "layout");
            if (firstLineIndex < 0) { throw new IllegalArgumentException("negative cell line index"); }
        }
    }
    /** Ordered atomic child IR; coordinates are relative to the enclosing cell textOrigin. */
    public record Blocks(List<LayoutFragment> fragments, List<Integer> sourceBlockIndices) implements Content {
        /** Compatibility constructor for a complete, ordered source block list. */
        public Blocks(List<LayoutFragment> fragments) {
            this(fragments, java.util.stream.IntStream.range(0, fragments.size()).boxed().toList());
        }
        public Blocks {
            fragments = List.copyOf(fragments);
            sourceBlockIndices = List.copyOf(sourceBlockIndices);
            if (sourceBlockIndices.size() != fragments.size() || sourceBlockIndices.stream().anyMatch(index -> index < 0)
                    || fragments.stream().anyMatch(fragment ->
                    !(fragment instanceof ParagraphFragment || fragment instanceof TableFragment))) {
                throw new IllegalArgumentException("block cell requires paragraph or table fragments");
            }
            for (int index = 1; index < sourceBlockIndices.size(); index++) {
                if (sourceBlockIndices.get(index) <= sourceBlockIndices.get(index - 1)) {
                    throw new IllegalArgumentException("cell source block indices must increase");
                }
            }
        }
    }

    public CellFragment {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(textOrigin, "textOrigin");
        Objects.requireNonNull(content, "content");
        if (columnIndex < 0) {
            throw new IllegalArgumentException("column index must be nonnegative");
        }
    }

    /** Compatibility constructor for an inline cell. Coordinates are table-local points. */
    public CellFragment(int columnIndex, Rect bounds, Point textOrigin, ParagraphLayout layout, int firstLineIndex) {
        this(columnIndex, bounds, textOrigin, new Inline(layout, firstLineIndex));
    }

    public CellFragment(int columnIndex, Rect bounds, Point textOrigin, ParagraphLayout layout) {
        this(columnIndex, bounds, textOrigin, layout, 0);
    }

    /** Creates block content with a defensive structural snapshot and consecutive source ordinals. */
    public static CellFragment blocks(int columnIndex, Rect bounds, Point textOrigin, List<LayoutFragment> fragments) {
        return new CellFragment(columnIndex, bounds, textOrigin, new Blocks(fragments));
    }

    /** Original cell block ordinals for this presentation, including continuation slices. */
    public List<Integer> sourceBlockIndices() {
        return content instanceof Blocks value ? value.sourceBlockIndices() : List.of();
    }

    public boolean hasBlocks() { return content instanceof Blocks; }

    /** Child IR in source order; empty for an inline cell. */
    public List<LayoutFragment> blocks() { return content instanceof Blocks value ? value.fragments() : List.of(); }

    /** Inline-only compatibility view; fails for block content instead of hiding it. */
    public ParagraphLayout layout() {
        if (content instanceof Inline value) { return value.layout(); }
        throw new IllegalStateException("block cell has no single paragraph layout");
    }

    /** Original inline paragraph-local line offset; not applicable to block content. */
    public int firstLineIndex() {
        if (content instanceof Inline value) { return value.firstLineIndex(); }
        throw new IllegalStateException("block cell has no single paragraph line offset");
    }

    /** Actual leaf text line count, including nested table headers in this presentation. */
    public long lineCount() {
        if (content instanceof Inline value) { return value.layout().lines().size(); }
        return blocks().stream().mapToLong(fragment -> switch (fragment) {
            case ParagraphFragment paragraph -> paragraph.layout().lines().size();
            case TableFragment table -> table.rows().stream().flatMap(row -> row.cells().stream())
                    .mapToLong(CellFragment::lineCount).sum();
            case ComponentFragment ignored -> throw new IllegalStateException("unexpected cell component");
        }).sum();
    }

    /** Leaf text diagnostics in source/IR order; contextual owners are supplied by the tree walker. */
    public List<TextDiagnostic> diagnostics() {
        if (content instanceof Inline value) { return value.layout().diagnostics(); }
        return blocks().stream().flatMap(fragment -> switch (fragment) {
            case ParagraphFragment paragraph -> paragraph.layout().diagnostics().stream();
            case TableFragment table -> table.rows().stream().flatMap(row -> row.cells().stream())
                    .flatMap(cell -> cell.diagnostics().stream());
            case ComponentFragment ignored -> throw new IllegalStateException("unexpected cell component");
        }).toList();
    }
}
