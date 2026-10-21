// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.model.PageSize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisplayListBuilderTest {
    @Test
    void snapshotsStayIndependentAndStateErrorsDoNotCorruptRecorder() {
        DisplayListBuilder builder = new DisplayListBuilder(PageSize.A4);
        assertThrows(IllegalStateException.class, builder::restore);
        builder.fillRect(new Rect(10, 10, 20, 20), RgbColor.BLACK);
        DisplayPage first = builder.build();
        builder.save();
        assertThrows(IllegalStateException.class, builder::build);
        builder.restore().fillRect(new Rect(30, 30, 20, 20), RgbColor.BLACK);
        assertEquals(1, first.commands().size());
        assertEquals(4, builder.build().commands().size());
    }

    @Test
    void invalidPathOperationsPreservePreviouslyRecordedPath() {
        PathBuilder builder = new PathBuilder();
        assertThrows(IllegalStateException.class, () -> builder.lineTo(1, 1));
        builder.moveTo(0, 0).lineTo(10, 10);
        var snapshot = builder.build();
        builder.close();
        assertThrows(IllegalStateException.class, () -> builder.curveTo(1, 1, 2, 2, 3, 3));
        assertEquals(2, snapshot.segments().size());
        builder.moveTo(20, 20).lineTo(30, 30);
        assertEquals(5, builder.build().segments().size());
    }
}
