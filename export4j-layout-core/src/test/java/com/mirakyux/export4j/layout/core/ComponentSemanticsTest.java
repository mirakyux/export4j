// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.core.DisplayListBuilder;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.SemanticPlacement;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;

class ComponentSemanticsTest {
    private static final PageSize SIZE = new PageSize(100, 100);
    private static final Point ZERO = new Point(0, 0);
    private static final CellFormat CELL = new CellFormat(10, 12, ParagraphAlignment.LEFT);
    private static FontGroup fonts;

    @BeforeAll
    static void loadFont() throws IOException {
        try (var input = ComponentSemanticsTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    @Test
    void legacyStaticLayoutRetainsItsCompleteCommandsAndPainting() {
        var source = List.<DrawCommand>of(marker(1));
        var layout = new ComponentLayout(100, 100, 0, source, List.of());
        assertEquals(source, layout.commands());
        assertEquals(source, layout.staticCommands());
        assertTrue(layout.semanticPlacements().isEmpty());
        assertTrue(ComponentSemantics.locate(layout, ZERO).isEmpty());
        var output = new DisplayListBuilder(SIZE);
        DocumentPainter.paintComponent(output, layout);
        assertEquals(source, output.build().commands());
    }

    @Test
    void semanticSlotsPreserveStartEndAndSharedSlotDrawingOrder() {
        var layout = new ComponentLayout(100, 100, 4, List.of(marker(1), marker(2)), List.of(), List.of(
                placement("A", 0), placement("B", 1), placement("C", 1), placement("D", 2)));
        var output = new DisplayListBuilder(SIZE);
        DocumentPainter.paintComponent(output, layout);
        var tokens = new ArrayList<String>();
        for (var command : output.build().commands()) {
            switch (command) {
                case DrawCommand.FillRect fill -> tokens.add("STATIC" + (int) fill.bounds().x());
                case DrawCommand.GlyphText text -> tokens.add(text.run().text());
                case DrawCommand.OutlineText text -> {
                    if (!text.logicalText().isEmpty()) { tokens.add(text.logicalText()); }
                }
                default -> { }
            }
        }
        assertEquals(List.of("A", "STATIC1", "B", "C", "STATIC2", "D"), tokens);
    }

    @Test
    void appendOffsetsSlotsAndRestoredTransformsDoNotLeakToFollowingChildren() {
        var builder = new DisplayListBuilder(SIZE);
        var insertions = new ArrayList<SemanticPlacement>();
        builder.save().transform(AffineTransform.translation(3, 5))
                .save().transform(AffineTransform.translation(7, 11));
        ComponentSemantics.append(builder, insertions, semantic("A"));
        builder.restore().restore();
        builder.save().transform(AffineTransform.translation(40, 50));
        ComponentSemantics.append(builder, insertions, semantic("B"));
        builder.restore();
        assertEquals(List.of(4, 8), insertions.stream().map(SemanticPlacement::commandIndex).toList());
        var combined = new ComponentLayout(100, 100, 2, builder.build().commands(), List.of(), insertions);
        var located = ComponentSemantics.locate(combined, new Point(100, 200));
        assertEquals(List.of("A", "B"), located.stream().map(item -> item.placement().source().id()).toList());
        assertEquals(List.of(new Point(110, 216), new Point(140, 250)),
                located.stream().map(ComponentSemantics.Located::translation).toList());
        assertTrue(located.stream().allMatch(item -> item.clip().isEmpty()));
    }

    @Test
    void completeClipUsesFinalPageCoordinates() {
        var child = semantic("A");
        var clip = new Rect(0, 0, child.width(), child.height());
        var combined = clipped(child, clip);
        var located = ComponentSemantics.locate(combined, new Point(100, 200)).getFirst();
        assertEquals(new Point(110, 220), located.translation());
        assertEquals(new Rect(110, 220, child.width(), child.height()), located.clip().orElseThrow());
    }

    @Test
    void partialAndEmptyClipFailInsteadOfDroppingSemanticContent() {
        var child = semantic("A");
        assertThrows(IllegalArgumentException.class,
                () -> ComponentSemantics.locate(clipped(child, new Rect(0, 0, child.width() - 1, child.height())), ZERO));
        var builder = new DisplayListBuilder(SIZE);
        var insertions = new ArrayList<SemanticPlacement>();
        builder.save().clip(new Rect(0, 0, 40, 30)).clip(new Rect(50, 50, 10, 10));
        ComponentSemantics.append(builder, insertions, child);
        builder.restore();
        var empty = new ComponentLayout(100, 100, 1, builder.build().commands(), List.of(), insertions);
        assertThrows(IllegalArgumentException.class, () -> ComponentSemantics.locate(empty, ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> DocumentPainter.paintComponent(new DisplayListBuilder(SIZE), empty));
    }

    @Test
    void semanticScaleAndRotationFailWhileRestoredStaticTransformsRemainSupported() {
        for (var matrix : List.of(new AffineTransform(2, 0, 0, 2, 0, 0),
                new AffineTransform(0, 1, -1, 0, 0, 0))) {
            var transformed = new ComponentLayout(100, 100, 1, List.of(new DrawCommand.Transform(matrix)),
                    List.of(), List.of(placement("A", 1)));
            assertThrows(IllegalArgumentException.class, () -> ComponentSemantics.locate(transformed, ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> DocumentPainter.paintComponent(new DisplayListBuilder(SIZE), transformed));
        }
        var commands = List.<DrawCommand>of(new DrawCommand.Save(),
                new DrawCommand.Transform(new AffineTransform(2, 0, 0, 2, 0, 0)), marker(1), new DrawCommand.Restore());
        var combined = new ComponentLayout(100, 100, 1, commands, List.of(), List.of(placement("A", 4)));
        assertEquals(ZERO, ComponentSemantics.locate(combined, ZERO).getFirst().translation());
        var output = new DisplayListBuilder(SIZE);
        DocumentPainter.paintComponent(output, combined);
        assertEquals(commands, output.build().commands().subList(0, commands.size()));
    }

    @Test
    void staticOnlyAccessCannotSilentlyDiscardDeferredTables() {
        var layout = semantic("A");
        assertThrows(IllegalStateException.class, layout::commands);
        assertTrue(layout.staticCommands().isEmpty());
        assertEquals("A", layout.semanticPlacements().getFirst().source().id());
        assertThrows(UnsupportedOperationException.class, () -> layout.semanticPlacements().clear());
    }

    @Test
    void unresolvedFieldsFailDuringDeferredPainting() {
        var cell = TableCell.mixed(List.of(new InlineField(InlineField.Kind.PAGE, "", 16, InlineStyle.of(10))), CELL);
        var placement = placement("field", cell, 0);
        var layout = new ComponentLayout(40, placement.fragment().height(), 1, List.of(), List.of(), List.of(placement));
        assertEquals(1, placement.fragment().rows().getFirst().cells().getFirst().layout().lines().getFirst().fields().size());
        var failure = assertThrows(IllegalArgumentException.class,
                () -> DocumentPainter.paintComponent(new DisplayListBuilder(SIZE), layout));
        assertTrue(failure.getMessage().contains("must be resolved"));
    }

    private static ComponentLayout clipped(ComponentLayout child, Rect clip) {
        var builder = new DisplayListBuilder(SIZE);
        var insertions = new ArrayList<SemanticPlacement>();
        builder.save().transform(AffineTransform.translation(10, 20)).clip(clip);
        ComponentSemantics.append(builder, insertions, child);
        builder.restore();
        return new ComponentLayout(100, 100, child.textLines(), builder.build().commands(), List.of(), insertions);
    }

    private static ComponentLayout semantic(String value) {
        var placement = placement(value, 0);
        return new ComponentLayout(40, placement.fragment().height(), 1, List.of(), List.of(), List.of(placement));
    }

    private static SemanticPlacement placement(String value, int slot) {
        return placement(value, new TableCell(value, CELL), slot);
    }

    private static SemanticPlacement placement(String id, TableCell cell, int slot) {
        Table source = Table.builder(id).columns(new ColumnWidth.Fixed(40)).cellFormat(CELL)
                .format(new TableFormat(PageMargins.uniform(0), 0, 0)).row(cell).build();
        var document = new Document(DocumentMetadata.EMPTY, new PageSettings(SIZE, PageMargins.uniform(0)), List.of(source));
        var measured = new DocumentLayouter().layout(document, fonts);
        var fragment = (TableFragment) measured.pages().getFirst().elements().getFirst();
        return new SemanticPlacement(slot, source, fragment, fonts, Map.of());
    }

    private static DrawCommand.FillRect marker(int value) {
        return new DrawCommand.FillRect(new Rect(value, 1, 1, 1), RgbColor.BLACK);
    }
}
