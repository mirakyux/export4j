// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

class PageSizeTest {
    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidDimensions(double dimension) {
        assertThrows(IllegalArgumentException.class, () -> new PageSize(dimension, 100));
        assertThrows(IllegalArgumentException.class, () -> new PageSize(100, dimension));
    }
}
