// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PhysicalPlacementScopeTest {
    private static final PageSettings PAGE = PageSettings.builder().size(300, 240).margins(20).build();
    private static final Component CONTENT = context -> new ComponentLayout(40, 20, 0, List.of(), List.of());
    private static FontGroup fonts;

    @BeforeAll static void fonts() throws IOException {
        try (var input = PhysicalPlacementScopeTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static PageTemplate template(int page) {
        return PageTemplate.builder().foreground(new Placement(CONTENT,
                new Position.Absolute(new Rect(20, 80, 40, 20)), page)).build();
    }

    private static Section section(String id) {
        return new Section(id, PAGE, List.of(new Paragraph(id + "-body", "Body", ParagraphFormat.of(10))), 1);
    }

    @Test void equalPlacementsInAnotherSectionCannotSatisfyAnOutOfSectionTarget() {
        var first = template(1);
        var second = template(1);
        assertEquals(first, second);
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("first"), section("second")));
        var failure = assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer().layout(document,
                List.of(SectionTemplate.uniform(first), SectionTemplate.uniform(second)), Navigation.EMPTY, fonts, Map.of(), List.of()));
        assertEquals("positioned page is outside its declaring section or selected page variant", failure.getMessage());
    }

    @Test void sharedTemplateStillRejectsATargetOutsideTheSecondSection() {
        var shared = SectionTemplate.uniform(template(1));
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("first"), section("second")));
        assertThrows(IllegalArgumentException.class, () -> new ComponentDocumentRenderer().layout(document,
                List.of(shared, shared), Navigation.EMPTY, fonts, Map.of(), List.of()));
    }

    @Test void equalPlacementsAcrossVariantsWithinOneSectionRemainAValidDeclaration() {
        var document = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("single")));
        var variants = new SectionTemplate(template(2), template(2), template(2));
        var result = new ComponentDocumentRenderer().layout(document, List.of(variants), Navigation.EMPTY, fonts, Map.of(), List.of());
        assertEquals(2, result.body().pages().size());
        assertEquals(1, result.decorations().size());
        assertEquals(1, result.decorations().getFirst().pageIndex());
    }
}
