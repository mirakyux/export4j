// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.ComponentContext;
import com.mirakyux.export4j.layout.core.FlowRegions;
import com.mirakyux.export4j.layout.core.RegionFlow;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ManuWeaveFlowTest {
    private static ManuWeaveFonts fonts;

    @BeforeAll static void loadFonts() throws IOException { fonts = new ManuWeaveFonts(); }

    private static ComponentContext context(double height) {
        return new ComponentContext(160, height, fonts.group(false, false, false), 0, 0, 1);
    }

    private static FlowText text(String value) {
        return new FlowText(value, ParagraphStyle.of(10).toBuilder().lineHeight(14).build(),
                RgbColor.BLACK, PaginationPolicy.BALANCED);
    }

    @Test void aHeadingAndFollowingParagraphMoveTogetherPastAShortInitialRegion() {
        var flow = new ManuWeaveFlow();
        flow.add(text("HEADING"), true);
        flow.add(text("BODY ONE\nBODY TWO"));
        var parts = flow.build().layoutRegions(context(100), new FlowRegions(List.of(20.0), 100));
        assertEquals(List.of(1), parts.stream().map(RegionFlow.Fragment::regionIndex).toList());
        assertEquals(3, parts.getFirst().layout().textLines());
        assertEquals("HEADINGBODY ONEBODY TWO", visible(parts.getFirst().layout()));
    }

    @Test void bodyContinuationLeavesAtLeastTwoLinesOnEachSideOfAnActualBreak() {
        var flow = new ManuWeaveFlow();
        flow.add(text("ONE\nTWO\nTHREE\nFOUR\nFIVE"));
        var parts = flow.build().layoutRegions(context(56), new FlowRegions(List.of(), 56));
        assertEquals(List.of(3, 2), parts.stream().map(part -> part.layout().textLines()).toList());
        assertEquals("ONETWOTHREEFOURFIVE", parts.stream().map(part -> visible(part.layout())).reduce("", String::concat));
    }

    @Test void anOverheightKeptGroupFailsInsteadOfSplittingOrClipping() {
        var group = ManuWeaveFlow.atomic(List.of(text("ONE\nTWO\nTHREE\nFOUR")), "test figure and caption");
        var failure = assertThrows(IllegalArgumentException.class, () -> group.layout(context(42)));
        assertTrue(failure.getMessage().contains("kept group exceeds a fresh column"));
    }

    @Test void aKeptCaptionAndTableRetainTheDeferredSemanticTableSource() {
        var table = new Table("kept-table", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(new TableCell("CELL", new CellFormat(10, 14, ParagraphAlignment.LEFT))))),
                0, TableFormat.builder().padding(2).spaceAfter(0).build());
        var children = FlowColumn.builder().add(text("CAPTION")).semanticTable(table).build();
        var result = ManuWeaveFlow.atomic(List.of(children), "caption and semantic table").layout(context(100));
        assertEquals(1, result.semanticPlacements().size());
        assertEquals(table, result.semanticPlacements().getFirst().source());
        assertEquals(1, result.semanticPlacements().getFirst().fragmentCount());
    }

    @Test void runningRegionsReserveSourceDistancesAndKeepFirstEvenAndFinalFieldsExplicit() {
        var properties = new Properties();
        properties.setProperty("id", "running-test");
        properties.setProperty("page.header_distance_mm", "10");
        properties.setProperty("page.footer_distance_mm", "5");
        properties.setProperty("running.header.size_pt", "8");
        properties.setProperty("running.header.line_spacing_pt", "12");
        properties.setProperty("running.header.font", "Times New Roman");
        properties.setProperty("running.header.bold", "true");
        properties.setProperty("running.footer.size_pt", "8");
        properties.setProperty("running.footer.line_spacing_pt", "12");
        properties.setProperty("running.footer.font", "Arial");
        properties.setProperty("running.footer.italic", "true");
        properties.setProperty("sections.different_first_page", "true");
        properties.setProperty("sections.different_odd_even_headers", "true");
        var profile = new ManuWeaveProfile(properties);
        var source = new PageSettings(new PageSize(400, 500), PageMargins.uniform(20));
        var actual = ManuWeaveRunning.configure(profile, fonts, source, "sections.body_page.", "effective.page.");
        assertEquals(20, source.margins().top());
        assertEquals(10 * ManuWeaveProfile.MM + 12, actual.settings().margins().top(), 1e-8);
        assertEquals(5 * ManuWeaveProfile.MM + 12, actual.settings().margins().bottom(), 1e-8);
        assertEquals(PageRegions.Bands.EMPTY, actual.regions().variants().get(PageRegions.Variant.FIRST));
        assertFalse(actual.regions().header().equals(actual.regions().variants().get(PageRegions.Variant.EVEN).header()));
        var footer = (Paragraph) actual.regions().footer().blocks().getFirst();
        var header = (Paragraph) actual.regions().header().blocks().getFirst();
        assertEquals("mw-running-header", header.inlines().getFirst().style().fontRole());
        assertEquals("mw-running-footer", footer.inlines().getFirst().style().fontRole());
        var roles = ManuWeaveRunning.fontRoles(profile, fonts);
        assertEquals(java.util.Set.of("mw-running-header", "mw-running-footer"), roles.keySet());
        assertFalse(roles.get("mw-running-header").fonts().getFirst().data().id()
                .equals(roles.get("mw-running-footer").fonts().getFirst().data().id()));
        assertEquals(List.of(InlineField.Kind.PAGE, InlineField.Kind.PAGES), footer.fields().stream()
                .map(field -> field.field().kind()).toList());
        assertEquals("" + actual.settings().margins().top(), profile.report.getProperty("effective.page.margin-top"));
    }

    @Test void compactPublisherFooterReservesTheInlineFieldAscentAsWellAsThePhysicalFontDescent() throws IOException {
        var profile = ManuWeaveProfile.load("acm-sigconf");
        var actual = ManuWeaveRunning.configure(profile, fonts, profile.bodyPage,
                "sections.body_page.", "effective.page.");
        assertTrue(actual.regions().footer().height() > profile.number("running.footer.line_spacing_pt", 0));
        var engine = DocumentEngine.builder(fonts.group(false, false, false));
        ManuWeaveRunning.fontRoles(profile, fonts).forEach(engine::fontRole);
        var source = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("footer-regression", actual.settings(),
                        List.of(new Paragraph("body", "BODY", ParagraphFormat.of(10))), 0, actual.regions())));
        assertDoesNotThrow(() -> engine.build().layout(source));
    }

    @Test void anonymousCatalogExamplesWithholdMetadataAndAuthorsOnTheActualEvenPage() throws IOException {
        for (String id : List.of("acm-manuscript-review-anonymous", "acm-sigconf-review-anonymous",
                "comp-tacl-2021-submission")) {
            var profile = ManuWeaveProfile.load(id);
            var source = new ManuWeaveSample(profile, fonts).document();
            assertEquals("", source.metadata().author(), id);
            if (!id.equals("acm-sigconf-review-anonymous")) { continue; }
            var engine = DocumentEngine.builder(fonts.group(false, false, false));
            ManuWeaveRunning.fontRoles(profile, fonts).forEach(engine::fontRole);
            var layout = engine.build().layout(source);
            var output = ManuWeaveReview.decorate(profile, fonts, layout, source);
            assertEquals("", output.metadata().author());
            assertTrue(output.pages().size() >= 2);
            var evenPageText = output.pages().get(1).commands().stream()
                    .map(ManuWeaveFlowTest::visible).reduce("", String::concat);
            assertTrue(evenPageText.contains("Anonymous authors"));
            for (var page : output.pages()) {
                var text = page.commands().stream().map(ManuWeaveFlowTest::visible).reduce("", String::concat);
                assertFalse(text.contains("Alex Example and Morgan Sample"));
            }
        }
    }

    @Test void sourceEquationAlignmentKeepsNaturalVectorGeometryCenteredInsideAnAtomicGroup() {
        var facts = new Properties();
        facts.setProperty("id", "equation-alignment-fixture");
        facts.setProperty("role.equation.alignment", "right");
        facts.setProperty("extracted_format.equations.alignment.value", "center");
        var profile = new ManuWeaveProfile(facts);
        var formula = ManuWeaveSample.alignedEquation(profile, fonts, "\\frac{a+b}{c+d}");
        for (double width : List.of(160.0, 240.0)) {
            var group = ManuWeaveFlow.atomic(List.of(formula), "centered equation");
            var layout = group.layout(new ComponentContext(width, 100, fonts.group(false, false, false), 0, 0, 1));
            var translations = layout.commands().stream().filter(DrawCommand.Transform.class::isInstance)
                    .map(DrawCommand.Transform.class::cast).mapToDouble(value -> value.matrix().e()).sum();
            var outline = layout.commands().stream().filter(DrawCommand.OutlineText.class::isInstance)
                    .map(DrawCommand.OutlineText.class::cast).findFirst().orElseThrow();
            assertEquals(width / 2, translations + outline.logicalBounds().x() + outline.logicalBounds().width() / 2, 1e-8);
            assertEquals("\\frac{a+b}{c+d}", outline.logicalText());
            assertFalse(outline.paths().isEmpty());
        }
        assertEquals("CENTER", profile.report.getProperty("effective.equation.alignment"));
        assertEquals("extracted_format.equations.alignment.value", profile.report.getProperty("effective.equation.alignment-source"));
    }

    private static String visible(ComponentLayout layout) {
        return layout.commands().stream().map(ManuWeaveFlowTest::visible).reduce("", String::concat);
    }

    private static String visible(DrawCommand command) {
        return switch (command) {
            case DrawCommand.Text text -> text.text();
            case DrawCommand.GlyphText text -> text.run().text();
            case DrawCommand.OutlineText text -> text.logicalText();
            default -> "";
        };
    }
}
