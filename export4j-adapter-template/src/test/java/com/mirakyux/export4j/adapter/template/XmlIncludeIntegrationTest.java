// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ParagraphFragment;
import com.mirakyux.export4j.layout.TableFragment;
import com.mirakyux.export4j.layout.core.DocumentLayoutException;
import com.mirakyux.export4j.model.CellLocation;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentTheme;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.text.ParagraphLayout;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextDiagnostic;
import com.mirakyux.export4j.text.TextLayoutException;

class XmlIncludeIntegrationTest {
    private static final PageSettings PAGE = PageSettings.builder().size(300, 260).margins(20).build();
    private static final String COLUMNS = "<columns><column weight='1'/></columns>";
    private static FontGroup regular;
    private static FontGroup bold;

    @BeforeAll static void fonts() throws IOException {
        regular = font("LiberationSans-Regular.ttf"); bold = font("LiberationSans-Bold.ttf");
    }

    private static FontGroup font(String name) throws IOException {
        try (var input = XmlIncludeIntegrationTest.class.getResourceAsStream("/fonts/" + name)) {
            if (input == null) { throw new IOException("missing test font"); }
            return FontGroup.strict(List.of(TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
        }
    }

    private static DocumentTheme theme() {
        return DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(24).spaceAfter(0))
                .headingFormat(1, format -> format.fontSize(10).lineHeight(24).spaceBefore(0).spaceAfter(0))
                .tableFormat(format -> format.padding(4).spaceAfter(0))
                .paragraphStyle("note", ParagraphFormat.builder().fontSize(10).lineHeight(24).spaceAfter(0).build())
                .inlineStyle("ink", new InlineStyle("report-bold", 10, 0x224488, 2, true, true))
                .inlineStyle("warn", new InlineStyle("warn-bold", 10, 0, 0, false, false)).build();
    }

    private static String xml(String content) { return "<document version='1'>" + content + "</document>"; }
    private static String fragment(String content) { return "<fragment version='1'>" + content + "</fragment>"; }
    private static String include(String prefix) { return "<include ref='part' prefix='" + prefix + "'/>"; }
    private static String table(String id, String content) {
        return "<table id='" + id + "'>" + COLUMNS + "<row><cell>" + content + "</cell></row></table>";
    }
    private static String deep(String content) {
        return table("outer", "<p id='before'>Before</p>" + table("inner", "<p id='leaf' style='note'>" + content + "</p>"));
    }
    private static XmlTemplate compiler(String part) { return XmlTemplate.builder().pageSettings(PAGE).theme(theme()).fragment("part", fragment(part)).build(); }
    private static Document included(String part) {
        return compiler(part).compile(xml("<p id='prefix'>Prefix</p>" + include("copy"))).render(Map.of());
    }
    private static TableFragment outer(ComponentDocumentLayout result) {
        return result.body().pages().getFirst().elements().stream().filter(TableFragment.class::isInstance)
                .map(TableFragment.class::cast).findFirst().orElseThrow();
    }
    private static ParagraphFragment leaf(TableFragment table) {
        var inner = (TableFragment) table.rows().getFirst().cells().getFirst().blocks().get(1);
        return (ParagraphFragment) inner.rows().getFirst().cells().getFirst().blocks().getFirst();
    }
    private static Paragraph leaf(Table table) {
        var inner = (Table) table.rows().getFirst().cells().getFirst().blocks().get(1);
        return (Paragraph) inner.rows().getFirst().cells().getFirst().blocks().getFirst();
    }
    private static List<CellLocation> path() {
        return List.of(new CellLocation("copy-outer", 0, 0, 1), new CellLocation("copy-inner", 0, 0, 0));
    }
    private static String logical(ParagraphLayout paragraph) {
        return paragraph.lines().stream().map(line -> line.outlines().isEmpty()
                ? line.runs().stream().map(run -> run.text()).collect(Collectors.joining())
                : line.outlines().stream().map(outline -> outline.logicalText()).collect(Collectors.joining()))
                .collect(Collectors.joining());
    }
    private static String logical(List<DrawCommand> commands) {
        var result = new StringBuilder();
        for (var command : commands) {
            if (command instanceof DrawCommand.GlyphText glyph) { result.append(glyph.run().text()); }
            else if (command instanceof DrawCommand.OutlineText outline) { result.append(outline.logicalText()); }
        }
        return result.toString();
    }

    @Test void expandedAndExplicitlyFlattenedDocumentsProduceIdenticalGeometrySourcesAndFinalInteractions() {
        String part = "<heading id='head' level='1'>${title}</heading><p id='body' style='note'><span style='ink'>Body</span>"
                + "<page-reference target='head' width='20'/><page-reference target='main' width='20'/></p>"
                + table("data", "<p id='leaf'>Leaf<page-reference target='body' width='20'/></p>");
        String main = "<p id='main'>Main</p>" + include("a") + "<page-break id='break'/>" + include("b");
        var included = compiler(part).compile(xml(main)).render(Map.of("title", "Report"));
        String first = part.replace("id='head'", "id='a-head'").replace("id='body'", "id='a-body'")
                .replace("id='data'", "id='a-data'").replace("id='leaf'", "id='a-leaf'")
                .replace("target='head'", "target='a-head'").replace("target='body'", "target='a-body'");
        String second = first.replace("a-head", "b-head").replace("a-body", "b-body").replace("a-data", "b-data").replace("a-leaf", "b-leaf");
        var flattened = XmlTemplate.builder().pageSettings(PAGE).theme(theme()).build()
                .compile(xml("<p id='main'>Main</p>" + first + "<page-break id='break'/>" + second)).render(Map.of("title", "Report"));
        assertEquals(flattened, included);
        var navigation = Navigation.fromHeadings(included, new TableOfContents(true, "Contents", ParagraphStyle.of(10)), List.of(), List.of());
        var engine = DocumentEngine.builder(regular).fontRole("report-bold", bold).navigation(navigation).build();
        var expected = engine.layout(flattened); var actual = engine.layout(included);
        assertEquals(2, actual.body().pages().size()); assertEquals(1, actual.bodyPageOffset());
        assertEquals(expected.body(), actual.body());
        assertEquals(expected.display(), actual.display());
        assertEquals(expected.contextualDiagnostics(), actual.contextualDiagnostics());
    }

    @Test void localAndExternalReferencesResolveAfterContentsOffsetAndOptionalSectionRestart() {
        var page = PageSettings.builder().size(440, 260).margins(20).build();
        String part = "<heading id='head' level='1'>Copy</heading><numbered id='step' list-id='checks' restart='7'>Step</numbered>"
                + "<p id='refs'>page=<page-number width='20'/> total=<page-count width='20'/> local=<page-reference target='head' width='20'/>"
                + " external=<page-reference target='outside' width='20'/> number=<number-reference target='step' width='20'/></p>";
        var document = XmlTemplate.builder().pageSettings(page).theme(theme()).fragment("part", fragment(part)).build()
                .compile(xml("<heading id='main' level='1'>Main</heading><p id='outside'>Outside</p>"
                + "<page-break/>" + include("copy"))).render(Map.of());
        var navigation = Navigation.fromHeadings(document, new TableOfContents(true, "Contents", ParagraphStyle.of(10)), List.of(), List.of());
        var engine = DocumentEngine.builder(regular).navigation(navigation).build();
        for (int restart : List.of(0, 9)) {
            var result = restart == 0 ? engine.layout(document) : engine.layout(new SectionedDocument(document.metadata(), List.of(
                    new Section("first", page, document.blocks().subList(0, 2), 0),
                    new Section("second", page, document.blocks().subList(3, document.blocks().size()), restart))));
            assertEquals(1, result.bodyPageOffset()); assertEquals(3, result.display().pages().size());
            String number = restart == 0 ? "3" : "9";
            String text = result.body().pages().getLast().fragments().stream().filter(value -> value.blockId().equals("copy-refs"))
                    .map(value -> logical(value.layout())).collect(Collectors.joining());
            assertEquals("page=" + number + " total=3 local=" + number + " external=2 number=7", text);
            assertEquals(List.of(1, 2), result.display().extras().bookmarks().stream().map(value -> value.pageIndex()).toList());
            var links = result.display().extras().links().stream().filter(value -> value.pageIndex() == 2).toList();
            assertEquals(3, links.size());
            assertEquals(List.of(2, 1, 2), links.stream().map(value -> value.targetPageIndex()).toList());
            assertTrue(result.body().pages().stream().flatMap(bodyPage -> bodyPage.fragments().stream()).flatMap(value -> value.layout().lines().stream())
                    .allMatch(line -> line.fields().isEmpty()));
        }
    }

    @Test void includedIterationListsActuallyContinueWithinTheirPrefixedIdentityAndKeepMainListsIndependent() {
        String part = "<numbered id='step' list-id='checks'>Step</numbered>";
        var document = compiler(part).compile(xml("<numbered id='first' list-id='checks'>Main</numbered>"
                + "<each items='items' as='item'>" + include("copy") + "<numbered id='legacy' list-id='checks'>Legacy</numbered></each>"
                + include("other") + "<numbered id='last' list-id='checks'>Main</numbered>"
                + "<p id='ref'>other=<number-reference target='other-step' width='20'/></p>"))
                .render(Map.of("items", List.of(1, 2)));
        var result = DocumentEngine.builder(regular).build().layout(document);
        String text = result.display().pages().stream().map(page -> logical(page.commands())).collect(Collectors.joining());
        assertEquals("1. Main1. Step2. Legacy2. Step3. Legacy1. Step4. Mainother=1", text);
        assertEquals(1, result.display().extras().links().size());
    }

    @Test void missingLocalConditionalTargetCannotResolveToTheMainDocumentsUnprefixedBlock() {
        String part = "<p id='ref'><page-reference target='hidden' width='20'/></p>"
                + "<if test='visible'><then><p id='hidden'>Hidden</p></then></if>";
        var source = compiler(part).compile(xml("<p id='hidden'>Main</p>" + include("copy"))).render(Map.of("visible", false));
        assertEquals("copy-hidden", source.paragraphs().getLast().fields().getFirst().field().targetId());
        assertThrows(IllegalArgumentException.class, () -> DocumentEngine.builder(regular).build().layout(source));
    }

    @Test void includedDeepImagesFieldsStyledInkAndWarningsKeepPrefixedSourcesAndFinalLinkBounds() throws IOException {
        var resource = image();
        String content = "A<span style='warn'>😀</span><link href='https://example.com/text'><span style='ink'>LINK</span></link>"
                + "<link href='https://example.com/image'><image ref='mark' width='8' height='9' alt='Marker'/></link>"
                + " P=<page-number width='18'/><page-reference target='leaf' width='18'/>";
        var source = XmlTemplate.builder().pageSettings(PAGE).theme(theme()).image("mark", resource).fragment("part", fragment(deep(content))).build()
                .compile(xml("<p id='prefix'>Prefix</p>" + include("copy"))).render(Map.of());
        var original = leaf((Table) source.blocks().getLast());
        assertEquals("copy-leaf", original.id()); assertEquals("A😀LINK\ufffc P=\ufffc\ufffc", original.text());
        assertEquals("copy-leaf", original.fields().getLast().field().targetId());
        var result = DocumentEngine.builder(regular).fontRole("report-bold", bold)
                .fontRole("warn-bold", FontGroup.placeholder(bold.fonts(), '?')).build().layout(source);
        var warnings = result.contextualDiagnostics().stream().filter(value -> value.diagnostic().code() == TextDiagnostic.Code.MISSING_GLYPH_REPLACED).toList();
        assertEquals(1, warnings.size()); var warning = warnings.getFirst();
        assertEquals("copy-leaf", warning.blockId()); assertEquals(0, warning.pageIndex()); assertEquals("TABLE", warning.region());
        assertEquals(path(), warning.sourcePath()); assertEquals(0, warning.rowIndex()); assertEquals(0, warning.columnIndex());
        assertEquals(1, warning.diagnostic().sourceStart()); assertEquals(3, warning.diagnostic().sourceEnd());
        assertEquals(0x1f600, warning.diagnostic().sourceCodePoint());
        assertEquals(bold.fonts().getFirst().data().id(), warning.diagnostic().fontId());
        var drawnLeaf = leaf(outer(result));
        assertEquals("copy-leaf", drawnLeaf.blockId()); assertTrue(logical(drawnLeaf.layout()).endsWith(" P=11"));
        assertEquals(13, drawnLeaf.layout().lines().getFirst().sourceEnd());
        assertEquals(7, drawnLeaf.layout().lines().getFirst().images().getFirst().sourceIndex());
        assertTrue(drawnLeaf.layout().lines().stream().allMatch(line -> line.fields().isEmpty()));
        var ink = new RgbColor(0x22 / 255.0, 0x44 / 255.0, 0x88 / 255.0);
        var marked = drawnLeaf.layout().lines().stream().flatMap(line -> line.outlines().stream())
                .filter(value -> value.rgb() == 0x224488).toList();
        assertEquals("LINK", marked.stream().map(com.mirakyux.export4j.text.TextOutline::logicalText).collect(Collectors.joining()));
        assertTrue(marked.stream().allMatch(value -> !value.segments().isEmpty()));
        var paintedInk = result.display().pages().stream().flatMap(page -> page.commands().stream()).filter(DrawCommand.FillPath.class::isInstance)
                .map(DrawCommand.FillPath.class::cast).filter(value -> value.color().equals(ink)).toList();
        assertEquals(marked.size(), paintedInk.size());
        var imageLinks = result.display().extras().externalLinks().stream().filter(link -> link.destination().uri().endsWith("/image")).toList();
        assertEquals(1, imageLinks.size()); assertEquals(paintedImages(result.display().pages().getFirst()), List.of(imageLinks.getFirst().bounds()));
        assertEquals(8, imageLinks.getFirst().bounds().width()); assertEquals(9, imageLinks.getFirst().bounds().height());
        assertTrue(result.display().extras().externalLinks().stream().anyMatch(link -> link.destination().uri().endsWith("/text") && link.bounds().width() > 0));
        assertEquals(1, result.display().extras().links().size()); assertEquals(0, result.display().extras().links().getFirst().targetPageIndex());
    }

    @Test void includedNamedStyleSelectsItsActualPhysicalFontAndRetainsDecorations() {
        var source = compiler("<p id='body' style='note'>A<span style='ink'>BOLD</span>C</p>")
                .compile(xml(include("copy"))).render(Map.of());
        var result = DocumentEngine.builder(regular).fontRole("report-bold", bold).build().layout(source);
        var runs = result.display().pages().stream().flatMap(page -> page.commands().stream()).filter(DrawCommand.GlyphText.class::isInstance)
                .map(DrawCommand.GlyphText.class::cast).toList();
        var marked = runs.stream().filter(value -> value.run().text().equals("BOLD")).findFirst().orElseThrow();
        assertEquals(bold.fonts().getFirst().data().id(), marked.run().font().id());
        assertEquals(10, marked.run().fontSize()); assertEquals(0x224488, marked.run().rgb());
        assertEquals(2, marked.run().baselineShift()); assertTrue(marked.run().underline()); assertTrue(marked.run().strikeThrough());
        for (var plain : runs.stream().filter(value -> !value.run().text().equals("BOLD")).toList()) {
            assertEquals(regular.fonts().getFirst().data().id(), plain.run().font().id());
            assertEquals(0, plain.run().rgb()); assertEquals(0, plain.run().baselineShift());
        }
        assertEquals("copy-body", result.body().pages().getFirst().fragments().getFirst().blockId());
    }

    @Test void includedMissingRoleAndGlyphFailuresIdentifyThePrefixedDeepLeafAndOriginalUtf16() {
        var unknown = included(deep("A<span style='ink'>SECRET</span>"));
        var missingRole = assertThrows(DocumentLayoutException.class, () -> DocumentEngine.builder(regular).build().layout(unknown));
        assertEquals(DocumentLayoutException.Code.TEXT_LAYOUT_FAILED, missingRole.code());
        assertEquals("copy-leaf", missingRole.blockId()); assertEquals(path(), missingRole.sourcePath());
        assertEquals(1, missingRole.sourceIndex()); assertFalse(missingRole.getMessage().contains("SECRET"));
        var unsupported = included(deep("A<span style='warn'>😀</span>"));
        var strict = assertThrows(DocumentLayoutException.class,
                () -> DocumentEngine.builder(regular).fontRole("warn-bold", bold).build().layout(unsupported));
        assertEquals(1, strict.sourceIndex()); assertEquals("copy-leaf", strict.blockId()); assertEquals(path(), strict.sourcePath());
        var cause = strict.getCause();
        while (!(cause instanceof TextLayoutException) && cause != null) { cause = cause.getCause(); }
        assertEquals(TextLayoutException.Code.MISSING_GLYPH, assertInstanceOf(TextLayoutException.class, cause).code());
    }

    @Test void includedDeepRichSubsetKeepsPrefixedIdsAndFieldTargetsThroughStrictDocxRoundTrip() throws IOException {
        String content = "A<span style='ink'>Bold</span><link href='https://example.com/report'>"
                + "<span style='ink' role='' color='#000000' underline='false' strike='false'>Black</span>"
                + "<image ref='mark' width='8' height='9' alt='Marker'/></link><page-reference target='leaf' width='18'/>";
        var source = XmlTemplate.builder().pageSettings(PAGE).theme(theme()).image("mark", image()).fragment("part", fragment(deep(content))).build()
                .compile(xml("<p id='prefix'>Prefix</p>" + include("copy"))).render(Map.of());
        var output = new ByteArrayOutputStream(); new DocxAdapter().write(source, output);
        var restored = new DocxAdapter().read(new ByteArrayInputStream(output.toByteArray()), DocxAdapter.Policy.STRICT);
        assertTrue(restored.diagnostics().isEmpty());
        var before = leaf((Table) source.blocks().getLast());
        var after = leaf((Table) restored.document().sections().getFirst().blocks().getLast());
        assertEquals("copy-leaf", after.id()); assertEquals(before.id(), after.id()); assertEquals(before.format(), after.format());
        assertEquals(before.text(), after.text()); assertEquals(before.inlines(), after.inlines());
        assertEquals(before.images(), after.images()); assertEquals(before.fields(), after.fields());
        assertEquals("copy-leaf", after.fields().getFirst().field().targetId());
    }

    @Test void includedDynamicTableHeadersResolveEachActualPageAndKeepSplitRowSourceOrder() {
        var page = PageSettings.builder().size(180, 80).margins(10).build();
        var selected = DocumentTheme.builder().bodyFormat(format -> format.fontSize(10).lineHeight(20).spaceAfter(0))
                .cellFormat(format -> format.fontSize(10).lineHeight(20))
                .tableFormat(format -> format.padding(0).spaceAfter(0).allowRowSplit(true)).build();
        String part = "<rows id='data' items='items' as='item'>" + COLUMNS
                + "<header><cell>HEAD<page-number width='15'/>/<page-count width='15'/></cell></header>"
                + "<row><cell>${item}</cell></row></rows>";
        var source = XmlTemplate.builder().pageSettings(page).theme(selected).fragment("part", fragment(part)).build()
                .compile(xml(include("copy"))).render(Map.of("items", List.of("A\nB\nC\nD")));
        var result = DocumentEngine.builder(regular).build().layout(source);
        assertEquals(2, result.body().pages().size());
        var fragments = result.body().pages().stream().flatMap(value -> value.elements().stream())
                .filter(TableFragment.class::isInstance).map(TableFragment.class::cast).toList();
        assertTrue(fragments.stream().allMatch(value -> value.blockId().equals("copy-data")));
        assertEquals(List.of("HEAD1/2", "HEAD2/2"), fragments.stream().map(value -> logical(value.rows().getFirst().cells().getFirst().layout())).toList());
        assertEquals(List.of("AB", "CD"), fragments.stream().map(value -> logical(value.rows().getLast().cells().getFirst().layout())).toList());
        assertEquals(List.of(0, 2), fragments.stream().map(value -> value.rows().getLast().cells().getFirst().firstLineIndex()).toList());
        assertTrue(fragments.getLast().rows().getFirst().repeatedHeader());
        assertTrue(fragments.stream().flatMap(value -> value.rows().stream()).flatMap(row -> row.cells().stream())
                .flatMap(cell -> cell.layout().lines().stream()).allMatch(line -> line.fields().isEmpty()));
    }

    @Test void cancellationDuringIncludedNestedLayoutRestoresScopeAndLeavesCompilerAndEngineReusable() {
        var compiler = compiler(deep("A<span style='ink'>BOLD</span><page-number width='18'/>"));
        var compiled = compiler.compile(xml("<p id='prefix'>Prefix</p>" + include("copy")));
        var source = compiled.render(Map.of());
        var engine = DocumentEngine.builder(regular).fontRole("report-bold", bold).build();
        var cancelled = new AtomicBoolean();
        var options = ExecutionOptions.builder().cancelled(cancelled::get).progress(progress -> {
            if (progress.stage().equals("BODY") && progress.blockId().equals("copy-outer")) { cancelled.set(true); }
        }).build();
        var failure = assertThrows(ExecutionException.class, () -> ExecutionContext.execute(options, () -> engine.layout(source)));
        assertEquals(ExecutionException.Code.CANCELLED, failure.code()); assertEquals("copy-outer", failure.context().blockId());
        ExecutionContext.checkpoint(); assertEquals(source, compiled.render(Map.of()));
        assertEquals("ABOLD1", logical(leaf(outer(engine.layout(source))).layout()));
    }

    private static List<Rect> paintedImages(DisplayPage page) {
        var result = new ArrayList<Rect>(); var offsets = new ArrayDeque<Point>(); Point offset = new Point(0, 0);
        for (var command : page.commands()) {
            switch (command) {
                case DrawCommand.Save ignored -> offsets.push(offset);
                case DrawCommand.Restore ignored -> offset = offsets.pop();
                case DrawCommand.Transform transform -> offset = new Point(offset.x() + transform.matrix().e(), offset.y() + transform.matrix().f());
                case DrawCommand.Image image -> result.add(new Rect(offset.x() + image.bounds().x(), offset.y() + image.bounds().y(),
                        image.bounds().width(), image.bounds().height()));
                default -> { }
            }
        }
        return result;
    }
    private static ImageResource image() throws IOException {
        var output = new ByteArrayOutputStream();
        if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output)) { throw new IOException("PNG encoder unavailable"); }
        return ImageResource.load(output.toByteArray());
    }
}
