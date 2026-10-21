// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private DocumentPainter() {
    }

    /** Paints black text and a thin table grid with gray headers; never changes layout geometry. */
    public static DisplayDocument paint(DocumentLayout layout) {
        Objects.requireNonNull(layout, "layout");
        List<DisplayPage> pages = new ArrayList<>();
        for (LayoutPage page : layout.pages()) {
            pages.add(paintPage(page));
        }
        return new DisplayDocument(layout.metadata(), pages);
    }

    /** Paints one page without wrapping it in a document or changing its physical IR page index.
     * DisplayPage carries geometry and commands only; the receiving backend owns sequential ordering.
     * Performs no measurement, pagination, I/O or ownership transfer. Thread-safe for immutable input.
     */
    public static DisplayPage paintPage(LayoutPage page) {
        Objects.requireNonNull(page, "page");
        DisplayListBuilder builder = new DisplayListBuilder(page.size());
        for (var fragment : page.elements()) {
            com.mirakyux.export4j.model.ExecutionContext.checkpoint();
            switch (fragment) {
                case ParagraphFragment paragraph ->
                    TextLayoutPainter.draw(builder, paragraph.layout(), paragraph.origin(), RgbColor.BLACK);
                case TableFragment table -> paintTable(builder, table);
                case ComponentFragment component -> {
                    builder.save().transform(AffineTransform.translation(component.origin().x(), component.origin().y()));
                    paintComponent(builder, component.layout());
                    builder.restore();
                }
            }
        }
        return builder.build();
    }

    /** Appends a fully resolved component in its current graphics state, retaining semantic z-order.
     * Performs no measurement, field resolution or I/O. Deferred fields must already be resolved.
     * @throws IllegalArgumentException when semantic placement geometry is unsupported or clipped
     */
    public static void paintComponent(DisplayListBuilder builder, ComponentLayout component) {
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
                paintTable(table, placement.fragment());
                commands.addAll(table.build().commands());
            }
            if (index < staticCommands.size()) { commands.add(staticCommands.get(index)); }
        }
        builder.appendCommands(commands);
    }

    private static void paintTable(DisplayListBuilder builder, TableFragment table) {
        for (var row : table.rows()) {
            for (var cell : row.cells()) {
                Rect bounds = cell.bounds();
                double x = table.origin().x() + bounds.x();
                double y = table.origin().y() + bounds.y();
                if (row.header()) {
                    builder.fillRect(new Rect(x, y, bounds.width(), bounds.height()), new RgbColor(0.93, 0.95, 0.97));
                }
                // Center the stroke inside each cell so outer borders stay in the content area.
                double stroke = Math.min(0.5, Math.min(bounds.width(), bounds.height()));
                double left = x + stroke / 2;
                double top = y + stroke / 2;
                double right = x + bounds.width() - stroke / 2;
                double bottom = y + bounds.height() - stroke / 2;
                builder.strokePath(new VectorPath(List.of(new VectorPath.MoveTo(new Point(left, top)),
                        new VectorPath.LineTo(new Point(right, top)), new VectorPath.LineTo(new Point(right, bottom)),
                        new VectorPath.LineTo(new Point(left, bottom)), new VectorPath.Close())), RgbColor.BLACK, stroke);
                var contentOrigin = new Point(table.origin().x() + cell.textOrigin().x(),
                        table.origin().y() + cell.textOrigin().y());
                if (cell.hasBlocks()) {
                    builder.save().transform(AffineTransform.translation(contentOrigin.x(), contentOrigin.y()));
                    for (var fragment : cell.blocks()) {
                        com.mirakyux.export4j.model.ExecutionContext.checkpoint();
                        switch (fragment) {
                            case ParagraphFragment paragraph -> TextLayoutPainter.draw(builder,
                                    paragraph.layout(), paragraph.origin(), RgbColor.BLACK);
                            case TableFragment nested -> paintTable(builder, nested);
                            case ComponentFragment ignored -> throw new IllegalArgumentException("unsupported cell IR component");
                        }
                    }
                    builder.restore();
                } else {
                    TextLayoutPainter.draw(builder, cell.layout(), contentOrigin, RgbColor.BLACK);
                }
            }
        }
    }
}
