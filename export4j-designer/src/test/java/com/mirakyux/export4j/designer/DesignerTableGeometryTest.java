// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

class DesignerTableGeometryTest {
    @Test void paddedInlineAndBlockCellsExposeTheirPhysicalBorders() throws Exception {
        String source = """
                <document version="1"><page width="420" height="600" margin="30"/>
                <table id="t" padding-left="9" padding-right="11"><columns><column width="120"/><column weight="1"/></columns>
                <row><cell>PADDED</cell><cell><p id="inside">BLOCK</p></cell></row>
                <row><cell column-span="2">MERGED</cell></row></table></document>
                """;
        var preview = DesignerService.preview(DesignerSamples.blank().source(source, "{}"), DesignerService.execution(() -> false));
        var first = preview.hits().stream().filter(hit -> hit.path().equals("/1/1/0")).findFirst().orElseThrow();
        assertNotNull(first.tableCell());
        assertEquals(30, first.tableCell().x(), .001);
        assertEquals(120, first.tableCell().width(), .001);
        assertEquals(first.tableCell().x() + 9, first.x(), .001);
        assertTrue(first.width() < first.tableCell().width());
        var block = preview.hits().stream().filter(hit -> hit.path().equals("/1/1/1")).findFirst().orElseThrow();
        assertEquals(150, block.tableCell().x(), .001);
        assertEquals(240, block.tableCell().width(), .001);
        assertEquals(1, block.tableCell().column());
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.path().equals("/1/1/1/0") && hit.id().equals("inside")));
        var merged = preview.hits().stream().filter(hit -> hit.path().equals("/1/2/0")).findFirst().orElseThrow();
        assertEquals(2, merged.tableCell().columnSpan());
        assertEquals(360, merged.tableCell().width(), .001);
    }
}
