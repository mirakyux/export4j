// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.graphics.AccessibilityTree;
import com.mirakyux.export4j.graphics.AffineTransform;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.graphics.DocumentExtras;
import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.Point;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.ComponentFragment;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.DocumentLayout;
import com.mirakyux.export4j.layout.LayoutPage;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.text.ParagraphLayouter;
import com.mirakyux.export4j.text.ParagraphStyle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class ManuWeaveReviewTest {
    private static final PageSize SIZE = new PageSize(400, 500);
    private static final PageSettings SETTINGS = new PageSettings(SIZE, new PageMargins(40, 60, 40, 40));
    private static final DocumentMetadata METADATA = new DocumentMetadata("Review fixture", "Export4j tests");
    private static ManuWeaveFonts fonts;

    @BeforeAll static void loadFonts() throws IOException { fonts = new ManuWeaveFonts(); }

    @Test void numbersUseActualTransformedBaselinesAndMergeRunsOnTheSameLine() {
        var run = new ParagraphLayouter().layout("BODY", fonts.group(false, false, false), 200,
                ParagraphStyle.of(10)).lines().getFirst().runs().getFirst();
        var component = new ComponentLayout(200, 100, 2, List.of(new DrawCommand.Save(),
                new DrawCommand.Transform(AffineTransform.translation(5, 10)),
                new DrawCommand.GlyphText(run, new Point(0, 20), RgbColor.BLACK),
                new DrawCommand.GlyphText(run, new Point(30, 20), RgbColor.BLACK),
                new DrawCommand.GlyphText(run, new Point(0, 40), RgbColor.BLACK),
                new DrawCommand.OutlineText("\\frac{a}{b}", List.of(), RgbColor.BLACK, new Rect(0, 55, 20, 20)),
                new DrawCommand.Restore()), List.of());
        var source = new SectionedDocument(METADATA, List.of(new Section("review", SETTINGS,
                List.of(new ComponentBlock("body", context -> component, 0, 0)), 0)));
        var page = new LayoutPage(0, SIZE, new Rect(40, 40, 300, 420),
                List.of(new ComponentFragment("body", 0, new Point(40, 40), component)));
        var layout = new ComponentDocumentLayout(new DocumentLayout(METADATA, List.of(page)), 0,
                new DisplayDocument(METADATA, List.of(new DisplayPage(SIZE, component.commands()))), List.of(), List.of());
        var profile = profile("comp-jasa-word", false);
        var output = ManuWeaveReview.decorate(profile, fonts, layout, source);
        var labels = output.pages().getFirst().commands().subList(component.commands().size(), output.pages().getFirst().commands().size())
                .stream().filter(DrawCommand.GlyphText.class::isInstance).map(DrawCommand.GlyphText.class::cast).toList();
        assertEquals(List.of("1", "2"), labels.stream().map(label -> label.run().text()).toList());
        assertEquals(List.of(70.0, 90.0), labels.stream().map(label -> label.baseline().y()).toList());
        assertEquals("2", profile.report.getProperty("effective.line-number-count"));
        assertEquals("90.0", profile.report.getProperty("effective.line-number.2.baseline"));
        assertTrue(labels.stream().allMatch(label -> label.baseline().x() + label.run().width() <= 34 + 1e-6));
    }

    @Test void actualBodyTextIsNumberedWithoutRunningTextOrSemanticTableCells() {
        var table = new Table("table", List.of(new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(new TableCell("CELL ONE\nCELL TWO", new CellFormat(10, 14, ParagraphAlignment.LEFT))))),
                0, TableFormat.builder().padding(2).spaceAfter(0).build());
        var flow = FlowColumn.builder().add(new FlowText("BODY ONE\nBODY TWO", ParagraphStyle.of(10), RgbColor.BLACK, PaginationPolicy.BALANCED))
                .semanticTable(table).add(new FlowText("BODY THREE", ParagraphStyle.of(10), RgbColor.BLACK, PaginationPolicy.BALANCED)).build();
        var regions = PageRegions.builder().header(new PageRegion(15, 0,
                List.of(new Paragraph("header", "RUNNING HEADER", ParagraphFormat.of(8))))).build();
        var source = new SectionedDocument(METADATA, List.of(new Section("review", SETTINGS,
                List.of(new ComponentBlock("body", flow, 0, 0)), 0, regions), section("continuation", 0)));
        var layout = DocumentEngine.builder(fonts.group(false, false, false)).build().layout(source);
        assertTrue(layout.body().pages().stream().flatMap(page -> page.elements().stream())
                .filter(ComponentFragment.class::isInstance).map(ComponentFragment.class::cast)
                .anyMatch(fragment -> !fragment.layout().semanticPlacements().isEmpty()));
        var profile = profile("comp-jslhr-word", false);
        ManuWeaveReview.decorate(profile, fonts, layout, source);
        assertEquals("4", profile.report.getProperty("effective.line-number-count"));
        assertEquals("3", profile.report.getProperty("effective.physical.page.1.line-number-count"));
        assertEquals("2", profile.report.getProperty("effective.line-number.4.page"));
    }

    @Test void mirroredMarginsFollowDisplayedPageNumbersAndPreserveBodyIr() {
        var sections = new ArrayList<Section>();
        sections.add(section("first", 0));
        sections.add(section("second", 0));
        sections.add(section("third", 4));
        sections.add(section("fourth", 7));
        var source = new SectionedDocument(METADATA, sections);
        var layout = DocumentEngine.builder(fonts.group(false, false, false)).build().layout(source);
        var profile = profile("mirror-fixture", true);
        var output = ManuWeaveReview.decorate(profile, fonts, layout, source);
        assertEquals(layout.display().pages().getFirst(), output.pages().getFirst());
        assertEquals(layout.display().pages().get(3), output.pages().get(3));
        for (int index : List.of(1, 2)) {
            var commands = output.pages().get(index).commands();
            assertEquals(new DrawCommand.Save(), commands.getFirst());
            assertEquals(new DrawCommand.Transform(AffineTransform.translation(20, 0)), commands.get(1));
            assertEquals(layout.display().pages().get(index).commands(), commands.subList(2, commands.size() - 1));
            assertEquals(new DrawCommand.Restore(), commands.getLast());
        }
        assertEquals("60.0", profile.report.getProperty("effective.physical.page.2.margin-left"));
        assertEquals("40.0", profile.report.getProperty("effective.physical.page.2.margin-right"));
        assertEquals("4", profile.report.getProperty("effective.physical.page.3.displayed-number"));
        assertEquals("7", profile.report.getProperty("effective.physical.page.4.displayed-number"));
        assertEquals(40, layout.body().pages().get(1).contentBounds().x());
        assertEquals(layout.display().metadata(), output.metadata());
    }

    @Test void decorationRejectsInteractionsInsteadOfLeavingTheirGeometryBehind() {
        var source = new SectionedDocument(METADATA, List.of(section("first", 0)));
        var layout = DocumentEngine.builder(fonts.group(false, false, false)).build().layout(source);
        var extras = new DocumentExtras(List.of(new DocumentExtras.Bookmark("Target", 1, 0, new Point(40, 40))),
                List.of(), List.of(), List.of());
        var bound = new DisplayDocument(METADATA, layout.display().pages(), extras);
        var fixture = new ComponentDocumentLayout(layout.body(), 0, bound, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ManuWeaveReview.decorate(profile("mirror-fixture", true), fonts, fixture, source));
    }

    @Test void decorationRejectsAccessibilityCommandBindingsInsteadOfInvalidatingThem() {
        var source = new SectionedDocument(METADATA, List.of(section("first", 0)));
        var layout = DocumentEngine.builder(fonts.group(false, false, false)).build().layout(source);
        var page = new DisplayPage(SIZE, List.of(new DrawCommand.FillRect(new Rect(40, 40, 10, 10), RgbColor.BLACK)));
        var tree = new AccessibilityTree("en", List.of(new AccessibilityTree.Node("body", AccessibilityTree.Role.P,
                List.of(new AccessibilityTree.CommandRange(0, 0, 1)), List.of())), List.of());
        var bound = new DisplayDocument(METADATA, List.of(page), DocumentExtras.EMPTY, tree);
        var fixture = new ComponentDocumentLayout(layout.body(), 0, bound, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> ManuWeaveReview.decorate(profile("mirror-fixture", true), fonts, fixture, source));
    }

    private static Section section(String id, int start) {
        return new Section(id, SETTINGS, List.of(new ComponentBlock(id + "-body",
                new FlowText(id, ParagraphStyle.of(10), RgbColor.BLACK, PaginationPolicy.BALANCED), 0, 0)), start);
    }

    private static ManuWeaveProfile profile(String id, boolean mirrored) {
        var properties = new Properties();
        properties.setProperty("id", id);
        properties.setProperty("page.mirror_margins", Boolean.toString(mirrored));
        return new ManuWeaveProfile(properties);
    }
}
