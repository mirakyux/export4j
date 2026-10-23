// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model;

import com.mirakyux.export4j.model.edit.DocumentEditor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PageOverlayTest {
    @Test void overlayThemeKeepsPropertyIntentAndFullSnapshotsIndependentOfOtherDomains() {
        var initial = DocumentTheme.builder().fontRole("global").rgb(0x112233)
                .bodyFontRole("body").headerRgb(0x445566)
                .backgroundFontRole("").foregroundRgb(0).build();
        assertEquals("", initial.backgroundStyle().fontRole()); assertEquals(0x112233, initial.backgroundStyle().rgb());
        assertEquals("global", initial.foregroundStyle().fontRole()); assertEquals(0, initial.foregroundStyle().rgb());
        var changed = initial.toBuilder().fontRole("next").rgb(0x778899).inheritBackgroundFontRole().build();
        assertEquals("next", changed.backgroundStyle().fontRole()); assertEquals(0x778899, changed.backgroundStyle().rgb());
        assertEquals("next", changed.foregroundStyle().fontRole()); assertEquals(0, changed.foregroundStyle().rgb());
        var fixed = changed.toBuilder().backgroundStyle(style -> style.format(format -> format.fontSize(9).lineHeight(14)))
                .fontRole("later").rgb(0xabcdef).build();
        assertEquals("next", fixed.backgroundStyle().fontRole()); assertEquals(0x778899, fixed.backgroundStyle().rgb());
        assertEquals(9, fixed.backgroundStyle().format().fontSize());
        var restored = fixed.toBuilder().inheritBackgroundRgb().inheritForegroundRgb().build();
        assertEquals(0xabcdef, restored.backgroundStyle().rgb()); assertEquals(0xabcdef, restored.foregroundStyle().rgb());
        assertEquals(restored, restored.toBuilder().build()); assertEquals(restored.hashCode(), restored.toBuilder().build().hashCode());
    }

    private static PageRegion content(String id, String text) {
        return new PageRegion(20, 0, List.of(new Paragraph(id, text, ParagraphFormat.of(10))));
    }

    @Test void repeatedLayerSourcesBindAndEditOnceWithoutChangingGeometryOrSource() throws Exception {
        var band = content("bg", "${value}");
        var regions = PageRegions.builder().background(new PageOverlay(0, 0, 80, band))
                .background(new PageOverlay(100, 0, 80, band)).foreground(new PageOverlay(0, 30, 80, content("fg", "${value}"))).build();
        var source = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(), DocumentTemplate.Limits.DEFAULT, regions)
                .renderWithPageRegions(Map.of("value", "ORIGINAL"));
        assertSame(source.pageRegions().background().getFirst().content(), source.pageRegions().background().getLast().content());
        var matches = DocumentEditor.edit(source).find("ORIGINAL").matches();
        assertEquals(List.of("BACKGROUND:0", "FOREGROUND:0"), matches.stream().map(match -> match.location().region()).toList());
        var edited = DocumentEditor.edit(source).replaceText("ORIGINAL", "EDITED").execute();
        assertEquals(2, edited.changes().size());
        assertEquals("EDITED", edited.document().pageRegions().background().getLast().content().paragraphs().getFirst().text());
        assertEquals(100, edited.document().pageRegions().background().getLast().x());
        assertEquals("ORIGINAL", source.pageRegions().background().getFirst().content().paragraphs().getFirst().text());
    }

    @Test void placementListsAreSnapshotsAndAllKindsShareTheGlobalIdNamespace() {
        var list = new ArrayList<>(List.of(new PageOverlay(0, 0, 80, content("source", "A"))));
        var builder = PageRegions.builder().background(list); list.clear();
        var regions = builder.build(); builder.background(List.of());
        assertEquals(1, regions.background().size());
        assertEquals(regions, regions.toBuilder().build());
        assertThrows(UnsupportedOperationException.class, () -> regions.background().clear());
        var duplicate = regions.toBuilder().foreground(new PageOverlay(0, 30, 80, content("source", "B"))).build();
        assertThrows(IllegalArgumentException.class, () -> new PageDocument(Document.builder().build(), duplicate));
        assertThrows(IllegalArgumentException.class, () -> new PageDocument(Document.builder().paragraph("source", "BODY").build(), regions));
    }

    @Test void invalidGeometryAndInheritedOutOfPageContentAlwaysFail() {
        var band = content("p", "A");
        assertThrows(IllegalArgumentException.class, () -> new PageOverlay(-1, 0, 20, band));
        assertThrows(IllegalArgumentException.class, () -> new PageOverlay(0, Double.NaN, 20, band));
        assertThrows(IllegalArgumentException.class, () -> new PageOverlay(0, 0, 0, band));
        assertThrows(IllegalArgumentException.class, () -> new PageOverlay(0, 0, 20, PageRegion.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> new PageOverlay(0, 0, 20, new PageRegion(20, 1, List.of())));
        var regions = PageRegions.builder().background(new PageOverlay(500, 0, 80, band)).build();
        assertThrows(IllegalArgumentException.class, () -> Section.builder("small").pageSettings(PageSettings.builder().size(200, 200).margins(0).build()).pageRegions(regions).build());
        assertThrows(IllegalArgumentException.class, () -> PageRegions.builder().background(java.util.Collections.nCopies(65, new PageOverlay(0, 0, 20, band))).build());
    }

    @Test void overlaysUseTheSameMissingDataChecksAsBodyAndBands() {
        var regions = PageRegions.builder().background(new PageOverlay(0, 0, 80, content("bg", "${missing}"))).build();
        var template = new DocumentTemplate(DocumentMetadata.EMPTY, PageSettings.A4, List.of(), DocumentTemplate.Limits.DEFAULT, regions);
        assertThrows(DocumentTemplate.BindingException.class, () -> template.renderWithPageRegions(Map.of()));
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("missing", "A")));
    }
}
