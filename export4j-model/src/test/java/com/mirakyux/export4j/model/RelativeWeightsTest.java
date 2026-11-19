// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelativeWeightsTest {
    @Test void equalProportionsRemainEqualAcrossTheFiniteRange() {
        for (double weight : new double[] {Double.MIN_VALUE, 1, 1e308, Double.MAX_VALUE}) {
            var proportions = RelativeWeights.of(weight, weight);
            assertEquals(2, proportions.size());
            assertEquals(0.5, proportions.share(0));
            assertEquals(240, proportions.portion(0, 480));
            assertEquals(240, proportions.portion(1, 480));
            assertEquals(480, RelativeWeights.of(weight).portion(0, 480));
        }
    }

    @Test void normalValuesKeepTheirExistingMultiplyThenDivideResultAndSnapshot() {
        double[] source = {3, 7, 11};
        var proportions = RelativeWeights.of(source);
        source[0] = 100;
        double total = 3.0 / 11 + 7.0 / 11 + 1;
        assertEquals((431.25 * (3.0 / 11)) / total, proportions.portion(0, 431.25));
        assertEquals((3.0 / 11) / total, proportions.share(0));
        var scaled = RelativeWeights.of(5e307, 1e308);
        assertEquals(RelativeWeights.of(1, 2).portion(0, 480), scaled.portion(0, 480));
        assertEquals(320, scaled.portion(1, 480));
    }

    @Test void aTinyShareCanStillHaveARepresentablePortion() {
        var proportions = RelativeWeights.of(Double.MIN_VALUE, Double.MAX_VALUE);
        assertEquals(0, proportions.share(0));
        assertEquals(Double.MIN_VALUE, proportions.portion(0, Double.MAX_VALUE));
        assertEquals(Double.MAX_VALUE, proportions.portion(1, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> proportions.portion(0, 1));
        var small = RelativeWeights.of(1e-308, 1e308);
        assertEquals(0, small.share(0));
        assertEquals(1e-308, small.portion(0, 1e308));
    }

    @Test void compensatedNormalizedSumKeepsAnExactlyRepresentableBoundary() {
        var proportions = RelativeWeights.of(10, 1, 1);
        assertEquals(100, 120 * proportions.share(0));
        assertEquals(120 * (0.1 / 1.2), 120 * proportions.share(1));
        assertEquals(120 * (0.1 / 1.2), 120 * proportions.share(2));
        assertEquals(100, proportions.portion(0, 120));
        assertEquals(10, proportions.portion(1, 120));
        assertEquals(10, proportions.portion(2, 120));
    }

    @Test void aSubnormalTotalDoesNotDoubleRoundAnUnrepresentablePortionIntoSuccess() {
        var proportions = RelativeWeights.of(0.75, 1);
        double unit = Double.MIN_VALUE;
        // The first exact share is 3/7 of a unit and must round to zero, while the second is 4/7.
        assertThrows(IllegalArgumentException.class, () -> proportions.portion(0, unit));
        assertEquals(unit, proportions.portion(1, unit));
        var equal = RelativeWeights.of(1, 1);
        assertThrows(IllegalArgumentException.class, () -> equal.portion(0, unit));
        assertThrows(IllegalArgumentException.class, () -> equal.portion(1, unit));
    }

    @Test void subnormalTotalsKeepKnownRepresentableUnitAllocations() {
        var proportions = RelativeWeights.of(0.75, 1);
        double unit = Double.MIN_VALUE;
        assertEquals(unit, proportions.portion(0, 2 * unit));
        assertEquals(unit, proportions.portion(1, 2 * unit));
        assertEquals(unit, proportions.portion(0, 3 * unit));
        assertEquals(2 * unit, proportions.portion(1, 3 * unit));
        assertEquals(3 * unit, proportions.portion(0, 7 * unit));
        assertEquals(4 * unit, proportions.portion(1, 7 * unit));
        for (int units : new int[] {2, 3, 4, 7}) {
            double total = units * unit;
            assertEquals(total, proportions.portion(0, total) + proportions.portion(1, total));
        }
        assertEquals(unit, RelativeWeights.of(1, 1).portion(0, 2 * unit));
    }

    @Test void exactSubnormalHalfUnitTiesRoundToTheEvenUnitCount() {
        var equal = RelativeWeights.of(1, 1);
        double unit = Double.MIN_VALUE;
        assertEquals(2 * unit, equal.portion(0, 3 * unit));
        assertEquals(2 * unit, equal.portion(0, 5 * unit));
        assertEquals(Double.MIN_NORMAL / 2, equal.portion(0, Math.nextDown(Double.MIN_NORMAL)));
        assertEquals(Double.MIN_NORMAL / 2, equal.portion(0, Double.MIN_NORMAL));
    }

    @Test void invalidWeightsTotalsAndIndexesFailExplicitly() {
        assertThrows(IllegalArgumentException.class, RelativeWeights::of);
        assertThrows(NullPointerException.class, () -> RelativeWeights.of((double[]) null));
        for (double invalid : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> RelativeWeights.of(1, invalid));
            assertThrows(IllegalArgumentException.class, () -> RelativeWeights.of(1).portion(0, invalid));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> RelativeWeights.of(1).share(1));
        assertThrows(IndexOutOfBoundsException.class, () -> RelativeWeights.of(1).portion(-1, 100));
    }
}
