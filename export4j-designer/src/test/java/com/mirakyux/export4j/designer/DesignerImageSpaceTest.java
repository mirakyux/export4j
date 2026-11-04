// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DesignerImageSpaceTest {
    private static final String MARKER = "<p id='image-marker' size='12' line-height='18' space-before='0' space-after='0'/>";

    @Test void gridAppendUsesItsOwnContentAndTrailingSpacing() throws Exception {
        var space = space("<p line-height='20'>EXISTING</p>" + MARKER,
                "<p line-height='100'>TALLER SIBLING</p>", "{}", "");
        // 160 pt body - 20 pt line - 6 pt trailing spacing leaves a 134 pt image line.
        assertEquals(174, space.width(), 0.000001);
        assertEquals(134, space.height(), 0.000001);
    }

    @Test void gridMiddleInsertionRetainsTheExistingTwoSidedSpacing() throws Exception {
        var space = space("<p line-height='20'>FIRST</p>" + MARKER
                + "<p line-height='20' space-before='7' space-after='3'>LAST</p>", "<p>RIGHT</p>", "{}", "");
        // Both original lines and their 6 + 7 pt inter-paragraph spacing still consume 53 pt.
        assertEquals(107, space.height(), 0.000001);
    }

    @Test void gridRejectsBeforeRegistrationWhenEvenTheMinimumImageLineDoesNotFit() throws Exception {
        var space = space("<p line-height='150'>FULL</p>" + MARKER, "<p>RIGHT</p>", "{}", "");
        assertEquals(0, space.width());
        assertEquals(0, space.height());
    }

    @Test void gridRepeatedInsertionDividesTheRealRemainingHeightAcrossEveryOccurrence() throws Exception {
        var space = space("<each items='values' as='value'><p line-height='20'>${value}</p>" + MARKER + "</each>",
                "<p>RIGHT</p>", "{\"values\":[\"A\",\"B\"]}", "");
        // Two 20 pt text lines and two 6 pt gaps leave two image lines of 54 pt each.
        assertEquals(54, space.height(), 0.000001);
    }

    @Test void gridProbeKeepsReferencesToTheRestOfTheDocumentAndFreshPageCapacity() throws Exception {
        var space = space("<p line-height='20'><page-reference target='outside' width='18'/></p>" + MARKER,
                "<p>RIGHT</p>", "{}", "<p id='outside' line-height='100'>TARGET</p>");
        assertEquals(134, space.height(), 0.000001);
    }

    @Test void atomicTableCellSubtractsExistingContentSpacingAndPadding() throws Exception {
        var space = tableSpace("", "<row><cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell></row>");
        assertEquals(348, space.width(), 0.000001);
        assertEquals(122, space.height(), 0.000001);
    }

    @Test void atomicTableCellKeepsRoomForRepeatedHeadersButCanMoveOtherRows() throws Exception {
        var space = tableSpace("<header><cell>HEADER</cell></header>",
                "<row><cell>ANOTHER ROW</cell></row><row><cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell></row>");
        // The 30 pt repeated header and 12 pt cell padding remain; the other body row can move.
        assertEquals(92, space.height(), 0.000001);
    }

    @Test void atomicTableCellWithoutMinimumRoomRejectsBeforeRegistration() throws Exception {
        var space = tableSpace("", "<row><cell><p line-height='130' space-after='6'>FULL</p>" + MARKER + "</cell></row>");
        assertEquals(0, space.height());
    }

    @Test void splitTableCellCanPutTheImageAfterItsTextOnAnotherPage() throws Exception {
        var space = tableSpace("", "<row><cell><p line-height='130'>TEXT</p>" + MARKER + "</cell></row>", " row-split='true'");
        assertEquals(148, space.height(), 0.000001);
    }

    @Test void spanningCellUsesTheMergedGroupAndItsOwnContent() throws Exception {
        String table = "<table><columns><column weight='1'/><column weight='1'/></columns>"
                + "<row><cell row-span='2'><p line-height='20' space-after='6'>EXISTING</p>" + MARKER
                + "</cell><cell>RIGHT ONE</cell></row><row><cell>RIGHT TWO</cell></row></table>";
        var space = documentSpace(table);
        assertEquals(122, space.height(), 0.000001);
    }

    @Test void nestedAtomicTableKeepsBothPaddingRegions() throws Exception {
        String inner = "<table><columns><column weight='1'/></columns><row><cell><p line-height='20' space-after='6'>EXISTING</p>"
                + MARKER + "</cell></row></table>";
        var space = tableSpace("", "<row><cell>" + inner + "</cell></row>");
        assertEquals(336, space.width(), 0.000001);
        assertEquals(110, space.height(), 0.000001);
    }

    @Test void nestedTableInSplitCellKeepsItsOwnHeaderAndAtomicRow() throws Exception {
        String inner = "<table><columns><column weight='1'/></columns><header><cell>HEADER</cell></header>"
                + "<row><cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell></row></table>";
        var space = tableSpace("", "<row><cell>" + inner + "</cell></row>", " row-split='true'");
        assertEquals(80, space.height(), 0.000001);
    }

    @Test void fixedRegionsKeepCellTrailingSpacingAndEveryOtherBlock() throws Exception {
        String table = "<table><columns><column weight='1'/></columns><row><cell><p line-height='20' space-after='20'>EXISTING</p>"
                + MARKER + "</cell></row></table>";
        for (String region : new String[] {"header", "footer"}) {
            var space = documentSpace("<" + region + " height='160'>" + table + "</" + region + ">", 600);
            assertEquals(108, space.height(), 0.000001);
        }
        var fixed = documentSpace("<foreground><box x='30' y='40' width='200' height='160'>" + table + "</box></foreground>", 600);
        assertEquals(108, fixed.height(), 0.000001);
        var auto = documentSpace("<foreground><box x='30' y='40' width='200' height='160' height-mode='AUTO'>" + table + "</box></foreground>", 600);
        assertEquals(508, auto.height(), 0.000001);
    }

    @Test void fixedRegionMiddleInsertionRetainsItsFollowingTextAndGaps() throws Exception {
        String table = "<table><columns><column weight='1'/></columns><row><cell><p line-height='20' space-after='6'>FIRST</p>" + MARKER
                + "<p line-height='20' space-before='7'>LAST</p></cell></row></table>";
        var space = documentSpace("<header height='160'>" + table + "</header>", 600);
        assertEquals(95, space.height(), 0.000001);
    }

    @Test void implicitCellParagraphDefaultsHaveNoTrailingSpacing() throws Exception {
        var space = tableSpace("", "<row><cell><p line-height='20'>EXISTING</p>" + MARKER + "</cell></row>");
        assertEquals(128, space.height(), 0.000001);
        var minimum = tableSpace("", "<row><cell><p line-height='130'>FULL</p>" + MARKER + "</cell></row>");
        assertEquals(18, minimum.height(), 0.000001);
    }

    @Test void fullFixedRegionCanUseSpaceInsideItsShorterCell() throws Exception {
        String table = "<table><columns><column weight='1'/><column weight='1'/></columns><row>"
                + "<cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell>"
                + "<cell><p line-height='148'>TALL SIBLING</p></cell></row></table>";
        var space = documentSpace("<header height='160'>" + table + "</header>", 600);
        assertEquals(122, space.height(), 0.000001);
    }

    @Test void fullFixedRegionCanUseSpaceInNestedCells() throws Exception {
        String inner = "<table><columns><column weight='1'/><column weight='1'/></columns><row>"
                + "<cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell>"
                + "<cell><p line-height='136'>TALL SIBLING</p></cell></row></table>";
        String outer = "<table><columns><column weight='1'/></columns><row><cell>" + inner + "</cell></row></table>";
        var space = documentSpace("<footer height='160'>" + outer + "</footer>", 600);
        assertEquals(110, space.height(), 0.000001);
    }

    @Test void gridCanUseSpaceInsideItsFullHeightTable() throws Exception {
        String inner = tallSiblingTable(148);
        String content = "<grid tracks='12'><item row='1' column-start='1' column-span='12'>" + inner + "</item></grid>";
        assertMeasuredBody(content, 122, 1);
    }

    @Test void atomicCellCanUseSpaceInsideItsFullHeightNestedTable() throws Exception {
        String content = "<table><columns><column weight='1'/></columns><row><cell>" + tallSiblingTable(136) + "</cell></row></table>";
        assertMeasuredBody(content, 110, 1);
    }

    @Test void bodyAvoidanceUsesTheLargestCompleteRegionForAtomicGrid() throws Exception {
        assertMeasuredBody(avoidingBox("y='150' height='30'", "<p>BLOCK</p>") + imageGrid(), 94, 1);
    }

    @Test void bodyAvoidanceAlsoConstrainsAtomicTableCells() throws Exception {
        String table = "<table><columns><column weight='1'/></columns><row><cell>"
                + "<p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell></row></table>";
        assertMeasuredBody(avoidingBox("y='150' height='30'", "<p>BLOCK</p>") + table, 82, 1);
    }

    @Test void bodyAvoidanceAllowsAnOrdinaryImageLineToMovePastItsPrecedingText() throws Exception {
        String paragraphs = "<p line-height='20' space-after='6'>EXISTING</p>" + MARKER;
        assertMeasuredBody(avoidingBox("y='150' height='30'", "<p>BLOCK</p>") + paragraphs, 120, 2);
    }

    @Test void bodyAvoidanceConstrainsTheImageLineInsideColumns() throws Exception {
        String columns = "<columns count='2'><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</columns>";
        assertMeasuredBody(avoidingBox("y='150' height='30'", "<p>BLOCK</p>") + columns, 120, 1);
    }

    @Test void anOrdinaryImageLineRetainsTheFootnoteReservationOnItsFreshPage() throws Exception {
        String content = "<notes><note id='note' paragraph='body' kind='FOOTNOTE'>Footnote</note></notes>"
                + "<p id='body' line-height='20'>EXISTING</p>" + MARKER;
        // The bundled fonts use a 13.5 pt note line plus the 16 pt separator/spacing band.
        assertMeasuredBody(content, 130.5, 2);
    }

    @Test void aKeptParagraphOrHeadingRetainsItsHeightAndSpacingBeforeTheImage() throws Exception {
        for (String tag : new String[] {"p", "heading level='2'"}) {
            String name = tag.startsWith("heading") ? "heading" : "p";
            String kept = "<" + tag + " size='12' line-height='20' space-before='0' space-after='6' keep-with-next='true'>KEPT</" + name + ">";
            assertMeasuredBody(kept + MARKER, 134, 1);
        }
    }

    @Test void theCompleteKeptChainUsesItsBoundGeometryAndBothSidedGaps() throws Exception {
        String chain = "<p size='6' line-height='10' space-after='3' keep-with-next='true'>FIRST</p>"
                + "<heading level='2' size='12' line-height='20' space-before='7' space-after='6' keep-with-next='true'>SECOND</heading>";
        assertMeasuredBody(chain + MARKER, 114, 1);
        String repeated = "<each items='values' as='value'><p line-height='20' space-after='6' keep-with-next='true'>${value}</p></each>";
        assertMeasuredBody(repeated + MARKER, 108, 1, "{\"values\":[\"A\",\"B\"]}");
    }

    @Test void anExplicitPageBreakStartsANewKeptChain() throws Exception {
        String chain = "<p line-height='20' keep-with-next='true'>BEFORE</p>"
                + "<p line-height='20' space-after='6' page-break-before='true' keep-with-next='true'>KEPT</p>";
        assertMeasuredBody(chain + MARKER, 134, 2);
    }

    @Test void aKeptPredecessorConstrainsOnlyTheFirstGridRow() throws Exception {
        assertMeasuredBody(keptTitle() + imageGrid(), 108, 1);
        String rows = "<grid tracks='12'><item row='1' column-start='1' column-span='12'><p>FIRST ROW</p></item>"
                + "<item row='2' column-start='1' column-span='12'><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</item></grid>";
        assertMeasuredBody(keptTitle() + rows, 134, 2);
    }

    @Test void aKeptPredecessorConstrainsOnlyTheFirstTableGroup() throws Exception {
        String row = "<row><cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell></row>";
        String head = "<table><columns><column weight='1'/></columns>";
        assertMeasuredBody(keptTitle() + head + row + "</table>", 96, 1);
        assertMeasuredBody(keptTitle() + head + "<row><cell>FIRST ROW</cell></row>" + row + "</table>", 122, 2);
    }

    @Test void futureAndPastPageSpecificAvoidanceDoNotReduceFreshCapacity() throws Exception {
        assertMeasuredBody(avoidingBox("y='150' height='30' page='5'", "<p>FUTURE</p>") + imageGrid(), 134, 5);
        String prefix = "<p line-height='100'>BEFORE</p><page-break/>";
        assertMeasuredBody(avoidingBox("y='150' height='30' page='1'", "<p>PAST</p>") + prefix + imageGrid(), 134, 2);
    }

    @Test void theNewParagraphProbeRetainsTheActualColumnWidth() throws Exception {
        var space = documentSpace("<columns count='2' gap='12'><p>BEFORE</p>" + MARKER + "</columns>");
        assertEquals(174, space.width(), 0.000001);
    }

    private static String keptTitle() {
        return "<p line-height='20' space-after='6' keep-with-next='true'>TITLE</p>";
    }

    @Test void autoAvoidanceUsesMeasuredHeightAndUnionsOverlappingAreas() throws Exception {
        String boxes = "<foreground>" + avoidBox("y='60' height='100' height-mode='AUTO'", "<p line-height='20'>AUTO</p>")
                + avoidBox("y='70' height='20'", "<p>FIXED</p>") + "</foreground>";
        // The union is y=60..90, leaving y=90..190 as the largest 100 pt body region.
        assertMeasuredBody(boxes + imageGrid(), 74, 1);
    }

    @Test void aPageSpecificAvoidanceCanBePassedOnTheNextPage() throws Exception {
        assertMeasuredBody(avoidingBox("y='150' height='30' page='1'", "<p>BLOCK</p>") + imageGrid(), 134, 2);
    }

    private static String tallSiblingTable(int siblingHeight) {
        return "<table><columns><column weight='1'/><column weight='1'/></columns><row>"
                + "<cell><p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</cell>"
                + "<cell line-height='" + siblingHeight + "'>TALL SIBLING</cell></row></table>";
    }

    private static String imageGrid() {
        return "<grid tracks='12'><item row='1' column-start='1' column-span='12'>"
                + "<p line-height='20' space-after='6'>EXISTING</p>" + MARKER + "</item></grid>";
    }

    private static String avoidingBox(String geometry, String content) {
        return "<foreground>" + avoidBox(geometry, content) + "</foreground>";
    }

    private static String avoidBox(String geometry, String content) {
        return "<box x='30' width='360' body-flow='AVOID' " + geometry + ">" + content + "</box>";
    }

    private static void assertMeasuredBody(String content, double expectedLineHeight, int expectedPages) throws Exception {
        assertMeasuredBody(content, expectedLineHeight, expectedPages, "{}");
    }

    private static void assertMeasuredBody(String content, double expectedLineHeight, int expectedPages, String data) throws Exception {
        String source = "<document version='1'><page width='420' height='220' margin='30'/>" + content + "</document>";
        var project = DesignerSamples.blank().source(source, data);
        var space = DesignerService.atomicImageSpace(project, "image-marker", DesignerService.execution(() -> false));
        assertEquals(expectedLineHeight, space.height(), 0.000001);
        String sized = source.replace(MARKER, MARKER.replace("line-height='18'", "line-height='" + expectedLineHeight + "'"));
        var control = DesignerService.preview(project.source(sized, data), DesignerService.execution(() -> false));
        assertEquals(expectedPages, control.layout().body().pages().size());
    }

    private static DesignerService.ImageSpace tableSpace(String headers, String rows) throws Exception {
        return tableSpace(headers, rows, "");
    }

    private static DesignerService.ImageSpace tableSpace(String headers, String rows, String attributes) throws Exception {
        String source = "<document version='1'><page width='420' height='220' margin='30'/><table id='table'" + attributes
                + "><columns><column weight='1'/></columns>" + headers + rows + "</table></document>";
        return DesignerService.atomicImageSpace(DesignerSamples.blank().source(source, "{}"), "image-marker", DesignerService.execution(() -> false));
    }

    private static DesignerService.ImageSpace documentSpace(String content) throws Exception {
        return documentSpace(content, 220);
    }

    private static DesignerService.ImageSpace documentSpace(String content, int pageHeight) throws Exception {
        String source = "<document version='1'><page width='420' height='" + pageHeight + "' margin='30'/>" + content + "</document>";
        return DesignerService.atomicImageSpace(DesignerSamples.blank().source(source, "{}"), "image-marker", DesignerService.execution(() -> false));
    }

    private static DesignerService.ImageSpace space(String left, String right, String data, String prefix) throws Exception {
        String source = "<document version='1'><page width='420' height='220' margin='30'/>" + prefix
                + "<grid id='grid' tracks='12' column-gap='12'>"
                + "<item id='left' row='1' column-start='1' column-span='6'>" + left + "</item>"
                + "<item id='right' row='1' column-start='7' column-span='6'>" + right + "</item></grid></document>";
        var project = DesignerSamples.blank().source(source, data);
        return DesignerService.atomicImageSpace(project, "image-marker", DesignerService.execution(() -> false));
    }
}
