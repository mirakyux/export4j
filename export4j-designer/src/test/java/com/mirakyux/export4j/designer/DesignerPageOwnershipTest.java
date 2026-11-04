// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.components.ComponentDocumentLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

class DesignerPageOwnershipTest {
    @Test void emptyBodyHasMeasuredSpaceWithAndWithoutPageRegions() throws Exception {
        for (String region : List.of("", "<header height='60'><p>HEADER</p></header>")) {
            var preview = preview("<document version='1'><page width='420' height='600' margin='30'/>" + region + "</document>");
            var body = preview.imageSpaces().stream().filter(space -> space.path().isEmpty()).findFirst().orElseThrow();
            assertEquals(0, body.page());
            assertEquals(360, body.width());
            assertEquals(region.isEmpty() ? 540 : 480, body.height());
            assertEquals(List.of(""), preview.pageOwners());
            assertEquals(List.of(0), preview.layout().bodySectionIndices());
            assertTrue(preview.layout().body().pages().getFirst().elements().isEmpty());
        }
    }

    @Test void emptySectionsKeepTheirActualOwnersAndTrailingBreaksDoNotAddPages() throws Exception {
        var preview = preview("""
                <document version='1'><page width='420' height='600' margin='30'/>
                <section id='first'/><section id='middle'><p>BODY</p><page-break/></section>
                <section id='last'><page width='300' height='400' margin='20'/></section></document>
                """);
        assertEquals(List.of(0, 1, 2), preview.layout().bodySectionIndices());
        assertEquals(List.of("/1", "/2", "/3"), preview.pageOwners());
        assertEquals(List.of(360.0, 360.0, 260.0), preview.imageSpaces().stream().map(DesignerService.ImageSpace::width).toList());
        assertEquals(360, preview.imageSpaces().getLast().height());
        assertThrows(UnsupportedOperationException.class, () -> preview.layout().bodySectionIndices().set(0, 9));
        var layout = preview.layout();
        var legacy = new ComponentDocumentLayout(layout.body(), layout.bodyPageOffset(), layout.display(), layout.contentsPages(),
                layout.decorations(), layout.footnotes(), layout.resolvedReferences(), layout.referenceDiagnostics());
        assertEquals(List.of(0, 0, 0), legacy.bodySectionIndices());
    }

    @Test void generatedContentsAndPositionedBlankPagesRetainPageOwners() throws Exception {
        var contents = preview("""
                <document version='1'><page width='420' height='600' margin='30'/>
                <navigation headings='true'><contents title='Contents'/></navigation>
                <section id='body'><heading id='title' level='1'>TITLE</heading></section>
                <section id='empty'/></document>
                """);
        assertEquals(1, contents.layout().bodyPageOffset());
        assertEquals(List.of(0, 1), contents.layout().bodySectionIndices());
        assertEquals(List.of("", "/2", "/3"), contents.pageOwners());
        assertEquals(List.of(1, 2), contents.imageSpaces().stream().map(DesignerService.ImageSpace::page).toList());

        var positioned = preview("""
                <document version='1'><page width='420' height='600' margin='30'/>
                <section id='first'/><section id='last'><foreground>
                <box x='30' y='40' width='100' height='30' page='3'><p>BOX</p></box>
                </foreground></section></document>
                """);
        assertEquals(List.of(0, 1, 1), positioned.layout().bodySectionIndices());
        assertEquals(List.of("/1", "/2", "/2"), positioned.pageOwners());
        assertEquals(List.of(0, 1, 2), positioned.imageSpaces().stream().filter(space -> space.path().equals("/1") || space.path().equals("/2"))
                .map(DesignerService.ImageSpace::page).toList());
    }

    private static DesignerService.Preview preview(String source) throws Exception {
        return DesignerService.preview(DesignerSamples.blank().source(source, "{}"), DesignerService.execution(() -> false));
    }
}
