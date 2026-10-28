// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentNavigation;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises actual pagination/anchor resolution instead of only comparing declaration accessors. */
class PortableNavigationTest {
    private static FontGroup fonts;
    @BeforeAll static void fonts() throws Exception {
        try (var input = PortableNavigationTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }
    private static SectionedDocument source() {
        return new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PageSettings.A4,
                List.of(new Heading("h", "ORIGINAL", 1), new PageBreak("break"), new Heading("h2", "SECOND", 2)), 7)),
                DocumentNavigation.builder().headings(true).contents(DocumentNavigation.Contents.of("CONTENTS", 10)).build());
    }
    @Test void engineAndDirectRendererResolveEditedTitlesAndFinalPhysicalPagesAutomatically() throws Exception {
        var source = DocumentEditor.edit(source()).replaceText("h", "ORIGINAL", "EDITED").execute().document();
        var engine = DocumentEngine.builder(fonts).build().layout(source);
        var direct = new ComponentDocumentRenderer().layout(source, List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), Navigation.EMPTY, fonts, Map.of(), List.of());
        for (var layout : List.of(engine, direct)) {
            assertEquals(1, layout.bodyPageOffset()); assertEquals(3, layout.display().pages().size());
            var extras = layout.display().extras();
            assertEquals(List.of("EDITED", "SECOND"), extras.bookmarks().stream().map(b -> b.title()).toList());
            assertEquals(List.of(1, 2), extras.bookmarks().stream().map(b -> b.pageIndex()).toList());
            assertEquals(List.of(1, 2), extras.links().stream().map(l -> l.targetPageIndex()).toList());
        }
    }
    @Test void explicitRuntimeNavigationConflictsRatherThanOverwritingPortableDeclaration() {
        var source = source(); var runtime = Navigation.fromHeadings(source);
        assertThrows(IllegalStateException.class, () -> DocumentEngine.builder(fonts).navigation(runtime).build().layout(source));
        assertThrows(IllegalStateException.class, () -> new ComponentDocumentRenderer().layout(source,
                List.of(SectionTemplate.uniform(PageTemplate.EMPTY)), runtime, fonts, Map.of(), List.of()));
        assertEquals(0, DocumentEngine.builder(fonts).build().layout(source.withNavigation(DocumentNavigation.NONE)).bodyPageOffset());
    }
    @Test void manualTargetAndExplicitColorIntentResolveWithoutHeadingInference() {
        var source = source().withNavigation(DocumentNavigation.builder().bookmark("Custom", "h2", 1)
                .contents(new DocumentNavigation.Contents(false, "", DocumentNavigation.TextStyle.of(12), Map.of(), Map.of(DocumentNavigation.Part.ENTRY, 0))).build());
        var resolved = Navigation.fromDocument(source);
        assertTrue(resolved.contents().appearance().isExplicit(ContentsStyle.Property.ENTRY_COLOR));
        var layout = DocumentEngine.builder(fonts).build().layout(source);
        assertEquals(0, layout.bodyPageOffset()); assertEquals("Custom", layout.display().extras().bookmarks().getFirst().title());
        assertEquals(1, layout.display().extras().bookmarks().getFirst().pageIndex());
    }
}
