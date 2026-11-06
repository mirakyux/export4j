// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnitsAndPageSizeTest {
    @Test void physicalUnitsProducePointsAndKeepSignedOffsets() {
        assertEquals(72, Units.inches(1), 1e-12);
        assertEquals(72, Units.mm(25.4), 1e-12);
        assertEquals(72, Units.cm(2.54), 1e-12);
        assertEquals(-36, Units.mm(-12.7), 1e-12);
        assertEquals(9.5, Units.pt(9.5));
        var a4 = PageSize.ofMillimeters(210, 297);
        assertEquals(PageSize.A4.width(), a4.width(), 1e-9);
        assertEquals(PageSize.A4.height(), a4.height(), 1e-9);
        assertEquals(PageSize.LETTER, PageSize.ofInches(8.5, 11));
        assertEquals(new PageSize(a4.height(), a4.width()), a4.landscape());
        assertEquals(a4.landscape(), a4.landscape().landscape());
        assertEquals(a4, a4.landscape().portrait());
    }

    @Test void nonfiniteConversionsOverflowAndInvalidPagesAreRejected() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Units.pt(invalid));
            assertThrows(IllegalArgumentException.class, () -> Units.mm(invalid));
            assertThrows(IllegalArgumentException.class, () -> Units.cm(invalid));
            assertThrows(IllegalArgumentException.class, () -> Units.inches(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> Units.inches(Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> PageSize.ofMillimeters(-1, 297));
        assertThrows(IllegalArgumentException.class, () -> PageSize.ofInches(0, 11));
    }
}
