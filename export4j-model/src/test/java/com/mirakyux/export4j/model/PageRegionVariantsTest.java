// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import com.mirakyux.export4j.model.edit.DocumentEditor;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageRegionVariantsTest {
    private static PageRegion band(String id, String text) {
        return PageRegion.builder().height(20).paragraph(new Paragraph(id, text, ParagraphFormat.of(10))).build();
    }

    @Test void sharedSourcesAreBoundAndEditedOnceWithStableVariantLocations() throws Exception {
        var shared = band("shared", "SHARED-${name}");
        var first = band("first", "FIRST-${name}");
        var regions = PageRegions.builder().header(shared).header(PageRegions.Variant.EVEN, shared)
                .header(PageRegions.Variant.FIRST, first).build();
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(),
                DocumentTemplate.Limits.DEFAULT, regions);
        var source = template.renderWithPageRegions(Map.of("name", "BOUND"));
        assertSame(source.pageRegions().header(), source.pageRegions().variants().get(PageRegions.Variant.EVEN).header());
        var found = DocumentEditor.edit(source).find("BOUND");
        assertEquals(2, found.matches().size());
        assertEquals(List.of("HEADER", "HEADER_FIRST"), found.matches().stream().map(value -> value.location().region()).toList());
        var edited = DocumentEditor.edit(source).replaceText("BOUND", "EDITED").prepare().execute();
        assertEquals(2, edited.changes().size());
        assertEquals("FIRST-EDITED", edited.document().pageRegions().variants().get(PageRegions.Variant.FIRST).header().paragraphs().getFirst().text());
        assertEquals("FIRST-BOUND", source.pageRegions().variants().get(PageRegions.Variant.FIRST).header().paragraphs().getFirst().text());
    }

    @Test void snapshotsDistinguishAnAbsentSelectorFromAnExplicitBlankPairAndRejectDuplicateIds() {
        var map = new EnumMap<PageRegions.Variant, PageRegions.Bands>(PageRegions.Variant.class);
        map.put(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY);
        var blank = new PageRegions(PageRegion.EMPTY, PageRegion.EMPTY, map);
        map.clear();
        assertNotEquals(PageRegions.EMPTY, blank);
        assertEquals(blank, blank.toBuilder().build());
        assertEquals(PageRegions.EMPTY, blank.toBuilder().inherit(PageRegions.Variant.FIRST).build());
        assertThrows(UnsupportedOperationException.class, () -> blank.variants().clear());
        var duplicate = PageRegions.builder().header(band("same", "A")).header(PageRegions.Variant.FIRST, band("same", "B")).build();
        assertThrows(IllegalArgumentException.class, () -> new PageDocument(Document.builder().build(), duplicate));
    }

    @Test void unselectedVariantContentStillUsesBindingValidationAndSharedBudgets() {
        var regions = PageRegions.builder().header(PageRegions.Variant.EVEN, band("even", "${missing}")).build();
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(), DocumentTemplate.Limits.DEFAULT, regions);
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE,
                assertThrows(DocumentTemplate.BindingException.class, () -> template.renderWithPageRegions(Map.of())).code());
    }
}
