// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.model.edit;

import com.mirakyux.export4j.model.Block;
import com.mirakyux.export4j.model.BulletItem;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ColumnWidth;
import com.mirakyux.export4j.model.CrossReference;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.Heading;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Note;
import com.mirakyux.export4j.model.NumberedItem;
import com.mirakyux.export4j.model.PageDocument;
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
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Action;
import com.mirakyux.export4j.model.processing.ProcessingPolicy.Treatment;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentEditorTest {
    private static final ParagraphFormat FORMAT = ParagraphFormat.of(12);
    private static final InlineStyle NORMAL = InlineStyle.of(12);
    private static final InlineStyle BLUE = NORMAL.toBuilder().rgb(0x1234ab).build();
    private static Paragraph p(String id, String text) { return new Paragraph(id, text, FORMAT); }
    private static Document doc(Block... blocks) { return new Document(DocumentMetadata.EMPTY, PageSettings.A4, List.of(blocks)); }
    private static ProcessingPolicy rule(Action action, Treatment treatment) {
        return ProcessingPolicy.builder().defaultRule(action, treatment).build();
    }
    private static Paragraph mixed() {
        return new Paragraph("p", List.of(new InlineText("ab", BLUE, "https://example.org/"), new InlineText("cd", NORMAL),
                new InlineText("tail", BLUE)), FORMAT);
    }
    @Test void sameStyleCrossRunMatchRetainsSurroundingLinksAndSource() throws Exception {
        var source = new Paragraph("p", List.of(new InlineText("😀a", BLUE, "https://example.org/"),
                new InlineText("bc", BLUE, "https://example.org/"), new InlineText(" tail", NORMAL)), FORMAT);
        var result = DocumentEditor.edit(doc(source)).replaceText("abc", "世界").execute();
        var changed = (Paragraph) result.document().blocks().getFirst();
        assertEquals("😀世界 tail", changed.text());
        assertEquals(BLUE, changed.inlines().get(1).style());
        assertEquals("https://example.org/", changed.inlines().get(1).hyperlink());
        assertEquals("😀abc tail", source.text());
        assertEquals(2, result.changes().getFirst().location().startUtf16());
        assertTrue(result.report().losses().isEmpty());
    }
    @Test void mixedRunMatchIsStrictAndContentFreeByDefault() {
        var error = assertThrows(ProcessingException.class, () -> DocumentEditor.edit(doc(mixed())).replaceText("bc", "SECRET").prepare());
        assertEquals("MIXED_RUN_STYLE", error.issue().feature());
        assertFalse(error.toString().contains("SECRET"));
        assertEquals(1, error.issue().location().startUtf16());
    }
    @Test void warnAndIgnoreProduceSameDegradationWithQueryableLosses() throws Exception {
        var source = doc(mixed());
        var warned = DocumentEditor.edit(source).policy(rule(Action.WARN, Treatment.DEGRADE)).replaceText("bc", "X").execute();
        var ignored = DocumentEditor.edit(source).policy(rule(Action.IGNORE, Treatment.DEGRADE)).replaceText("bc", "X").execute();
        assertEquals(warned.document(), ignored.document());
        assertEquals("aXdtail", ((Paragraph) ignored.document().blocks().getFirst()).text());
        assertEquals(1, warned.report().warnings().size());
        assertTrue(ignored.report().warnings().isEmpty());
        assertEquals(1, ignored.report().losses().size());
        assertEquals("https://example.org/", ((Paragraph) ignored.document().blocks().getFirst()).inlines().get(1).hyperlink());
    }
    @Test void skipMatchKeepsUnsupportedMatchButEditsLaterPlainMatches() throws Exception {
        var source = new Paragraph("p", List.of(new InlineText("a", BLUE), new InlineText("b ab", NORMAL)), FORMAT);
        var result = DocumentEditor.edit(doc(source)).policy(rule(Action.WARN, Treatment.SKIP_MATCH)).replaceText("ab", "X").execute();
        assertEquals("ab X", ((Paragraph) result.document().blocks().getFirst()).text());
        assertEquals(1, result.changes().size());
        assertTrue(result.report().losses().isEmpty());
    }
    @Test void skipBlockRollsBackEarlierMatchesAndTentativeLosses() throws Exception {
        var source = new Paragraph("p", List.of(new InlineText("ab a", BLUE), new InlineText("b", NORMAL)), FORMAT);
        var result = DocumentEditor.edit(doc(source)).policy(rule(Action.WARN, Treatment.SKIP_BLOCK)).replaceText("ab", "X").execute();
        assertSame(source, result.document().blocks().getFirst());
        assertTrue(result.changes().isEmpty());
        assertEquals(1, result.report().skipped().size());
    }
    @Test void skipRequestRollsBackPreviousOperationsAndLosses() throws Exception {
        var source = doc(p("first", "abc"), mixed());
        var result = DocumentEditor.edit(source).policy(rule(Action.IGNORE, Treatment.SKIP_REQUEST))
                .remove("first").replaceText("bc", "X").execute();
        assertEquals(source, result.document()); assertTrue(result.changes().isEmpty());
        assertEquals(1, result.report().skipped().size()); assertTrue(result.report().losses().isEmpty());
    }
    @Test void policyPrecedenceAndBuilderSnapshotAreObservable() throws Exception {
        var builder = ProcessingPolicy.builder().defaultRule(Action.ERROR, Treatment.SKIP_MATCH)
                .format("MODEL", Action.WARN, Treatment.SKIP_BLOCK)
                .feature("MIXED_RUN_STYLE", Action.IGNORE, Treatment.DEGRADE)
                .operation("REPLACE_TEXT", Action.WARN, Treatment.SKIP_MATCH);
        var snapshot = builder.build(); builder.operation("REPLACE_TEXT", Action.ERROR, Treatment.SKIP_REQUEST);
        var result = DocumentEditor.edit(doc(mixed())).policy(snapshot).replaceText("bc", "X").execute();
        assertEquals("abcdtail", ((Paragraph) result.document().blocks().getFirst()).text());
        assertEquals(Treatment.SKIP_MATCH, result.report().issues().getFirst().treatment());
        assertEquals(Action.WARN, result.report().issues().getFirst().action());
    }
    @Test void prepareBindsExactCandidateAndConfiguration() throws Exception {
        var request = DocumentEditor.edit(doc(p("p", "ab"))).replaceText("ab", "cd");
        var prepared = request.prepare(); request.replaceText("cd", "ef");
        assertEquals(ProcessingReport.Phase.PREVIEW, prepared.preview().phase());
        assertEquals("cd", ((Paragraph) prepared.execute().document().blocks().getFirst()).text());
        assertEquals("ef", ((Paragraph) request.execute().document().blocks().getFirst()).text());
    }
    @Test void literalReplacementIsNonrecursiveAndNonoverlapping() throws Exception {
        var result = DocumentEditor.edit(doc(p("p", "aaaa.*"))).replaceText("aa", "aaa").replaceText(".*", "x").execute();
        assertEquals("aaaaaax", ((Paragraph) result.document().blocks().getFirst()).text());
        assertEquals(3, result.changes().size());
    }
    @Test void objectMarkersAreAtomicAndShiftAfterReplacement() throws Exception {
        var image = image();
        var field = new InlineField(InlineField.Kind.PAGE, "", 30, NORMAL);
        var source = Paragraph.mixed("p", List.of(new InlineText("hello", NORMAL), image, field, new InlineText("tail", BLUE)), FORMAT);
        var result = DocumentEditor.edit(doc(source)).replaceText("hello", "x").execute();
        var changed = (Paragraph) result.document().blocks().getFirst();
        assertEquals("x\ufffc\ufffctail", changed.text());
        assertEquals(1, changed.images().getFirst().sourceIndex()); assertSame(image, changed.images().getFirst().image());
        assertEquals(2, changed.fields().getFirst().sourceIndex()); assertSame(field, changed.fields().getFirst().field());
        assertThrows(ProcessingException.class, () -> DocumentEditor.edit(doc(source)).replaceText("o\ufffc", "x").execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(source)).policy(rule(Action.IGNORE, Treatment.DEGRADE))
                .replaceText("o\ufffc", "x").execute());
    }
    @Test void imageAndParagraphStyleUpdatesRetainFields() throws Exception {
        var image = image(); var field = new InlineField(InlineField.Kind.PAGE, "", 30, NORMAL);
        var source = Paragraph.mixed("p", List.of(image, field), FORMAT);
        var replacement = image.toBuilder().altText("changed").build();
        var result = DocumentEditor.edit(doc(source)).image("p", 0, replacement).paragraphFormat("p", ParagraphFormat.of(14)).execute();
        var changed = (Paragraph) result.document().blocks().getFirst();
        assertEquals(replacement, changed.images().getFirst().image()); assertEquals(field, changed.fields().getFirst().field());
        assertEquals(14, changed.format().fontSize());
    }
    @Test void headingAndListEditingRetainsMarkerAndListIdentity() throws Exception {
        var source = doc(new Heading("h", "old", 2), new BulletItem("b", "old", "+", 1, FORMAT, List.of(), BLUE),
                new NumberedItem("n", "list", "old", 3, FORMAT, 1, List.of(), BLUE));
        var result = DocumentEditor.edit(source).replaceText("old", "new").paragraphFormat("h", FORMAT).execute().document();
        assertEquals("new", ((Heading) result.blocks().getFirst()).text());
        assertEquals(2, ((Heading) result.blocks().getFirst()).level());
        assertEquals(BLUE, ((BulletItem) result.blocks().get(1)).markerStyle());
        var numbered = (NumberedItem) result.blocks().get(2);
        assertEquals("list", numbered.listId()); assertEquals(3, numbered.restartAt()); assertEquals(BLUE, numbered.markerStyle());
    }
    @Test void nestedCellLocationsUsePhysicalAnchorsAndPreserveSpans() throws Exception {
        var inner = Table.text("inner", List.of("old"), List.of(List.of("old")));
        var outer = new Table("outer", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(TableCell.builder().columnSpan(2).blocks(List.of(p("deep", "old"), inner)).build()))), 0, TableFormat.DEFAULT);
        var result = DocumentEditor.edit(doc(outer)).replaceText("old", "new").execute();
        var table = (Table) result.document().blocks().getFirst();
        assertEquals(2, table.rows().getFirst().cells().getFirst().columnSpan());
        assertEquals("new", ((Paragraph) table.rows().getFirst().cells().getFirst().blocks().getFirst()).text());
        assertEquals(3, result.changes().size());
        assertEquals("outer", result.changes().get(1).location().cellPath().getFirst().tableId());
        assertEquals("inner", result.changes().get(1).location().cellPath().getLast().tableId());
    }
    @Test void cellReplacementRejectsCoveredAliasesAndInvalidCoverage() throws Exception {
        var spanning = TableCell.builder().text("left").columnSpan(2).build();
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(spanning, new TableCell("right", CellFormat.of(12))))), 0, TableFormat.DEFAULT);
        var replacement = new TableCell("new", CellFormat.of(12));
        var result = DocumentEditor.edit(doc(table)).cell("t", 0, 2, replacement).execute();
        assertSame(replacement, ((Table) result.document().blocks().getFirst()).rows().getFirst().cells().get(1));
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(table)).cell("t", 0, 1, replacement).execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(table)).cell("t", 0, 0, replacement).execute());
    }
    @Test void sharedPageRegionIsEditedOnceAndRemainsShared() throws Exception {
        var header = new PageRegion(30, 5, List.of(p("header", "old")));
        var regions = new PageRegions(header, PageRegion.EMPTY);
        var source = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("a", PageSettings.A4, List.of(p("a", "old")), 1, regions),
                new Section("b", PageSettings.A4, List.of(p("b", "old")), 0, regions)));
        var result = DocumentEditor.edit(source).replaceText("old", "new").execute();
        assertEquals(3, result.changes().size());
        assertSame(result.document().sections().getFirst().pageRegions().header(), result.document().sections().getLast().pageRegions().header());
        assertSame(header, source.sections().getFirst().pageRegions().header());
        var page = new PageDocument(doc(p("body", "old")), regions);
        assertEquals("new", DocumentEditor.edit(page).replaceText("old", "new").execute().document().pageRegions().header().paragraphs().getFirst().text());
    }
    @Test void insertMoveDeleteAreAtomicAndPreserveUntouchedBlocks() throws Exception {
        var a = p("a", "a"); var b = p("b", "b"); var c = p("c", "c");
        var source = doc(a, b, c);
        var result = DocumentEditor.edit(source).insertAfter("a", p("x", "x")).moveBefore("c", "a").remove("b").execute();
        assertEquals(List.of("c", "a", "x"), result.document().blocks().stream().map(Block::id).toList());
        assertSame(c, result.document().blocks().getFirst()); assertEquals(List.of(a, b, c), source.blocks());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(source).insertBefore("a", p("b", "duplicate")).execute());
    }
    @Test void danglingReferencesCannotBeIgnoredButBatchRemovalIsValid() throws Exception {
        var ref = new CrossReference("ref", "see", "p", CrossReference.Kind.PAGE, FORMAT);
        var source = doc(p("p", "target"), ref);
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(source).policy(rule(Action.IGNORE, Treatment.DISCARD)).remove("p").execute());
        assertTrue(DocumentEditor.edit(source).remove("p").remove("ref").execute().document().blocks().isEmpty());
    }
    @Test void brokenListHierarchyAndNotesAreRejected() throws Exception {
        var source = doc(new NumberedItem("parent", "list", "parent", 0, FORMAT, 1), new NumberedItem("child", "list", "child", 0, FORMAT, 2));
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(source).remove("parent").execute());
        var field = new InlineField(InlineField.Kind.NOTE_MARKER, "note", 20, NORMAL);
        var noted = doc(Paragraph.mixed("p", List.of(new InlineText("text", NORMAL), field), FORMAT));
        var notes = List.of(new Note("note", "p", "content", Note.Kind.FOOTNOTE));
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(noted).execute());
        var result = DocumentEditor.edit(noted).notes(notes).replaceText("text", "changed").execute();
        assertEquals(notes, result.notes());
        assertEquals(7, ((Paragraph) result.document().blocks().getFirst()).fields().getFirst().sourceIndex());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(noted).notes(notes).remove("p").execute());
    }
    @Test void invalidUnicodeAndAmplificationCannotBeIgnored() {
        var lenient = rule(Action.IGNORE, Treatment.DEGRADE);
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(p("p", "a\ud800"))).policy(lenient).replaceText("a", "b").execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(p("p", "😀"))).replaceText("\ud83d", "x").execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(p("p", "aaaa")))
                .limits(new EditLimits(10, 100, 10, 100, 100, 1_000)).replaceText("a", "0123456789").execute());
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(p("p", "aa")))
                .limits(new EditLimits(10, 100, 100, 1, 100, 1_000)).replaceText("a", "b").execute());
    }
    @Test void cancellationDuringPreparationAndPublicationRestoresExecutionScope() throws Exception {
        var cancelled = new AtomicBoolean(false);
        var execution = ExecutionOptions.builder().cancelled(cancelled::get).build();
        var request = DocumentEditor.edit(doc(p("p", "a"))).execution(execution).replaceText("a", "b");
        var prepared = request.prepare(); cancelled.set(true);
        assertThrows(ExecutionException.class, prepared::execute);
        assertThrows(ExecutionException.class, request::prepare);
        ExecutionContext.checkpoint();
        assertEquals("ok", ExecutionContext.execute(ExecutionOptions.DEFAULT, () -> "ok"));
    }
    @Test void unavailablePreservationNeverClaimsSuccess() {
        assertThrows(IllegalArgumentException.class, () -> DocumentEditor.edit(doc(mixed())).policy(rule(Action.IGNORE, Treatment.PRESERVE))
                .replaceText("bc", "x").execute());
    }
    @Test void partialGraphemeMatchesAndStyleRangesUseExplicitSkipPolicy() throws Exception {
        var source = doc(p("p", "e\u0301 👩‍💻"));
        assertThrows(ProcessingException.class, () -> DocumentEditor.edit(source).replaceText("e", "x").execute());
        var skipped = DocumentEditor.edit(source).policy(rule(Action.IGNORE, Treatment.SKIP_MATCH)).replaceText("👩", "x").execute();
        assertEquals(source, skipped.document()); assertEquals(1, skipped.report().skipped().size());
        assertEquals("GRAPHEME_BOUNDARY", skipped.report().issues().getFirst().feature());
        var styled = DocumentEditor.edit(source).inlineStyle("p", 0, 2, BLUE).execute();
        assertEquals("e\u0301", ((Paragraph) styled.document().blocks().getFirst()).inlines().getFirst().text());
        var partial = DocumentEditor.edit(source).policy(rule(Action.WARN, Treatment.SKIP_MATCH)).inlineStyle("p", 0, 1, BLUE).execute();
        assertEquals(source, partial.document()); assertTrue(partial.changes().isEmpty());
    }
    @Test void findReportsCrossRunPositionsWithoutDisclosingTextAndSeesQueuedEdits() throws Exception {
        var result = DocumentEditor.edit(doc(mixed(), p("second", "old"))).replaceText("second", "old", "bc").find("bc");
        assertEquals(2, result.matches().size());
        assertFalse(result.matches().getFirst().uniformStyle());
        assertEquals(1, result.matches().getFirst().location().startUtf16());
        assertTrue(result.matches().getLast().uniformStyle());
        assertEquals("second", result.matches().getLast().location().blockId());
        assertFalse(result.toString().contains("abcdtail"));
    }
    @Test void skipBlockRollsBackEveryCellInTheOwningTable() throws Exception {
        var plain = new TableCell("ab", CellFormat.of(12));
        var mixed = TableCell.rich(List.of(new InlineText("a", BLUE), new InlineText("b", NORMAL)), CellFormat.of(12));
        var table = new Table("t", List.of(new ColumnWidth.Fraction(1), new ColumnWidth.Fraction(1)),
                List.of(new TableRow(List.of(plain, mixed))), 0, TableFormat.DEFAULT);
        var result = DocumentEditor.edit(doc(table)).policy(rule(Action.WARN, Treatment.SKIP_BLOCK)).replaceText("ab", "X").execute();
        assertSame(table, result.document().blocks().getFirst()); assertTrue(result.changes().isEmpty());
        assertEquals(List.of("BLOCK_NOT_EDITED"), result.report().issues().getFirst().effects());
    }
    @Test void inlineStylingRetainsHyperlinksAndRejectsUnsupportedCaptionStyles() throws Exception {
        var source = new Paragraph("p", List.of(new InlineText("text", NORMAL, "https://example.org/")), FORMAT);
        var styled = DocumentEditor.edit(doc(source)).inlineStyle("p", 1, 3, BLUE).execute();
        var runs = ((Paragraph) styled.document().blocks().getFirst()).inlines();
        assertEquals(List.of("t", "ex", "t"), runs.stream().map(InlineText::text).toList());
        assertTrue(runs.stream().allMatch(run -> run.hyperlink().equals("https://example.org/")));
        var caption = new com.mirakyux.export4j.model.Caption("cap", "Figure", "text", FORMAT);
        assertThrows(ProcessingException.class, () -> DocumentEditor.edit(doc(caption)).inlineStyle("cap", 0, 2, BLUE).execute());
    }
    private static InlineImage image() throws Exception {
        var bytes = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new InlineImage(ImageResource.load(bytes.toByteArray()), 12, 12, 0, "original", "");
    }
}
