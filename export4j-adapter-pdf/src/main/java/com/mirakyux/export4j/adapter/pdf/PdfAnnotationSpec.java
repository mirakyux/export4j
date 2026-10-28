// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import java.util.List;
import java.util.Objects;

/** Passive markup in native unrotated PDF user space, with explicit colors and opacity.
 * Quad vertices use PDF's common Z order: upper-left, upper-right, lower-left, lower-right.
 * A fill color of -1 means no interior fill. No script, external resource or rich popup is accepted.
 */
public record PdfAnnotationSpec(Type type, PdfAdapter.Box box, String contents, List<Quad> quads,
        List<List<Point>> ink, int strokeRgb, int fillRgb, double width, double opacity) {
    public enum Type { HIGHLIGHT, UNDERLINE, STRIKEOUT, INK, SQUARE, CIRCLE }
    public record Point(double x, double y) {
        public Point { if (!finite(x) || !finite(y)) { throw new IllegalArgumentException("invalid annotation point"); } }
    }
    public record Quad(Point upperLeft, Point upperRight, Point lowerLeft, Point lowerRight) {
        public Quad { Objects.requireNonNull(upperLeft); Objects.requireNonNull(upperRight); Objects.requireNonNull(lowerLeft); Objects.requireNonNull(lowerRight); }
        List<Point> points() { return List.of(upperLeft, upperRight, lowerLeft, lowerRight); }
    }
    public PdfAnnotationSpec {
        Objects.requireNonNull(type); Objects.requireNonNull(box); Objects.requireNonNull(contents);
        quads = List.copyOf(quads); ink = ink.stream().map(List::copyOf).toList();
        if (!finite(box.x()) || !finite(box.y()) || !finite(box.width()) || !finite(box.height()) || box.width() <= 0 || box.height() <= 0
                || (strokeRgb & 0xff000000) != 0 || fillRgb != -1 && (fillRgb & 0xff000000) != 0
                || !Double.isFinite(width) || width < 0 || width > 10_000 || !Double.isFinite(opacity) || opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException("invalid annotation appearance");
        }
        boolean markup = type == Type.HIGHLIGHT || type == Type.UNDERLINE || type == Type.STRIKEOUT;
        if (markup != !quads.isEmpty() || type == Type.INK != !ink.isEmpty() || !markup && !quads.isEmpty() || type != Type.INK && !ink.isEmpty()) {
            throw new IllegalArgumentException("annotation geometry does not match type");
        }
        for (var quad : quads) {
            for (var point : quad.points()) { inside(box, point); }
            double area = (quad.upperRight.x - quad.upperLeft.x) * (quad.lowerLeft.y - quad.upperLeft.y)
                    - (quad.upperRight.y - quad.upperLeft.y) * (quad.lowerLeft.x - quad.upperLeft.x);
            if (Math.abs(area) < 1e-6) { throw new IllegalArgumentException("degenerate annotation quad"); }
        }
        for (var path : ink) { if (path.size() < 2) { throw new IllegalArgumentException("ink path needs two points"); } for (var point : path) { inside(box, point); } }
    }
    private static boolean finite(double value) { return Double.isFinite(value) && Math.abs(value) <= 1_000_000; }
    private static void inside(PdfAdapter.Box box, Point point) {
        if (point.x < box.x() || point.x > box.x() + box.width() || point.y < box.y() || point.y > box.y() + box.height()) {
            throw new IllegalArgumentException("annotation point outside rectangle");
        }
    }
}
