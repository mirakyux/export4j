// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable, unit-independent proportions from a nonempty snapshot of positive finite weights.
 * Raw weight sums and intermediate products cannot overflow. This type does not resolve fixed
 * widths, content measurements, rounding to target units, resources or layout budgets.
 */
public final class RelativeWeights {
    private final double[] normalized;
    private final double sum;
    private final double[] weights;
    private final BigDecimal exactSum;

    private RelativeWeights(double[] values) {
        weights = values;
        double maximum = 0;
        for (double value : weights) {
            if (!Double.isFinite(value) || value <= 0) {
                throw new IllegalArgumentException("relative weights must be finite and positive");
            }
            maximum = Math.max(maximum, value);
        }
        normalized = new double[weights.length];
        boolean tiny = false;
        for (int index = 0; index < weights.length; index++) {
            normalized[index] = weights[index] / maximum;
            tiny |= normalized[index] < Double.MIN_NORMAL;
        }
        // Compensated summation retains exact viewport boundaries such as 120 / (1 + .1 + .1).
        sum = Arrays.stream(normalized).sum();
        BigDecimal exact = null;
        if (tiny) {
            exact = BigDecimal.ZERO;
            for (double value : weights) { exact = exact.add(new BigDecimal(value)); }
        }
        exactSum = exact;
    }

    /** Snapshots nonempty weights; null throws NullPointerException and invalid values throw IllegalArgumentException. */
    public static RelativeWeights of(double... weights) {
        var copy = Objects.requireNonNull(weights, "weights").clone();
        if (copy.length == 0) { throw new IllegalArgumentException("relative weights must not be empty"); }
        return new RelativeWeights(copy);
    }

    /** Returns the positive number of snapshotted weights. */
    public int size() { return normalized.length; }

    /**
     * Returns the share in [0, 1]. An extremely small share may underflow to zero even though
     * portion(index, total) remains representable. Invalid indexes throw IndexOutOfBoundsException.
     */
    public double share(int index) {
        Objects.checkIndex(index, size());
        return normalized[index] < Double.MIN_NORMAL ? exactPortion(index, 1) : normalized[index] / sum;
    }

    /**
     * Returns this weight's positive finite portion of a positive finite total, in the total's units.
     * Ordinary values retain the normalized multiply-then-divide order. Extremely small relative
     * weights use bounded-precision decimal division. Subnormal results are rounded in exact
     * minimum-double units to avoid premature binary underflow and double rounding.
     * An invalid total or an unrepresentable positive result throws IllegalArgumentException;
     * an invalid index throws IndexOutOfBoundsException. No minimum target-unit size is imposed.
     */
    public double portion(int index, double total) {
        Objects.checkIndex(index, size());
        if (!Double.isFinite(total) || total <= 0) {
            throw new IllegalArgumentException("relative total must be finite and positive");
        }
        double result = (total * normalized[index]) / sum;
        if (normalized[index] < Double.MIN_NORMAL || result < Double.MIN_NORMAL) {
            result = exactPortion(index, total);
        }
        if (!Double.isFinite(result) || result <= 0) {
            throw new IllegalArgumentException("relative portion is not representable as a positive finite value");
        }
        return result;
    }

    private double exactPortion(int index, double total) {
        BigDecimal denominator = exactSum;
        if (denominator == null) {
            denominator = BigDecimal.ZERO;
            for (double value : weights) { denominator = denominator.add(new BigDecimal(value)); }
        }
        var numerator = new BigDecimal(total).multiply(new BigDecimal(weights[index]));
        if (numerator.compareTo(denominator.multiply(new BigDecimal(Double.MIN_NORMAL))) <= 0) {
            // Every subnormal is an integer multiple of MIN_VALUE. Round the rational directly,
            // including half-unit ties; an intermediate decimal approximation could flip the tie.
            var unit = denominator.multiply(new BigDecimal(Double.MIN_VALUE));
            long units = numerator.divide(unit, 0, RoundingMode.HALF_EVEN).longValueExact();
            return units * Double.MIN_VALUE;
        }
        return numerator.divide(denominator, MathContext.DECIMAL128).doubleValue();
    }
}
