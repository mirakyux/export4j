// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlSectionsTest {
    private static FontGroup fonts;
    private static ImageResource image;
    private static final String REGIONS = "<header height='28' gap='4'><p id='brand' line-height='24'>BRAND <image ref='logo' width='12' height='12'/></p></header>"
            + "<footer height='26' gap='4'><table id='foot'><columns><column weight='1'/><column weight='1'/></columns>"
            + "<row><cell>PAGE <page-number width='20'/></cell><cell>OF <page-count width='20'/></cell></row></table></footer>";
    @BeforeAll static void setup() throws IOException {
        fonts = FontGroup.strict(List.of(TrueTypeFontLoader.load(Files.readAllBytes(Path.of("../assets/fonts/LiberationSans-Regular.ttf")))));
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        image = ImageResource.load(bytes.toByteArray());
    }
    private static XmlTemplate compiler() {
        return XmlTemplate.builder().image("logo", image).theme(DocumentTheme.builder()
                .bodyFormat(value -> value.fontSize(10).lineHeight(20).spaceAfter(0)).tableFormat(value -> value.padding(3).spaceAfter(0)).build()).build();
    }
    private static String xml(String content) { return "<document version='1'><page width='260' height='180' margin='10'/>" + content + "</document>"; }
    private static String text(List<DrawCommand> commands) {
        return commands.stream().map(value -> value instanceof DrawCommand.GlyphText t ? t.run().text()
                : value instanceof DrawCommand.OutlineText t ? t.logicalText() : "").collect(java.util.stream.Collectors.joining());
    }

    @Test void inheritedMixedRegionsRepeatOnEveryPageAndSectionNumberingRestarts() {
        var template = compiler().compile(xml(REGIONS + "<section id='one'><p id='a'>A\nB\nC\nD\nE</p></section>"
                + "<section id='two' first-page-number='8'><page width='300' height='200'/><p id='b'>Second</p></section>"));
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
        assertThrows(DocumentTemplate.BindingException.class, () -> template.renderWithPageRegions(Map.of()));
        var document = template.renderSections(Map.of());
        assertEquals(template, template.toBuilder().build());
        assertSame(document.sections().getFirst().pageRegions().header(), document.sections().getLast().pageRegions().header());
        var result = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals(3, result.display().pages().size());
        assertEquals(300, result.display().pages().getLast().size().width());
        assertEquals(List.of("BRAND \uFFFC", "PAGE 1OF 3", "BRAND \uFFFC", "PAGE 2OF 3", "BRAND \uFFFC", "PAGE 8OF 3"),
                result.decorations().stream().map(value -> text(value.layout().commands())).toList());
        assertEquals(3, result.decorations().stream().flatMap(value -> value.layout().commands().stream())
                .filter(DrawCommand.Image.class::isInstance).count());
        assertTrue(result.body().pages().stream().allMatch(page -> page.contentBounds().y() == 42));
        assertThrows(IllegalStateException.class, () -> document.sections().getFirst().pageRegions().footer().paragraphs());
    }

    @Test void perSectionRegionsCanOverrideAndExplicitlyClearInheritedBands() {
        var document = compiler().compile(xml(REGIONS
                + "<section id='one'><p>A</p></section><section id='two'><header height='0'/><footer height='20'><p>NEW</p></footer><p>B</p></section>"))
                .renderSections(Map.of());
        var result = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals(2, result.display().pages().size());
        assertEquals(3, result.decorations().size());
        assertEquals("NEW", text(result.decorations().getLast().layout().commands()));
        assertEquals(PageRegion.EMPTY, document.sections().getLast().pageRegions().header());
    }

    @Test void tablesImagesAndSectionsSurviveStrictDocxRoundTripWithScopedRelationships() throws IOException {
        var original = compiler().compile(xml(REGIONS + "<section id='one'><p id='p1'>First</p></section>"
                + "<section id='two' first-page-number='8'><page width='300' height='200'/><p id='p2'>Second</p></section>"
                + "<section id='three'><header height='0'/><footer height='0'/><p id='p3'>Third</p></section>" )).renderSections(Map.of());
        var adapter = new DocxAdapter(); var bytes = new ByteArrayOutputStream(); adapter.write(original, bytes);
        var read = adapter.read(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(read.diagnostics().isEmpty());
        assertEquals(original.sections().stream().map(value -> value.pageSettings()).toList(), read.document().sections().stream().map(value -> value.pageSettings()).toList());
        assertEquals(original.sections().stream().map(value -> value.pageRegions()).toList(), read.document().sections().stream().map(value -> value.pageRegions()).toList());
        assertEquals(List.of(0, 8, 0), read.document().sections().stream().map(value -> value.firstPageNumber()).toList());
        assertEquals(3, DocumentEngine.builder(fonts).build().layout(read.document()).display().pages().size());
    }

    @Test void allSectionBranchesCompileAndStructuralDeclarationsCannotBeDynamicOrNested() {
        for (var content : List.of("<p>A</p><section id='s'/>", "<section id='s'/><section id='s'/>",
                "<section id='${secret}'/>", "<section id='s' first-page-number='-1'/>",
                "<section id='s'><if test='flag'><then/><else><header height='20'/></else></if></section>",
                "<section id='s'><section id='nested'/></section>",
                "<section id='s'><p>A</p><page/></section>",
                "<section id='s'><header height='20'/><header height='20'/></section>")) {
            assertThrows(TemplateException.class, () -> compiler().compile(xml(content)), content);
        }
    }

    @Test void bindingBudgetsAreSharedBySectionsAndRegionTables() {
        var template = compiler().compile(xml(REGIONS + "<section id='one'><p>${value}</p></section><section id='two'><p>${value}</p></section>"));
        var limited = template.toBuilder().limits(new DocumentTemplate.Limits(1000, 3, 100, 10000, 32)).build();
        assertEquals(DocumentTemplate.BindingException.Code.BUDGET_EXCEEDED,
                assertThrows(DocumentTemplate.BindingException.class, () -> limited.renderSections(Map.of("value", "Body"))).code());
        assertThrows(DocumentTemplate.BindingException.class, () -> template.renderSections(Map.of()));
    }

    @Test void overflowingRegionTableFailsBeforeBackendAndRetainsTarget(@org.junit.jupiter.api.io.TempDir Path directory) throws IOException {
        var document = compiler().compile(xml("<footer height='10'><table><columns><column weight='1'/></columns><row><cell>Too tall</cell></row></table></footer>"
                + "<section id='s'><p>Body</p></section>")).renderSections(Map.of());
        var path = directory.resolve("old.pdf"); Files.writeString(path, "ORIGINAL");
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(fonts).build().export(document)
                .backend((value, output) -> { throw new AssertionError("backend must not run"); }).to(path));
        assertEquals("ORIGINAL", Files.readString(path));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void offlineFragmentsWorkInsideSectionsAndBandsWithoutPermittingFragmentPageDeclarations() {
        var compiler = XmlTemplate.builder().fragment("line", "<fragment version='1'><p id='value'>${name}</p></fragment>").build();
        var document = compiler.compile(xml("<header height='20'><include ref='line' prefix='head'/></header>"
                + "<section id='s'><include ref='line' prefix='body'/></section>")).renderSections(Map.of("name", "Fragment"));
        assertEquals("head-value", document.sections().getFirst().pageRegions().header().blocks().getFirst().id());
        assertEquals("body-value", document.sections().getFirst().blocks().getFirst().id());
        assertEquals(1, DocumentEngine.builder(fonts).build().layout(document).display().pages().size());
        var invalid = XmlTemplate.builder().fragment("page", "<fragment version='1'><page size='A4'/></fragment>").build();
        assertThrows(TemplateException.class, () -> invalid.compile(xml("<section id='s'><include ref='page' prefix='x'/></section>")));
    }

    @Test void headerTablesFooterImagesAndNestedBlockCellsKeepFinalFieldsAndGeometry() throws IOException {
        var document = compiler().compile(xml("<header height='30'><table id='t'><columns><column weight='1'/></columns>"
                + "<row><cell><p size='10' line-height='20'>HEAD <page-number width='20'/></p></cell></row></table></header>"
                + "<footer height='26'><p line-height='22'>FOOT <image ref='logo' width='12' height='12'/></p></footer>"
                + "<section id='s' first-page-number='9'><p>Body</p></section>")).renderSections(Map.of());
        var result = DocumentEngine.builder(fonts).build().layout(document);
        assertEquals("HEAD 9", text(result.decorations().getFirst().layout().commands()));
        assertEquals(1, result.decorations().getLast().layout().commands().stream().filter(DrawCommand.Image.class::isInstance).count());
        var bytes = new ByteArrayOutputStream(); new DocxAdapter().write(document, bytes);
        var imported = new DocxAdapter().read(new ByteArrayInputStream(bytes.toByteArray()), DocxAdapter.Policy.STRICT);
        assertEquals(document.sections().getFirst().pageRegions(), imported.document().sections().getFirst().pageRegions());
    }

    @Test void regionImageAndCellSourceBudgetsAreCheckedAtConstruction() {
        var large = new Paragraph("p", "a".repeat(100_001), com.mirakyux.export4j.model.ParagraphFormat.of(10));
        assertThrows(IllegalArgumentException.class, () -> new PageRegion(20, 0, List.of(large)));
        var table = Table.text("t", List.of("Head"), List.of(List.of("Cell")), com.mirakyux.export4j.model.CellFormat.of(10));
        var value = PageRegion.builder().height(100).paragraph(new Paragraph("p", "Before", com.mirakyux.export4j.model.ParagraphFormat.of(10))).table(table).build();
        assertEquals(value, value.toBuilder().build());
        assertEquals(2, value.blocks().size());
    }
}
