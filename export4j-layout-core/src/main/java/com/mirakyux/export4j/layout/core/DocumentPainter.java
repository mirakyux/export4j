// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import com.mirakyux.export4j.font.FontData;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.VectorPath;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.graphics.core.TextLayoutPainter;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.graphics.AffineTransform;

/** Pure IR-to-Display-List conversion; never remeasures, reflows, paginates or writes files. */
public final class DocumentPainter {
    private static final int MAX_CACHED_BORDER_PATHS = 256;
    private static final int MAX_CACHED_COMMANDS = 1024;
    private static final int MAX_CACHED_COMMAND_UNITS = 65_536;
    private static final long MAX_CACHED_COMMAND_FONT_BYTES = 32L * 1024 * 1024;
    private static final RgbColor HEADER_COLOR = new RgbColor(0.93, 0.95, 0.97);

    private DocumentPainter() {
    }

    /** Paints black text and a thin table grid with gray headers; never changes layout geometry. */
    public static DisplayDocument paint(DocumentLayout layout) {
        return paint(layout, Map.of());
    }

    /** Applies visual rotations supplied by the semantic source without changing measured flow boxes. */
    public static DisplayDocument paint(DocumentLayout layout, Map<String, Double> rotations) {
        Objects.requireNonNull(layout, "layout");
        rotations = Map.copyOf(rotations);
        List<DisplayPage> pages = new ArrayList<>();
        try (var session = openSession()) {
            for (LayoutPage page : layout.pages()) {
                pages.add(session.paintPage(page, rotations));
            }
        }
        return new DisplayDocument(layout.metadata(), pages);
    }

    /** Opens one synchronous painting task with bounded caches reused across its pages.
     * The caller must close the session on its creating thread. Fonts and emitted pages remain
     * caller-owned; closing drops only cache references and does not change earlier commands.
     */
    public static Session openSession() {
        return new Session();
    }

    /** Thread-confined page painter; does not retain completed pages or semantic table sources.
     * Reuses at most 256 border paths, 1024 bounded geometry/glyph commands and the bounded image cache.
     * Glyph-command font sources are additionally limited to 32 MiB by exact resource identity.
     * Not thread-safe or reentrant.
     */
    public static final class Session implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final PaintResources resources = new PaintResources();
        private boolean closed;

        private Session() { }

        /** Paints one immutable page with no external rotations; all inputs remain borrowed. */
        public DisplayPage paintPage(LayoutPage page) {
            return paintPage(page, Map.of());
        }

        /** Paints one page and snapshots its explicit rotations; no I/O or layout occurs.
         * @throws IllegalStateException when closed or used from another thread
         */
        public DisplayPage paintPage(LayoutPage page, Map<String, Double> rotations) {
            requireOwner();
            if (closed) { throw new IllegalStateException("painting session is closed"); }
            return DocumentPainter.paintPage(page, Map.copyOf(rotations),
                    new java.util.IdentityHashMap<>(), resources);
        }

        /** Releases bounded cache entries. Repeated closure on the creating thread is harmless. */
        @Override
        public void close() {
            requireOwner();
            if (!closed) {
                resources.images.clear();
                resources.borders.clear();
                resources.commands.clear();
                resources.commandFonts.clear();
                resources.commandUnits = 0;
                resources.commandFontBytes = 0;
                closed = true;
            }
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("painting session belongs to another thread");
            }
        }
    }

    /** Binds semantic body blocks to final commands without layout or font substitution.
     * The source must be the document used to produce the IR, before list-marker lowering.
     * Supports ordinary paragraphs, headings, level-one lists, standalone inline figures and
     * inline-content tables. Complex components, mixed inline figures and block cells require
     * an explicit {@link com.mirakyux.export4j.graphics.AccessibilityTree} instead.
     * Repeated table headers become artifacts; original header cells retain stable associations.
     */
    public static DisplayDocument paintAccessible(com.mirakyux.export4j.model.Document source,
            DocumentLayout layout, String language) {
        return AccessiblePainter.paint(source, layout, language);
    }

    /** Paints one page without wrapping it in a document or changing its physical IR page index.
     * DisplayPage carries geometry and commands only; the receiving backend owns sequential ordering.
     * Performs no measurement, pagination, I/O or ownership transfer. Thread-safe for immutable input.
     */
    public static DisplayPage paintPage(LayoutPage page) {
        return paintPage(page, Map.of());
    }

    /** Draws a page with rotations fitted inside each fragment's measured box. */
    public static DisplayPage paintPage(LayoutPage page, Map<String, Double> rotations) {
        try (var session = openSession()) {
            return session.paintPage(page, rotations);
        }
    }

    private static DisplayPage paintPage(LayoutPage page, Map<String, Double> rotations,
            java.util.IdentityHashMap<com.mirakyux.export4j.model.Table, Map<String, Double>> semanticRotations,
            PaintResources resources) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(rotations, "rotations");
        DisplayListBuilder builder = new DisplayListBuilder(page.size());
        for (var fragment : page.elements()) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            double rotation = rotations.getOrDefault(fragment.blockId(), 0.0);
            if (rotation != 0) {
                Rect bounds = switch (fragment) {
                    case ParagraphFragment p -> new Rect(p.origin().x(), p.origin().y(), p.layout().width(), p.layout().height());
                    case TableFragment t -> new Rect(t.origin().x(), t.origin().y(), t.width(), t.height());
                    case ComponentFragment c -> new Rect(c.origin().x(), c.origin().y(), c.layout().width(), c.layout().height());
                };
                builder.save().transform(AffineTransform.fittedRotation(bounds, rotation));
            }
            switch (fragment) {
                case ParagraphFragment paragraph ->
                    TextLayoutPainter.draw(builder, paragraph.layout(), paragraph.origin(), RgbColor.BLACK, resources.images);
                case TableFragment table -> paintTable(builder, table, rotations, resources);
                case ComponentFragment component -> {
                    builder.save().transform(AffineTransform.translation(component.origin().x(), component.origin().y()));
                    paintComponent(builder, component.layout(), semanticRotations, resources);
                    builder.restore();
                }
            }
            if (rotation != 0) { builder.restore(); }
        }
        return resources.reuse(builder.build());
    }

    /** Appends a fully resolved component in its current graphics state, retaining semantic z-order.
     * Performs no measurement, field resolution or I/O. Deferred fields must already be resolved.
     * @throws IllegalArgumentException when semantic placement geometry is unsupported or clipped
     */
    public static void paintComponent(DisplayListBuilder builder, ComponentLayout component) {
        paintComponent(builder, component, new java.util.IdentityHashMap<>(), new PaintResources());
    }

    private static void paintComponent(DisplayListBuilder builder, ComponentLayout component,
            java.util.IdentityHashMap<com.mirakyux.export4j.model.Table, Map<String, Double>> semanticRotations,
            PaintResources resources) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(component, "component");
        ComponentSemantics.locate(component, new Point(0, 0));
        if (component.semanticPlacements().isEmpty()) {
            builder.appendCommands(component.staticCommands());
            return;
        }
        var commands = new ArrayList<DrawCommand>();
        var staticCommands = component.staticCommands();
        int slot = 0;
        for (int index = 0; index <= staticCommands.size(); index++) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            while (slot < component.semanticPlacements().size()
                    && component.semanticPlacements().get(slot).commandIndex() == index) {
                var placement = component.semanticPlacements().get(slot++);
                var table = new DisplayListBuilder(new com.mirakyux.export4j.model.PageSize(component.width(), component.height()));
                var rotations = semanticRotations.computeIfAbsent(placement.source(), DocumentPainter::sourceRotations);
                var fragment = placement.fragment();
                double angle = rotations.getOrDefault(fragment.blockId(), 0.0);
                if (angle != 0) {
                    table.save().transform(AffineTransform.fittedRotation(new Rect(fragment.origin().x(), fragment.origin().y(),
                            fragment.width(), fragment.height()), angle));
                }
                paintTable(table, fragment, rotations, resources);
                if (angle != 0) { table.restore(); }
                commands.addAll(table.build().commands());
            }
            if (index < staticCommands.size()) { commands.add(staticCommands.get(index)); }
        }
        builder.appendCommands(commands);
    }

    private static Map<String, Double> sourceRotations(com.mirakyux.export4j.model.Table source) {
        var rotations = new java.util.HashMap<String, Double>();
        com.mirakyux.export4j.model.SemanticTree.walk(source, visit -> {
            double angle = switch (visit.block()) {
                case com.mirakyux.export4j.model.Paragraph paragraph -> paragraph.format().rotation();
                case com.mirakyux.export4j.model.Table table -> table.format().rotation();
                default -> 0;
            };
            if (angle != 0) { rotations.put(visit.block().id(), angle); }
        });
        return Map.copyOf(rotations);
    }

    private static void paintTable(DisplayListBuilder builder, TableFragment table, Map<String, Double> rotations,
            PaintResources resources) {
        for (var row : table.rows()) {
            for (var cell : row.cells()) {
                Rect bounds = cell.bounds();
                double x = table.origin().x() + bounds.x();
                double y = table.origin().y() + bounds.y();
                if (row.header()) {
                    builder.fillRect(new Rect(x, y, bounds.width(), bounds.height()), HEADER_COLOR);
                }
                if (table.drawBorders()) {
                    // Center the stroke inside each cell so outer borders stay in the content area.
                    double stroke = Math.min(0.5, Math.min(bounds.width(), bounds.height()));
                    double left = x + stroke / 2;
                    double top = y + stroke / 2;
                    double right = x + bounds.width() - stroke / 2;
                    double bottom = y + bounds.height() - stroke / 2;
                    builder.strokePath(resources.borderPath(left, top, right, bottom), RgbColor.BLACK, stroke);
                }
                var contentOrigin = new Point(table.origin().x() + cell.textOrigin().x(),
                        table.origin().y() + cell.textOrigin().y());
                if (cell.hasBlocks()) {
                    builder.save().transform(AffineTransform.translation(contentOrigin.x(), contentOrigin.y()));
                    for (var fragment : cell.blocks()) {
                        com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                        double angle = rotations.getOrDefault(fragment.blockId(), 0.0);
                        if (angle != 0) {
                            Rect fragmentBounds = switch (fragment) {
                                case ParagraphFragment p -> new Rect(p.origin().x(), p.origin().y(), p.layout().width(), p.layout().height());
                                case TableFragment t -> new Rect(t.origin().x(), t.origin().y(), t.width(), t.height());
                                case ComponentFragment c -> new Rect(c.origin().x(), c.origin().y(), c.layout().width(), c.layout().height());
                            };
                            builder.save().transform(AffineTransform.fittedRotation(fragmentBounds, angle));
                        }
                        switch (fragment) {
                            case ParagraphFragment paragraph -> TextLayoutPainter.draw(builder,
                                    paragraph.layout(), paragraph.origin(), RgbColor.BLACK, resources.images);
                            case TableFragment nested -> paintTable(builder, nested, rotations, resources);
                            case ComponentFragment ignored -> throw new IllegalArgumentException("unsupported cell IR component");
                        }
                        if (angle != 0) { builder.restore(); }
                    }
                    builder.restore();
                } else {
                    TextLayoutPainter.draw(builder, cell.layout(), contentOrigin, RgbColor.BLACK, resources.images);
                }
            }
        }
    }

    /** Confined to one paint operation or session; only immutable resources escape through commands. */
    private static final class PaintResources {
        private final TextLayoutPainter.ImageCache images = new TextLayoutPainter.ImageCache();
        private final LinkedHashMap<BorderBounds, VectorPath> borders = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<DrawCommand, CachedCommand> commands = new LinkedHashMap<>(16, 0.75f, true);
        private final java.util.IdentityHashMap<FontData, Integer> commandFonts = new java.util.IdentityHashMap<>();
        private int commandUnits;
        private long commandFontBytes;

        /** Repeated pages retain every command slot while sharing equal immutable values. Images,
         * outlines and recursive command graphs never enter this bounded session-local pool.
         */
        private DisplayPage reuse(DisplayPage page) {
            ArrayList<DrawCommand> replacements = null;
            for (int index = 0; index < page.commands().size(); index++) {
                com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                var command = page.commands().get(index);
                int units = switch (command) {
                    case DrawCommand.GlyphText text -> text.run().glyphs().size() < MAX_CACHED_COMMAND_UNITS
                            ? text.run().glyphs().size() + 1 : 0;
                    case DrawCommand.StrokePath stroke -> stroke.path().segments().size() <= 64
                            ? stroke.path().segments().size() + 1 : 0;
                    case DrawCommand.FillRect ignored -> 1;
                    case DrawCommand.Save ignored -> 1;
                    case DrawCommand.Restore ignored -> 1;
                    case DrawCommand.Transform ignored -> 1;
                    case DrawCommand.Clip ignored -> 1;
                    case DrawCommand.Opacity ignored -> 1;
                    case DrawCommand.Blend ignored -> 1;
                    default -> 0;
                };
                if (units == 0 || additionalFontBytes(command) > MAX_CACHED_COMMAND_FONT_BYTES) { continue; }
                var cached = commands.get(command);
                if (cached == null) {
                    while (commands.size() >= MAX_CACHED_COMMANDS || commandUnits > MAX_CACHED_COMMAND_UNITS - units
                            || additionalFontBytes(command) > MAX_CACHED_COMMAND_FONT_BYTES - commandFontBytes) {
                        var evicted = commands.pollFirstEntry().getValue();
                        commandUnits -= evicted.units();
                        if (evicted.command() instanceof DrawCommand.GlyphText text) {
                            var font = text.run().font();
                            int references = commandFonts.get(font);
                            if (references == 1) { commandFonts.remove(font); commandFontBytes -= font.byteSize(); }
                            else { commandFonts.put(font, references - 1); }
                        }
                    }
                    commands.put(command, new CachedCommand(command, units));
                    commandUnits += units;
                    if (command instanceof DrawCommand.GlyphText text) {
                        var font = text.run().font();
                        commandFontBytes += additionalFontBytes(command);
                        commandFonts.merge(font, 1, Integer::sum);
                    }
                } else if (cached.command() != command) {
                    if (replacements == null) { replacements = new ArrayList<>(page.commands()); }
                    replacements.set(index, cached.command());
                }
            }
            return replacements == null ? page : new DisplayPage(page.size(), replacements);
        }

        private long additionalFontBytes(DrawCommand command) {
            return command instanceof DrawCommand.GlyphText text && !commandFonts.containsKey(text.run().font())
                    ? text.run().font().byteSize() : 0;
        }

        private VectorPath borderPath(double left, double top, double right, double bottom) {
            var bounds = new BorderBounds(left, top, right, bottom);
            var existing = borders.get(bounds);
            if (existing != null) { return existing; }
            var path = new VectorPath(List.of(new VectorPath.MoveTo(new Point(left, top)),
                    new VectorPath.LineTo(new Point(right, top)), new VectorPath.LineTo(new Point(right, bottom)),
                    new VectorPath.LineTo(new Point(left, bottom)), new VectorPath.Close()));
            if (borders.size() == MAX_CACHED_BORDER_PATHS) { borders.pollFirstEntry(); }
            borders.put(bounds, path);
            return path;
        }
    }

    private record CachedCommand(DrawCommand command, int units) { }
    private record BorderBounds(double left, double top, double right, double bottom) { }
}
