// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.model.ExecutionContext;

/** Pure composition and geometry for deferred component semantics. All dimensions are points.
 * Semantic tables support translation only and must remain wholly visible inside the component
 * and any effective clip. Static drawings retain their existing affine-transform support.
 * Stateless and thread-safe; mutable builders and destination lists remain caller-confined.
 */
public final class ComponentSemantics {
    /** One semantic insertion and its effective component-to-page translation.
     * The fragment's own origin is additional. clip() is the effective page-space clip, when present.
     */
    public static final class Located {
        private final SemanticPlacement placement;
        private final Point translation;
        private final Rect clipBounds;

        private Located(SemanticPlacement placement, Point translation, Rect clipBounds) {
            this.placement = placement;
            this.translation = translation;
            this.clipBounds = clipBounds;
        }

        public SemanticPlacement placement() { return placement; }
        public Point translation() { return translation; }
        public Optional<Rect> clip() { return Optional.ofNullable(clipBounds); }
    }

    private record State(Point translation, Rect clip, boolean emptyClip, boolean supported) { }

    private ComponentSemantics() { }

    /** Appends one independently balanced component while preserving semantic insertion order.
     * placements must be a mutable, caller-owned destination list matching builder's recorded commands.
     * Does not paint, resolve fields, change measurements or close borrowed resources.
     */
    public static DisplayListBuilder append(DisplayListBuilder builder, List<SemanticPlacement> placements,
            ComponentLayout layout) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(placements, "placements");
        Objects.requireNonNull(layout, "layout");
        if (layout.semanticPlacements().size() > 100_000 - placements.size()) {
            throw new IllegalArgumentException("semantic placement budget exceeded");
        }
        int offset = builder.commandCount();
        for (var placement : layout.semanticPlacements()) {
            ExecutionContext.checkpoint();
            placements.add(new SemanticPlacement(Math.addExact(offset, placement.commandIndex()), placement.source(),
                    placement.fragment(), placement.fonts(), placement.roles(), placement.fragmentIndex(), placement.fragmentCount()));
        }
        return builder.appendCommands(layout.staticCommands());
    }

    /** Resolves insertion geometry against the exact Save/Restore, Transform and Clip command order.
     * origin is the component's page-space top-left position. The returned list is immutable.
     * @throws IllegalArgumentException for transformed, clipped or out-of-component semantic content
     */
    public static List<Located> locate(ComponentLayout layout, Point origin) {
        Objects.requireNonNull(layout, "layout");
        Objects.requireNonNull(origin, "origin");
        if (layout.semanticPlacements().isEmpty()) { return List.of(); }
        var result = new ArrayList<Located>();
        var stack = new ArrayDeque<State>();
        var state = new State(origin, null, false, true);
        var viewport = new Rect(origin.x(), origin.y(), layout.width(), layout.height());
        var commands = layout.staticCommands();
        int slot = 0;
        for (int index = 0; index <= commands.size(); index++) {
            ExecutionContext.checkpoint();
            while (slot < layout.semanticPlacements().size()
                    && layout.semanticPlacements().get(slot).commandIndex() == index) {
                var placement = layout.semanticPlacements().get(slot++);
                if (!state.supported()) {
                    throw new IllegalArgumentException("semantic placements support translation transforms only");
                }
                var fragment = placement.fragment();
                var bounds = translated(new Rect(fragment.origin().x(), fragment.origin().y(),
                        fragment.width(), fragment.height()), state.translation());
                if (!contains(viewport, bounds) || state.emptyClip()
                        || state.clip() != null && !contains(state.clip(), bounds)) {
                    throw new IllegalArgumentException("semantic placement must remain wholly visible inside its component and clip");
                }
                result.add(new Located(placement, state.translation(), state.clip()));
            }
            if (index == commands.size()) { break; }
            switch (commands.get(index)) {
                case DrawCommand.Save ignored -> stack.push(state);
                case DrawCommand.Restore ignored -> state = stack.pop();
                case DrawCommand.Transform transform -> {
                    var matrix = transform.matrix();
                    boolean supported = state.supported() && matrix.a() == 1 && matrix.b() == 0
                            && matrix.c() == 0 && matrix.d() == 1;
                    var translation = supported ? new Point(state.translation().x() + matrix.e(),
                            state.translation().y() + matrix.f()) : state.translation();
                    state = new State(translation, state.clip(), state.emptyClip(), supported);
                }
                case DrawCommand.Clip clip -> {
                    if (state.supported() && !state.emptyClip()) {
                        var bounds = translated(clip.bounds(), state.translation());
                        if (state.clip() == null) {
                            state = new State(state.translation(), bounds, false, true);
                        } else {
                            var intersection = intersection(state.clip(), bounds);
                            state = new State(state.translation(), intersection, intersection == null, true);
                        }
                    }
                }
                default -> { }
            }
        }
        return List.copyOf(result);
    }

    private static Rect translated(Rect bounds, Point translation) {
        double x = bounds.x() + translation.x();
        double y = bounds.y() + translation.y();
        if (!Double.isFinite(x + bounds.width()) || !Double.isFinite(y + bounds.height())) {
            throw new IllegalArgumentException("semantic geometry exceeds finite coordinates");
        }
        return new Rect(x, y, bounds.width(), bounds.height());
    }

    private static boolean contains(Rect outer, Rect inner) {
        return inner.x() >= Math.nextDown(outer.x()) && inner.y() >= Math.nextDown(outer.y())
                && inner.x() + inner.width() <= Math.nextUp(outer.x() + outer.width())
                && inner.y() + inner.height() <= Math.nextUp(outer.y() + outer.height());
    }

    private static Rect intersection(Rect first, Rect second) {
        double x = Math.max(first.x(), second.x());
        double y = Math.max(first.y(), second.y());
        double right = Math.min(first.x() + first.width(), second.x() + second.width());
        double bottom = Math.min(first.y() + first.height(), second.y() + second.height());
        return right > x && bottom > y ? new Rect(x, y, right - x, bottom - y) : null;
    }
}
