// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignerTableTest {
    private static final String GRID = "<document version='1'><table id='grid'><columns><column weight='1'/><column weight='1'/><column weight='1'/></columns>"
            + "<header><cell>A</cell><cell>B</cell><cell>C</cell></header>"
            + "<row><cell color='#224488'>ONE <span role='bold'>RICH</span></cell><cell>TWO</cell><cell>THREE</cell></row>"
            + "<row><cell>FOUR</cell><cell>FIVE</cell><cell>SIX</cell></row></table></document>";

    @Test void rectangularMergeKeepsContentAndSplitRestoresThePhysicalGrid() throws Exception {
        String merged = DesignerTableEdits.merge(GRID, "/0", List.of("/0/2/0", "/0/2/1", "/0/3/0", "/0/3/1"));
        var project = DesignerSamples.create(0).source(merged, "{}");
        var table = (Table) project.bundle().compile().render(Map.of()).blocks().getFirst();
        var owner = table.rows().get(1).cells().getFirst();
        assertEquals(2, owner.columnSpan()); assertEquals(2, owner.rowSpan());
        assertEquals("ONE RICH\nTWO\nFOUR\nFIVE", owner.text());
        assertEquals(0x224488, owner.inlines().getFirst().style().rgb());
        assertTrue(owner.inlines().stream().anyMatch(run -> run.text().equals("RICH") && run.style().fontRole().equals("bold")));
        assertEquals("SIX", table.rows().get(2).cells().getFirst().text());
        String split = DesignerTableEdits.split(merged, "/0", "/0/2/0");
        var restored = (Table) DesignerSamples.create(0).source(split, "{}").bundle().compile().render(Map.of()).blocks().getFirst();
        assertEquals(3, restored.rows().get(1).cells().size()); assertEquals(3, restored.rows().get(2).cells().size());
        assertEquals(owner.text(), restored.rows().get(1).cells().getFirst().text());
        assertEquals("", restored.rows().get(2).cells().getFirst().text());
        assertEquals("SIX", restored.rows().get(2).cells().get(2).text());
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertEquals(1, preview.layout().display().pages().size());
    }
    @Test void invalidSelectionsRejectWithoutDroppingCellsOrRules() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DesignerTableEdits.merge(GRID, "/0", List.of("/0/2/0", "/0/3/1")));
        assertThrows(IllegalArgumentException.class, () -> DesignerTableEdits.merge(GRID, "/0", List.of("/0/1/0", "/0/2/0")));
        assertThrows(IllegalArgumentException.class, () -> DesignerTableEdits.split(GRID, "/0", "/0/2/0"));
        String conditional = GRID.replace("TWO</cell>", "TWO<style-if test='flag' color='#FF0000'/></cell>");
        assertThrows(IllegalArgumentException.class, () -> DesignerTableEdits.merge(conditional, "/0", List.of("/0/2/0", "/0/2/1")));
        assertTrue(GRID.contains("FIVE"));
    }
    @Test void dynamicCellRulesCompareExactNumbersAndResolveOrderedPartialStyles() throws Exception {
        String source = "<document version='1'><rows id='scores' items='records' as='record'><columns><column weight='1'/></columns>"
                + "<row><cell line-height='24'><link href='https://example.com'><span underline='true'>${record.name}</span></link>"
                + "<style-if test='record.score' operator='GE' value='9007199254740993' color='#C0392B' role='bold'/>"
                + "<style-if test='record.name' operator='CONTAINS' value='HIGH' size='14'/>"
                + "<style-if test='record.flag' operator='FALSE' color='#224488'/>"
                + "</cell></row></rows></document>";
        var template = DesignerSamples.create(0).source(source, "{}").bundle().compile().toBuilder().build();
        var table = (Table) template.render(Map.of("records", List.of(
                Map.of("name", "HIGH", "score", new BigDecimal("9007199254740993"), "flag", true),
                Map.of("name", "LOW", "score", new BigDecimal("9007199254740992"), "flag", false)))).blocks().getFirst();
        var high = table.rows().getFirst().cells().getFirst(); var low = table.rows().get(1).cells().getFirst();
        assertEquals(14, high.format().fontSize()); assertEquals(21, high.format().lineHeight());
        assertEquals(0xC0392B, high.inlines().getFirst().style().rgb());
        assertEquals("bold", high.inlines().getFirst().style().fontRole());
        assertTrue(high.inlines().getFirst().style().underline());
        assertEquals("https://example.com", high.inlines().getFirst().hyperlink());
        assertEquals(0x224488, low.inlines().getFirst().style().rgb());
        assertEquals("", low.inlines().getFirst().style().fontRole());
        var missing = assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of("records", List.of(Map.of("name", "HIGH", "flag", true)))));
        assertEquals(DocumentTemplate.BindingException.Code.MISSING_VALUE, missing.code());
        var project = DesignerSamples.create(0).source(source, "{\"records\":[{\"name\":\"HIGH\",\"score\":9007199254740993,\"flag\":true},{\"name\":\"LOW\",\"score\":1,\"flag\":false}]}");
        assertEquals(1, DesignerService.preview(project, DesignerService.execution(() -> false)).layout().display().pages().size());
        assertTrue(DesignerService.prepare(project, DesignerService.Format.DOCX, ProcessingPolicy.STRICT, DesignerService.execution(() -> false)).preview().losses().isEmpty());
    }
    @Test void rulesAreValidatedEvenInUnusedBranchesAndNeverFlattenBlockCells() throws Exception {
        String prefix = "<document version='1'><if test='show'><then><table><columns><column weight='1'/></columns><row><cell>";
        String suffix = "</cell></row></table></then></if></document>";
        for (String invalid : List.of("<style-if test='flag' color='red'/>", "<style-if test='flag' operator='GT' value='NaN' color='#FF0000'/>",
                "<style-if test='flag' operator='EXECUTE' color='#FF0000'/>", "<style-if test='flag'/>", "<p>BLOCK</p><style-if test='flag' color='#FF0000'/>")) {
            assertThrows(RuntimeException.class, () -> DesignerSamples.create(0).source(prefix + invalid + suffix, "{\"show\":false}"));
        }
    }
    @Test void rulesKeepLexicalScopesFieldsImagesAndIndependentEqualCells() throws Exception {
        String table = "<table id='scope'><columns><column weight='1'/><column weight='1'/></columns><row>"
                + "<cell>SAME<style-if test='record.flag' color='#C0392B'/></cell><cell>SAME</cell></row></table>";
        String source = "<document version='1'><section id='body'><each items='groups' as='group'>"
                + "<each items='group.records' as='record'>" + table + "</each></each></section></document>";
        var template = DesignerSamples.create(0).source(source, "{}").bundle().compile();
        var rendered = template.renderSections(Map.of("groups", List.of(Map.of("records", List.of(Map.of("flag", true), Map.of("flag", false))))));
        var first = (Table) rendered.sections().getFirst().blocks().getFirst();
        var second = (Table) rendered.sections().getFirst().blocks().get(1);
        assertEquals(0xC0392B, first.rows().getFirst().cells().getFirst().inlines().getFirst().style().rgb());
        assertEquals(0, first.rows().getFirst().cells().get(1).inlines().getFirst().style().rgb());
        assertEquals(0, second.rows().getFirst().cells().getFirst().inlines().getFirst().style().rgb());
        var mismatch = assertThrows(DocumentTemplate.BindingException.class, () -> template.renderSections(
                Map.of("groups", List.of(Map.of("records", List.of(Map.of("flag", "true")))))));
        assertEquals(DocumentTemplate.BindingException.Code.TYPE_MISMATCH, mismatch.code());

        String objects = "<document version='1'><header height='50'><table id='band'><columns><column weight='1'/></columns><row>"
                + "<cell><image ref='mark' width='10' height='10' alt='MARK'/>"
                + "<page-number width='24'/><style-if test='flag' color='#224488' underline='true'/></cell>"
                + "</row></table></header><p>BODY</p></document>";
        var project = DesignerSamples.create(2).source(objects, "{\"flag\":true}");
        var bound = project.bundle().compile().renderWithPageRegions(Map.of("flag", true));
        var cell = ((Table) bound.pageRegions().header().blocks().getFirst()).rows().getFirst().cells().getFirst();
        assertEquals(1, cell.images().size()); assertEquals("MARK", cell.images().getFirst().image().altText());
        assertEquals(1, cell.fields().size()); assertEquals(0x224488, cell.fields().getFirst().field().style().rgb());
        assertTrue(cell.fields().getFirst().field().style().underline());
        assertEquals(1, DesignerService.preview(project, DesignerService.execution(() -> false)).layout().display().pages().size());
    }
}
