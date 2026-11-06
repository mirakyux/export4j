// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.BlendMode;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Paint;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.graphics.VectorPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GraphicsPaintMappingTest {
    private static final Rect BOUNDS = new Rect(0, 0, 20, 20);
    private static final VectorPath PATH = new PathBuilder().moveTo(0, 0).lineTo(20, 20).build();

    @Test
    void identityMappingReusesImmutableGraphButStillVisitsEveryPaintInOrder() {
        var black = new Paint.Solid(RgbColor.BLACK);
        var blue = new Paint.Solid(new RgbColor(0, 0, 1));
        var tile = new Paint.Tile(BOUNDS, 20, 20, AffineTransform.IDENTITY,
                List.of(new DrawCommand.PaintFill(PATH, black)));
        var group = new DrawCommand.Group(BOUNDS,
                List.of(new DrawCommand.PaintFill(PATH, blue), new DrawCommand.PaintFill(PATH, tile)),
                0.5, BlendMode.MULTIPLY);
        List<DrawCommand> source = List.of(new DrawCommand.Save(), group,
                new DrawCommand.PaintStroke(PATH, black, 2), new DrawCommand.Restore());
        var visited = new ArrayList<Paint>();

        var mapped = GraphicsResources.mapPaints(source, paint -> { visited.add(paint); return paint; });

        assertSame(source, mapped);
        assertSame(group, mapped.get(1));
        assertEquals(List.of(blue, black, tile, black), visited);
    }

    @Test
    void identityMappingStillSnapshotsMutableInput() {
        var paint = new Paint.Solid(RgbColor.BLACK);
        var fill = new DrawCommand.PaintFill(PATH, paint);
        var source = new ArrayList<DrawCommand>(List.of(fill));

        var mapped = GraphicsResources.mapPaints(source, value -> value);
        source.clear();

        assertNotSame(source, mapped);
        assertEquals(List.of(fill), mapped);
        assertSame(fill, mapped.getFirst());
        assertThrows(UnsupportedOperationException.class, mapped::clear);
    }

    @Test
    void replacingNestedPaintPreservesCommandIndicesAndUnchangedPrefixes() {
        var black = new Paint.Solid(RgbColor.BLACK);
        var blue = new Paint.Solid(new RgbColor(0, 0, 1));
        var red = new Paint.Solid(new RgbColor(1, 0, 0));
        var tile = new Paint.Tile(BOUNDS, 20, 20, AffineTransform.IDENTITY,
                List.of(new DrawCommand.PaintFill(PATH, black, DrawCommand.FillRule.EVEN_ODD)));
        var nested = new DrawCommand.PaintFill(PATH, tile);
        var stroke = new DrawCommand.PaintStroke(PATH, black, 2.5);
        var group = new DrawCommand.Group(BOUNDS, List.of(nested, stroke), 0.5, BlendMode.MULTIPLY);
        List<DrawCommand> source = List.of(new DrawCommand.Save(), new DrawCommand.PaintFill(PATH, blue),
                group, new DrawCommand.Restore());
        var visited = new ArrayList<Paint>();

        var mapped = GraphicsResources.mapPaints(source, paint -> {
            visited.add(paint);
            return paint == black ? red : paint;
        });

        assertNotSame(source, mapped);
        assertEquals(source.size(), mapped.size());
        assertSame(source.get(0), mapped.get(0));
        assertSame(source.get(1), mapped.get(1));
        assertSame(source.get(3), mapped.get(3));
        var mappedGroup = assertInstanceOf(DrawCommand.Group.class, mapped.get(2));
        assertEquals(group.bounds(), mappedGroup.bounds());
        assertEquals(group.opacity(), mappedGroup.opacity());
        assertEquals(group.blend(), mappedGroup.blend());
        var mappedFill = assertInstanceOf(DrawCommand.PaintFill.class, mappedGroup.commands().get(0));
        var mappedTile = assertInstanceOf(Paint.Tile.class, mappedFill.paint());
        assertEquals(tile.bounds(), mappedTile.bounds());
        assertEquals(tile.transform(), mappedTile.transform());
        assertEquals(tile.xStep(), mappedTile.xStep());
        assertEquals(tile.yStep(), mappedTile.yStep());
        var child = assertInstanceOf(DrawCommand.PaintFill.class, mappedTile.commands().getFirst());
        assertSame(red, child.paint());
        assertSame(PATH, child.path());
        assertEquals(DrawCommand.FillRule.EVEN_ODD, child.rule());
        var mappedStroke = assertInstanceOf(DrawCommand.PaintStroke.class, mappedGroup.commands().get(1));
        assertSame(red, mappedStroke.paint());
        assertEquals(stroke.width(), mappedStroke.width());
        assertEquals(List.of(blue, black, mappedTile, black), visited);
        assertSame(black, ((DrawCommand.PaintFill) tile.commands().getFirst()).paint());
        assertSame(stroke, group.commands().get(1));
    }

    @Test
    void identityMappingCannotBypassNestedTraversalBudget() {
        List<DrawCommand> source = List.of(new DrawCommand.PaintFill(PATH, new Paint.Solid(RgbColor.BLACK)));
        for (int i = 0; i < 34; i++) {
            source = List.of(new DrawCommand.Group(BOUNDS, source, 1, BlendMode.NORMAL));
        }
        var nested = source;
        var visited = new ArrayList<Paint>();

        assertThrows(IllegalArgumentException.class,
                () -> GraphicsResources.mapPaints(nested, paint -> { visited.add(paint); return paint; }));
        assertEquals(List.of(), visited);
    }
}
