// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManuWeaveReferencesTest {
    @Test void authorYearSourceSelectsUnnumberedAlphabeticalEntriesAndReportsItsOrigin() throws Exception {
        var result = ManuWeaveReferences.references(ManuWeaveProfile.load("springer-llncs-author-year"));
        assertEquals("author-year", result.mode());
        assertTrue(result.entries().getFirst().startsWith("Demo, R. (2026)."));
        assertTrue(result.entries().getLast().startsWith("Sample, M. (2026)."));
        assertFalse(result.entries().getFirst().contains("[1]"));
        assertTrue(result.source().startsWith("extracted_format.references.citation_mode.value="));
        assertTrue(result.limitations().stream().anyMatch(value -> value.contains("not full CSL")));
    }

    @Test void ieeeSourceUsesQuotedTitleInitialsAndCitationOrderNumericLabels() throws Exception {
        var result = ManuWeaveReferences.references(ManuWeaveProfile.load("ieee-journal"));
        assertEquals("numeric", result.mode());
        assertTrue(result.entries().getFirst().startsWith("[1] A. Example."));
        assertTrue(result.entries().getFirst().contains("\"Reproducible document layout\""));
        assertTrue(result.entries().getLast().startsWith("[3] R. Demo."));
    }

    @Test void unspecifiedModesAndTextualFootnotesNeverPretendToImplementAnExternalStyle() throws Exception {
        var unspecified = ManuWeaveReferences.references(new Properties());
        assertEquals("numeric", unspecified.mode());
        assertTrue(unspecified.source().contains("example-default"));
        var footnotes = ManuWeaveReferences.references(ManuWeaveProfile.load("comp-jasa-word"));
        assertEquals("textual-footnote", footnotes.mode());
        assertTrue(footnotes.limitations().stream().anyMatch(value -> value.contains("does not place them as page footnotes")));
        assertEquals(3, footnotes.entries().size());
        assertThrows(UnsupportedOperationException.class, () -> footnotes.entries().clear());
    }

    @Test void explicitTitleExclusionAndYearPositionAreObservedRatherThanInferredFromAnId() {
        var values = new Properties();
        values.setProperty("extracted_format.references.scheme.value", "numeric");
        values.setProperty("extracted_format.references.article_title_included.value", "false");
        values.setProperty("extracted_format.references.year_position.value", "after_pages");
        String first = ManuWeaveReferences.references(values).entries().getFirst();
        assertFalse(first.contains("Reproducible document layout"));
        assertTrue(first.endsWith("1-8 (2026)."));
    }
}
