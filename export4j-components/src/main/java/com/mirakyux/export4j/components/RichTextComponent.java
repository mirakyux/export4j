// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.TextInheritance;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.layout.core.WidthAwareRegionFlow;
import java.util.function.IntToDoubleFunction;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.StyledSpan;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextLayoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Atomic rich text in a finite point-based viewport, using shared wrapping and physical fonts.
 * Every run's InlineStyle is complete: font size, RGB, baseline shift and decorations remain explicit.
 * An empty run role uses the context's default font group; other roles must be registered, even on empty
 * runs. Paragraph inheritance controls line height, indents, alignment and line breaking; its font-size
 * and color declarations do not replace explicit run values. The fixed line frame must contain all runs.
 * Limited to 4,096 runs, including empty runs, and 100,000 source Unicode code points. Empty lists,
 * hyperlinks and U+FFFC object markers are rejected. Source offsets and diagnostics use concatenated
 * original UTF-16 positions. Text never splits across pages, shrinks or clips to fit.
 * Immutable and thread-safe with immutable borrowed font resources; owns no resources and performs no I/O.
 */
public record RichTextComponent(List<InlineText> inlines, ParagraphStyle style,
        TextInheritance inheritance) implements Component {
    private static final int MAX_RUNS = 4_096;
    private static final int MAX_CODE_POINTS = 100_000;

    public RichTextComponent {
        inlines = validate(inlines).values();
        Objects.requireNonNull(style, "style");
        Objects.requireNonNull(inheritance, "inheritance");
    }

    /** Complete explicit paragraph geometry; every run retains its complete explicit inline style. */
    public RichTextComponent(List<InlineText> inlines, ParagraphStyle style) {
        this(inlines, style, TextInheritance.NONE);
    }

    @Override
    public ComponentLayout layout(ComponentContext context) {
        var text = shape(context);
        ComponentSupport.fits(text.width(), text.height(), context);
        var builder = new DisplayListBuilder(new PageSize(text.width(), text.height()));
        TextLayoutPainter.draw(builder, text, new Point(0, 0), RgbColor.BLACK);
        return new ComponentLayout(text.width(), text.height(), text.lines().size(),
                builder.build().commands(), text.diagnostics());
    }

    /** Returns a complete-line continuation using the same immutable runs and explicit fonts.
     * Supports FlowColumn, grid cells and continuous columns; unequal widths with minimum-line policies
     * use bounded full-source reflow. Fixed-width and default-line policies keep the single-wrap path.
     */
    public RegionFlow flow() { return flow(PaginationPolicy.DEFAULT); }

    /** Returns continuation with explicit orphan/widow line counts. Adjacency flags are unsupported.
     * The policy and source are immutable; resources remain borrowed from the layout context.
     */
    public RegionFlow flow(PaginationPolicy pagination) {
        Objects.requireNonNull(pagination, "pagination");
        if (pagination.keepWithNext() || pagination.pageBreakBefore()) {
            throw new IllegalArgumentException("nested rich text uses line limits, not block adjacency flags");
        }
        return new Flowing(this, pagination);
    }

    private record Flowing(RichTextComponent source, PaginationPolicy pagination) implements WidthAwareRegionFlow {
        @Override public List<RegionFlow.Fragment> layoutRegions(ComponentContext context, FlowRegions regions) {
            ComponentSupport.fits(context.width(), regions.continuationHeight(), context);
            var shaped = source.shape(context);
            var style = source.inheritance.resolveStyle(source.style, context);
            return FlowText.layoutShaped(shaped, style.lineHeight(), RgbColor.BLACK, pagination, regions);
        }

        @Override public List<RegionFlow.Fragment> layoutVariableRegions(ComponentContext context, FlowRegions regions, List<Double> widths) {
            var style = source.inheritance.resolveStyle(source.style, context);
            var slots = new TextRegionWidths(context, regions, widths, style.lineHeight(), pagination);
            int units = source.inlines.stream().mapToInt(value -> value.text().length()).sum();
            var shaped = slots.shape(units, lineWidths -> source.shape(context, lineWidths));
            return FlowText.layoutShaped(shaped, style.lineHeight(), RgbColor.BLACK, slots.slices(shaped.lines().size()), slots::width);
        }
    }

    private ParagraphLayout shape(ComponentContext context) {
        return shape(context, line -> context.width());
    }

    private ParagraphLayout shape(ComponentContext context, IntToDoubleFunction widths) {
        Objects.requireNonNull(context, "context");
        var spans = new ArrayList<StyledSpan>(inlines.size());
        int sourceOffset = 0;
        for (var inline : inlines) {
            ExecutionContext.checkpoint();
            var ink = inline.style();
            var fonts = ink.fontRole().isEmpty() ? context.fontGroup() : context.fontRoles().get(ink.fontRole());
            if (fonts == null) {
                throw new TextLayoutException(TextLayoutException.Code.INVALID_TEXT, sourceOffset,
                        "unregistered inline font role");
            }
            spans.add(new StyledSpan(inline.text(), fonts, ink.fontSize(), ink.rgb(), ink.baselineShift(),
                    ink.underline(), ink.strikeThrough()));
            sourceOffset += inline.text().length();
        }
        var paragraphStyle = inheritance.resolveStyle(style, context);
        return new ParagraphLayouter().layout(spans, context.width(), paragraphStyle, widths);
    }

    private record ValidatedRuns(List<InlineText> values, int codePoints, int sourceLength) { }

    private static ValidatedRuns validate(List<InlineText> values) {
        Objects.requireNonNull(values, "inlines");
        if (values.isEmpty()) { throw new IllegalArgumentException("rich text requires at least one inline run"); }
        if (values.size() > MAX_RUNS) {
            throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, -1,
                    "rich text run budget exceeded");
        }
        var snapshot = List.copyOf(values);
        int codePoints = 0;
        int sourceLength = 0;
        for (var inline : snapshot) {
            codePoints = checkedRun(inline, codePoints, sourceLength);
            sourceLength += inline.text().length();
        }
        return new ValidatedRuns(snapshot, codePoints, sourceLength);
    }

    private static int checkedRun(InlineText inline, int codePoints, int sourceOffset) {
        ExecutionContext.checkpoint();
        Objects.requireNonNull(inline, "inline");
        if (!inline.hyperlink().isEmpty()) {
            throw new IllegalArgumentException("rich text components do not support hyperlinks");
        }
        String text = inline.text();
        // Bound the UTF-16 scan before counting or concatenating potentially large source strings.
        if (text.length() > (MAX_CODE_POINTS - codePoints) * 2) {
            throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, sourceOffset,
                    "rich text source code point budget exceeded");
        }
        int count = text.codePointCount(0, text.length());
        if (count > MAX_CODE_POINTS - codePoints) {
            throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, sourceOffset,
                    "rich text source code point budget exceeded");
        }
        int marker = text.indexOf('\ufffc');
        if (marker >= 0) {
            throw new TextLayoutException(TextLayoutException.Code.INVALID_TEXT, sourceOffset + marker,
                    "rich text components do not support inline object markers");
        }
        return codePoints + count;
    }

    /** Starts task-confined construction with inherited paragraph geometry and explicit inline runs. */
    public static Builder builder() { return new Builder(); }

    /** Starts an independent builder preserving run snapshots and paragraph declaration intent. */
    public Builder toBuilder() { return new Builder(this); }

    /** Mutable, task-confined construction state. Inputs are snapshotted and charged before insertion. */
    public static final class Builder {
        private List<InlineText> inlines = new ArrayList<>();
        private int codePoints;
        private int sourceLength;
        private ComponentTextStyle typography = new ComponentTextStyle(
                ParagraphStyle.of(12), RgbColor.BLACK, new TextInheritance(true, false));

        private Builder() { }

        private Builder(RichTextComponent source) {
            inlines(source.inlines);
            typography = new ComponentTextStyle(source.style, RgbColor.BLACK, source.inheritance);
        }

        /** Appends one complete inline style; empty runs count toward the 4,096-run limit. */
        public Builder add(InlineText value) {
            if (inlines.size() >= MAX_RUNS) {
                throw new TextLayoutException(TextLayoutException.Code.BUDGET_EXCEEDED, sourceLength,
                        "rich text run budget exceeded");
            }
            int nextCodePoints = checkedRun(value, codePoints, sourceLength);
            inlines.add(value);
            codePoints = nextCodePoints;
            sourceLength += value.text().length();
            return this;
        }

        /** Appends literal text with a complete inline style, including explicit font size and RGB. */
        public Builder add(String text, InlineStyle style) { return add(new InlineText(text, style)); }

        /** Replaces all runs after validating and snapshotting the complete replacement. */
        public Builder inlines(List<InlineText> value) {
            var checked = validate(value);
            inlines = new ArrayList<>(checked.values());
            codePoints = checked.codePoints();
            sourceLength = checked.sourceLength();
            return this;
        }

        /** Declares complete paragraph geometry; explicit inline font sizes and colors stay unchanged. */
        public Builder style(ParagraphStyle value) { typography.style(value); return this; }
        /** Configures complete paragraph geometry synchronously; does not retain the callback. */
        public Builder style(Consumer<ParagraphStyle.Builder> configure) { typography.style(configure); return this; }
        /** Restores paragraph geometry from the context without changing any inline style. */
        public Builder inheritStyle() { typography.inheritStyle(); return this; }

        /** Declares only the fixed baseline step in points. */
        public Builder lineHeight(double value) { typography.lineHeight(value); return this; }
        public Builder inheritLineHeight() { typography.inherit(TextInheritance.Property.LINE_HEIGHT); return this; }
        public Builder alignment(TextAlignment value) { typography.alignment(value); return this; }
        public Builder inheritAlignment() { typography.inherit(TextInheritance.Property.ALIGNMENT); return this; }
        /** Declares only the first-line indent in points. */
        public Builder firstLineIndent(double value) { typography.firstLineIndent(value); return this; }
        public Builder inheritFirstLineIndent() { typography.inherit(TextInheritance.Property.FIRST_LINE_INDENT); return this; }
        /** Declares only the continuation-line indent in points. */
        public Builder subsequentLineIndent(double value) { typography.subsequentLineIndent(value); return this; }
        public Builder inheritSubsequentLineIndent() { typography.inherit(TextInheritance.Property.SUBSEQUENT_LINE_INDENT); return this; }
        public Builder lineBreakPolicy(LineBreakPolicy value) { typography.lineBreakPolicy(value); return this; }
        public Builder inheritLineBreakPolicy() { typography.inherit(TextInheritance.Property.LINE_BREAK_POLICY); return this; }

        /** Validates the complete value and takes an immutable structural snapshot. */
        public RichTextComponent build() {
            return new RichTextComponent(inlines, typography.style(), typography.inheritance());
        }
    }
}
