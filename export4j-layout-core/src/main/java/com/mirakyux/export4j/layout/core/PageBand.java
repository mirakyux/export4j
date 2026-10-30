// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.layout.core;

import java.util.List;

/** Repeating header/footer viewport. Height and gap reserve body space in points; placements must stay inside it. */
public record PageBand(double height, double gap, List<Placement> placements,
        double edgeOffset, double leftInset, double rightInset) {
    public static final PageBand EMPTY = new PageBand(0, 0, List.of());

    public PageBand(double height, double gap, List<Placement> placements) {
        this(height, gap, placements, -1, -1, -1);
    }

    public PageBand {
        placements = List.copyOf(placements);
        if (!Double.isFinite(height) || height < 0 || !Double.isFinite(gap) || gap < 0
                || !offset(edgeOffset) || !offset(leftInset) || !offset(rightInset)
                || height == 0 && (!placements.isEmpty() || gap != 0 || edgeOffset != -1 || leftInset != -1 || rightInset != -1)) {
            throw new IllegalArgumentException("invalid page band dimensions");
        }
    }

    private static boolean offset(double value) { return Double.isFinite(value) && (value == -1 || value >= 0); }

    /** Task-confined builder. Dimensions use points unless the type specifies another unit.
     * build() validates the complete value and takes an immutable structural snapshot. */
    public static Builder builder() { return new Builder(); }
    /** Starts an independent builder from this value; referenced resources remain borrowed. */
    public Builder toBuilder() { return new Builder().height(height()).gap(gap()).placements(placements())
            .edgeOffset(edgeOffset()).leftInset(leftInset()).rightInset(rightInset()); }

    /** Mutable, task-confined construction state; built values do not retain this builder. */
    public static final class Builder {
        private double height = 0;
        private double gap = 0;
        private double edgeOffset = -1;
        private double leftInset = -1;
        private double rightInset = -1;
        private List<Placement> placements = new java.util.ArrayList<>();

        private Builder() { }
        public Builder height(double value) { height = value; return this; }
        public Builder gap(double value) { gap = value; return this; }
        public Builder edgeOffset(double value) { edgeOffset = value; return this; }
        public Builder leftInset(double value) { leftInset = value; return this; }
        public Builder rightInset(double value) { rightInset = value; return this; }
        public Builder placements(List<Placement> value) { placements = new java.util.ArrayList<>(java.util.List.copyOf(value)); return this; }
        public Builder add(Placement placement) { placements.add(java.util.Objects.requireNonNull(placement, "placement")); return this; }
        /** Fills the band viewport without positional arithmetic. */
        public Builder content(Component component) { return add(new Placement(component, new Position.Fractional(0, 0, 1, 1))); }
        public PageBand build() { return new PageBand(height, gap, placements, edgeOffset, leftInset, rightInset); }
    }
}
