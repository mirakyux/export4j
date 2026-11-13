// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.template;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.DocumentTemplate;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.LineBreakPolicy;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.PageBreak;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageRegion;
import com.mirakyux.export4j.model.PageRegions;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.model.Table;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.TableFormat;
import com.mirakyux.export4j.model.TableRow;
import com.mirakyux.export4j.model.VectorResource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XmlTemplateWriterTest {
    private static final PageSettings PAGE = PageSettings.builder().size(420, 500).margins(24).build();
    private static final ParagraphFormat FORMAT = ParagraphFormat.builder().fontSize(11).lineHeight(24).firstLineIndent(3)
            .subsequentLineIndent(5).spaceBefore(2).spaceAfter(7).lineBreakPolicy(LineBreakPolicy.CJK_STRICT)
            .pagination(PaginationPolicy.BALANCED).build();
    private static final InlineStyle STYLE = new InlineStyle("accent", 10, 0x224488, -2, true, true);

    @Test void inlineVectorsRoundTripWithCompleteGeometryUnderEveryPolicy(@TempDir Path directory) throws IOException {
        var vector = new InlineVector(VectorResource.of(12, 6, 3, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -6.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(12.0, 3.0)))), "x+y");
        var paragraph = Paragraph.mixed("vector", List.of(vector), ParagraphFormat.of(12));
        var table = Table.builder("vector-cell").columns(new ColumnWidth.Fraction(1))
                .row(TableCell.builder().content(List.of(vector)).build()).build();
        var blockCell = Table.builder("vector-block-cell").columns(new ColumnWidth.Fraction(1))
                .row(TableCell.builder().blocks(List.of(paragraph)).build()).build();
        Path target = directory.resolve("old-template.zip"); Files.writeString(target, "old");
        for (var block : List.<Block>of(paragraph, table, blockCell)) {
            var document = Document.builder().add(block).build();
            for (var policy : List.of(ProcessingPolicy.STRICT,
                    ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE).build(),
                    ProcessingPolicy.builder().defaultRule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD).build())) {
                var prepared = XmlTemplateWriter.from(document).policy(policy).prepare();
                prepared.to(target);
                assertEquals(document.blocks(), XmlTemplateBundle.read(Files.readAllBytes(target)).compile().render(Map.of()).blocks());
                var output = new ByteArrayOutputStream();
                DocumentConversion.from(document).policy(policy).prepare(XmlTemplateWriter.conversionTarget()).to(output);
                assertEquals(document.blocks(), XmlTemplateBundle.read(output.toByteArray()).compile().renderSections(Map.of())
                        .sections().getFirst().blocks());
                assertTrue(prepared.preview().issues().isEmpty());
            }
        }
        try (var paths = Files.list(directory)) { assertEquals(List.of(target), paths.toList()); }
    }

    @Test void headingAndListsSaveMixedObjectsWithLinksAndLiteralVectorReadingText() throws IOException {
        var resource = VectorResource.of(12, 6, 3, List.of(
                new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, -6.0)),
                new VectorResource.Segment(VectorResource.Operation.LINE, List.of(12.0, 3.0))));
        var vector = new InlineVector(resource, "${raw}", 0x224466, "https://example.com/vector");
        var image = new InlineImage(image(), 12, 10, 0, "Image ${raw}", "https://example.com/image");
        var content = List.of(new InlineText("Before ", InlineStyle.of(11)), image, vector,
                new InlineField(InlineField.Kind.PAGE, "", 20, InlineStyle.of(11)), new InlineText(" After", InlineStyle.of(11)));
        var document = new Document(DocumentMetadata.EMPTY, PAGE, List.of(Heading.mixed("h", content, 1, FORMAT),
                BulletItem.mixed("b", content, "*", 1, FORMAT), NumberedItem.mixed("n", "steps", content, 0, FORMAT, 1)));
        var bundle = XmlTemplateWriter.from(document).prepare().bundle();
        for (int i = 0; i < 3; i++) {
            var restored = XmlTemplateBundle.read(bundle.encode()).compile().render(Map.of());
            assertEquals(document.blocks(), restored.blocks());
            bundle = XmlTemplateWriter.from(restored).prepare().bundle();
        }
    }

    @Test void malformedVectorPathsAreRejectedBeforeBindingAndCannotExecuteExpressions() {
        for (String segment : List.of("<segment operation='LINE' points='0 0'/>",
                "<segment operation='MOVE' points='NaN 0'/>", "<segment operation='MOVE' points='0 0 1'/>",
                "<segment operation='MOVE' points='${x} 0'/>", "<segment operation='MOVE' points='20 0'/>",
                "<segment operation='MOVE' points='0 0' href='https://example.com'/>")) {
            String source = "<document version='1'><p><vector width='12' ascent='6' descent='3' logical='x'>"
                    + segment + "</vector></p></document>";
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile(source));
        }
    }

    @Test void renamedListIdsDoNotMergeIndependentListsAcrossSections() throws IOException {
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(
                new Section("first", PAGE, List.of(new NumberedItem("n1", "list@x", "First list", 0, FORMAT)), 1),
                new Section("second", PAGE, List.of(new NumberedItem("n2", "saved-1", "Independent list", 0, FORMAT),
                        new NumberedItem("n3", "list@x", "First list continued", 0, FORMAT)), 0)));
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var policy = ProcessingPolicy.builder().feature("IDENTIFIER", action, ProcessingPolicy.Treatment.DEGRADE).build();
            var prepared = XmlTemplateWriter.from(source).policy(policy).prepare();
            var restored = prepared.bundle().compile().renderSections(Map.of());
            var first = assertInstanceOf(NumberedItem.class, restored.sections().getFirst().blocks().getFirst());
            var independent = assertInstanceOf(NumberedItem.class, restored.sections().getLast().blocks().getFirst());
            var continued = assertInstanceOf(NumberedItem.class, restored.sections().getLast().blocks().getLast());
            assertFalse(first.listId().equals(independent.listId()));
            assertEquals("saved-1", independent.listId());
            assertEquals(first.listId(), continued.listId());
            assertEquals(1, prepared.preview().losses().size());
            assertEquals(restored, XmlTemplateWriter.from(restored).prepare().bundle().compile().renderSections(Map.of()));
        }
    }

    @Test void richSemanticValuesSurviveZipReopenAndRepeatedXmlSaving() throws IOException {
        var source = rich();
        var prepared = XmlTemplateWriter.from(source).font("Regular.ttf", new byte[]{1, 2, 3}).license("Font.txt", "Original\r\n").prepare();
        assertTrue(prepared.preview().issues().isEmpty());
        var bytes = new ByteArrayOutputStream(); assertEquals(ProcessingReport.Phase.ACTUAL, prepared.to(bytes).phase());
        var reopened = XmlTemplateBundle.read(bytes.toByteArray());
        assertEquals(source, reopened.compile().render(Map.of()));
        assertArrayEquals(new byte[]{1, 2, 3}, reopened.fonts().get("Regular.ttf"));
        assertEquals("Original\r\n", reopened.licenses().get("Font.txt"));
        var again = XmlTemplateWriter.from(reopened.compile().render(Map.of())).prepare();
        assertEquals(prepared.bundle().source(), again.bundle().source());
        assertEquals(source, XmlTemplateBundle.read(again.bundle().encode()).compile().render(Map.of()));
    }

    @Test void continuousColumnsSurviveRepeatedZipSavingWithRichContentAndSharedBudgets() throws IOException {
        var rich = rich();
        for (var weights : List.of(List.<Double>of(), List.of(1.0, 2.0, 1.0))) {
            var flow = new ColumnFlow("article", List.of(rich.blocks().get(1), rich.blocks().get(4)), 3, 17.25, false, weights);
            var source = new Document(rich.metadata(), PAGE, List.of(rich.blocks().getFirst(), rich.blocks().get(3), flow,
                    rich.blocks().getLast()));
            var prepared = XmlTemplateWriter.from(source).prepare();
            assertTrue(prepared.preview().issues().isEmpty());
            assertTrue(prepared.bundle().source().contains("<columns id=\"article\" count=\"3\" gap=\"17.25\" balanced=\"false\""));
            var reopened = XmlTemplateBundle.read(prepared.bundle().encode()).compile().render(Map.of());
            assertEquals(source, reopened);
            var again = XmlTemplateWriter.from(reopened).prepare();
            assertEquals(prepared.bundle().source(), again.bundle().source());
            assertEquals(source, XmlTemplateBundle.read(again.bundle().encode()).compile().render(Map.of()));
            assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source)
                    .limits(new XmlTemplateWriter.Limits(3, 100, 100)).prepare());
        }
    }

    @Test void fullWidthTableAndImageBetweenColumnFlowsRetainTheirBodyBoundaries() throws IOException {
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(
                new ColumnFlow("before", List.of(paragraph("lead", "First columns")), 2, 18, true),
                rich().blocks().get(4),
                Paragraph.mixed("wide-image", List.of(new InlineImage(image(), 300, 90, 0, "Full width", "https://example.com/image")), FORMAT),
                new ColumnFlow("after", List.of(paragraph("tail", "Resumed columns")), 3, 12, false, List.of(1.0, 2.0, 1.0))));
        var bundle = XmlTemplateWriter.from(source).prepare().bundle();
        var restored = XmlTemplateBundle.read(bundle.encode()).compile().render(Map.of());
        assertEquals(source, restored);
        assertEquals(List.of("before", "table", "wide-image", "after"), restored.blocks().stream().map(Block::id).toList());
        assertInstanceOf(ColumnFlow.class, restored.blocks().getFirst());
        assertInstanceOf(Table.class, restored.blocks().get(1));
        assertEquals(300, assertInstanceOf(Paragraph.class, restored.blocks().get(2)).images().getFirst().image().width());
        assertInstanceOf(ColumnFlow.class, restored.blocks().getLast());
        assertEquals(source, XmlTemplateWriter.from(restored).prepare().bundle().compile().render(Map.of()));
    }

    @Test void borderlessTableDeclarationSurvivesBundleAndNestedTemplateExpansion() throws IOException {
        var inner = Table.builder("inner").columns(new ColumnWidth.Fixed(80))
                .row(TableCell.rich(List.of(new InlineText("Inner", InlineStyle.of(12))), CellFormat.of(12)))
                .format(TableFormat.builder().drawBorders(false).build()).build();
        var outer = Table.builder("outer").columns(new ColumnWidth.Fixed(140))
                .row(TableCell.builder().blocks(List.of(inner)).build())
                .format(TableFormat.builder().drawBorders(false).build()).build();
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(outer));
        var bundle = XmlTemplateWriter.from(source).prepare().bundle();
        assertTrue(bundle.source().contains("draw-borders=\"false\""));
        assertEquals(source, XmlTemplateBundle.read(bundle.encode()).compile().render(Map.of()));
    }

    @Test void sectionsPageNumbersAndPaperSurviveWithoutFlattening() throws IOException {
        var source = new SectionedDocument(new DocumentMetadata("${raw", "A\r\nB\tC"), List.of(
                new Section("first", PAGE, List.of(paragraph("p1", "First")), 3),
                new Section("second", PageSettings.builder().size(500, 300).margins(16).build(), List.of(paragraph("p2", "Second")), 0)));
        var bundle = XmlTemplateWriter.from(source).prepare().bundle();
        assertEquals(source, XmlTemplateBundle.read(bundle.encode()).compile().renderSections(Map.of()));
        assertEquals(DocumentTemplate.BindingException.Code.UNSUPPORTED_BLOCK,
                assertThrows(DocumentTemplate.BindingException.class, () -> bundle.compile().render(Map.of())).code());
    }

    @Test void literalModeKeepsAllTextWhileDefaultBindingAndControlNodesRemainExplicit() {
        var literal = XmlTemplate.builder().build().compile("""
                <document version='1' literal-text='true' title='${missing'>
                  <if test='shown'><then><p>${missing} &amp; raw ${</p></then><else><p>Hidden</p></else></if>
                </document>
                """);
        assertEquals("${missing} & raw ${", literal.render(Map.of("shown", true)).paragraphs().getFirst().text());
        assertEquals("${missing", literal.render(Map.of("shown", false)).metadata().title());
        var ordinary = XmlTemplate.builder().build().compile("<document version='1'><p>${value}</p></document>");
        assertEquals("Bound", ordinary.render(Map.of("value", "Bound")).paragraphs().getFirst().text());
        assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1' literal-text='maybe'/>"));
    }

    @Test void xmlTextAndAttributeWhitespaceAndSupplementaryCharactersRemainExact() throws IOException {
        String text = "<&>\"' 中文😀\r\n\t ${data.value} ${";
        var source = new Document(new DocumentMetadata(text, text), PAGE, List.of(paragraph("p", text)));
        assertEquals(source, XmlTemplateWriter.from(source).prepare().bundle().compile().render(Map.of()));
        for (String bad : List.of("\u0000", "\u0001", "\ud800", "\ufffe")) {
            var invalid = new Document(DocumentMetadata.EMPTY, PAGE, List.of(paragraph("p", bad)));
            assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(invalid)
                    .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)).prepare());
        }
    }

    @Test void unsupportedBlocksRequireTheRealDiscardTreatmentAndIgnoreRetainsLosses() throws IOException {
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(paragraph("before", "Before"), new Opaque("opaque"), paragraph("after", "After")));
        var rejected = assertThrows(ProcessingException.class, () -> XmlTemplateWriter.from(source).prepare());
        assertEquals("UNSUPPORTED_BLOCK", rejected.issue().feature()); assertEquals("opaque", rejected.issue().location().blockId());
        assertThrows(ProcessingException.class, () -> XmlTemplateWriter.from(source).policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.PRESERVE)).prepare());
        assertThrows(ProcessingException.class, () -> XmlTemplateWriter.from(source).policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)).prepare());
        for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
            var prepared = XmlTemplateWriter.from(source).policy(rule(action, ProcessingPolicy.Treatment.DISCARD)).prepare();
            assertEquals(1, prepared.preview().losses().size());
            assertEquals(action == ProcessingPolicy.Action.WARN ? 1 : 0, prepared.preview().warnings().size());
            assertEquals(List.of("Before", "After"), prepared.bundle().compile().render(Map.of()).paragraphs().stream().map(Paragraph::text).toList());
            var result = prepared.to(new ByteArrayOutputStream()); assertEquals(prepared.preview().issues(), result.issues());
        }
    }

    @Test void identifierDegradationPreservesReferencesAndAvoidsExistingGeneratedNames() throws IOException {
        var target = paragraph("not an XML id", "Target");
        var reference = Paragraph.mixed("reference", List.of(new InlineField(InlineField.Kind.REFERENCE_PAGE, target.id(), 18, InlineStyle.of(11))), FORMAT);
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(paragraph("saved-1", "Reserved"), target, reference));
        assertEquals("IDENTIFIER", assertThrows(ProcessingException.class, () -> XmlTemplateWriter.from(source).prepare()).issue().feature());
        var prepared = XmlTemplateWriter.from(source).policy(rule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)).prepare();
        var restored = prepared.bundle().compile().render(Map.of());
        assertEquals("saved-2", restored.blocks().get(1).id());
        assertEquals("saved-2", assertInstanceOf(Paragraph.class, restored.blocks().get(2)).fields().getFirst().field().targetId());
        assertEquals(1, prepared.preview().issues().size());
    }

    @Test void fontRoleLossInNestedCellReportsPhysicalPathAndUtf16Range() throws IOException {
        var cell = TableCell.mixed(List.of(new InlineText("A😀", new InlineStyle("invalid role", 11, 0, 0, false, false))), CellFormat.of(11));
        var table = new Table("table", List.of(new ColumnWidth.Fixed(100)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(table));
        var prepared = XmlTemplateWriter.from(source).policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DEGRADE)).prepare();
        var issue = prepared.preview().issues().getFirst();
        assertEquals("FONT_ROLE", issue.feature()); assertEquals(0, issue.location().cellPath().getFirst().columnIndex());
        assertEquals(0, issue.location().startUtf16()); assertEquals(3, issue.location().endUtf16()); assertEquals(1, prepared.preview().losses().size());
        var restored = assertInstanceOf(Table.class, prepared.bundle().compile().render(Map.of()).blocks().getFirst());
        assertEquals("A😀", restored.rows().getFirst().cells().getFirst().text());
        assertEquals("", restored.rows().getFirst().cells().getFirst().inlines().getFirst().style().fontRole());
    }

    @Test void pageRegionsSurviveStrictSavingInsteadOfBeingDiscarded() throws IOException {
        var regions = new PageRegions(new PageRegion(30, 4, List.of(paragraph("header", "Header"))), PageRegion.EMPTY);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("s", PAGE, List.of(paragraph("body", "Body")), 1, regions)));
        var prepared = XmlTemplateWriter.from(source).prepare();
        assertEquals(source, prepared.bundle().compile().renderSections(Map.of()));
        assertTrue(prepared.preview().issues().isEmpty());
    }

    @Test void notesMarkersAndHeadingLinksSurviveStrictSaving() throws IOException {
        var heading = Heading.rich("h", List.of(new InlineText("Linked", STYLE, "https://example.com/heading")), 1, FORMAT);
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(heading,
                Paragraph.mixed("p", List.of(new InlineText("Body", InlineStyle.of(11)),
                        new InlineField(InlineField.Kind.NOTE_MARKER, "note", 12, STYLE)), FORMAT)));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).prepare());
        var notes = List.of(new Note("note", "p", "Note body", Note.Kind.FOOTNOTE));
        var prepared = XmlTemplateWriter.from(source).notes(notes).prepare();
        var template = prepared.bundle().compile();
        assertThrows(DocumentTemplate.BindingException.class, () -> template.render(Map.of()));
        var restored = template.renderComplete(Map.of());
        assertEquals(source.blocks(), restored.document().sections().getFirst().blocks());
        assertEquals(notes, restored.notes());
        assertTrue(prepared.preview().issues().isEmpty());
    }

    @Test void plainAuthoringAndAllFourFieldsKeepSemanticContentAndGeometry() throws IOException {
        var plain = new Paragraph("plain", "Plain ${data}", FORMAT);
        var fields = Paragraph.mixed("fields", List.of(new InlineField(InlineField.Kind.PAGE, "", 18, STYLE),
                new InlineField(InlineField.Kind.PAGES, "", 20, STYLE),
                new InlineField(InlineField.Kind.REFERENCE_PAGE, "plain", 22, STYLE),
                new InlineField(InlineField.Kind.REFERENCE_NUMBER, "list", 24, STYLE)), FORMAT);
        var source = new Document(DocumentMetadata.EMPTY, PAGE, List.of(plain, fields,
                new NumberedItem("list", "steps", "Item", 1, FORMAT)));
        var restored = XmlTemplateWriter.from(source).prepare().bundle().compile().render(Map.of());
        assertEquals(plain.id(), restored.paragraphs().getFirst().id());
        assertEquals(plain.text(), restored.paragraphs().getFirst().text());
        assertEquals(plain.format(), restored.paragraphs().getFirst().format());
        assertEquals(plain.content(), restored.paragraphs().getFirst().content());
        assertEquals(fields, restored.paragraphs().get(1));
    }

    @Test void grammarRejectsInvalidNewGeometryAndMutuallyExclusiveAutoColumnForms() {
        for (String body : List.of("<p min-lines-before='0'>X</p>", "<p first-indent='-1'>X</p>", "<p line-break='UNKNOWN'>X</p>",
                "<bullet level='0'>X</bullet>", "<numbered list-id='s' marker-color='red'>X</numbered>",
                "<table><columns><column min-width='10' max-width='20' weight='1'/></columns><row><cell>X</cell></row></table>",
                "<table padding-left='-1'><columns><column width='20'/></columns><row><cell>X</cell></row></table>")) {
            assertThrows(TemplateException.class, () -> XmlTemplate.builder().build().compile("<document version='1'>" + body + "</document>"));
        }
    }

    @Test void writerBudgetsAndCancellationCannotBeIgnored() throws IOException {
        var source = rich();
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).limits(new XmlTemplateWriter.Limits(1, 100, 100)).prepare());
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).limits(new XmlTemplateWriter.Limits(100, 1, 100)).prepare());
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(source).bundleLimits(new XmlTemplateBundle.Limits(1000, 1000, 10, 20, 1000)).prepare());
        var many = new Document(DocumentMetadata.EMPTY, PAGE, List.of(new Opaque("a"), new Opaque("b")));
        assertThrows(IllegalArgumentException.class, () -> XmlTemplateWriter.from(many).limits(new XmlTemplateWriter.Limits(10, 10, 1))
                .policy(rule(ProcessingPolicy.Action.IGNORE, ProcessingPolicy.Treatment.DISCARD)).prepare());
        assertThrows(ExecutionException.class, () -> XmlTemplateWriter.from(source).execution(ExecutionOptions.builder().cancelled(() -> true).build()).prepare());
    }

    @Test void smallFinitePageLengthsUseTheExistingDecimalGrammar() throws IOException {
        var page = PageSettings.builder().size(420, 500).margins(0.00001).build();
        var source = new Document(DocumentMetadata.EMPTY, page, List.of(paragraph("p", "Small margins")));
        assertEquals(source, XmlTemplateWriter.from(source).prepare().bundle().compile().render(Map.of()));
    }

    @Test void preparationSnapshotsResourcesPolicyAndExactBytes() throws IOException {
        byte[] font = {1, 2, 3}; var request = XmlTemplateWriter.from(rich()).font("Regular.ttf", font);
        font[0] = 7; var prepared = request.prepare();
        request.font("Regular.ttf", new byte[]{9}).license("Later.txt", "Later");
        assertArrayEquals(new byte[]{1, 2, 3}, prepared.bundle().fonts().get("Regular.ttf")); assertTrue(prepared.bundle().licenses().isEmpty());
        var first = new ByteArrayOutputStream(); var second = new ByteArrayOutputStream(); prepared.to(first); prepared.to(second);
        assertArrayEquals(first.toByteArray(), second.toByteArray());
    }

    @Test void outputIsBorrowedAndPathFailuresRetainOldTarget(@TempDir Path directory) throws IOException {
        var cancelled = new AtomicBoolean(); var prepared = XmlTemplateWriter.from(rich()).execution(ExecutionOptions.builder().cancelled(cancelled::get).build()).prepare();
        var borrowed = new ByteArrayOutputStream() {
            @Override public void close() { throw new AssertionError("closed"); }
            @Override public void flush() { throw new AssertionError("flushed"); }
        };
        prepared.to(borrowed);
        var broken = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("failure"); } };
        assertThrows(IOException.class, () -> prepared.to(broken));
        Path target = directory.resolve("template.zip"); Files.writeString(target, "old"); cancelled.set(true);
        assertThrows(ExecutionException.class, () -> prepared.to(target)); assertEquals("old", Files.readString(target)); cancelled.set(false);
        var small = XmlTemplateWriter.from(rich()).execution(ExecutionOptions.builder().maxOutputBytes(1).build()).prepare();
        assertThrows(ExecutionException.class, () -> small.to(target)); assertEquals("old", Files.readString(target));
        try (var paths = Files.list(directory)) { assertEquals(List.of(target), paths.toList()); }
        prepared.to(target); assertEquals(rich(), XmlTemplateBundle.read(Files.readAllBytes(target)).compile().render(Map.of()));
    }

    @Test void sharedConversionCanSaveReopenEditAndSaveAgainWithMatchingReports() throws IOException {
        var source = rich(); var conversion = DocumentConversion.from(source).prepare(XmlTemplateWriter.conversionTarget());
        var bytes = new ByteArrayOutputStream(); assertEquals(conversion.preview().issues(), conversion.to(bytes).report().issues());
        var bundle = XmlTemplateBundle.read(bytes.toByteArray());
        var edited = bundle.conversion().edit(editor -> editor.replaceText("intro", "Hello", "Edited")).prepare(XmlTemplateWriter.conversionTarget());
        var second = new ByteArrayOutputStream(); edited.to(second);
        var restored = XmlTemplateBundle.read(second.toByteArray()).compile().renderSections(Map.of());
        assertTrue(assertInstanceOf(Paragraph.class, restored.sections().getFirst().blocks().get(1)).text().startsWith("Edited"));
        assertFalse(restored.sections().getFirst().blocks().isEmpty());
    }

    private record Opaque(String id) implements Block { }
    private static ProcessingPolicy rule(ProcessingPolicy.Action action, ProcessingPolicy.Treatment treatment) {
        return ProcessingPolicy.builder().defaultRule(action, treatment).build();
    }
    private static Paragraph paragraph(String id, String text) { return Paragraph.mixed(id, List.of(new InlineText(text, InlineStyle.of(11))), FORMAT); }
    private static ImageResource image() throws IOException {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes); return ImageResource.load(bytes.toByteArray());
    }
    private static Document rich() throws IOException {
        var image = new InlineImage(image(), 12, 10, 1, "Logo ${raw}", "https://example.com/image");
        var intro = Paragraph.mixed("intro", List.of(new InlineText("Hello <&> ${raw}\r\n", STYLE, "https://example.com/text"), image,
                new InlineText(" page=", InlineStyle.of(11)), new InlineField(InlineField.Kind.REFERENCE_NUMBER, "number", 24, STYLE)), FORMAT);
        var cell = TableCell.mixed(List.of(new InlineText("Cell", STYLE)), CellFormat.of(11).withLineBreakPolicy(LineBreakPolicy.CJK_STRICT));
        var inner = new Table("inner", List.of(new ColumnWidth.Fixed(60)), List.of(new TableRow(List.of(cell))), 0, TableFormat.DEFAULT);
        var table = new Table("table", List.of(new ColumnWidth.Auto(40, 80), new ColumnWidth.Fraction(1)), List.of(
                new TableRow(List.of(cell, cell)), new TableRow(List.of(TableCell.builder().format(CellFormat.of(11)).columnSpan(2)
                        .blocks(List.of(paragraph("nested", "Nested"), inner)).build()))), 1,
                new TableFormat(new PageMargins(2, 3, 4, 5), 3, 7, true));
        return new Document(new DocumentMetadata("Title ${raw", "Author"), PAGE, List.of(
                Heading.rich("heading", List.of(new InlineText("Heading", STYLE)), 2, FORMAT), intro,
                BulletItem.rich("bullet", List.of(new InlineText("Bullet", STYLE)), "-", 2, FORMAT, STYLE),
                NumberedItem.rich("number", "steps", List.of(new InlineText("Number", STYLE)), 7, FORMAT, 1, STYLE), table, new PageBreak("break"), paragraph("end", "End")));
    }
}
