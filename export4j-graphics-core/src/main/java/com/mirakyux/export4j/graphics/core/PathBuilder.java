// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.graphics.core;

import java.util.ArrayList;
import java.util.List;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.VectorPath;

/** Task-confined vector path builder, with immutable snapshots and point units. */
public final class PathBuilder {
    private final List<VectorPath.Segment> segments = new ArrayList<>();
    private boolean open;

    public PathBuilder moveTo(double x, double y) {
        segments.add(new VectorPath.MoveTo(new Point(x, y)));
        open = true;
        return this;
    }

    public PathBuilder lineTo(double x, double y) {
        requireOpen();
        segments.add(new VectorPath.LineTo(new Point(x, y)));
        return this;
    }

    public PathBuilder curveTo(double x1, double y1, double x2, double y2, double x, double y) {
        requireOpen();
        segments.add(new VectorPath.CurveTo(new Point(x1, y1), new Point(x2, y2), new Point(x, y)));
        return this;
    }

    public PathBuilder close() {
        requireOpen();
        segments.add(new VectorPath.Close());
        open = false;
        return this;
    }

    public VectorPath build() {
        return new VectorPath(segments);
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("path segment requires moveTo");
        }
    }
}
