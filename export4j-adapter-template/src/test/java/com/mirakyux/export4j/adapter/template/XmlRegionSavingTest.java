// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.PageDocument;
import com.mirakyux.export4j.model.PageOverlay;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.edit.DocumentEditor;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlRegionSavingTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(10);
    private static Paragraph paragraph(String id, String value) {
        return Paragraph.mixed(id, List.of(new InlineText(value, InlineStyle.of(10))), FORMAT);
    }
    private static Section section(String id, PageRegions regions) {
        return new Section(id, PageSettings.A4, List.of(paragraph("body-" + id, id)), 2, regions);
    }
    private static PageRegion band(String id) { return new PageRegion(30, 4, List.of(paragraph(id, "${raw " + id))); }

    @Test void allSelectorsEmptyPairsAndRepeatedPlacementsSurviveThreeCycles() throws IOException {
        var header = band("header"); var even = band("even");
        var background = new PageRegion(20, 0, List.of(paragraph("background", "BG")));
        var foreground = new PageRegion(20, 0, List.of(paragraph("foreground", "FG")));
        var regions = PageRegions.builder().header(header).footer(new PageRegion(12, 3, List.of()))
                .variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY)
                .variant(PageRegions.Variant.EVEN, new PageRegions.Bands(even, PageRegion.EMPTY))
                .background(new PageOverlay(10, 100, 100, background)).background(new PageOverlay(130, 100, 100, background))
                .foreground(new PageOverlay(20, 200, 150, foreground)).build();
        var other = regions.toBuilder().header(PageRegions.Variant.FIRST, header).background(List.of()).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("a", regions), section("b", other), section("c", regions)));
        var prepared = XmlTemplateWriter.from(source).prepare();
        String xml = prepared.bundle().source();
        assertTrue(xml.contains("source-ref=")); assertTrue(prepared.preview().issues().isEmpty());
        for (int cycle = 0; cycle < 3; cycle++) {
            var restored = XmlTemplateBundle.read(prepared.bundle().encode()).compile().renderSections(Map.of());
            assertEquals(source, restored);
            assertSame(restored.sections().getFirst().pageRegions().header(), restored.sections().get(1).pageRegions().header());
            var placements = restored.sections().getFirst().pageRegions().background();
            assertSame(placements.getFirst().content(), placements.getLast().content());
            prepared = XmlTemplateWriter.from(restored).prepare(); assertEquals(xml, prepared.bundle().source());
        }
        var edited = DocumentEditor.edit(source).replaceText("header", "header", "edited").execute();
        assertEquals(1, edited.changes().size());
        var reopened = XmlTemplateWriter.from(edited.document()).prepare().bundle().compile().renderSections(Map.of());
        assertEquals(edited.document(), reopened);
        assertTrue(reopened.sections().get(1).pageRegions().variants().get(PageRegions.Variant.FIRST).header()
                .paragraphs().getFirst().text().contains("edited"));
    }

    @Test void pageDocumentUsesTheExistingNonSectionedEntryPoint() throws IOException {
        var source = new PageDocument(new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(paragraph("body", "Body"))),
                PageRegions.builder().header(band("h")).build());
        var saved = XmlTemplateWriter.from(source).prepare().bundle();
        assertEquals(source, saved.compile().renderWithPageRegions(Map.of()));
        assertFalse(saved.source().contains("<section"));
    }

    @Test void sharedSourcesCountOnceInWriterAndBindingBudgets() throws IOException {
        var regions = PageRegions.builder().header(band("shared")).build();
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("a", regions), section("b", regions), section("c", regions)));
        var saved = XmlTemplateWriter.from(source).limits(new XmlTemplateWriter.Limits(4, 1, 1)).prepare().bundle();
        assertEquals(source, saved.compile().renderSections(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).limits(new XmlTemplateWriter.Limits(3, 1, 1)).prepare());
        var compiler = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 4, 100, 1000, 8)).build();
        assertEquals(source, compiler.compile(saved.source()).renderSections(Map.of()));
        var smaller = XmlTemplate.builder().bindingLimits(new DocumentTemplate.Limits(100, 3, 100, 1000, 8)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED, assertThrows(DocumentTemplate.BindingException.class,
                () -> smaller.compile(saved.source()).renderSections(Map.of())).code());
    }

    @Test void distinctSourcesAreNotMergedAndRegionIdsParticipateInRenamingReservations() throws IOException {
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("a", PageRegions.builder().header(band("saved-1")).build()),
                section("b", PageRegions.builder().header(band("not xml")).build())));
        var saved = XmlTemplateWriter.from(source).policy(ProcessingPolicy.builder()
                .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
        assertEquals(1, saved.preview().losses().size());
        assertEquals("HEADER", saved.preview().issues().getFirst().location().region());
        var reopened = saved.bundle().compile().renderSections(Map.of());
        assertEquals("saved-1", reopened.sections().getFirst().pageRegions().header().blocks().getFirst().id());
        assertEquals("saved-2", reopened.sections().getLast().pageRegions().header().blocks().getFirst().id());
    }

    @Test void nestedRegionLossReportsTheActualKindAndCellPath() throws IOException {
        var cell = TableCell.mixed(List.of(new InlineText("A😀", new InlineStyle("bad role", 10, 0, 0, false, false))), CellFormat.of(10));
        var table = new Table("table", List.of(new ColumnWidth.Fixed(100)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var box = new PageOverlay(20, 20, 100, new PageRegion(40, 0, List.of(table)));
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(section("s", PageRegions.builder().foreground(box).build())));
        var issue = assertThrows(ProcessingException.class, () -> XmlTemplateWriter.from(source).prepare()).issue();
        assertEquals("FOREGROUND:0", issue.location().region()); assertEquals("s", issue.location().sectionId());
        assertEquals("table", issue.location().cellPath().getFirst().tableId()); assertEquals(3, issue.location().endUtf16());
        var saved = XmlTemplateWriter.from(source).policy(ProcessingPolicy.builder()
                .defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
        assertEquals(1, saved.preview().losses().size()); assertEquals(0, saved.preview().warnings().size());
        assertEquals(1, saved.bundle().compile().renderSections(Map.of()).sections().getFirst().pageRegions().foreground().size());
    }

    @Test void staticReferencesBindOnceAndKeepInheritedAndLocalGeometry() {
        var template = XmlTemplate.builder().build().compile("""
                <document version='1'>
                  <header source-id='brand' height='20' gap='3'><p id='brand'>${brand}</p></header>
                  <header variant='even' source-ref='brand'/>
                  <background><box source-id='logo' x='10' y='40' width='100' height='20'><p id='logo'>${brand}</p></box>
                    <box source-ref='logo' x='120' y='50' width='200'/></background>
                  <section id='a'><p id='a'>A</p></section>
                  <section id='b'><header variant='first' source-ref='brand'/><p id='b'>B</p></section>
                </document>
                """);
        var source = template.renderSections(Map.of("brand", "BOUND"));
        var a = source.sections().getFirst().pageRegions(); var b = source.sections().getLast().pageRegions();
        assertSame(a.header(), b.variants().get(PageRegions.Variant.FIRST).header());
        assertEquals("BOUND", a.header().paragraphs().getFirst().text());
        assertEquals(3, a.header().gap()); assertEquals(200, a.background().getLast().width());
        assertSame(a.background().getFirst().content(), a.background().getLast().content());
    }

    @Test void invalidReferencesCannotBypassStructureKindGeometryOrSourceValidation() {
        String declaration = "<header source-id='h' height='20'><p id='p'>Text</p></header>";
        for (String suffix : List.of("<header variant='even' source-ref='missing'/>", "<footer source-ref='h'/>",
                "<header variant='even' source-ref='h' height='20'/>", "<header variant='even' source-ref='h' gap='0'/>",
                "<header variant='even' source-ref='h' source-id='x'/>", "<header variant='even' source-ref='h'><p>Copy</p></header>",
                "<header variant='even' source-ref='${h}'/>", "<header variant='even' source-id='h' height='20'/>",
                "<header variant='even' source-id='bad id' height='20'/>",
                "<background><box x='10' y='10' width='100' source-ref='h'/></background>",
                "<background><box source-id='b' x='10' y='10' width='100' height='20'/><box source-ref='b' x='10000' y='0' width='20'/></background>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'>" + declaration + suffix + "</document>"), suffix);
        }
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(
                "<document version='1'><header source-ref='later'/><header variant='even' source-id='later' height='20'/></document>"));
    }
}
