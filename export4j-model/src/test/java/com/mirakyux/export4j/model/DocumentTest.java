// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentTest {
    @Test
    void snapshotsOrderedParagraphsAndRejectsDuplicateOrBlankIds() {
        Paragraph paragraph = new Paragraph("body", "", ParagraphFormat.of(12));
        List<Paragraph> source = new ArrayList<>(List.of(paragraph));
        Document document = new Document(DocumentMetadata.EMPTY, PageSettings.A4, source);
        source.clear();
        assertEquals(List.of(paragraph), document.paragraphs());
        assertThrows(UnsupportedOperationException.class, () -> document.paragraphs().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(paragraph, paragraph)));
        assertThrows(IllegalArgumentException.class, () -> new Paragraph(" ", "", ParagraphFormat.of(12)));
    }

    @Test
    void rejectsInvalidMarginsAndExhaustedContentAreas() {
        assertThrows(IllegalArgumentException.class, () -> PageMargins.uniform(-1));
        assertThrows(IllegalArgumentException.class, () -> PageMargins.uniform(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new PageSettings(new PageSize(100, 100), new PageMargins(50, 0, 50, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new PageSettings(new PageSize(100, 100), new PageMargins(0, 50, 0, 50)));
        assertThrows(IllegalArgumentException.class,
                () -> new PageSettings(new PageSize(100, 100), PageMargins.uniform(Double.MAX_VALUE)));
        PageSettings settings = new PageSettings(new PageSize(100, 200), new PageMargins(10, 20, 30, 40));
        assertEquals(40, settings.contentWidth());
        assertEquals(160, settings.contentHeight());
    }

    @Test
    void rejectsInvalidSemanticParagraphDimensions() {
        assertThrows(IllegalArgumentException.class, () -> ParagraphFormat.of(0));
        assertThrows(IllegalArgumentException.class, () -> ParagraphFormat.of(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> new ParagraphFormat(10, 15, -1, 0, ParagraphAlignment.LEFT, 0, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ParagraphFormat(10, 15, 0, 0, ParagraphAlignment.LEFT, -1, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ParagraphFormat(10, 15, 0, 0, ParagraphAlignment.LEFT, 0, Double.NaN, false));
    }
}
