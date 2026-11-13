// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable, explicit vector outline in baseline-relative points. Coordinates stay inside
 * [0,width] x [-ascent,descent]. No parser, callback or mutable drawing state is retained.
 */
public final class VectorResource {
    public static final int MAX_SEGMENTS = 10_000;
    private final double width;
    private final double ascent;
    private final double descent;
    private final List<Segment> segments;
    private final String id;
    private final int byteSize;

    public enum Operation { MOVE, LINE, CURVE, CLOSE }

    /** Cubic curves have six coordinates, move/line two and close none. */
    public record Segment(Operation operation, List<Double> coordinates) {
        public Segment {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(coordinates, "coordinates");
            int count = switch (operation) { case MOVE, LINE -> 2; case CURVE -> 6; case CLOSE -> 0; };
            if (coordinates.size() != count) { throw new IllegalArgumentException("invalid vector coordinate count"); }
            coordinates = List.copyOf(coordinates);
            if (coordinates.stream().anyMatch(value -> !Double.isFinite(value))) {
                throw new IllegalArgumentException("invalid vector coordinates");
            }
            coordinates = coordinates.stream().map(value -> value == 0 ? 0.0 : value).toList();
        }
    }

    private VectorResource(double width, double ascent, double descent, List<Segment> segments) {
        this.width = width; this.ascent = ascent; this.descent = descent; this.segments = segments;
        int length = 4 + 3 * Double.BYTES + Integer.BYTES;
        for (var segment : segments) { length += 1 + segment.coordinates().size() * Double.BYTES; }
        byteSize = length;
        var encoding = ByteBuffer.allocate(length);
        encoding.putInt(1).putDouble(width).putDouble(ascent).putDouble(descent).putInt(segments.size());
        for (var segment : segments) {
            encoding.put((byte) segment.operation().ordinal());
            for (double coordinate : segment.coordinates()) { encoding.putDouble(coordinate); }
        }
        try { id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoding.array())); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 is required", failure); }
    }

    /** Snapshots a nonempty path with at most 10,000 segments and 14,400 pt extents.
     * Each subpath begins with MOVE; CLOSE ends its current subpath.
     */
    public static VectorResource of(double width, double ascent, double descent, List<Segment> segments) {
        Objects.requireNonNull(segments, "segments");
        if (!Double.isFinite(width) || width <= 0 || width > 14_400
                || !Double.isFinite(ascent) || ascent < 0 || !Double.isFinite(descent) || descent < 0
                || ascent + descent <= 0 || ascent + descent > 14_400
                || segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("invalid vector dimensions or segment budget");
        }
        var snapshot = List.copyOf(segments);
        boolean open = false;
        for (var segment : snapshot) {
            ExecutionContext.checkpoint();
            if (segment.operation() == Operation.MOVE) { open = true; }
            else if (!open) { throw new IllegalArgumentException("vector subpath must begin with MOVE"); }
            if (segment.operation() == Operation.CLOSE) { open = false; }
            for (int index = 0; index < segment.coordinates().size(); index += 2) {
                double x = segment.coordinates().get(index); double y = segment.coordinates().get(index + 1);
                if (x < 0 || x > width || y < -ascent || y > descent) {
                    throw new IllegalArgumentException("vector ink exceeds its reserved box");
                }
            }
        }
        return new VectorResource(width, ascent == 0 ? 0 : ascent, descent == 0 ? 0 : descent, snapshot);
    }

    public double width() { return width; }
    public double ascent() { return ascent; }
    public double descent() { return descent; }
    public List<Segment> segments() { return segments; }
    public String id() { return id; }
    public int segmentCount() { return segments.size(); }
    /** Size of the versioned canonical encoding, not a JVM heap-size estimate. */
    public int byteSize() { return byteSize; }
    @Override public boolean equals(Object value) { return value instanceof VectorResource other && id.equals(other.id); }
    @Override public int hashCode() { return id.hashCode(); }
    @Override public String toString() { return "VectorResource[" + width + ", " + ascent + ", " + descent + ", " + id + "]"; }
}
