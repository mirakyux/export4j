// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesignerTest {
    @TempDir Path temporary;
    @Test void blankDocumentHasOneEmptyEditablePageAndNoSampleDataOrImages() throws Exception {
        var project = DesignerSamples.blank();
        assertEquals("{}", project.data());
        assertTrue(project.bundle().images().isEmpty());
        assertTrue(project.bundle().compile().render(Map.of()).blocks().isEmpty());
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertEquals(1, preview.layout().display().pages().size());
        assertTrue(preview.hits().isEmpty());
        var changed = project.source(project.bundle().source().replace("</document>", "<p id='first'>First content</p></document>"), "{}");
        assertTrue(DesignerService.preview(changed, DesignerService.execution(() -> false)).hits().stream().anyMatch(hit -> hit.id().equals("first")));
    }
    @Test void adverseReactionSampleUsesContentDrivenThreePageFormGeometry() throws Exception {
        var project = DesignerSamples.create(4);
        var source = project.bundle().source();
        assertTrue(source.contains("row-span=\"2\""));
        assertTrue(source.contains("column-span=\"3\""));
        assertTrue(source.contains("${drug.name}"));
        assertFalse(source.contains("<page-break"));
        assertFalse(source.contains("page-break-before"));
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertEquals(3, preview.layout().display().pages().size());
        var patient = preview.hits().stream().filter(hit -> hit.id().equals("adr-patient")).findFirst().orElseThrow();
        // Points, with 0.05pt for decimal column/line metrics in the dense form.
        assertEquals(183, patient.y(), 0.05);
        assertEquals(197.5, patient.height(), 0.05);
        var medication = (com.mirakyux.export4j.layout.TableFragment) preview.layout().body().pages().getFirst().elements()
                .stream().filter(fragment -> fragment.blockId().equals("adr-suspect-drugs")).findFirst().orElseThrow();
        assertEquals(480.2, medication.origin().y(), 0.05);
        assertEquals(282.61, medication.height(), 0.05);
        assertEquals(188, medication.rows().getLast().cells().getFirst().bounds().height(), 0.05);
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("adr-suspect-drugs") && hit.page() == 0));
        assertEquals(List.of(0, 1), preview.hits().stream().filter(hit -> hit.id().equals("adr-suspect-notes"))
                .map(DesignerService.Hit::page).toList());
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("adr-remarks") && hit.page() == 2 && hit.y() == 42.5));
        // Shorter real content removes the third page; pagination is not a template fixture.
        var shorter = project.source(source, project.data().replace("【示例疾病甲（DEMO01）】", "示例疾病"));
        assertEquals(2, DesignerService.preview(shorter, DesignerService.execution(() -> false)).layout().display().pages().size());
        var longer = project.source(source, project.data().replace("患者因示例疾病甲", "较长的虚构反应经过。".repeat(40) + "患者因示例疾病甲"));
        var expanded = DesignerService.preview(longer, DesignerService.execution(() -> false));
        assertTrue(expanded.hits().stream().filter(hit -> hit.id().equals("adr-reaction-description"))
                .allMatch(hit -> hit.height() > 78.5));
        assertTrue(expanded.hits().stream().filter(hit -> hit.id().equals("adr-remarks"))
                .allMatch(hit -> hit.page() > 2 || hit.y() > 42.5));
        var changed = project.source(source, project.data().replace("示例患者甲", "示例患者乙"));
        assertEquals(source, changed.bundle().source());
        var output = new ByteArrayOutputStream();
        var result = DesignerService.prepare(changed, DesignerService.Format.PDF, ProcessingPolicy.STRICT,
                DesignerService.execution(() -> false)).to(output);
        assertTrue(result.report().losses().isEmpty());
        assertTrue(output.size() > 1000);
    }
    @Test void jsonRetainsNestedBindingTypesAndRoundTripsEscapes() {
        var source = BindingJson.parse("{\"title\":\"x\\n\\\"\\u4e2d\",\"rows\":[{\"count\":1.25}],\"ok\":true}");
        assertEquals(source, BindingJson.parse(BindingJson.write(source)));
        assertEquals("x\n\"中", source.get("title"));
        assertThrows(UnsupportedOperationException.class, () -> source.put("x", "y"));
    }
    @Test void jsonRejectsUnsupportedAndAmbiguousInputs() {
        for (String source : List.of("{\"x\":null}", "{\"x\":1,\"x\":2}", "{\"x\":01}", "{\"x\":true,}", "{\"x\":NaN}",
                "{\"x\":1e999999999}", "{} {}", "[]", "{\"x\":\"\\uD800\"}")) {
            assertThrows(IllegalArgumentException.class, () -> BindingJson.parse(source), source);
        }
        assertThrows(IllegalArgumentException.class, () -> BindingJson.parse("{\"x\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}"));
    }
    @Test void formattingKeepsLiteralInlineWhitespaceExpressionsAndComments() throws Exception {
        String mixed = " A <span role='bold'> bold ${title} </span> B <link href='https://example.com'> link </link> ";
        String source = "<document version='1' literal-text='true'><!--keep--><page size='A4'/><header height='20'><p>H</p></header><p id='a'>" + mixed + "</p><table><columns><column weight='1'/></columns><row><cell>  cell <span>X</span> tail  </cell></row></table></document>";
        String formatted = DesignerFormatting.xml(source);
        var before = DesignerXml.parse(source); var after = DesignerXml.parse(formatted);
        assertTrue(formatted.contains("\n  <page")); assertTrue(formatted.contains("<!--keep-->"));
        assertEquals(before.getElementsByTagName("p").item(1).getTextContent(), after.getElementsByTagName("p").item(1).getTextContent());
        assertEquals(before.getElementsByTagName("cell").item(0).getTextContent(), after.getElementsByTagName("cell").item(0).getTextContent());
        assertEquals(formatted, DesignerFormatting.xml(formatted));
        assertTrue(formatted.contains("${title}"));
        assertEquals("<p>   </p>", DesignerFormatting.xml("<p>   </p>"));
        assertThrows(Exception.class, () -> DesignerFormatting.xml("<!DOCTYPE document><document/>"));
    }
    @Test void jsonFormattingDoesNotRoundLargeNumbersOrAcceptAmbiguity() {
        String input = "{\"n\":12345678901234567890.123456789,\"rows\":[{},[],{\"text\":\"a\\\"b\\n中\"}]}";
        String formatted = DesignerFormatting.json(input);
        assertTrue(formatted.contains("12345678901234567890.123456789")); assertTrue(formatted.contains("\n"));
        assertEquals(BindingJson.parse(input), BindingJson.parse(formatted));
        assertEquals(formatted, DesignerFormatting.json(formatted));
        assertThrows(IllegalArgumentException.class, () -> DesignerFormatting.json("{\"x\":1,\"x\":2}"));
    }
    @Test void pageRegionsRichRunsAndTableFormattingReachActualLayout() throws Exception {
        String source = """
                <document version="1">
                  <page width="148mm" height="210mm" orientation="LANDSCAPE" margin="12mm" margin-left="18mm"/>
                  <header height="20" gap="5"><p size="10" line-height="15">DESIGNER-HEADER</p></header>
                  <footer height="20" gap="5"><p size="10" line-height="15">PAGE <page-number width="24"/> / <page-count width="24"/></p></footer>
                  <p id="rich" line-height="22">Plain <span role="bold" color="#224488" size="14" underline="true">Bold ${title}</span><link href="https://example.com"> Link</link></p>
                  <table id="styled" draw-borders="false" padding-top="3" padding-right="4" padding-bottom="5" padding-left="6" space-before="7" space-after="8">
                    <columns><column weight="1"/></columns><header><cell size="9" line-height="15">Column</cell></header><row><cell size="9" line-height="15">CELL-END</cell></row>
                  </table>
                </document>
                """;
        var project = DesignerSamples.create(0).source(source, "{\"title\":\"STYLE\"}");
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertEquals(1, preview.layout().display().pages().size());
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.path().equals("/1/0")
                && hit.y() < preview.layout().body().pages().getFirst().contentBounds().y()));
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.path().equals("/2/0")
                && hit.y() > preview.layout().body().pages().getFirst().contentBounds().y()));
        var size = preview.layout().display().pages().getFirst().size();
        assertEquals(210 * 72 / 25.4, size.width(), 0.01); assertEquals(148 * 72 / 25.4, size.height(), 0.01);
        var prepared = DesignerService.prepare(project, DesignerService.Format.PDF, ProcessingPolicy.STRICT, DesignerService.execution(() -> false));
        var output = new ByteArrayOutputStream(); prepared.to(output); assertTrue(output.size() > 1000);
        var saved = XmlTemplateBundle.read(project.bundle().encode()); assertEquals(source, saved.source());
    }
    @Test void rotatedTextImageAndTableSurviveTemplateBindingAndStaticExport() throws Exception {
        String source = "<document version='1'><p id='turn-text' line-height='90' rotation='90'>ROTATED TEXT</p>"
                + "<p id='turn-image' line-height='90'><image ref='mark' width='70' height='50' rotation='-90' alt='Mark'/></p>"
                + "<table id='turn-table' rotation='45'><columns><column weight='1'/></columns>"
                + "<row><cell>ROTATED TABLE</cell></row></table></document>";
        var project = DesignerSamples.create(0).source(source, "{}");
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        long rotatedCommands = preview.layout().display().pages().getFirst().commands().stream()
                .filter(command -> command instanceof com.mirakyux.export4j.graphics.DrawCommand.Transform transform
                        && Math.abs(transform.matrix().b()) > 0.01).count();
        assertTrue(rotatedCommands >= 3);
        var reopened = XmlTemplateBundle.read(project.bundle().encode());
        assertEquals(source, reopened.source());
        assertEquals(3, reopened.compile().renderComplete(Map.of()).document().sections().getFirst().blocks().size());
        var prepared = DesignerService.prepare(project, DesignerService.Format.PDF, ProcessingPolicy.STRICT,
                DesignerService.execution(() -> false));
        var output = new ByteArrayOutputStream(); prepared.to(output);
        assertTrue(output.size() > 1000);
        assertTrue(prepared.preview().losses().isEmpty());

        assertThrows(com.mirakyux.export4j.model.processing.ProcessingException.class,
                () -> DesignerService.prepare(project, DesignerService.Format.DOCX, ProcessingPolicy.STRICT,
                        DesignerService.execution(() -> false)));
        var warning = DesignerService.policy(DesignerService.Format.DOCX, ProcessingPolicy.Action.WARN, "RECOMMENDED");
        var docx = DesignerService.prepare(project, DesignerService.Format.DOCX, warning, DesignerService.execution(() -> false));
        assertEquals(3, docx.preview().issues().stream().filter(issue -> issue.feature().equals("ELEMENT_ROTATION")).count());
        assertEquals(3, docx.preview().warnings().stream().filter(issue -> issue.feature().equals("ELEMENT_ROTATION")).count());
        assertTrue(docx.preview().issues().stream().filter(issue -> issue.feature().equals("ELEMENT_ROTATION"))
                .allMatch(issue -> issue.treatment() == ProcessingPolicy.Treatment.DEGRADE));
    }
    @Test void pageBandsCanReachPaperEdgesWithoutChangingBodyMargins() throws Exception {
        String source = "<document version='1'><page width='400' height='500' margin='40'/>"
                + "<header height='20' edge='0' left='0' right='0'><p>EDGE HEADER</p></header>"
                + "<footer height='20' edge='0' left='0' right='0'><p>EDGE FOOTER</p></footer>"
                + "<p id='body'>BODY</p></document>";
        var preview = DesignerService.preview(DesignerSamples.create(0).source(source, "{}"),
                DesignerService.execution(() -> false));
        var header = preview.hits().stream().filter(hit -> hit.path().equals("/1/0")).findFirst().orElseThrow();
        var footer = preview.hits().stream().filter(hit -> hit.path().equals("/2/0")).findFirst().orElseThrow();
        assertEquals(0, header.x()); assertEquals(0, header.y()); assertEquals(400, header.width());
        assertEquals(0, footer.x()); assertEquals(480, footer.y()); assertEquals(400, footer.width());
        assertEquals(40, preview.hits().stream().filter(hit -> hit.id().equals("body")).findFirst().orElseThrow().x());
        assertThrows(Exception.class, () -> DesignerService.preview(DesignerSamples.create(0).source(
                source.replace("edge='0'", "edge='490'"), "{}"), DesignerService.execution(() -> false)));
    }
    @Test void startingSectionsKeepsBodyOrderAndAppliesPerSectionPageSettings() throws Exception {
        String source = "<document version='1'><page size='A4' margin='30'/><header height='20'><p>COMMON</p></header>"
                + "<p id='one'>One</p><p id='two'>Two</p><p id='three'>Three</p></document>";
        String split = DesignerXml.startSection(source, "/3", "appendix", "introduction");
        var root = DesignerXml.parse(split).getDocumentElement();
        assertEquals(List.of("page", "header", "section", "section"), DesignerXml.children(root).stream().map(org.w3c.dom.Element::getTagName).toList());
        assertEquals(List.of("one"), DesignerXml.children(DesignerXml.at(DesignerXml.parse(split), "/2")).stream().map(node -> node.getAttribute("id")).toList());
        assertEquals(List.of("two", "three"), DesignerXml.children(DesignerXml.at(DesignerXml.parse(split), "/3")).stream().map(node -> node.getAttribute("id")).toList());
        String configured = DesignerXml.replace(split, "/3", "<section id='appendix' first-page-number='1'><page size='LETTER' orientation='LANDSCAPE'/>"
                + "<header height='20'><p>APPENDIX</p></header><p id='two'>Two</p><p id='three'>Three</p></section>");
        var preview = DesignerService.preview(DesignerSamples.create(0).source(configured, "{}"), DesignerService.execution(() -> false));
        assertEquals(2, preview.layout().display().pages().size());
        assertTrue(preview.layout().display().pages().get(1).size().width() > preview.layout().display().pages().get(0).size().width());
        String threeSections = DesignerXml.startSection(configured, "/3/3", "ending", "unused");
        assertEquals(3, DesignerXml.parse(threeSections).getElementsByTagName("section").getLength());
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.startSection(configured, "/3/2", "empty", "unused"));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.startSection(configured, "/0", "invalid", "unused"));
    }
    @Test void propertiesPreserveMixedContentAndDynamicIntent() throws Exception {
        var project = DesignerSamples.create(0); String source = project.bundle().source();
        String changed = DesignerXml.properties(source, "/2", Map.of("id", "report-summary", "size", "12", "line-height", "20"), "CHANGED ${summary}");
        var candidate = project.source(changed, project.data());
        assertTrue(candidate.bundle().source().contains("${title}"));
        assertTrue(candidate.bundle().source().contains("<list")); assertTrue(candidate.bundle().source().contains("<if"));
        assertTrue(candidate.bundle().source().contains("CHANGED ${summary}")); assertEquals(source, project.bundle().source());
        var rendered = DesignerService.preview(candidate, DesignerService.execution(() -> false)); assertEquals(1, rendered.layout().display().pages().size());
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.properties(source, "/3", Map.of(), "replace image"));
    }
    @Test void canvasDropUsesExplicitBeforeAfterAndRetainsTemplateStructure() throws Exception {
        String original = "<document version='1'><p id='a'>${title}</p><p id='b'>B <span role='bold'>bold</span></p><each items='items' as='item'><p>${item}</p></each></document>";
        String after = DesignerXml.relocate(original, "/0", "/1", true);
        var root = DesignerXml.parse(after).getDocumentElement();
        assertEquals("b", DesignerXml.children(root).getFirst().getAttribute("id"));
        assertEquals("${title}", DesignerXml.children(root).get(1).getTextContent());
        assertTrue(after.contains("<each")); assertTrue(after.contains("<span"));
        String inserted = DesignerXml.insertBeside(after, "/1", "<page-break id='split'/>", false);
        var children = DesignerXml.children(DesignerXml.parse(inserted).getDocumentElement());
        assertEquals(List.of("p", "page-break", "p", "each"), children.stream().map(org.w3c.dom.Element::getTagName).toList());
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.relocate(original, "/0", "/2/0", false));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.insertBeside(original, "", "<p/>", false));
        assertEquals(DesignerXml.serialize(DesignerXml.parse(original)), DesignerXml.relocate(original, "/0", "/0", true));
    }
    @Test void multiSelectionMovesAsOneBlockAndPatchesOnlyChosenAttributes() throws Exception {
        String source="<document version='1'><p id='a' size='10'>A</p><p id='b' size='11'>B</p><p id='c' size='12'>C</p><p id='d'>D</p></document>";
        String moved=DesignerXml.relocateMany(source,List.of("/0","/2"),"/3",true);
        var children=DesignerXml.children(DesignerXml.parse(moved).getDocumentElement());
        assertEquals(List.of("b","d","a","c"),children.stream().map(node->node.getAttribute("id")).toList());
        String patched=DesignerXml.patchProperties(moved,List.of("/2","/3"),Map.of("size","15"));
        assertEquals("10",DesignerXml.at(DesignerXml.parse(source),"/0").getAttribute("size"));
        assertEquals("11",DesignerXml.at(DesignerXml.parse(patched),"/0").getAttribute("size"));
        assertEquals("15",DesignerXml.at(DesignerXml.parse(patched),"/2").getAttribute("size"));
        assertEquals("15",DesignerXml.at(DesignerXml.parse(patched),"/3").getAttribute("size"));
        assertEquals(List.of("b","d"),DesignerXml.children(DesignerXml.parse(DesignerXml.removeMany(patched,List.of("/2","/3"))).getDocumentElement()).stream().map(node->node.getAttribute("id")).toList());
        assertThrows(IllegalArgumentException.class,()->DesignerXml.relocateMany(source,List.of("/0","/2"),"/1/0",false));
        assertThrows(IllegalArgumentException.class,()->DesignerXml.removeMany(source,List.of("/0","/0")));
    }
    @Test void previewMapsRepeatedListItemsWithoutWritingSyntheticIdsToSavedSource() throws Exception {
        var project = DesignerSamples.create(0); String original = project.bundle().source();
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        var items = preview.hits().stream().filter(hit -> hit.path().equals("/5/0")).toList();
        assertEquals(3, items.size());
        assertEquals(3, items.stream().map(DesignerService.Hit::id).distinct().count());
        assertEquals(original, project.bundle().source());
        assertFalse(XmlTemplateBundle.read(project.bundle().encode()).source().contains("e4j-preview-"));
        String source = "<document version='1'><p id='e4j-preview-0'>A</p><bullet>B</bullet><numbered list-id='list'>C</numbered></document>";
        var mapping = DesignerXml.previewSource(source);
        assertEquals("/0", mapping.paths().get("e4j-preview-0"));
        assertEquals(3, mapping.paths().size());
        assertTrue(mapping.paths().containsValue("/1")); assertTrue(mapping.paths().containsValue("/2"));
    }
    @Test void listContainerBindsOneListItemAndRejectsOtherChildren() throws Exception {
        var base = DesignerSamples.create(0);
        String source = "<document version='1'><list items='items' as='item'><numbered id='entry' list-id='entries'>${item.name}</numbered></list></document>";
        var preview = DesignerService.preview(base.source(source, base.data()), DesignerService.execution(() -> false));
        assertEquals(3, preview.hits().stream().filter(hit -> hit.path().equals("/0/0")).count());
        for (String child : List.of("<p>A</p>", "<bullet>A</bullet><bullet>B</bullet>")) {
            String invalid = "<document version='1'><list items='items' as='item'>" + child + "</list></document>";
            assertThrows(Exception.class, () -> DesignerService.preview(base.source(invalid, base.data()), DesignerService.execution(() -> false)));
        }
    }
    @Test void pageRegionHitsFollowSectionVariantAndInheritedSource() throws Exception {
        String source = """
                <document version="1"><page width="400" height="500" margin="40"/>
                  <header height="20"><p id="base-band">BASE</p></header>
                  <section id="a" first-page-number="2">
                    <header variant="first" height="20"><p id="first-band">FIRST</p></header>
                    <footer variant="first" height="0"/>
                    <p id="first-body">A</p>
                  </section>
                  <section id="b"><p id="second-body">B</p></section>
                </document>
                """;
        var project = DesignerSamples.create(0).source(source, "{}");
        var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertEquals(2, preview.layout().display().pages().size());
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.page() == 0 && hit.path().equals("/2/0/0")));
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.page() == 1 && hit.path().equals("/1/0")));
    }
    @Test void pageBreakMarkersUseRealBoundPagesAndDoNotInventBlankPages() throws Exception {
        for (boolean contents : List.of(false, true)) {
            String navigation = contents ? "<navigation headings='true'><contents title='Contents'/></navigation>" : "";
            String source = "<document version='1'>" + navigation + "<heading id='a' level='1'>A</heading><page-break id='first'/><page-break id='second'/><p id='b'>B</p><page-break id='trailing'/></document>";
            var project = DesignerSamples.create(0).source(source, "{}");
            var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
            assertEquals(contents ? 3 : 2, preview.layout().display().pages().size());
            assertEquals(List.of("first", "second"), preview.breaks().stream().map(DesignerService.Break::id).toList());
            assertTrue(preview.breaks().stream().allMatch(item -> item.page() == (contents ? 2 : 1)));
            assertEquals(preview.hits().stream().filter(hit -> hit.id().equals("b")).findFirst().orElseThrow().y(), preview.breaks().getFirst().y());
        }
    }
    @Test void structuralEditingReordersSiblingsAndKeepsNestedTableCells() throws Exception {
        String original = "<document version='1'><p id='a'>A <span> inline </span> end</p><!--keep--><p id='b'>B</p><p id='c'>C</p></document>";
        String moved = DesignerXml.moveBefore(original, "/2", "/0"); var document = DesignerXml.parse(moved);
        assertEquals(List.of("c", "a", "b"), DesignerXml.children(document.getDocumentElement()).stream().map(element -> element.getAttribute("id")).toList());
        assertTrue(moved.contains("<!--keep-->")); assertTrue(moved.contains("A <span> inline </span> end"));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.moveBefore(original, "/0/0", "/1"));
        String replaced = DesignerXml.replace(original, "/1", "<table id='t'><columns><column weight='1'/></columns><row><cell>Cell</cell></row></table>");
        assertEquals("Cell", DesignerXml.at(DesignerXml.parse(replaced), "/1/1/0").getTextContent());
        assertFalse(DesignerXml.remove(replaced, "/2").contains("id=\"c\""));
    }
    @Test void unsafeXmlAndFailedEditsDoNotChangeHistory() throws Exception {
        var original = DesignerSamples.create(0); var history = new DesignerProject.History(original);
        assertThrows(Exception.class, () -> history.change(original.source("<document version='1'><unknown/></document>", "{}")));
        assertThrows(Exception.class, () -> DesignerXml.parse("<!DOCTYPE document [<!ENTITY x SYSTEM 'file:///denied'>]><document>&x;</document>"));
        assertThrows(IllegalArgumentException.class, () -> DesignerXml.parse(" ".repeat(1_000_001)));
        assertEquals(original, history.current()); assertFalse(history.canUndo());
    }
    @Test void undoRedoRestoreSourceDataAndResourceSnapshots() throws Exception {
        var original = DesignerSamples.create(0); var history = new DesignerProject.History(original);
        var changed = original.source(original.bundle().source().replace("REPORT-END", "REPORT-CHANGED"), original.data().replace("true", "false"));
        history.change(changed); assertEquals(changed, history.current()); history.undo(); assertEquals(original, history.current());
        history.redo(); assertEquals(changed, history.current()); history.undo(); history.change(original.source(original.bundle().source(), "{}")); assertFalse(history.canRedo());
    }
    @Test void savedTemplateKeepsOriginalDynamicSourcesFontsAndLicenses() throws Exception {
        var project = DesignerSamples.create(0); var bundle = project.bundle().toBuilder().fragment("panel", "<fragment version='1'><p>UNCHANGED</p></fragment>").build();
        Path target = temporary.resolve("template.zip"); bundle.write(target);
        var reopened = XmlTemplateBundle.read(Files.readAllBytes(target));
        assertEquals(bundle.source(), reopened.source()); assertEquals(bundle.fragments(), reopened.fragments()); assertEquals(bundle.licenses(), reopened.licenses());
        bundle.fonts().forEach((name, bytes) -> org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, reopened.fonts().get(name)));
        assertEquals(project.bundle().images().get("mark").id(), reopened.images().get("mark").id());
    }
    @Test void previewHasRealPageGeometryAndExplicitDocumentFonts() throws Exception {
        var report = DesignerService.preview(DesignerSamples.create(0), DesignerService.execution(() -> false));
        var logo = report.hits().stream().filter(hit -> hit.id().equals("report-logo")).findFirst().orElseThrow().images().getFirst();
        assertEquals(40, logo.width()); assertEquals(24, logo.height()); assertEquals(42, logo.x());
        assertTrue(logo.y() > 42 && logo.y() + logo.height() < 800);
        var project = DesignerSamples.create(3); var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
        assertTrue(preview.layout().display().pages().size() >= 3);
        assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals("appendix") && hit.page() >= 2));
        var image = preview.image(0); assertNotNull(image); assertTrue(image.getWidth() > 500); image.flush();
        var fontless = XmlTemplateBundle.builder("<document version='1'><p>Missing font</p></document>").build();
        assertThrows(java.io.IOException.class, () -> DesignerService.engine(fontless));
    }
    @Test void warnAndIgnoreKeepTheSameActualLossesForStaticNavigation() throws Exception {
        var project = DesignerSamples.create(3);
        assertThrows(RuntimeException.class, () -> DesignerService.prepare(project, DesignerService.Format.PNG, ProcessingPolicy.STRICT, DesignerService.execution(() -> false)));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DISCARD).build();
            var prepared = DesignerService.prepare(project, DesignerService.Format.PNG, policy, DesignerService.execution(() -> false));
            assertEquals(1, prepared.preview().losses().size()); assertEquals("INTERACTIVE_CONTENT", prepared.preview().issues().getFirst().feature());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, prepared.preview().warnings().size());
            var output = new ByteArrayOutputStream(); var result = prepared.to(output);
            assertEquals(prepared.preview().issues(), result.report().issues()); assertTrue(output.size() > 1000);
        }
    }
    @Test void cancellationAndBudgetFailureKeepPreviousTarget() throws Exception {
        var project = DesignerSamples.create(0); Path target = temporary.resolve("document.pdf"); Files.writeString(target, "OLD");
        assertThrows(RuntimeException.class, () -> DesignerService.preview(project, DesignerService.execution(() -> true)));
        assertThrows(RuntimeException.class, () -> DesignerService.prepare(project, DesignerService.Format.PDF, ProcessingPolicy.STRICT,
                DesignerService.execution(() -> false).toBuilder().maxOutputBytes(32).build()).to(target));
        assertEquals("OLD", Files.readString(target));
        try (var files = Files.list(temporary)) { assertEquals(List.of(target), files.toList()); }
    }
}
