// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignerColumnsTest {
    @Test void fullWidthInsertionSplitsColumnsWithoutMovingNodesOutOfDynamicScopes() throws Exception {
        String source = "<document version='1'><columns id='body' count='3' gap='12' balanced='true' weights='1 2 1'>"
                + "<!--prefix--><p id='first'>First ${name}</p><each items='items' as='item'><p id='item'>${item}</p></each>"
                + "<p id='last'>Last</p><!--tail--></columns></document>";
        String table = "<table id='wide'><columns><column weight='1'/></columns><row><cell>Wide table</cell></row></table>";
        String changed = DesignerXml.insertFullWidth(source, "/0/0", table, true);
        var document = DesignerXml.parse(changed); var blocks = DesignerXml.children(document.getDocumentElement());
        assertEquals(List.of("columns", "table", "columns"), blocks.stream().map(org.w3c.dom.Element::getTagName).toList());
        assertEquals("body", blocks.getFirst().getAttribute("id"));
        assertFalse(blocks.getLast().getAttribute("id").isBlank()); assertFalse(blocks.getLast().getAttribute("id").equals("body"));
        for (String name : List.of("count", "gap", "balanced", "weights")) { assertEquals(blocks.getFirst().getAttribute(name), blocks.getLast().getAttribute(name)); }
        assertEquals(List.of("first"), DesignerXml.children(blocks.getFirst()).stream().map(node -> node.getAttribute("id")).toList());
        assertEquals(List.of("each", "p"), DesignerXml.children(blocks.getLast()).stream().map(org.w3c.dom.Element::getTagName).toList());
        assertTrue(changed.contains("${name}")); assertTrue(changed.contains("${item}"));
        assertTrue(changed.contains("<!--prefix-->")); assertTrue(changed.contains("<!--tail-->"));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.insertFullWidth(source, "/0/1/0", table, true));
        String before = DesignerXml.insertFullWidth(source, "/0/0", table, false);
        var beforeBlocks = DesignerXml.children(DesignerXml.parse(before).getDocumentElement());
        assertEquals(List.of("table", "columns"), beforeBlocks.stream().map(org.w3c.dom.Element::getTagName).toList());
        assertEquals("body", beforeBlocks.getLast().getAttribute("id")); assertTrue(before.contains("<!--prefix-->"));
        String after = DesignerXml.insertFullWidth(source, "/0/2", table, true);
        assertEquals(List.of("columns", "table"), DesignerXml.children(DesignerXml.parse(after).getDocumentElement()).stream().map(org.w3c.dom.Element::getTagName).toList());
        assertTrue(after.contains("<!--tail-->"));
    }

    @Test void sourceEditsAndBundleReopenKeepColumnSettingsAndDynamicChildren() throws Exception {
        String source = "<document version='1'><columns id='body' count='2' gap='18' balanced='true' weights='1 2'>"
                + "<p id='first'>First ${name}</p><each items='items' as='item'><p id='item'>${item}</p></each>"
                + "<p id='last'>Last</p></columns></document>";
        var base = DesignerSamples.blank().source(source, "{\"name\":\"Ada\",\"items\":[\"One\",\"Two\"]}");
        String inserted = DesignerXml.insert(source, "/0", "<p id='new'>New paragraph</p>");
        String reordered = DesignerXml.relocate(inserted, "/0/3", "/0/0", false);
        String configured = DesignerXml.properties(reordered, "/0", Map.of("id", "body", "count", "3", "gap", "12",
                "balanced", "false", "weights", "1 2 1"), null);
        var changed = base.source(configured, base.data());
        var columns = DesignerXml.at(DesignerXml.parse(changed.bundle().source()), "/0");
        assertEquals(List.of("new", "first", "", "last"), DesignerXml.children(columns).stream().map(node -> node.getAttribute("id")).toList());
        assertEquals("3", columns.getAttribute("count")); assertEquals("1 2 1", columns.getAttribute("weights"));
        assertTrue(configured.contains("${name}")); assertTrue(configured.contains("${item}")); assertTrue(configured.contains("<each"));
        var reopened = XmlTemplateBundle.read(changed.bundle().encode());
        assertEquals(configured, reopened.source());
        assertEquals(source, base.bundle().source());
        assertFalse(reopened.source().contains("e4j-preview-"));
        DesignerService.preview(new DesignerProject(reopened, base.data()), DesignerService.execution(() -> false));
    }

    @Test void columnPreviewUsesFullComponentGeometryAndLeavesFollowingContentInNormalFlow() throws Exception {
        String source = "<document version='1'><page width='420' height='500' margin='30'/>"
                + "<p id='before' line-height='18'>Before</p><columns id='body' count='3' gap='12' balanced='true'>"
                + "<p id='inside' size='10' line-height='15'>" + "Continuous reading order across columns. ".repeat(35)
                + "</p></columns><p id='after'>After</p></document>";
        var preview = DesignerService.preview(DesignerSamples.blank().source(source, "{}"), DesignerService.execution(() -> false));
        var column = preview.hits().stream().filter(hit -> hit.id().equals("body")).findFirst().orElseThrow();
        var fragment = preview.layout().body().pages().stream().flatMap(page -> page.elements().stream())
                .filter(element -> element instanceof ComponentFragment && element.blockId().equals("body"))
                .map(ComponentFragment.class::cast).findFirst().orElseThrow();
        assertEquals(fragment.layout().height(), column.height());
        assertEquals(fragment.layout().width(), column.width());
        assertTrue(column.height() > 30, "a multi-column region must not use the old 12 pt fallback hit box");
        assertEquals(10, Double.parseDouble(preview.formats().get("/2/0").get("size")));
        var after = preview.hits().stream().filter(hit -> hit.id().equals("after")).findFirst().orElseThrow();
        assertEquals(column.page(), after.page());
        assertTrue(after.y() >= column.y() + column.height());
        assertEquals(30, after.x());
    }

    @Test void bodyColumnPreviewIdsNeverChangeTableColumnDefinitions() throws Exception {
        String source = "<document version='1'><columns count='2'><p>Body</p></columns>"
                + "<table id='table'><columns><column weight='1'/></columns><row><cell>Cell</cell></row></table></document>";
        var mapped = DesignerXml.previewSource(source);
        assertTrue(mapped.paths().containsValue("/0"));
        assertFalse(mapped.paths().containsValue("/1/0"));
        var tree = DesignerXml.parse(mapped.source());
        assertTrue(DesignerXml.at(tree, "/0").hasAttribute("id"));
        assertFalse(DesignerXml.at(tree, "/1/0").hasAttribute("id"));
    }

    @Test void twoAndThreeColumnSamplesAreRealEditableTemplatesAndExportPdf() throws Exception {
        for (int index : List.of(11, 12)) {
            var sample = DesignerSamples.create(index);
            var source = sample.bundle().source();
            var root = DesignerXml.parse(source);
            var columns = (org.w3c.dom.Element) root.getElementsByTagName("columns").item(0);
            assertEquals(Integer.toString(index - 9), columns.getAttribute("count"));
            assertTrue(source.contains("<each")); assertTrue(source.contains("${title}"));
            var preview = DesignerService.preview(sample, DesignerService.execution(() -> false));
            assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals(columns.getAttribute("id")) && hit.height() > 100));
            String prefix = index == 11 ? "article" : "briefing";
            var wide = preview.hits().stream().filter(hit -> hit.id().equals(prefix + "-wide-table")).findFirst().orElseThrow();
            var picture = preview.hits().stream().filter(hit -> hit.id().equals(prefix + "-wide-figure")).findFirst().orElseThrow();
            var continued = preview.hits().stream().filter(hit -> hit.id().equals(prefix + "-body-after")).findFirst().orElseThrow();
            assertTrue(wide.width() > 500, "the interruption table must use the full body width");
            assertEquals(1, picture.images().size()); assertEquals(400, picture.images().getFirst().width());
            assertTrue(continued.page() == picture.page() && continued.y() >= picture.y() + picture.height(),
                    "columns must resume below the picture on that same page");
            var reopened = XmlTemplateBundle.read(sample.bundle().encode());
            assertEquals(source, reopened.source());
            var pdf = DesignerService.prepare(new DesignerProject(reopened, sample.data()), DesignerService.Format.PDF,
                    ProcessingPolicy.STRICT, DesignerService.execution(() -> false));
            var output = new ByteArrayOutputStream(); pdf.to(output);
            assertTrue(output.size() > 1000);
            assertEquals(source, sample.bundle().source());
        }
    }

    @Test void invalidColumnDraftAndPropertiesProfileCannotReplaceSavedHistory() throws Exception {
        var original = DesignerSamples.create(11); var history = new DesignerProject.History(original);
        assertThrows(Exception.class, () -> history.change(original.source(original.bundle().source().replace("count=\"2\"", "count=\"4\""), original.data())));
        assertThrows(Exception.class, () -> original.source("<properties><entry key='page.columns'>2</entry></properties>", "{}"));
        assertEquals(original, history.current()); assertFalse(history.canUndo());
    }
}
