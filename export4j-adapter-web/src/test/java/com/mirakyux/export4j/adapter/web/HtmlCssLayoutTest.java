// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.web;

import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.ComponentSemantics;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlCssLayoutTest {
    @Test void borderlessTablesRetainRealHtmlCssAndMarkdownCannotHideTheirLoss() throws Exception {
        var document = new HtmlImporter().read("<table><thead><tr><th style='border:none;font-weight:normal'>HEADER</th></tr></thead><tbody><tr><td style='border:none'>BODY</td></tr></tbody></table>").execute().document();
        assertFalse(((Table) document.blocks().getFirst()).format().drawBorders());
        var html = new ByteArrayOutputStream(); new HtmlAdapter().export(document).to(html);
        assertTrue(html.toString(StandardCharsets.UTF_8).contains("border:none"));
        var failure = assertThrows(ProcessingException.class, () -> DocumentConversion.from(document).prepare(new MarkdownAdapter().conversionTarget())); assertEquals("TABLE_GEOMETRY", failure.issue().feature());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).feature("HTML_METADATA", action, ProcessingPolicy.Treatment.DISCARD).build();
            var reopened = HtmlImporter.builder().policy(policy).build().read(html.toString(StandardCharsets.UTF_8)).execute();
            assertFalse(((Table) reopened.document().blocks().getFirst()).format().drawBorders());
            var bytes = new ByteArrayOutputStream(); var result = DocumentConversion.from(document).policy(policy).prepare(new MarkdownAdapter().conversionTarget()).to(bytes);
            assertTrue(result.report().losses().stream().anyMatch(issue -> issue.feature().equals("TABLE_GEOMETRY"))); assertEquals(action == ProcessingPolicy.Action.IGNORE, result.report().warnings().isEmpty());
        }
    }
    @Test void staticSelectorsCascadeAttributesSiblingsFunctionsAndChildFormulas() throws Exception {
        String css = "p{color:black}:where(#a){color:red}.x + p[data-x='ok']{color:blue}"
                + "p:nth-child(2n+1){font-weight:bold}p:not(.skip){font-style:italic}"
                + ".x ~ p:last-child{color:green}p:is(#a,[class='other']){font-size:14pt}";
        var policy = ProcessingPolicy.builder().feature("HTML_ATTRIBUTE", ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build();
        var result = HtmlImporter.builder().policy(policy).build().read("<style>" + css + "</style><p id='a' class='x'>A</p><p data-x='ok'>B</p><p class='skip'>C</p>").execute();
        var a = (Paragraph) result.document().blocks().get(0); var b = (Paragraph) result.document().blocks().get(1); var c = (Paragraph) result.document().blocks().get(2);
        assertEquals(0, a.inlines().getFirst().style().rgb()); assertEquals(14, a.format().fontSize()); assertEquals("boldItalic", a.inlines().getFirst().style().fontRole());
        assertEquals(0x0000ff, b.inlines().getFirst().style().rgb()); assertEquals(0x008000, c.inlines().getFirst().style().rgb()); assertEquals("bold", c.inlines().getFirst().style().fontRole());
    }
    @Test void explicitMediaAndPageRulesAffectActualSettingsWithoutViewportFeedback() throws Exception {
        String source = "<style>@page{size:300pt 400pt;margin:10pt 20pt}@media print and (min-width:500pt){p{font-size:16pt}}@media screen{p{color:red}}@media (orientation:landscape){p{font-style:italic}}</style><p>A</p>";
        var print = new HtmlImporter().read(source).execute().document(); assertEquals(300, print.pageSettings().size().width()); assertEquals(260, print.pageSettings().contentWidth()); assertEquals(16, ((Paragraph) print.blocks().getFirst()).format().fontSize());
        var screen = HtmlImporter.builder().media(HtmlImporter.Media.SCREEN).viewport(new PageSize(600, 400)).build().read(source).execute().document();
        assertEquals(PageSize.A4, screen.pageSettings().size()); var p = (Paragraph) screen.blocks().getFirst(); assertEquals(0xff0000, p.inlines().getFirst().style().rgb()); assertEquals("italic", p.inlines().getFirst().style().fontRole());
    }
    @Test void flexGrowShrinkClampsAndSourceOrderHaveRealGeometry() throws Exception {
        var grow = geometry("display:flex;width:300pt;height:60pt;gap:10pt", "<p id='a' style='flex:1 1 50pt'>A</p><p id='b' style='flex:2 1 50pt'>B</p>");
        assertEquals(113.333333, grow.get("a")[2], 1e-5); assertEquals(123.333333, grow.get("b")[0], 1e-5); assertEquals(176.666667, grow.get("b")[2], 1e-5);
        var shrink = geometry("display:flex;width:200pt;height:60pt", "<p id='a' style='flex:0 1 150pt'>A</p><p id='b' style='flex:0 3 150pt'>B</p>");
        assertEquals(125, shrink.get("a")[2], 1e-6); assertEquals(75, shrink.get("b")[2], 1e-6);
        var clamp = geometry("display:flex;width:300pt;height:60pt", "<p id='a' style='flex:1;max-width:100pt'>A</p><p id='b' style='flex:1'>B</p>");
        assertEquals(100, clamp.get("a")[2], 1e-6); assertEquals(200, clamp.get("b")[2], 1e-6);
        var reverse = geometry("display:flex;flex-direction:row-reverse;width:200pt;height:60pt", "<p id='a' style='flex:1'>A</p><p id='b' style='flex:1'>B</p>");
        assertEquals(100, reverse.get("a")[0], 1e-6); assertEquals(0, reverse.get("b")[0], 1e-6);
    }
    @Test void flexWrappingAndColumnUseExplicitMainAxisDimensions() throws Exception {
        var wrap = geometry("display:flex;flex-wrap:wrap;width:200pt;height:80pt;gap:10pt", "<p id='a' style='flex:0 0 90pt;height:20pt'>A</p><p id='b' style='flex:0 0 90pt;height:20pt'>B</p><p id='c' style='flex:0 0 90pt;height:20pt'>C</p>");
        assertEquals(100, wrap.get("b")[0], 1e-6); assertEquals(45, wrap.get("c")[1], 1e-6);
        var start = geometry("display:flex;flex-wrap:wrap;align-content:flex-start;width:200pt;height:80pt;gap:10pt", "<p id='a' style='flex:0 0 90pt;height:20pt'>A</p><p id='b' style='flex:0 0 90pt;height:20pt'>B</p><p id='c' style='flex:0 0 90pt;height:20pt'>C</p>");
        assertEquals(30, start.get("c")[1], 1e-6);
        var column = geometry("display:flex;flex-direction:column;width:200pt;height:100pt;gap:10pt", "<p id='a' style='flex:1'>A</p><p id='b' style='flex:1'>B</p>");
        assertEquals(55, column.get("b")[1], 1e-6);
    }
    @Test void gridFixedFractionalTracksGapsAndSpansChangeActualLeafBounds() throws Exception {
        var grid = geometry("display:grid;width:300pt;grid-template-columns:80pt 1fr 2fr;grid-template-rows:40pt 40pt;column-gap:10pt;row-gap:5pt", "<p id='a' style='grid-column:1 / span 2'>A</p><p id='b'>B</p><p id='c' style='grid-column:span 3'>C</p>");
        assertEquals(156.666667, grid.get("a")[2], 1e-5); assertEquals(166.666667, grid.get("b")[0], 1e-5); assertEquals(45, grid.get("c")[1], 1e-5); assertEquals(300, grid.get("c")[2], 1e-5);
        var spans = geometry("display:grid;width:200pt;grid-template-columns:repeat(2,1fr);grid-template-rows:30pt 30pt;gap:5pt", "<p id='a' style='grid-row:1 / span 2;grid-column:1'>A</p><p id='b' style='grid-column:2;grid-row:1'>B</p><p id='c' style='grid-column:2;grid-row:2'>C</p>");
        assertEquals(102.5, spans.get("b")[0], 1e-6); assertEquals(35, spans.get("c")[1], 1e-6);
        var reserved = geometry("display:grid;width:200pt;grid-template-columns:repeat(2,1fr);grid-template-rows:30pt;gap:5pt", "<p id='a'>A</p><p id='b' style='grid-column:1;grid-row:1'>B</p>");
        assertEquals(102.5, reserved.get("a")[0], 1e-6); assertEquals(0, reserved.get("b")[0], 1e-6);
    }
    @Test void boxSizingPaddingAndPositioningRemainInsideTheirContainingBox() throws Exception {
        var result = geometry("position:relative;width:300pt;height:100pt;padding:10pt;box-sizing:border-box", "<p id='a' style='width:50pt;height:20pt;position:relative;left:5pt;top:3pt'>A</p><p id='b' style='position:absolute;right:20pt;bottom:15pt;width:50pt;height:20pt'>B</p>");
        assertEquals(15, result.get("a")[0], 1e-6); assertEquals(13, result.get("a")[1], 1e-6); assertEquals(230, result.get("b")[0], 1e-6); assertEquals(65, result.get("b")[1], 1e-6);
        var percentage = geometry("position:relative;width:300pt;height:100pt;padding:10pt;box-sizing:border-box", "<p id='a' style='position:relative;left:10%;top:10%;width:50pt;height:20pt'>A</p>");
        assertEquals(38, percentage.get("a")[0], 1e-6); assertEquals(18, percentage.get("a")[1], 1e-6);
        assertThrows(IllegalArgumentException.class, () -> geometry("position:relative;width:100pt;height:50pt", "<p style='position:absolute;left:90pt;top:0;width:30pt'>A</p>"));
        assertThrows(IllegalArgumentException.class, () -> geometry("position:relative;width:100pt;height:50pt", "<p style='width:50pt;height:20pt'>A</p><p style='position:absolute;left:0;top:0;width:50pt;height:20pt'>B</p>"));
    }
    @Test void pageBreaksAndWidowsOrphansBecomeSemanticConstraints() throws Exception {
        var document = new HtmlImporter().read("<p>A</p><p style='break-before:page;break-after:page;break-inside:avoid;orphans:2;widows:3'>B</p><p>C</p>").execute().document();
        assertTrue(document.blocks().get(1) instanceof PageBreak); var paragraph = (Paragraph) document.blocks().get(2); assertTrue(paragraph.format().keepTogether()); assertEquals(2, paragraph.format().pagination().minLinesBeforeBreak()); assertEquals(3, paragraph.format().pagination().minLinesAfterBreak()); assertTrue(document.blocks().get(3) instanceof PageBreak);
    }
    @Test void unsupportedLayoutFallbackKeepsActualLossesForWarnAndIgnore() throws Exception {
        String source = "<div style='display:flex'><p>A</p></div>";
        assertThrows(ProcessingException.class, () -> HtmlImporter.builder().layoutMode(true).build().read(source).execute());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().defaultRule(action, ProcessingPolicy.Treatment.DEGRADE).build(); var request = HtmlImporter.builder().layoutMode(true).policy(policy).build().read(source).prepare();
            var result = request.execute(); assertEquals(request.preview().losses(), result.report().losses()); assertEquals("CSS_LAYOUT", result.report().losses().getFirst().feature()); assertEquals(action == ProcessingPolicy.Action.IGNORE, result.report().warnings().isEmpty());
            var grid = geometry("display:grid;width:200pt;height:100pt;justify-content:center", "<p id='a'>A</p>", policy);
            assertEquals(18, grid.get("a")[3], 1e-6);
            var absolute = geometry("position:relative;width:200pt;height:100pt", "<p id='a' style='position:absolute;width:50pt;height:20pt'>A</p>", policy);
            assertEquals(0, absolute.get("a")[0], 1e-6); assertEquals(0, absolute.get("a")[1], 1e-6);
        }
        assertThrows(ProcessingException.class, () -> new HtmlImporter().read("<p style='width:40pt'>A</p>").execute());
        assertThrows(ProcessingException.class, () -> HtmlImporter.builder().layoutMode(true).build().read("<p><span style='padding:2pt'>A</span></p>").execute());
        for (String unsupported : List.of("<p><span style='display:block'>A</span></p>", "<p style='display:inline'>A</p>", "<p style='display:flex'>A</p>", "<p style='height:50%'>A</p>", "<div style='padding:1pt'><ul><li>A</li></ul></div>", "<div style='display:grid;justify-items:center'><p>A</p></div>", "<p style='position:relative;left:5pt'>A</p>")) {
            assertThrows(ProcessingException.class, () -> HtmlImporter.builder().layoutMode(true).build().read(unsupported).execute());
        }
        assertThrows(ProcessingException.class, () -> geometry("display:grid;width:200pt;height:100pt", "<p>A</p>"));
        assertThrows(ProcessingException.class, () -> geometry("display:flex;width:200pt", "<p style='flex:0.5 1 50pt'>A</p>"));
    }
    @Test void invalidActiveHiddenAndOverbudgetCssCannotBeIgnored() throws Exception {
        var policy = ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE).build();
        for (String css : List.of("@media print{p{background:url(https://example.com/x)}}", "p{width:expression(1)}", "p{font-family:'bad\\\\name'}")) { assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().policy(policy).build().read("<style>" + css + "</style><p>A</p>").execute()); }
        assertThrows(IllegalArgumentException.class, () -> geometry("display:grid;grid-template-columns:repeat(99,1fr)", "<p>A</p>"));
        assertThrows(IllegalArgumentException.class, () -> geometry("width:100pt;height:10pt", "<p>A</p>"));
        var budget = new ImportLimits(4000, 4000, 10, 20, 4000, 100, 100, 100000);
        var exceeded = assertThrows(IllegalArgumentException.class, () -> HtmlImporter.builder().layoutMode(true).policy(policy).limits(budget).build().read("<p style='" + "margin:0;".repeat(4) + "'>A</p>").execute());
        assertTrue(exceeded.getMessage().contains("budget"));
    }
    private static Map<String, double[]> geometry(String style, String children) throws Exception {
        return geometry(style, children, ProcessingPolicy.STRICT);
    }
    private static Map<String, double[]> geometry(String style, String children, ProcessingPolicy policy) throws Exception {
        var document = HtmlImporter.builder().layoutMode(true).policy(policy).build().read("<style>p{margin:0}</style><div style='" + style + "'>" + children + "</div>").execute().document();
        var block = (ComponentBlock) document.blocks().getFirst();
        try (var input = HtmlCssLayoutTest.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            var font = TrueTypeFontLoader.load(input.readAllBytes()); var context = new ComponentContext(400, 400, List.of(font), 0, 1); var measured = block.component().layout(context); var result = new HashMap<String, double[]>();
            for (var located : ComponentSemantics.locate(measured, new Point(0, 0))) {
                var source = located.placement().source(); var id = source.rows().getFirst().cells().getFirst().blocks().getFirst().id(); var fragment = located.placement().fragment();
                result.put(id, new double[] {located.translation().x() + fragment.origin().x(), located.translation().y() + fragment.origin().y(), fragment.width(), fragment.height()}); assertFalse(source.format().drawBorders());
            }
            return result;
        }
    }
}
