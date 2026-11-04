// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DesignerGridTest {
    private static final String GRID = "<document version='1'><page width='420' height='600' margin='30'/>"
            + "<grid id='grid' tracks='12' column-gap='12' row-gap='10'>"
            + "<item id='left' row='1' column-start='1' column-span='6'><p id='left-text' size='10' line-height='15'>Left ${name}</p></item>"
            + "<item id='right' row='1' column-start='7' column-span='6'><p id='right-text' size='10' line-height='15'>Right</p></item>"
            + "<item id='offset' row='3' column-start='3' column-span='8'><p id='offset-text' size='10' line-height='15'>Offset</p></item>"
            + "</grid><p id='after'>After grid</p></document>";

    @Test void realItemBoundsRetainSourcePathsAndEditableParagraphFormatting() throws Exception {
        var project = DesignerSamples.blank().source(GRID, "{\"name\":\"Ada\"}");
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        var left = preview.hits().stream().filter(hit -> hit.id().equals("left")).findFirst().orElseThrow();
        var right = preview.hits().stream().filter(hit -> hit.id().equals("right")).findFirst().orElseThrow();
        var offset = preview.hits().stream().filter(hit -> hit.id().equals("offset")).findFirst().orElseThrow();
        assertEquals("/1/0", left.path()); assertEquals(30, left.x(), .0001); assertEquals(174, left.width(), .0001);
        assertEquals(216, right.x(), .0001); assertEquals(174, right.width(), .0001);
        assertEquals(left.y(), right.y(), .0001); assertEquals(left.height(), right.height(), .0001);
        assertEquals(92, offset.x(), .0001); assertEquals(236, offset.width(), .0001);
        assertEquals(left.y() + left.height() + 10, offset.y(), .0001, "a skipped logical row must not add an empty physical row");
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("left-text") && hit.path().equals("/1/0/0")));
        assertEquals(10, Double.parseDouble(preview.formats().get("/1/0/0").get("size")));
        var after = preview.hits().stream().filter(hit -> hit.id().equals("after")).findFirst().orElseThrow();
        assertTrue(after.y() >= offset.y() + offset.height());
        assertEquals(GRID, project.bundle().source());
    }

    @Test void coordinateEditsSortStaticPeersButPreserveDynamicScopesAndOverlapErrors() throws Exception {
        String reordered = DesignerXml.patchProperties(GRID, List.of("/1/0"), Map.of("row", "4"));
        var grid = DesignerXml.at(DesignerXml.parse(reordered), "/1");
        assertEquals(List.of("right", "offset", "left"), DesignerXml.children(grid).stream().map(item -> item.getAttribute("id")).toList());
        assertTrue(reordered.contains("${name}"));
        String largeRow = DesignerXml.patchProperties(GRID, List.of("/1/0"), Map.of("row", Integer.toString(Integer.MAX_VALUE)));
        assertEquals("left", DesignerXml.children(DesignerXml.at(DesignerXml.parse(largeRow), "/1")).getLast().getAttribute("id"));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.move(GRID, "/1/0", 1));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.relocate(GRID, "/1/0", "/1/1", true));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.relocateMany(GRID, List.of("/1/0"), "/1/1", true));
        String scoped = "<document version='1'><grid id='g'><each items='items' as='item'>"
                + "<item id='a' row='1' column-start='7' column-span='6'><p>A ${item}</p></item>"
                + "<!--kept--><item id='b' row='2' column-start='1' column-span='6'><p>B</p></item>"
                + "</each><item id='tail' row='3' column-start='1' column-span='12'><p>Tail</p></item></grid></document>";
        String changed = DesignerXml.patchProperties(scoped, List.of("/0/0/1"), Map.of("row", "1"));
        var tree = DesignerXml.parse(changed); var each = DesignerXml.at(tree, "/0/0");
        assertEquals("each", each.getTagName()); assertEquals("items", each.getAttribute("items"));
        assertEquals(List.of("b", "a"), DesignerXml.children(each).stream().map(item -> item.getAttribute("id")).toList());
        assertEquals("tail", DesignerXml.at(tree, "/0/1").getAttribute("id")); assertTrue(changed.contains("<!--kept-->"));
        String overlapping = DesignerXml.patchProperties(GRID, List.of("/1/1"), Map.of("column-start", "5"));
        var original = DesignerSamples.blank().source(GRID, "{\"name\":\"Ada\"}");
        assertEquals("5", DesignerXml.at(DesignerXml.parse(overlapping), "/1/1").getAttribute("column-start"),
                "sorting must not silently repair the overlap");
        assertThrows(Exception.class, () -> DesignerService.preview(original.source(overlapping, original.data()), DesignerService.execution(() -> false)));
        assertEquals(GRID, original.bundle().source());
    }

    @Test void gridSourceRetainsEditableChildrenThroughBundleReopenAndPrivatePreviewIds() throws Exception {
        String inserted = DesignerXml.insert(GRID, "/1/0", "<p id='added' size='10' line-height='15'>Added ${name}</p>");
        var project = DesignerSamples.blank().source(inserted, "{\"name\":\"Ada\"}");
        var reopened = XmlTemplateBundle.read(project.bundle().encode());
        assertEquals(inserted, reopened.source()); assertTrue(reopened.source().contains("<item"));
        var preview = DesignerService.preview(new DesignerProject(reopened, project.data()), DesignerService.execution(() -> false));
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("added") && hit.path().equals("/1/0/1")));
        var mapped = DesignerXml.previewSource("<document version='1'><grid><item row='1' column-start='1' column-span='12'><p>Text</p></item></grid></document>");
        assertTrue(mapped.paths().containsValue("/0")); assertTrue(mapped.paths().containsValue("/0/0"));
        assertFalse(reopened.source().contains("e4j-preview-"));
    }

    @Test void gridSampleIncludesOffsetsFullWidthTablesPicturesAndSiblingColumnFlows() throws Exception {
        var sample = DesignerSamples.create(13); var tree = DesignerXml.parse(sample.bundle().source());
        assertEquals("十二轨栅格编辑", DesignerSamples.catalog().get(13).get("name"));
        assertEquals(1, tree.getElementsByTagName("grid").getLength());
        assertTrue(sample.bundle().source().contains("<each")); assertTrue(sample.bundle().source().contains("${title}"));
        var preview = DesignerService.preview(sample, DesignerService.execution(() -> false));
        var grid = preview.hits().stream().filter(hit -> hit.id().equals("editor-grid")).toList();
        assertFalse(grid.isEmpty());
        var offset = preview.hits().stream().filter(hit -> hit.id().equals("grid-offset")).findFirst().orElseThrow();
        assertTrue(offset.x() > 36); assertTrue(offset.width() < 500);
        var table = preview.hits().stream().filter(hit -> hit.id().equals("grid-evidence")).findFirst().orElseThrow();
        assertEquals(595.2755905511812 - 72, table.width(), .01);
        var figure = preview.hits().stream().filter(hit -> hit.id().equals("grid-figure")).findFirst().orElseThrow();
        assertEquals(1, figure.images().size()); assertEquals(320, figure.images().getFirst().width());
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("grid-before")));
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("grid-after")));
        var pdf = DesignerService.prepare(sample, DesignerService.Format.PDF, ProcessingPolicy.STRICT, DesignerService.execution(() -> false));
        var output = new ByteArrayOutputStream(); pdf.to(output); assertTrue(output.size() > 1000);
        assertEquals(sample.bundle().source(), XmlTemplateBundle.read(sample.bundle().encode()).source());
    }
}
